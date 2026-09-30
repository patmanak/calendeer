// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith



/** Real original Provider row and durable journals; fake remote transport only. */
@RunWith(AndroidJUnit4::class)
class CapturedCreateDispatchIntegrationDeviceTest {
    @Test fun dispatchPreservesTheOriginalDirtyRowAndUnknownInspectionAfterDeletion() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare()); val before = providerSnapshot()
        assertTrue(submit() is CreateSubmissionObservation.Verified)
        assertEquals(before, providerSnapshot()); assertEquals("1", field(Events.DIRTY))
        assertNull(field(Events._SYNC_ID)); assertNull(field(Events.SYNC_DATA1))
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, checkNotNull(record()).state); assertEquals(1, sends)
        mutate(ContentValues().apply { put(Events.DELETED, 1) }); reopen()
        assertTrue(submit() is CreateSubmissionObservation.Verified)
        assertEquals(1, sends); assertEquals(1, inspections); assertEquals("1", field(Events.DELETED))
        assertEquals("1", field(Events.DIRTY))
    }
    @Test fun actualContentEditedDuringFreshContextCannotClaimOrUpload() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare())
        beforeClaim = { mutate(ContentValues().apply { put(Events.TITLE, "Concurrent captured Provider edit") }) }
        assertEquals(CreateSubmissionObservation.Unknown, submit()); assertEquals(0, sends)
        assertEquals(CreateAttemptState.PREPARED, checkNotNull(record()).state)
        assertEquals("Concurrent captured Provider edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun newReminderDuringFreshContextCannotBeSilentlyDiscarded() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare())
        beforeClaim = {
            val reminders = uri.buildUpon().path(CalendarContract.Reminders.CONTENT_URI.path).build()
            assertNotNull(context.contentResolver.insert(reminders, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, row); put(CalendarContract.Reminders.MINUTES, 10)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }))
        }
        assertEquals(CreateSubmissionObservation.Unknown, submit()); assertEquals(0, sends)
        assertEquals(CreateAttemptState.PREPARED, checkNotNull(record()).state)
        assertEquals("1", field(Events.DIRTY)); assertNull(field(Events._SYNC_ID))
    }
    @Test fun reopenedCompletedStagingNeverRearmsDispatchOfAHistoricalRow() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare()); val before = providerSnapshot(); reopen()
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare())
        assertEquals(CreateSubmissionObservation.Unknown, submit()); assertNull(record()); assertEquals(0, sends)
        assertEquals(before, providerSnapshot()); assertEquals(1, calls)
    }
    private class Fixture(val context: Context) {
        private val token = UUID.randomUUID().toString()
        private val importName = "dispatch-integration-import-$token.db"
        private val dispatchName = "captured-provider-dispatch-$token.db"
        private val journalName = "dispatch-integration-capture-$token.db"
        val target = CalendarScope(AccountId("dispatch-integration-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("dispatch-integration-import", 1)
        val preparationFormat = AdapterFormat("controlled-known-empty-submission", 2)
        val localId = LocalEventId(UUID.randomUUID().toString())
        val id = MutationId("create")
        val attempt = MutationId(UUID.randomUUID().toString())
        val timestamp = Instant.parse("2030-10-01T12:00:00Z")
        val content = EventContent("Preparation integration fixture",
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails())
        val intent = LocalCreateIntent(LocalCreateReservation(id, localId, target, format, timestamp), content)
        val projection = AndroidCalendarProjection(context)
        val store = ImportStore(context, importName)
        val creates = CreateAttemptStore(context, dispatchName)
        var journal = LocalCreateStore(context, journalName)
        var calendar = -1L
        var row = -1L
        var sends = 0
        var inspections = 0
        var beforeClaim: suspend () -> Unit = { }
        var calls = 0
        fun receipt() = PreparedCalendarCreate(target, preparationFormat, "synthetic-protected-preparation-$calls")
        var onPrepare: suspend (CalendarCreateDraft) -> PreparedCalendarCreate = { receipt() }
        val port = object : CalendarCreateSubmissionPort {
            override val knownEmptySubmissionFormat: AdapterFormat get() = preparationFormat
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate {
                assertEquals(CalendarCreateDraft(target, attempt, timestamp, content), draft)
                calls++; return onPrepare(draft)
            }
                        override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = error("No generic upload")
            override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft, journal: CreateAttemptJournal): CreateSubmissionObservation {
                assertEquals(CalendarCreateDraft(target, attempt, timestamp, content), expected)
                beforeClaim()
                if (!journal.claim(record)) return CreateSubmissionObservation.Unknown
                sends++
                return CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), RemoteChange.Upsert(
                    EventId("synthetic-remote"), Revision("synthetic-revision"), content))
            }
            override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft): CreateSubmissionObservation {
                inspections++
                return CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), RemoteChange.Upsert(
                    EventId("synthetic-remote"), Revision("synthetic-revision"), content))
            }
            override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = error("No remote inspection")
        }
        val uri get() = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = target.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No imports")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No dispatch")
        }
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, creates = creates, localCreates = journal)
        suspend fun seed() {
            val summary = CalendarSummary(target.calendar, "Calendeer preparation integration fixture")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true) }
            calendar = projection.ensureCalendar(target, summary)
            row = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues())!!)
            start()
            assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalCreate(target, row, localId, id, timestamp))
        }
        private suspend fun start() {
            val before = schedules.get(); coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
        }
        fun candidateValues() = ContentValues().apply {
            val time = content.time as EventTime.Timed
            put(Events.CALENDAR_ID, calendar); put(Events.TITLE, content.title); put(Events.DESCRIPTION, content.description)
            put(Events.EVENT_LOCATION, content.location); put(Events.DIRTY, 1); put(Events.DELETED, 0)
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.HAS_ATTENDEE_DATA, 0); put(Events.HAS_ALARM, 0)
            putNull(Events.ORGANIZER); put(Events.IS_ORGANIZER, 0); put(Events.GUESTS_CAN_MODIFY, 0)
            put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            putNull(Events.EVENT_COLOR); putNull(Events.EVENT_COLOR_KEY)
            put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
            put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
        }
        suspend fun prepare() = coordinator.stageLocalCreateSubmission(target, localId, id, attempt, port)
        suspend fun submit() = coordinator.submitLocalCreate(target, localId, id, attempt, port)
        suspend fun record() = creates.read(target, attempt)
        suspend fun retainedSubmission(): LocalCreateSubmission? {
            journal.admitCapture = { it == target }; return journal.readSubmission(target, localId)
        }
        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun providerSnapshot() = listOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.DIRTY, Events.DELETED,
            Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5,
            Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10).associateWith(::field)
        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(uri, values, "${Events._ID}=?", arrayOf(row.toString())))
        }
        fun failPreparation(enabled: Boolean) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(journalName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (enabled) db.execSQL("CREATE TRIGGER fixture_fail_paused_prep BEFORE UPDATE OF payload ON local_create_submissions " +
                    "WHEN NEW.payload IS NOT NULL BEGIN SELECT RAISE(ABORT, 'fixture preparation commit interrupted'); END")
                else db.execSQL("DROP TRIGGER IF EXISTS fixture_fail_paused_prep")
            }
        }
        suspend fun reopen() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin(); journal.close()
            journal = LocalCreateStore(context, journalName); jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, creates = creates, localCreates = journal); start()
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close(); creates.close(); context.deleteDatabase(dispatchName); context.deleteDatabase(importName); context.deleteDatabase(journalName)
            projection.admitOperation = { true }; projection.removeOwnedCalendar(target)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
