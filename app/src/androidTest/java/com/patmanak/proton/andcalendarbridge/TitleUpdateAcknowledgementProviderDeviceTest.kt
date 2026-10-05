// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.Manifest
import android.accounts.AccountManager
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.RemoteException
import android.provider.CalendarContract
import android.provider.CalendarContract.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated local Provider fixtures only. No Proton session, service request or user-owned rows. */
class TitleUpdateAcknowledgementProviderDeviceTest {
    @Test fun exactOrdinaryEditChangesOnlyThreeMetadataFieldsOnTheSameRow() = fixture {
        val before = values(); val adapter = intercepted(writes = 1)
        assertEquals(1, adapter.titleUpdateAcknowledgementProtocol)
        assertEquals(AppliedTitleUpdateAcknowledgement(receipt), adapter.acknowledgeTitleUpdate(receipt))
        assertEquals(1, batches); assertEquals(1, rows())
        assertEquals(before + mapOf(Events.DIRTY to "0", Events.SYNC_DATA2 to newRevision, Events.SYNC_DATA3 to marker), values())
        val after = values()
        assertEquals(AppliedTitleUpdateAcknowledgement(receipt), intercepted().inspectTitleUpdateAcknowledgement(receipt))
        assertEquals(after, values())
    }
    @Test fun allDayContentIsNotRewritten() = fixture(allDay = true) {
        mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) })
        val before = values(); acknowledge()
        assertEquals(before + mapOf(Events.DIRTY to "0", Events.SYNC_DATA2 to newRevision, Events.SYNC_DATA3 to marker), values())
    }
    @Test fun changedContentBeforeAcknowledgementIsPreserved() = fixture {
        mutate(ContentValues().apply { put(Events.TITLE, "Later title") }); val before = values()
        assertNull(projection.acknowledgeTitleUpdate(receipt)); assertEquals(before, values())
    }
    @Test fun tombstoneCannotBeAcknowledged() = fixture {
        mutate(ContentValues().apply { put(Events.DELETED, 1) }); val before = values()
        assertNull(projection.acknowledgeTitleUpdate(receipt)); assertEquals(before, values())
    }
    @Test fun everyUnsupportedChildBlocksAcknowledgement() = fixture {
        for (kind in 0..2) {
            val child = addChild(kind); val before = values()
            assertNull(projection.acknowledgeTitleUpdate(receipt)); assertEquals(before, values())
            removeChild(child)
        }
    }
    @Test fun wrongBaseRevisionAndAllOriginOrReservedMarkersAreRefused() = fixture {
        val original = values()
        for (column in listOf(Events.SYNC_DATA2, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            mutate(ContentValues().apply { put(column, "unrelated") }); val before = values()
            assertNull(projection.acknowledgeTitleUpdate(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original[column]) })
        }
    }
    @Test fun finiteUnknownAndMalformedPreviousReceiptMarkersAreRefused() = fixture {
        for (value in listOf("calendeer-finite-daily-dates-v1-receipt-v1:${UUID.randomUUID()}", "unknown", "calendeer-projection-receipt-v1:broken")) {
            mutate(ContentValues().apply { put(Events.SYNC_DATA3, value) }); val before = values()
            assertNull(projection.acknowledgeTitleUpdate(receipt)); assertEquals(before, values())
        }
    }
    @Test fun concurrentFullContentChangeAbortsTheAtomicBatch() = fixture {
        val adapter = intercepted(writes = 1, before = { mutate(ContentValues().apply { put(Events.DESCRIPTION, "Concurrent detail") }) })
        rejected { adapter.acknowledgeTitleUpdate(receipt) }; assertEquals(1, batches)
        assertEquals("Concurrent detail", field(Events.DESCRIPTION)); unchangedMarkers()
    }
    @Test fun concurrentTombstoneAbortsTheAtomicBatch() = fixture {
        val adapter = intercepted(writes = 1, before = { mutate(ContentValues().apply { put(Events.DELETED, 1) }) })
        rejected { adapter.acknowledgeTitleUpdate(receipt) }; assertEquals("1", field(Events.DELETED)); unchangedMarkers()
    }
    @Test fun concurrentReceiptOrReservedMarkerChangeAbortsTheAtomicBatch() = fixture {
        for (column in listOf(Events.SYNC_DATA3, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            val before = field(column)
            rejected { intercepted(writes = 1, before = { mutate(ContentValues().apply { put(column, "changed-marker") }) })
                .acknowledgeTitleUpdate(receipt) }
            assertEquals("1", field(Events.DIRTY)); assertEquals(digest("revision-v1", base.revision.value), field(Events.SYNC_DATA2))
            assertEquals("changed-marker", field(column)); mutate(ContentValues().apply { put(column, before) })
        }
    }
    @Test fun eachConcurrentChildAbortsTheAtomicBatch() = fixture {
        for (kind in 0..2) {
            var child: Uri? = null
            val adapter = intercepted(writes = 1, before = { child = addChild(kind) })
            rejected { adapter.acknowledgeTitleUpdate(receipt) }; unchangedMarkers()
            removeChild(checkNotNull(child))
        }
    }
    @Test fun concurrentRemoteAndSyncIdentityDuplicatesAbortTheAtomicBatch() = fixture {
        for (remote in listOf(true, false)) {
            var duplicateRow: Uri? = null
            val adapter = intercepted(writes = 1, before = { duplicateRow = duplicate(remote) })
            rejected { adapter.acknowledgeTitleUpdate(receipt) }; unchangedMarkers()
            context.contentResolver.delete(syncUri(checkNotNull(duplicateRow)), null, null)
        }
    }
    @Test fun concurrentCalendarOwnershipChangeAbortsTheAtomicBatch() = fixture {
        val adapter = intercepted(writes = 1, before = { context.contentResolver.update(syncUri(Calendars.CONTENT_URI),
            ContentValues().apply { put(Calendars.CAL_SYNC1, "foreign-owner") }, "${Calendars._ID}=?", arrayOf(calendar.toString())) })
        try { rejected { adapter.acknowledgeTitleUpdate(receipt) }; unchangedMarkers() }
        finally { context.contentResolver.update(syncUri(Calendars.CONTENT_URI), ContentValues().apply {
            put(Calendars.CAL_SYNC1, "calendar-bridge-projection-v1") }, "${Calendars._ID}=?", arrayOf(calendar.toString())) }
    }
    @Test fun historicalProofAfterLostReplyPreservesLaterEditDeletionAndChildren() = fixture {
        val adapter = intercepted(writes = 1, after = { throw RemoteException("synthetic lost reply") })
        unavailable { adapter.acknowledgeTitleUpdate(receipt) }; assertEquals(1, batches)
        mutate(ContentValues().apply { put(Events.TITLE, "Later local edit"); put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        addChild(0); val before = values()
        assertEquals(AppliedTitleUpdateAcknowledgement(receipt), intercepted().inspectTitleUpdateAcknowledgement(receipt))
        assertEquals(before, values()); assertNull(projection.acknowledgeTitleUpdate(receipt))
    }
    @Test fun interruptionBeforeExecutionCreatesNoHistoricalProof() = fixture {
        val before = values(); val adapter = intercepted(writes = 1, before = { throw RemoteException("synthetic interruption") })
        unavailable { adapter.acknowledgeTitleUpdate(receipt) }
        assertEquals(0, batches); assertNull(projection.inspectTitleUpdateAcknowledgement(receipt)); assertEquals(before, values())
    }
    @Test fun malformedBatchResponseNeverProducesAWitnessButHistoryRemainsRecoverable() = fixture {
        unavailable { intercepted(writes = 1, malformed = true).acknowledgeTitleUpdate(receipt) }
        assertEquals(AppliedTitleUpdateAcknowledgement(receipt), projection.inspectTitleUpdateAcknowledgement(receipt))
    }
    @Test fun historicalInspectionRefusesNonceRevisionOriginsAndReservedMetadataMismatch() = fixture {
        acknowledge(); val original = values()
        for (column in listOf(Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA5, Events.SYNC_DATA6,
            Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            mutate(ContentValues().apply { put(column, "other") }); val before = values()
            assertNull(projection.inspectTitleUpdateAcknowledgement(receipt)); assertEquals(before, values())
            mutate(ContentValues().apply { put(column, original[column]) })
        }
        assertNull(projection.inspectTitleUpdateAcknowledgement(receipt.copy(projection = receipt.projection.copy(nonce = UUID.randomUUID().toString()))))
    }
    @Test fun historicalInspectionAtomicallyRejectsConcurrentOriginChange() = fixture {
        acknowledge()
        rejected { intercepted(before = { mutate(ContentValues().apply { put(Events.SYNC_DATA5, "foreign-origin") }) })
            .inspectTitleUpdateAcknowledgement(receipt) }
        assertEquals("foreign-origin", field(Events.SYNC_DATA5))
    }
    @Test fun modeAndPermissionGatesBlockBeforeQueries() = fixture {
        val before = values(); val closed = intercepted(writes = 1); closed.admitOperation = { false }
        try { closed.acknowledgeTitleUpdate(receipt); fail("Inactive mode must cancel") } catch (_: CancellationException) { }
        try { intercepted(writes = 1, denied = true).acknowledgeTitleUpdate(receipt); fail("Permission gate must refuse") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PERMISSION_REQUIRED, failure.category) }
        assertEquals(0, queries); assertEquals(0, batches); assertEquals(before, values())
    }
    @Test fun modeLossAfterCommitDoesNotReturnAWitnessAndHistoryCanRecover() = fixture {
        var admitted = true
        val adapter = intercepted(writes = 1, after = { admitted = false }); adapter.admitOperation = { admitted }
        try { adapter.acknowledgeTitleUpdate(receipt); fail("Lost mode must cancel witness return") } catch (_: CancellationException) { }
        assertEquals(1, batches)
        assertEquals(AppliedTitleUpdateAcknowledgement(receipt), projection.inspectTitleUpdateAcknowledgement(receipt))
    }

    private class Fixture(val context: Context, allDay: Boolean) {
        val scope = CalendarScope(AccountId("title-ack-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val projection = AndroidCalendarProjection(context)
        val format = AdapterFormat("synthetic-title-ack-import", 1)
        val content = EventContent("Original synthetic title", if (allDay) EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 1))
            else EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails())
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-old"), content)
        val intent = LocalEditIntent(MutationId("synthetic-intent"), scope, format, base, content.copy(title = "Submitted synthetic title"))
        val record = TitleUpdateAttemptRecord(MutationId("synthetic-attempt"), LocalEditPreparation(intent,
            Instant.parse("2030-10-01T12:00:00Z"), AdapterFormat("synthetic-preparation", 1), "opaque-preparation"),
            AdapterFormat("synthetic-submission", 1), "opaque-submission", TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        val observed = base.copy(revision = Revision("synthetic-new"), content = intent.content)
        val receipt = TitleUpdateAcknowledgementReceipt.of(TitleUpdateVerifiedResult.of(record,
            TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), observed)), UUID.randomUUID().toString())
        val marker = "calendeer-projection-receipt-v1:${receipt.projection.nonce}"
        val oldMarker = "calendeer-projection-receipt-v1:${UUID.randomUUID()}"
        val newRevision = digest("revision-v1", observed.revision.value)
        var calendar = -1L; var row = -1L; var queries = 0; var batches = 0
        val uri get() = syncUri(Events.CONTENT_URI)
        fun syncUri(uri: Uri): Uri = projection.accountFor(scope.account).let { account -> uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build() }
        suspend fun seed() {
            calendar = projection.ensureCalendarState(scope, CalendarSummary(scope.calendar, "Synthetic title ACK fixture"), true).id
            projection.apply(ProjectionIntent(scope, base))
            row = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
                check(it.count == 1 && it.moveToFirst()); it.getLong(0)
            }
            mutate(ContentValues().apply { put(Events.TITLE, intent.content.title); put(Events.DIRTY, 1); put(Events.SYNC_DATA3, oldMarker) })
        }
        suspend fun acknowledge() { assertEquals(AppliedTitleUpdateAcknowledgement(receipt), projection.acknowledgeTitleUpdate(receipt)) }
        fun mutate(values: ContentValues) { assertEquals(1, context.contentResolver.update(uri, values, "${Events._ID}=?", arrayOf(row.toString()))) }
        fun unchangedMarkers() { assertEquals("1", field(Events.DIRTY)); assertEquals(digest("revision-v1", base.revision.value), field(Events.SYNC_DATA2)); assertEquals(oldMarker, field(Events.SYNC_DATA3)) }
        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column), "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            check(it.count == 1 && it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun values(): Map<String, String?> = context.contentResolver.query(uri, null, "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            check(it.count == 1 && it.moveToFirst()); it.columnNames.indices.associate { index -> it.getColumnName(index) to if (it.isNull(index)) null else it.getString(index) }
        }
        fun rows() = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { it.count }
        fun addChild(kind: Int): Uri = when (kind) {
            0 -> context.contentResolver.insert(syncUri(Reminders.CONTENT_URI), ContentValues().apply { put(Reminders.EVENT_ID, row); put(Reminders.MINUTES, 10); put(Reminders.METHOD, Reminders.METHOD_ALERT) })!!
            1 -> context.contentResolver.insert(syncUri(Attendees.CONTENT_URI), ContentValues().apply { put(Attendees.EVENT_ID, row); put(Attendees.ATTENDEE_EMAIL, "fixture@example.invalid"); put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED); put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE) })!!
            else -> context.contentResolver.insert(syncUri(ExtendedProperties.CONTENT_URI), ContentValues().apply { put(ExtendedProperties.EVENT_ID, row); put(ExtendedProperties.NAME, "synthetic"); put(ExtendedProperties.VALUE, "fixture") })!!
        }
        fun removeChild(child: Uri) {
            context.contentResolver.delete(syncUri(child), null, null)
            mutate(ContentValues().apply { put(Events.HAS_ALARM, 0); put(Events.HAS_ATTENDEE_DATA, 0) })
        }
        fun duplicate(remote: Boolean): Uri {
            val copy = ContentValues().apply {
                put(Events.CALENDAR_ID, calendar); put(Events.TITLE, content.title); put(Events.DTSTART, field(Events.DTSTART)!!.toLong())
                put(Events.DTEND, field(Events.DTEND)!!.toLong()); put(Events.ALL_DAY, field(Events.ALL_DAY)!!.toInt()); put(Events.EVENT_TIMEZONE, field(Events.EVENT_TIMEZONE))
                put(Events.SYNC_DATA1, field(Events.SYNC_DATA1)); put(Events._SYNC_ID, if (remote) "other-sync" else field(Events._SYNC_ID))
                put(Events.SYNC_DATA4, if (remote) field(Events.SYNC_DATA4) else "other-remote")
            }
            return context.contentResolver.insert(uri, copy)!!
        }
        fun intercepted(writes: Int = 0, malformed: Boolean = false, denied: Boolean = false,
            after: () -> Unit = {}, before: () -> Unit = {}): AndroidCalendarProjection {
            val real = context.contentResolver
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("ACK must not insert")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("ACK must not delete")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Atomic batch required")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
                    queries++; return real.query(uri, columns, selection, args, order)
                }
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertEquals(writes, operations.count { it.isUpdate }); assertTrue(operations.all { it.isAssertQuery || it.isUpdate })
                    if (writes == 1) {
                        assertTrue(operations.last().isUpdate)
                        val values = operations.last().resolveValueBackReferences(emptyArray<ContentProviderResult>(), 0)!!
                        assertEquals(setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3), values.keySet())
                        assertEquals(0, values.getAsInteger(Events.DIRTY)); assertEquals(newRevision, values.getAsString(Events.SYNC_DATA2)); assertEquals(marker, values.getAsString(Events.SYNC_DATA3))
                        val snapshots = operations.filter { it.isAssertQuery }.mapNotNull { it.resolveValueBackReferences(emptyArray<ContentProviderResult>(), 0) }
                        val snapshot = snapshots.single { it.containsKey(Events.TITLE) }
                        assertTrue(snapshot.keySet().containsAll(listOf(Events.SYNC_DATA3, Events.SYNC_DATA5, Events.SYNC_DATA6,
                            Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)))
                        assertEquals(oldMarker, snapshot.getAsString(Events.SYNC_DATA3))
                    }
                    before(); batches++; val results = real.applyBatch(CalendarContract.AUTHORITY, operations); after()
                    return if (malformed) emptyArray() else results
                }
            }
            val wrapped = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = ContentResolver.wrap(provider)
                override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                    if (denied && permission in listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) PackageManager.PERMISSION_DENIED
                    else super.checkPermission(permission, pid, uid)
            }
            return AndroidCalendarProjection(wrapped)
        }
        suspend fun close() {
            projection.admitOperation = { true }; projection.removeOwnedCalendar(scope)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(scope.account))
        }
    }
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay); assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    private suspend fun rejected(block: suspend () -> Any?) {
        try { block(); fail("Atomic assertion must reject the changed snapshot") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
    }
    private suspend fun unavailable(block: suspend () -> Any?) {
        try { block(); fail("Missing valid batch reply cannot establish a witness") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_UNAVAILABLE, failure.category) }
    }
    private companion object {
        fun digest(vararg parts: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            parts.forEach { val bytes = it.toByteArray(); digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
