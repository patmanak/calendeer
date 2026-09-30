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


/** Real Provider capture/staging, controlled submission preparation; no transport. */
@RunWith(AndroidJUnit4::class)
class LocalCreateSubmissionIntegrationDeviceTest {
    @Test fun currentCapturedRowStagesAndReopensWithoutProviderAcknowledgementOrCryptoRetry() = fixture {
        val baseline = providerSnapshot()
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare())
        val retained = retainedSubmission()
        assertNotNull(retained); assertEquals(intent, retained!!.reservation.intent)
        assertEquals(baseline, providerSnapshot()); assertEquals("1", field(Events.DIRTY))
        assertNull(field(Events._SYNC_ID)); assertNull(field(Events.SYNC_DATA1))
        reopen(); onPrepare = { error("Never prepare retained work again") }
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare())
        assertEquals(retained, retainedSubmission()); assertEquals(baseline, providerSnapshot()); assertEquals(1, calls)
    }

    @Test fun providerChangesDuringPreparationPreserveTheGapAndAllFlags() = fixture {
        onPrepare = { mutate(ContentValues().apply { put(Events.TITLE, "Concurrent synthetic edit") }); receipt() }
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare()); assertNull(retainedSubmission())
        assertEquals("Concurrent synthetic edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        mutate(ContentValues().apply { put(Events.TITLE, content.title) })
        assertEquals(LocalCreateSubmissionResult.UNRESOLVED, prepare()); assertEquals(1, calls)
    }

    @Test fun retainedWorkCannotQualifyADeletedRowOrNewPersonalReminder() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare()); val retained = retainedSubmission()
        mutate(ContentValues().apply { put(Events.DELETED, 1) })
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare())
        mutate(ContentValues().apply { put(Events.DELETED, 0) })
        val remindersUri = uri.buildUpon().path(CalendarContract.Reminders.CONTENT_URI.path).build()
        assertNotNull(context.contentResolver.insert(remindersUri, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, row); put(CalendarContract.Reminders.MINUTES, 10)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }))
        context.contentResolver.query(remindersUri, arrayOf(CalendarContract.Reminders._ID),
            "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(row.toString()), null)!!.use { assertEquals(1, it.count) }
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare())
        assertEquals(retained, retainedSubmission()); assertEquals(1, calls)
    }

    @Test fun duplicateMarkerTombstoneRefusesBeforeReservingOrReadingProton() = fixture {
        assertNotNull(context.contentResolver.insert(uri, candidateValues().apply {
            put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)); put(Events.DELETED, 1)
        }))
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare()); assertEquals(0, calls)
        journal.admitCapture = { it == target }; assertNull(journal.readSubmissionReservation(target, localId))
    }

    @Test fun failedPayloadCommitIsInspectOnlyAfterReopen() = fixture {
        failPreparation(true)
        assertEquals(LocalCreateSubmissionResult.UNAVAILABLE, prepare()); assertNull(retainedSubmission())
        failPreparation(false); reopen()
        assertEquals(LocalCreateSubmissionResult.UNRESOLVED, prepare()); assertEquals(1, calls)
        assertEquals("1", field(Events.DIRTY)); assertNull(field(Events._SYNC_ID))
        assertNull(retainedSubmission())
    }
    private class Fixture(val context: Context) {
        private val token = UUID.randomUUID().toString()
        private val importName = "stage-integration-import-$token.db"
        private val journalName = "stage-integration-capture-$token.db"
        val target = CalendarScope(AccountId("stage-integration-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("stage-integration-import", 1)
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
        var journal = LocalCreateStore(context, journalName)
        var calendar = -1L
        var row = -1L
        var calls = 0
        fun receipt() = PreparedCalendarCreate(target, preparationFormat, "synthetic-protected-preparation-$calls")
        var onPrepare: suspend (CalendarCreateDraft) -> PreparedCalendarCreate = { receipt() }
        val port = object : CalendarCreateSubmissionPort {
            override val knownEmptySubmissionFormat: AdapterFormat get() = preparationFormat
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate {
                assertEquals(CalendarCreateDraft(target, attempt, timestamp, content), draft)
                calls++; return onPrepare(draft)
            }
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = error("No upload")
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
        var coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, localCreates = journal)
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
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, localCreates = journal); start()
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close(); context.deleteDatabase(importName); context.deleteDatabase(journalName)
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
