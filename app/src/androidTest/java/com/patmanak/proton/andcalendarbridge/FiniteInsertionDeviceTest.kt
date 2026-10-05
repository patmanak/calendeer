// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.Attendees
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exact persisted finite first insertion and historical recovery; no service calls. */
@RunWith(AndroidJUnit4::class)
class FiniteInsertionDeviceTest {
    @Test fun savedDatesInsertWithoutRecalculationAndKeepCanonicalSourceAfterAcknowledgement() = runBlocking {
        for (spring in listOf(false, true)) fixture(spring) { f ->
            val reference = f.reference(alteredFold = !spring)
            val receipt = f.prepare(reference)
            f.reopen()
            assertEquals(receipt, f.store.projectionReceipt(f.scope, f.event.eventId))
            val witness = f.native.insertFiniteProjection(receipt)
            assertEquals(receipt, witness.receipt)
            val row = f.row()
            assertEquals(reference.dates.dropLast(1).map { Triple(row, it.toEpochMilli(), it.plusSeconds(7200).toEpochMilli()) }, f.instances())
            assertNull(f.field(Events.RRULE)); assertEquals("UTC", f.field(Events.EVENT_TIMEZONE))
            assertEquals("UTC", f.field(Events.EVENT_END_TIMEZONE)); assertEquals("PT7200S", f.field(Events.DURATION))
            assertEquals((0x334499 or 0xFF000000.toInt()).toString(), f.field(Events.EVENT_COLOR))
            assertEquals(2, f.children(Reminders.CONTENT_URI, Reminders.EVENT_ID).size)
            assertEquals(2, f.children(Attendees.CONTENT_URI, Attendees.EVENT_ID).size)
            assertTrue(f.store.restoreProjection(witness))
            assertEquals(f.event, f.store.projectedBaseline(f.scope, f.event.eventId))
            assertEquals(reference, f.store.projectedReference(f.scope, f.event.eventId))
            assertEquals("Europe/Paris", (f.store.projectedBaseline(f.scope, f.event.eventId)!!.content.time as EventTime.Timed).zone.id)
            assertEquals("FREQ=DAILY;COUNT=3", f.store.projectedBaseline(f.scope, f.event.eventId)!!.content.recurrence!!.rule)
            assertTrue(f.store.pending(f.scope).isEmpty()); assertNull(f.store.projectionReceipt(f.scope, f.event.eventId))
            f.reopen(); assertEquals(reference, f.store.projectedReference(f.scope, f.event.eventId))
        }
    }

    @Test fun lostAcknowledgementRecoversHistoryAndPreservesLaterDirtyOrDeletedContent() = runBlocking {
        for (localState in listOf("clean", "dirty", "deleted")) fixture { f ->
            val reference = f.reference(); val receipt = f.prepare(reference)
            val applied = f.native.insertFiniteProjection(receipt)
            f.store.writableDatabase.execSQL("CREATE TRIGGER fixture_lost_ack BEFORE UPDATE OF pending ON records " +
                "WHEN NEW.pending=0 BEGIN SELECT RAISE(ABORT, 'fixture interrupted acknowledgement'); END")
            try { f.store.restoreProjection(applied); fail("Expected interrupted SQL acknowledgement") }
            catch (_: SQLiteException) { }
            assertNull(f.store.projectedBaseline(f.scope, f.event.eventId))
            assertEquals(receipt, f.store.projectionReceipt(f.scope, f.event.eventId))
            if (localState != "clean") f.update(ContentValues().apply {
                put(Events.TITLE, "Later local fixture"); put(Events.DIRTY, 1)
                if (localState == "deleted") put(Events.DELETED, 1)
            })
            val newer = f.event.copy(revision = Revision("newer"), content = f.event.content.copy(title = "Newer source fixture"))
            f.queue(newer)
            val rows = f.events(); val reminders = f.children(Reminders.CONTENT_URI, Reminders.EVENT_ID)
            val people = f.children(Attendees.CONTENT_URI, Attendees.EVENT_ID); val writes = f.eventBatches
            f.reopen(); f.store.writableDatabase.execSQL("DROP TRIGGER fixture_lost_ack")
            val recovered = f.native.inspectFiniteInsertion(f.store.projectionReceipt(f.scope, f.event.eventId)!!)!!
            assertTrue(f.store.restoreProjection(recovered))
            assertEquals(writes, f.eventBatches); assertEquals(rows, f.events())
            assertEquals(reminders, f.children(Reminders.CONTENT_URI, Reminders.EVENT_ID))
            assertEquals(people, f.children(Attendees.CONTENT_URI, Attendees.EVENT_ID))
            assertEquals(reference, f.store.projectedReference(f.scope, f.event.eventId))
            assertEquals(listOf(ProjectionIntent(f.scope, newer)), f.store.pending(f.scope))
            assertNull(f.store.projectionReceipt(f.scope, f.event.eventId))
        }
    }

