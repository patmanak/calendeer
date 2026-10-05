// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated Provider and durable journals with a controlled submission port; no Proton IO. */
@RunWith(AndroidJUnit4::class)
class SystemCreatePipelineDeviceTest {
    @Test fun offlineCaptureThenOnlineCreateAttachesAcknowledgesAndHandsOffTheSameRow() = fixture {
        val original = providerSnapshot()
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false))
        val captured = work.read(target, row)!!
        assertEquals(SystemCreateWorkStatus.CAPTURED, captured.status)
        assertEquals(0, preparations); assertEquals(0, sends); assertEquals(0, inspections)
        assertNotNull(field(Events.SYNC_DATA5)); assertEquals("1", field(Events.DIRTY))
        assertEquals(original - Events.SYNC_DATA5, providerSnapshot() - Events.SYNC_DATA5)
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
        val completed = work.read(target, row)!!
        assertSameIdentity(captured, completed)
        assertEquals(SystemCreateWorkStatus.COMPLETE, completed.status)
        assertEquals(1, preparations); assertEquals(1, sends)
        assertEquals(1, rows()); assertEquals("0", field(Events.DIRTY))
        assertNotNull(field(Events.SYNC_DATA4))
        assertNotNull(field(Events.SYNC_DATA6)); assertNotNull(field(Events.SYNC_DATA7))
        val released = handoff()!!
        assertEquals(CapturedCreateHandoffState.RELEASED, released.state)
        // The handoff reader validates the historical ACK's PROVEN state and exact fingerprint.
        // The pre-handoff ACK reader intentionally refuses a transferred captured baseline.
        val acknowledged = released.frame.receipt.acknowledgement
        assertEquals(remoteEvent, acknowledged.baseline.change)
        assertEquals(remoteEvent, released.frame.base)
        val identity = acknowledged.baseline.receipt.binding.identity
        assertEquals(target, identity.attempt.scope)
        assertEquals(captured.attempt, identity.attempt.id)
        assertEquals(captured.localId, identity.localId)
        assertEquals(captured.captureId, identity.captureId)
        assertEquals(captured.timestamp, identity.timestamp)
        assertTrue(identity.matchesContent(content))
        assertEquals(0, work.count(target))
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
        assertEquals(1, sends); assertEquals(1, rows())
    }

    @Test fun lostResponseAndRestartInspectUnknownWithoutAnotherSendOrNewIdentity() = fixture {
        loseResponse = true
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
        val unknown = work.read(target, row)!!
        assertEquals(SystemCreateWorkStatus.UNKNOWN, unknown.status)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, attempts.read(target, unknown.attempt)!!.state)
        assertEquals(1, sends); assertEquals("1", field(Events.DIRTY))
        val before = providerSnapshot()
        reopen()
        assertSameIdentity(unknown, work.read(target, row)!!)
        assertEquals(before, providerSnapshot())
        loseResponse = false
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
        assertEquals(1, preparations); assertEquals(1, sends); assertTrue(inspections > 0)
        val completed = work.read(target, row)!!
        assertSameIdentity(unknown, completed)
        assertEquals(SystemCreateWorkStatus.COMPLETE, completed.status)
        assertEquals(1, rows()); assertEquals("0", field(Events.DIRTY))
    }

    @Test fun exactSystemSelfOrganizerProfileCompletesOfflineAndOnlineWithoutNormalizingTheRow() = fixture {
        mutate(ContentValues().apply {
            put(Events.ORGANIZER, projection.accountFor(target.account).name)
            put(Events.HAS_ATTENDEE_DATA, 1); put(Events.IS_ORGANIZER, 1)
            put(Events.GUESTS_CAN_INVITE_OTHERS, 1); put(Events.GUESTS_CAN_SEE_GUESTS, 1)
            put(Events.GUESTS_CAN_MODIFY, 0); put(Events.HAS_ALARM, 0)
            put(Events.ACCESS_LEVEL, 0)
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            putNull(Events.EVENT_END_TIMEZONE)
        })
        val before = providerSnapshot()
        assertEquals("0", before[Events.SELF_ATTENDEE_STATUS])
        assertNull(before[Events.EVENT_END_TIMEZONE])
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false))
        val captured = work.read(target, row)!!
        assertEquals(SystemCreateWorkStatus.CAPTURED, captured.status)
        assertEquals(before - Events.SYNC_DATA5, providerSnapshot() - Events.SYNC_DATA5)
        assertEquals(0, preparations); assertEquals(0, sends)
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
        val completed = work.read(target, row)!!
        assertSameIdentity(captured, completed)
        assertEquals(SystemCreateWorkStatus.COMPLETE, completed.status)
        assertEquals(1, preparations); assertEquals(1, sends); assertEquals(1, rows())
        assertEquals("0", field(Events.DIRTY))
        val ownMetadata = setOf(Events.DIRTY, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2,
            Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7)
        assertEquals(before - ownMetadata, providerSnapshot() - ownMetadata)
        assertNull(field(Events.EVENT_END_TIMEZONE))
        assertEquals(projection.accountFor(target.account).name, field(Events.ORGANIZER))
        val released = handoff()!!
        assertEquals(CapturedCreateHandoffState.RELEASED, released.state)
        assertEquals(remoteEvent, released.frame.receipt.acknowledgement.baseline.change)
        assertEquals(captured.localId, released.frame.receipt.acknowledgement.baseline.receipt.binding.identity.localId)
        assertEquals(captured.attempt, released.frame.receipt.acknowledgement.baseline.receipt.binding.identity.attempt.id)
        assertNotNull(field(Events.SYNC_DATA7)); assertEquals(0, work.count(target))
    }

    @Test fun localEditAfterClaimIsRetainedWithoutCleaningOrAcknowledgingIt() = fixture {
        afterClaim = { mutate(ContentValues().apply { put(Events.TITLE, "Synthetic edit after claim"); put(Events.DIRTY, 1) }) }
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
        val retained = work.read(target, row)!!
        assertEquals(1, sends); assertEquals(1, preparations)
        assertEquals(SystemCreateWorkStatus.WAITING, retained.status)
        assertEquals("Synthetic edit after claim", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        assertEquals(content, remoteEvent.content)
        assertNull(acknowledgement()); assertNull(handoff())
        val change = localEdit()!!
        assertEquals(remoteEvent, change.base)
        assertEquals("Synthetic edit after claim", change.content.title)
        val before = providerSnapshot()
        afterClaim = {}
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
        assertEquals(1, sends); assertEquals(before, providerSnapshot())
        assertEquals(change, localEdit()); assertSameIdentity(retained, work.read(target, row)!!)
    }

    @Test fun disableAndReenableWhileAdapterAwaitsCannotAuthorizeTheOldClaim() = fixture {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeClaim = { reached.complete(Unit); release.await() }
        coroutineScope {
            val running = async { controller.run(network = true) }
            withTimeout(10_000) { reached.await() }
            val selection = coordinator.state.value.selections.single { it.scope == target }
            controller.setEnabled(selection, false)
            controller.setEnabled(selection, true)
            assertTrue(work.modeEnabled(target))
            release.complete(Unit)
            withTimeout(10_000) { running.await() }
        }
        assertEquals(0, sends); assertEquals("1", field(Events.DIRTY))
        val retained = work.read(target, row)!!
        assertNotEquals(SystemCreateWorkStatus.COMPLETE, retained.status)
        assertNotEquals(CreateAttemptState.OUTCOME_UNKNOWN, attempts.read(target, retained.attempt)?.state)
        assertNull(field(Events.SYNC_DATA4)); assertNull(acknowledgement())
    }

    @Test fun restartAfterOfflineCaptureKeepsTheOriginalIdentifiersAndCanSendOnline() = fixture {
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false))
        val original = work.read(target, row)!!
        val marker = field(Events.SYNC_DATA5)
        reopen()
        assertEquals(original, work.read(target, row)); assertEquals(marker, field(Events.SYNC_DATA5))
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false))
        assertEquals(0, preparations); assertEquals(0, sends)
        assertSameIdentity(original, work.read(target, row)!!)
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
        assertSameIdentity(original, work.read(target, row)!!)
        assertEquals(SystemCreateWorkStatus.COMPLETE, work.read(target, row)!!.status)
        assertEquals(1, preparations); assertEquals(1, sends); assertEquals(1, rows())
    }

    @Test fun unsupportedPersonalReminderPreservesTheWholeNativeCandidateAndNeverSends() = fixture {
        val reminder = context.contentResolver.insert(syncUri(Reminders.CONTENT_URI), ContentValues().apply {
            put(Reminders.EVENT_ID, row); put(Reminders.MINUTES, 15); put(Reminders.METHOD, Reminders.METHOD_ALERT)
        })!!
        val before = providerSnapshot()
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false))
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
        assertEquals(0, preparations); assertEquals(0, sends)
        assertEquals(before, providerSnapshot()); assertNull(field(Events.SYNC_DATA5))
        assertEquals("1", field(Events.DIRTY)); assertEquals(1, rows())
        context.contentResolver.query(reminder, arrayOf(Reminders.MINUTES, Reminders.METHOD), null, null, null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst())
            assertEquals(15, it.getInt(0)); assertEquals(Reminders.METHOD_ALERT, it.getInt(1))
        }
        assertNotEquals(SystemCreateWorkStatus.COMPLETE, work.read(target, row)!!.status)
    }

    @Test fun pausingImportClosesEditorAccessAndDisablingTheModeNeverCreatesAnInactiveCalendar() = fixture {
        assertTrue(coordinator.configureSystemCalendar(target))
        assertEquals(CalendarContract.Calendars.CAL_ACCESS_EDITOR, calendarAccess())
        val before = providerSnapshot()
        val selection = coordinator.state.value.selections.single { it.scope == target }
        coordinator.setEnabled(selection, false)
        withTimeout(10_000) {
            while (coordinator.state.value.selections.single { it.scope == target }.enabled) delay(10)
        }
        assertTrue(coordinator.configureSystemCalendar(target))
        assertEquals(CalendarContract.Calendars.CAL_ACCESS_READ, calendarAccess())
        assertTrue(work.modeEnabled(target))
        val paused = coordinator.state.value.selections.single { it.scope == target }
        controller.setEnabled(paused, false)
        assertFalse(work.modeEnabled(target))
        assertTrue(coordinator.configureSystemCalendar(target))
        assertEquals(CalendarContract.Calendars.CAL_ACCESS_READ, calendarAccess())
        val calendarCount = calendarRows()
        coordinator.configureSystemCalendar(target.copy(calendar = CalendarId("missing-fixture-calendar")))
        coordinator.configureSystemCalendar(target.copy(generation = 1))
        assertEquals(calendarCount, calendarRows())
        assertEquals(1, calendarCount); assertEquals(before, providerSnapshot())
        assertEquals(0, preparations); assertEquals(0, sends)
    }

    @Test fun waitingAndUnknownWorkRemainsVisibleAndUnchangedAfterTheModeIsDisabled() {
        for (status in listOf(SystemCreateWorkStatus.WAITING, SystemCreateWorkStatus.UNKNOWN)) fixture {
            if (status == SystemCreateWorkStatus.UNKNOWN) loseResponse = true
            else afterClaim = { mutate(ContentValues().apply { put(Events.TITLE, "Pending synthetic local edit"); put(Events.DIRTY, 1) }) }
            assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
            val retained = work.read(target, row)!!
            assertEquals(status, retained.status)
            assertEquals(1, controller.state.value.pending[target])
            val before = providerSnapshot()
            val selection = coordinator.state.value.selections.single { it.scope == target }
            controller.setEnabled(selection, false)
            withTimeout(10_000) {
                while (target in controller.state.value.enabled || controller.state.value.pending[target] != 1) delay(10)
            }
            assertEquals(1, controller.state.value.pending[target])
            assertEquals(retained, work.read(target, row)); assertEquals(1, work.count(target))
            assertEquals(before, providerSnapshot())
            assertEquals(ImportRunResult.COMPLETE, controller.run(network = true))
            assertEquals(1, sends); assertEquals(retained, work.read(target, row))
            assertEquals(1, controller.state.value.pending[target])
            assertEquals(CalendarContract.Calendars.CAL_ACCESS_READ, calendarAccess())
        }
    }

    @Test fun signingOutClosesTheRetainedEditorCalendarWithoutChangingItsEvents() = fixture {
        assertTrue(coordinator.configureSystemCalendar(target))
        assertEquals(CalendarContract.Calendars.CAL_ACCESS_EDITOR, calendarAccess())
        val before = providerSnapshot()
        coordinator.authenticationChanged(false)
        assertFalse(coordinator.state.value.authenticated)
        coordinator.reconcileSystemCalendarAccess()
        assertEquals(CalendarContract.Calendars.CAL_ACCESS_READ, calendarAccess())
        assertEquals(before, providerSnapshot()); assertEquals(1, rows()); assertEquals(1, calendarRows())
        assertTrue(work.modeEnabled(target))
        assertEquals(0, preparations); assertEquals(0, sends)
    }

    private class Fixture(base: Context) {
        private val token = UUID.randomUUID().toString()
        private val names = listOf("system-pipeline-import-$token.db", "system-pipeline-capture-$token.db",
            "system-pipeline-attempt-$token.db", "system-pipeline-work-$token.db")
        private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = "system-pipeline-$token-$name"
                preferences.add(actual)
                return base.getSharedPreferences(actual, mode)
            }
        }
        val target = CalendarScope(AccountId("system-pipeline-$token"), CalendarId("fixture"), 0)
        private val importFormat = AdapterFormat("system-pipeline-import", 1)
        private val submissionFormat = AdapterFormat("controlled-known-empty-submission", 2)
        val content = EventContent("Controlled system creation fixture",
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails())
        val remoteEvent = RemoteChange.Upsert(EventId("controlled-created-event"), Revision("controlled-created-revision"), content)
        val projection = AndroidCalendarProjection(context)
        var store = ImportStore(context, names[0])
        var captures = LocalCreateStore(context, names[1])
        var attempts = CreateAttemptStore(context, names[2])
        var work = SystemCreateWorkStore(context, names[3])
        var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var calendar = -1L
        var row = -1L
        var preparations = 0
        var sends = 0
        var inspections = 0
        var loseResponse = false
        var beforeClaim: suspend () -> Unit = {}
        var afterClaim: suspend () -> Unit = {}
        private var created = false
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = importFormat
            override suspend fun currentAccount() = target.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No transport in controlled fixture")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No generic dispatch")
        }
        private val port = object : CalendarCreateSubmissionPort {
            override val knownEmptySubmissionFormat = submissionFormat
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate {
                assertDraft(draft)
                preparations++
                return PreparedCalendarCreate(target, submissionFormat, "controlled-opaque-preparation")
            }
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation =
                error("No generic submission")
            override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = error("No generic inspection")
            override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft,
                journal: CreateAttemptJournal): CreateSubmissionObservation {
                assertDraft(expected)
                assertEquals(expected.correlation, record.id)
                beforeClaim()
                if (!journal.claim(record)) return CreateSubmissionObservation.Unknown
                sends++; created = true
                assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, attempts.read(target, record.id)!!.state)
                afterClaim()
                if (loseResponse) throw IllegalStateException("Controlled response loss after claim")
                return CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), remoteEvent)
            }
            override suspend fun inspectCaptured(record: CreateAttemptRecord,
                expected: CalendarCreateDraft): CreateSubmissionObservation {
                inspections++; assertDraft(expected)
                assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, record.state)
                return if (created) CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), remoteEvent)
                    else CreateSubmissionObservation.Unknown
            }
        }
        var coordinator = newCoordinator()
        var controller = newController()
        private fun newCoordinator() = ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            creates = attempts, localCreates = captures, systemCreatesEnabled = work::modeEnabled)
        private fun newController() = SystemCreateController(context, coordinator, projection, work, captures,
            attempts, port, jobs, scheduler)

        suspend fun seed() {
            val summary = CalendarSummary(target.calendar, "Controlled system creation fixture")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            store.transaction(target) { checkpoint = PageCheckpoint(target, importFormat, traversalComplete = true) }
            calendar = projection.ensureCalendar(target, summary)
            row = ContentUris.parseId(context.contentResolver.insert(syncUri(Events.CONTENT_URI), candidateValues())!!)
            work.setMode(target, true)
            startOwner()
        }

        private suspend fun startOwner() {
            val before = schedules.get()
            coordinator.authenticationChanged(true)
            withTimeout(10_000) {
                while (schedules.get() == before || !coordinator.state.value.authenticated ||
                    !coordinator.state.value.hasPermission || coordinator.state.value.selections.none { it.scope == target && it.enabled }) delay(10)
            }
        }

        private suspend fun assertDraft(draft: CalendarCreateDraft) {
            val retained = work.read(target, row)!!
            assertEquals(CalendarCreateDraft(target, retained.attempt, retained.timestamp, content), draft)
        }

        fun syncUri(uri: android.net.Uri) = uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()

        private fun candidateValues() = ContentValues().apply {
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

        fun field(column: String): String? = context.contentResolver.query(syncUri(Events.CONTENT_URI), arrayOf(column),
            "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun providerSnapshot() = listOf(Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND,
            Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY,
            Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA, Events.STATUS, Events.AVAILABILITY, Events.DIRTY, Events.DELETED,
            Events.ORGANIZER, Events.IS_ORGANIZER, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS,
            Events.GUESTS_CAN_MODIFY, Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL,
            Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5,
            Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10).associateWith(::field)
        fun mutate(values: ContentValues) { assertEquals(1, context.contentResolver.update(syncUri(Events.CONTENT_URI),
            values, "${Events._ID}=?", arrayOf(row.toString()))) }
        fun rows() = context.contentResolver.query(syncUri(Events.CONTENT_URI), arrayOf(Events._ID),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { it.count }

        fun calendarAccess() = context.contentResolver.query(syncUri(CalendarContract.Calendars.CONTENT_URI),
            arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL), "${CalendarContract.Calendars._ID}=?",
            arrayOf(calendar.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getInt(0)
        }
        fun calendarRows(): Int {
            val account = projection.accountFor(target.account)
            return context.contentResolver.query(syncUri(CalendarContract.Calendars.CONTENT_URI),
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
                arrayOf(account.name, account.type), null)!!.use { it.count }
        }

        private suspend fun <T> stored(block: suspend () -> T): T {
            val previous = store.admitTransaction
            store.admitTransaction = { it == target }
            try { return block() } finally { store.admitTransaction = previous }
        }
        suspend fun handoff() = stored { store.capturedHandoff(target, remoteEvent.eventId) }
        suspend fun acknowledgement() = stored { store.capturedAcknowledgement(target, remoteEvent.eventId) }
        suspend fun localEdit() = stored { store.localEdit(target, remoteEvent.eventId) }

        suspend fun reopen() {
            coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); captures.close(); attempts.close(); work.close()
            store = ImportStore(context, names[0]); captures = LocalCreateStore(context, names[1])
            attempts = CreateAttemptStore(context, names[2]); work = SystemCreateWorkStore(context, names[3])
            jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = newCoordinator(); controller = newController()
            startOwner()
        }

        suspend fun close() {
            val selection = coordinator.state.value.selections.singleOrNull { it.scope == target }
            if (selection != null && target in controller.state.value.enabled) {
                controller.setEnabled(selection, false)
                withTimeout(10_000) { while (target in controller.state.value.enabled) delay(10) }
            } else work.setMode(target, false)
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); captures.close(); attempts.close(); work.close()
            names.forEach { context.deleteDatabase(it) }
            projection.admitOperation = { true }; projection.removeOwnedCalendar(target)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
            synchronized(preferences) { preferences.forEach { context.baseContext.deleteSharedPreferences(it) } }
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }

    private fun assertSameIdentity(expected: SystemCreateWork, actual: SystemCreateWork) {
        assertEquals(expected.copy(status = actual.status), actual)
    }
}
