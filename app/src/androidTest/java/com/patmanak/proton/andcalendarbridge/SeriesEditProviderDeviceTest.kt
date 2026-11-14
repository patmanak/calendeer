// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated native snapshots only, no client UI, account session or service access. */
@RunWith(AndroidJUnit4::class)
class SeriesEditProviderDeviceTest {
    @Test fun actualSeriesSnapshotSurvivesOfflineSqlCaptureAndReopeningWithoutCleaningTheProvider() {
        for (allDay in listOf(false, true)) fixture(allDay, EventColorMode.CALENDAR) {
            val name = "series-capture-integration-${UUID.randomUUID()}.db"
            val store = ImportStore(context, name)
            val format = AdapterFormat("native-series-fixture", 1)
            val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scheduled = AtomicBoolean()
            val scheduler = object : ImportScheduler {
                override fun cancel() = Unit
                override fun requestLocal() = Unit
                override fun requestNetwork() { scheduled.set(true) }
                override fun continueWork(network: Boolean) = Unit
            }
            val remote = object : ReadOnlyCalendarRemote {
                override val format = format
                override suspend fun currentAccount() = scope.account
                override suspend fun fetch(request: PageRequest): RemotePage = error("Offline capture cannot download")
                override suspend fun dispatch(mutation: Mutation): DispatchResult = error("Offline capture cannot upload")
            }
            val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
            try {
                store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Series fixture"))); store.setEnabled(scope, true)
                store.transaction(scope) {
                    putRemote(base); putProjection(ProjectionIntent(scope, base))
                    checkpoint = PageCheckpoint(scope, format, traversalComplete = true)
                }
                assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
                mutate(ContentValues().apply { put(Events.TITLE, "Offline series edit"); put(Events.RRULE, "FREQ=DAILY;COUNT=8") })
                coordinator.authenticationChanged(true)
                withTimeout(10_000) { while (!scheduled.get()) delay(10) }
                assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalSeriesEdit(scope, base.eventId, MutationId("series-edit")))
                coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin(); store.close()
                ImportStore(context, name).use { reopened ->
                    val intent = reopened.localEdit(scope, base.eventId)!!
                    assertEquals(base, intent.base); assertEquals("Offline series edit", intent.content.title)
                    assertEquals(SeriesEditProfile(CreateSeriesRule(5), CreateSeriesRule(8)), intent.seriesProfile)
                    assertEquals(base.content.colorRgb, intent.content.colorRgb)
                    assertEquals(base.content.details, intent.content.details)
                    assertEquals(PersonalPreparationAdmission.UNQUALIFIED, reopened.assessPersonalPreparation(intent))
                    reopened.requeueAll(scope); assertTrue(reopened.pending(scope).isEmpty())
                }
                assertEquals("1", field(Events.DIRTY)); assertEquals("FREQ=DAILY;COUNT=8", field(Events.RRULE))
                assertNull(field(Events.DTEND))
            } finally {
                coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
                store.close(); context.deleteDatabase(name)
            }
        }
    }
    @Test fun dailyMasterCapturesTitleRuleAndPersonalFieldsWithoutChangingItsNativeRepresentation() {
        for (allDay in listOf(false, true)) for (mode in EventColorMode.entries) fixture(allDay, mode) {
            mutate(ContentValues().apply { put(Events.TITLE, "Edited series"); put(Events.RRULE, "COUNT=8;INTERVAL=2;FREQ=DAILY") })
            val before = snapshot()
            val observed = projection.captureOwnedSeriesEdit(scope, base)!!
            assertEquals(base.content.time, observed.content.time)
            assertEquals(base.content.colorRgb, observed.content.colorRgb)
            assertEquals(base.content.details, observed.content.details)
            assertEquals(EventRecurrence(base.content.recurrence!!.uid, "FREQ=DAILY;COUNT=8;INTERVAL=2"), observed.content.recurrence)
            assertEquals("Edited series", observed.content.title)
            assertEquals(before, snapshot()); assertEquals("1", field(Events.DIRTY)); assertNull(field(Events.DTEND))
            assertNull(projection.captureOwnedPersonalEdit(scope, base)); assertNull(projection.captureOwnedSimpleEdit(scope, base))
        }
    }
    @Test fun changedStartAndDurationAreCapturedForTimedAndAllDaySeries() {
        for (allDay in listOf(false, true)) fixture(allDay) {
            val expected = if (allDay) EventTime.AllDay(LocalDate.of(2030, 4, 4), LocalDate.of(2030, 4, 7))
                else EventTime.Timed(Instant.parse("2030-04-04T09:00:00Z"), Instant.parse("2030-04-04T11:30:00Z"), ZoneId.of("UTC"))
            val start = if (allDay) Instant.parse("2030-04-04T00:00:00Z") else Instant.parse("2030-04-04T09:00:00Z")
            mutate(ContentValues().apply {
                put(Events.DTSTART, start.toEpochMilli()); put(Events.DURATION, if (allDay) "P3D" else "PT9000S")
            })
            val before = snapshot()
            val captured = projection.captureOwnedSeriesEdit(scope, base)!!
            assertEquals(expected, captured.content.time); assertEquals(base.content.recurrence, captured.content.recurrence)
            assertEquals(before, snapshot()); assertEquals("1", field(Events.DIRTY)); assertNull(field(Events.DTEND))
        }
    }
    @Test fun reminderAndEventColorEditsAreCapturedWithoutReplacingTheirRows() = fixture {
        mutate(ContentValues().apply { put(Events.EVENT_COLOR, 0xff654321.toInt()) })
        assertEquals(1, context.contentResolver.update(uri(Reminders.CONTENT_URI), ContentValues().apply { put(Reminders.MINUTES, 20) },
            "${Reminders.EVENT_ID}=?", arrayOf(row.toString())))
        val before = snapshot()
        val captured = projection.captureOwnedSeriesEdit(scope, base)!!
        assertEquals(0x654321, captured.content.colorRgb)
        assertEquals(EventDetails(listOf(EventReminder(20))), captured.content.details)
        assertEquals(before, snapshot())
    }
    @Test fun unchangedMasterAndNativeTombstoneDoNotProduceAnEdit() = fixture {
        mutate(ContentValues().apply { put(Events.DIRTY, 1) })
        assertNull(projection.captureOwnedSeriesEdit(scope, base))
        mutate(ContentValues().apply { put(Events.DELETED, 1) })
        assertNull(projection.captureOwnedSeriesEdit(scope, base))
        assertEquals("1", field(Events.DIRTY))
    }
    @Test fun unsupportedNativeFormsAndMetadataArePreserved() {
        for (change in listOf(ContentValues().apply { put(Events.RRULE, "FREQ=WEEKLY;COUNT=5") },
            ContentValues().apply { put(Events.RDATE, "20300402T100000Z") },
            ContentValues().apply { put(Events.EXDATE, "20300402T100000Z") },
            ContentValues().apply { put(Events.DTEND, Instant.parse("2030-04-01T11:00:00Z").toEpochMilli()) },
            ContentValues().apply { put(Events.SYNC_DATA8, "unknown-owner") },
            ContentValues().apply { put(Events._SYNC_ID, "different-series") },
            ContentValues().apply { put(Events.SYNC_DATA2, "different-revision") })) fixture {
            mutate(change)
            val before = snapshot()
            assertNull(projection.captureOwnedSeriesEdit(scope, base)); assertEquals(before, snapshot())
        }
    }
    @Test fun detachedChildrenByLocalOrSyncIdentityIncludingTombstonesRefuseCapture() {
        for (bySync in listOf(false, true)) for (deleted in listOf(false, true)) fixture {
            mutate(ContentValues().apply { put(Events.TITLE, "Edited master") })
            child(bySync, deleted)
            assertNull(projection.captureOwnedSeriesEdit(scope, base))
            assertEquals("1", field(Events.DIRTY))
        }
    }
    @Test fun concurrentChildAndNewerEditCannotPassTheAssertionBatch() {
        for (injectChild in listOf(false, true)) fixture {
            mutate(ContentValues().apply { put(Events.TITLE, "First series edit") })
            var entered = false
            val intercepted = intercepted {
                entered = true
                if (injectChild) child(true, true) else mutate(ContentValues().apply { put(Events.TITLE, "Newer series edit") })
            }
            var refused = false
            try { refused = intercepted.captureOwnedSeriesEdit(scope, base) == null }
            catch (_: CalendarProjectionException) { refused = true }
            assertTrue(entered); assertTrue(refused); assertEquals("1", field(Events.DIRTY))
        }
    }
    @Test fun revokedOperationAdmissionDoesNotReadOrCaptureASeries() = fixture {
        projection.admitOperation = { false }
        var refused = false
        try { refused = projection.captureOwnedSeriesEdit(scope, base) == null }
        catch (_: CalendarProjectionException) { refused = true }
        catch (_: CancellationException) { refused = true }
        finally { projection.admitOperation = { true } }
        assertTrue(refused)
    }

    private class Fixture(val context: Context, allDay: Boolean, mode: EventColorMode) {
        val scope = CalendarScope(AccountId("series-edit-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val projection = AndroidCalendarProjection(context) { mode }
        val base = RemoteChange.Upsert(EventId("master"), Revision("revision"), EventContent("Series fixture",
            if (allDay) EventTime.AllDay(LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 3))
            else EventTime.Timed(Instant.parse("2030-04-01T10:00:00Z"), Instant.parse("2030-04-01T11:00:00Z"), ZoneId.of("UTC")),
            colorRgb = 0x123456, recurrence = EventRecurrence("fixture@calendeer.invalid", "FREQ=DAILY;COUNT=5"),
            details = EventDetails(listOf(EventReminder(10)))))
        var row = -1L
        var calendar = -1L
        suspend fun seed() {
            calendar = projection.ensureCalendar(scope, CalendarSummary(scope.calendar, "Series edit fixture", 0x167d8d))
            projection.apply(ProjectionIntent(scope, base))
            row = context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use { assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0) }
            mutate(ContentValues().apply { put(Events.DIRTY, 1) })
        }
        fun uri(source: Uri): Uri = source.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(scope.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(scope.account).type).build()
        fun mutate(values: ContentValues) { assertEquals(1, context.contentResolver.update(uri(Events.CONTENT_URI), values,
            "${Events._ID}=?", arrayOf(row.toString()))) }
        fun field(column: String): String? = context.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(column),
            "${Events._ID}=?", arrayOf(row.toString()), null)!!.use { assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0) }
        fun snapshot(): Map<String, String?> = listOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.RRULE, Events.DURATION,
            Events.EVENT_COLOR, Events.SYNC_DATA3, Events.DIRTY, Events._SYNC_ID).associateWith(::field)
        fun child(bySync: Boolean, deleted: Boolean) {
            context.contentResolver.insert(uri(Events.CONTENT_URI), ContentValues().apply {
                put(Events.CALENDAR_ID, calendar); put(Events.TITLE, "Synthetic detached child")
                put(Events.DTSTART, Instant.parse("2030-04-02T10:00:00Z").toEpochMilli())
                put(Events.DTEND, Instant.parse("2030-04-02T11:00:00Z").toEpochMilli())
                put(Events.EVENT_TIMEZONE, "UTC"); put(Events.ALL_DAY, 0); put(Events.DELETED, if (deleted) 1 else 0)
                if (bySync) put(Events.ORIGINAL_SYNC_ID, field(Events._SYNC_ID)) else put(Events.ORIGINAL_ID, row)
                put(Events.ORIGINAL_INSTANCE_TIME, Instant.parse("2030-04-02T10:00:00Z").toEpochMilli())
            })!!
        }
        fun intercepted(beforeBatch: () -> Unit): AndroidCalendarProjection {
            val real = context.contentResolver
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Capture cannot insert")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Capture cannot update")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Capture cannot delete")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? =
                    real.query(uri, columns, selection, args, order)
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertTrue(operations.all { it.isAssertQuery }); beforeBatch()
                    return real.applyBatch(CalendarContract.AUTHORITY, operations)
                }
            }
            val resolver = ContentResolver.wrap(provider)
            return AndroidCalendarProjection(object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = resolver
            })
        }
        suspend fun close() {
            projection.admitOperation = { true }; projection.removeOwnedCalendar(scope)
            val account = projection.accountFor(scope.account)
            val manager = AccountManager.get(context)
            if (manager.getAccountsByType(account.type).contains(account)) assertTrue(manager.removeAccountExplicitly(account))
        }
    }
    private fun fixture(allDay: Boolean = false, mode: EventColorMode = EventColorMode.EVENT,
        block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay, mode)
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