    @Test fun firstInsertionNeverAdoptsAnExistingEventOrReappliesItsOwnNonce() = runBlocking {
        for (finiteExisting in listOf(false, true)) fixture { f ->
            val receipt = f.prepare(f.reference())
            if (finiteExisting) f.native.insertFiniteProjection(receipt)
            else f.native.apply(ProjectionIntent(f.scope, f.reference().nativeChange))
            val rows = f.events(); val writes = f.eventBatches
            refused { f.native.insertFiniteProjection(receipt) }
            assertEquals(rows, f.events()); assertEquals(writes, f.eventBatches)
            assertEquals(receipt, f.store.projectionReceipt(f.scope, f.event.eventId))
            assertNull(f.store.projectedBaseline(f.scope, f.event.eventId))
        }
    }

    @Test fun previousBaseOrWrongRepresentationIsRejectedBeforeProviderQueries() = runBlocking {
        fixture { f ->
            val receipt = f.prepare(f.reference())
            val direct = ProjectedEventReference.direct(f.event)
            for (candidate in listOf(receipt.copy(previousBase = f.event),
                receipt.copy(previousBase = f.event, previousRepresentation = f.reference()), receipt.copy(representation = direct))) {
                val queries = f.queries; val writes = f.eventBatches
                refused { f.native.insertFiniteProjection(candidate) }
                refused { f.native.inspectFiniteInsertion(candidate) }
                assertEquals(queries, f.queries); assertEquals(writes, f.eventBatches)
            }
        }
    }

    @Test fun existingSeriesIdentityCannotBeAdoptedByADifferentFiniteEvent() = runBlocking {
        fixture { f ->
            val reference = f.reference()
            f.native.apply(ProjectionIntent(f.scope, reference.nativeChange.copy(eventId = EventId("different-event"))))
            val receipt = f.prepare(reference); val rows = f.events(); val writes = f.eventBatches
            refused { f.native.insertFiniteProjection(receipt) }
            assertEquals(rows, f.events()); assertEquals(writes, f.eventBatches)
            assertEquals(receipt, f.store.projectionReceipt(f.scope, f.event.eventId))
        }
    }

    @Test fun eventIdentityAppearingAtTheBatchBoundaryBlocksFiniteInsertion() = runBlocking {
        fixture { f ->
            f.native.apply(ProjectionIntent(f.scope, f.reference().nativeChange))
            val collision = f.copyValues(); f.deleteRow()
            val receipt = f.prepare(f.reference()); val writes = f.eventBatches
            f.beforeBatch = { assertNotNull(f.real.insert(f.uri, collision)) }
            refused { f.native.insertFiniteProjection(receipt) }
            assertEquals(writes, f.eventBatches); assertEquals(1, f.events().size)
            assertNull(f.field(Events.SYNC_DATA3))
            assertEquals(receipt, f.store.projectionReceipt(f.scope, f.event.eventId))
            assertNull(f.store.projectedBaseline(f.scope, f.event.eventId))
        }
    }

    @Test fun copiedDatesCannotRestoreSqlAndDirectReceiptCannotClaimFiniteHistory() = runBlocking {
        fixture { f ->
            val receipt = f.prepare(f.reference()); f.native.insertFiniteProjection(receipt)
            val other = receipt.copy(representation = f.reference(alteredFold = true))
            // History identifies the nonce. Only the exact persisted receipt authorizes SQL ACK.
            val copied = f.native.inspectFiniteInsertion(other)!!
            assertFalse(f.store.restoreProjection(copied))
            assertNull(f.native.inspectProjectionReceipt(receipt.copy(representation = ProjectedEventReference.direct(f.event))))
            assertEquals(receipt, f.store.projectionReceipt(f.scope, f.event.eventId))
            assertEquals(listOf(ProjectionIntent(f.scope, f.event)), f.store.pending(f.scope))
            assertTrue(f.store.restoreProjection(f.native.inspectFiniteInsertion(receipt)!!))
            assertEquals(f.reference(), f.store.projectedReference(f.scope, f.event.eventId))
        }
    }

    @Test fun missingChangedAndDuplicateNativeIdentityCannotProveFiniteInsertion() = runBlocking {
        for (failure in listOf("missing", "nonce", "owner", "duplicate")) fixture { f ->
            val receipt = f.prepare(f.reference()); f.native.insertFiniteProjection(receipt)
            when (failure) {
                "missing" -> f.deleteRow()
                "nonce" -> f.update(ContentValues().apply { putNull(Events.SYNC_DATA3) })
                "owner" -> f.update(ContentValues().apply { put(Events.SYNC_DATA1, "foreign-fixture") })
                "duplicate" -> f.real.insert(f.uri, f.copyValues())!!
            }
            val rows = f.events(); val writes = f.eventBatches
            var unproven = false
            try { unproven = f.native.inspectFiniteInsertion(receipt) == null }
            catch (_: CalendarProjectionException) { unproven = true }
            assertTrue(unproven); assertEquals(rows, f.events()); assertEquals(writes, f.eventBatches)
            assertEquals(receipt, f.store.projectionReceipt(f.scope, f.event.eventId))
            assertNull(f.store.projectedBaseline(f.scope, f.event.eventId))
        }
    }

    @Test fun genericEventWritesAndExceptionsPreserveFiniteAndFutureMarkers() = runBlocking {
        for (future in listOf(false, true)) fixture { f ->
            val receipt = f.prepare(f.reference()); f.native.insertFiniteProjection(receipt)
            if (future) f.update(ContentValues().apply { put(Events.SYNC_DATA3, "calendeer-finite-future-v99:fixture") })
            val rows = f.events(); val writes = f.eventBatches
            val next = f.event.copy(revision = Revision("next"), content = f.event.content.copy(title = "Ordinary replacement", recurrence = null))
            refused { f.native.apply(ProjectionIntent(f.scope, next)) }
            refused { f.native.applyTracked(ProjectionReceipt(f.scope, f.format, next, UUID.randomUUID().toString(), f.event)) }
            refused { f.native.apply(ProjectionIntent(f.scope, RemoteChange.Deleted(f.event.eventId, Revision("delete")))) }
            val child = next.copy(eventId = EventId("exception"), content = next.content.copy(recurrence = EventRecurrence("finite-fixture-series",
                original = RecurrencePoint.Timed(f.reference().dates[0]))))
            refused { f.native.apply(ProjectionIntent(f.scope, child)) }
            assertEquals(rows, f.events()); assertEquals(writes, f.eventBatches)
            assertEquals(receipt, f.store.projectionReceipt(f.scope, f.event.eventId))
        }
    }

    @Test fun orphanChildrenAndTheirConcurrentAppearanceBlockFirstInsertion() = runBlocking {
        for (race in listOf(false, true)) fixture { f ->
            val reference = f.reference()
            f.native.apply(ProjectionIntent(f.scope, reference.nativeChange))
            val series = f.field(Events._SYNC_ID)!!; f.deleteRow()
            val receipt = f.prepare(reference)
            if (race) f.beforeBatch = { f.insertOrphan(series) } else f.insertOrphan(series)
            val writes = f.eventBatches
            refused { f.native.insertFiniteProjection(receipt) }
            assertEquals(writes, f.eventBatches)
            assertEquals(1, f.events().size); assertEquals("Orphan fixture", f.events().single()[Events.TITLE])
            assertEquals(receipt, f.store.projectionReceipt(f.scope, f.event.eventId))
            assertNull(f.store.projectedBaseline(f.scope, f.event.eventId))
        }
    }

    @Test fun markerChangedAtTheBatchBoundaryCannotBeOverwrittenByOrdinaryProjection() = runBlocking {
        fixture { f ->
            f.native.apply(ProjectionIntent(f.scope, f.reference().nativeChange))
            val rows = f.events(); val writes = f.eventBatches
            f.beforeBatch = { f.update(ContentValues().apply { put(Events.SYNC_DATA3, "calendeer-finite-future-v99:fixture") }) }
            val next = f.event.copy(revision = Revision("next"), content = f.event.content.copy(title = "Ordinary replacement", recurrence = null))
            refused { f.native.apply(ProjectionIntent(f.scope, next)) }
            assertEquals(writes, f.eventBatches)
            assertEquals(rows.map { it - Events.SYNC_DATA3 }, f.events().map { it - Events.SYNC_DATA3 })
            assertEquals("calendeer-finite-future-v99:fixture", f.field(Events.SYNC_DATA3))
        }
    }

    @Test fun missingMasterDeletionCannotRemoveChildrenWithFiniteMarkers() = runBlocking {
        fixture { f ->
            f.native.apply(ProjectionIntent(f.scope, f.reference().nativeChange))
            val series = f.field(Events._SYNC_ID)!!; f.deleteRow(); f.insertOrphan(series)
            f.update(ContentValues().apply { put(Events.SYNC_DATA3, "calendeer-finite-future-v99:fixture") })
            val rows = f.events(); val writes = f.eventBatches
            refused { f.native.apply(ProjectionIntent(f.scope, RemoteChange.Deleted(f.event.eventId, Revision("deleted"))), "finite-fixture-series") }
            assertEquals(rows, f.events()); assertEquals(writes, f.eventBatches)
        }
    }

    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("Expected native operation refusal") }
        catch (_: CalendarProjectionException) { }
    }

    private suspend fun fixture(spring: Boolean = false, block: suspend (Fixture) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val f = Fixture(ApplicationProvider.getApplicationContext(), spring)
        assumeTrue(f.native.hasPermissions())
        try { f.initialize(); block(f) } finally { f.close() }
    }

    private class Fixture(val context: Context, spring: Boolean) {
        private val token = UUID.randomUUID().toString()
        val scope = CalendarScope(AccountId("finite-insertion-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("finite-insertion-fixture", 1)
        private val name = "finite-insertion-$token.db"
        private val starts = (if (spring) listOf("2030-03-30T01:30:00Z", "2030-03-31T01:30:00Z", "2030-04-01T00:30:00Z")
            else listOf("2030-10-26T00:30:00Z", "2030-10-27T00:30:00Z", "2030-10-28T01:30:00Z")).map(Instant::parse)
        val event = RemoteChange.Upsert(EventId("fixture"), Revision("original"), EventContent("Finite insertion fixture",
            EventTime.Timed(starts[0], starts[0].plusSeconds(7200), ZoneId.of("Europe/Paris")), colorRgb = 0x334499,
            recurrence = EventRecurrence("finite-fixture-series", "FREQ=DAILY;COUNT=3", excludedDates = listOf(RecurrencePoint.Timed(starts.last()))),
            details = EventDetails(listOf(EventReminder(10), EventReminder(30)), EventPerson("organizer@example.test", "Organizer"),
                listOf(EventAttendee(EventPerson("guest@example.test", "Guest"), AttendanceRole.REQUIRED, AttendanceStatus.NEEDS_ACTION)))))
        val real: ContentResolver = context.contentResolver
        var eventBatches = 0
        var queries = 0
        var beforeBatch: (() -> Unit)? = null
        private val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
                queries++; return real.query(uri, columns, selection, args, order)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = real.insert(uri, values)
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = real.update(uri, values, selection, args)
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = real.delete(uri, selection, args)
            override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                val hook = beforeBatch; beforeBatch = null; hook?.invoke()
                val result = real.applyBatch(CalendarContract.AUTHORITY, operations)
                if (operations.any { !it.isAssertQuery && it.uri.pathSegments.firstOrNull() in listOf("events", "reminders", "attendees") }) eventBatches++
                return result
            }
        }
        private val resolver = ContentResolver.wrap(provider)
        private val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = resolver
        }
        val native = AndroidCalendarProjection(wrapped)
        var store = ImportStore(context, name)
        private var calendar = -1L
        val uri get() = syncUri(Events.CONTENT_URI)
        private fun syncUri(uri: Uri): Uri = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, native.accountFor(scope.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, native.accountFor(scope.account).type).build()

        suspend fun initialize() {
            val summary = CalendarSummary(scope.calendar, "Calendeer finite insertion fixture")
            store.remember(scope.account, listOf(summary)); store.setEnabled(scope, true)
            calendar = native.ensureCalendar(scope, summary)
        }
        fun reference(alteredFold: Boolean = false): ProjectedEventReference = ProjectedEventReference.restore(event,
            ProjectionRepresentation.FINITE_DAILY_V1, starts.mapIndexed { index, instant -> if (alteredFold && index == 1) instant.plusSeconds(3600) else instant })
        suspend fun queue(change: RemoteChange) = store.transaction(scope) { putRemote(change); putProjection(ProjectionIntent(scope, change)) }
        suspend fun prepare(reference: ProjectedEventReference): ProjectionReceipt {
            queue(event); return store.prepareProjection(ProjectionIntent(scope, event), format, reference)
        }
        fun reopen() { store.close(); store = ImportStore(context, name) }
        fun events(): List<Map<String, String?>> = real.query(uri, null, "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), "${Events._ID}")!!.use { c ->
            buildList { while (c.moveToNext()) add(c.columnNames.mapIndexed { index, key -> key to if (c.isNull(index)) null else c.getString(index) }.toMap()) }
        }
        fun row() = events().single()[Events._ID]!!.toLong()
        fun field(column: String) = events().single()[column]
        fun update(values: ContentValues) { assertEquals(1, real.update(uri, values, "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()))) }
        fun deleteRow() { assertEquals(1, real.delete(uri, "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()))) }
        fun copyValues(): ContentValues = ContentValues().apply {
            val columns = setOf(Events.CALENDAR_ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4,
                Events.DTSTART, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.RDATE, Events.TITLE)
            events().single().filterKeys { it in columns }.forEach { (key, value) -> put(key, value) }
        }
        fun insertOrphan(series: String) {
            assertNotNull(real.insert(uri, ContentValues().apply {
                put(Events.CALENDAR_ID, calendar); put(Events._SYNC_ID, "orphan"); put(Events.SYNC_DATA1, "calendar-bridge-projection-v1")
                put(Events.SYNC_DATA4, "orphan"); put(Events.TITLE, "Orphan fixture"); put(Events.DTSTART, starts[0].toEpochMilli())
                put(Events.DTEND, starts[0].plusSeconds(7200).toEpochMilli()); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.ALL_DAY, 0)
                put(Events.ORIGINAL_SYNC_ID, series); put(Events.ORIGINAL_INSTANCE_TIME, starts[0].toEpochMilli()); put(Events.ORIGINAL_ALL_DAY, 0)
                put(Events.DIRTY, 0); put(Events.DELETED, 0)
            }))
        }
        fun children(uri: Uri, foreignKey: String): List<Map<String, String?>> = real.query(syncUri(uri), null,
            "$foreignKey=?", arrayOf(row().toString()), "_id")!!.use { c ->
            buildList { while (c.moveToNext()) add(c.columnNames.mapIndexed { index, key -> key to if (c.isNull(index)) null else c.getString(index) }.toMap()) }
        }
        fun instances(): List<Triple<Long, Long, Long>> {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, starts[0].minusSeconds(86400).toEpochMilli())
                ContentUris.appendId(it, starts.last().plusSeconds(5 * 86400).toEpochMilli())
            }.build()
            return real.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END),
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), CalendarContract.Instances.BEGIN)!!.use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getLong(1), c.getLong(2))) }
            }
        }
        suspend fun close() {
            store.close(); context.deleteDatabase(name); native.admitOperation = { true }; beforeBatch = null
            // Restore only fixture ownership if a refusal test deliberately changed it.
            real.update(uri, ContentValues().apply { put(Events.SYNC_DATA1, "calendar-bridge-projection-v1") },
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()))
            native.removeOwnedCalendar(scope)
            val account = native.accountFor(scope.account); val manager = AccountManager.get(context)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account }) assertTrue(manager.removeAccountExplicitly(account))
        }
    }
}
