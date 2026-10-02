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
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
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
class CapturedCreateBaselineIntegrationDeviceTest {
    @Test fun exactIncomingCacheIsAcknowledgedWithoutCleaningOriginalOrReleasingHold() = fixture {
        attached(); incoming(verifiedEvent); val before = providerSnapshot()
        assertEquals(CapturedCreateBaselineResult.RETAINED, retain())
        assertEquals(expectedBaseline(), baseline()); assertEquals(verifiedEvent, currentBase()); assertEquals(verifiedEvent, remoteRecord())
        assertEquals(0, pendingCount()); assertEquals(before, providerSnapshot())
        assertTrue(creates.capturedProjectionHold(target, format).holds(verifiedEvent.eventId)); reopen()
        assertEquals(CapturedCreateBaselineResult.RETAINED, retain()); assertEquals(before, providerSnapshot())
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false)); assertEquals(1, rows()); assertEquals(1, sends)
        assertEquals(1, attachmentWrites); assertEquals(1, inspections)
    }
    @Test fun referenceBeforeIncomingDataNeverInventsACacheRecord() = fixture {
        attached(); assertEquals(CapturedCreateBaselineResult.RETAINED, retain()); assertNull(remoteRecord())
        assertEquals(0, pendingCount()); val first = baseline(); incoming(verifiedEvent)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false)); assertEquals(1, pendingCount())
        assertEquals(CapturedCreateBaselineResult.RETAINED, retain()); assertEquals(0, pendingCount()); assertEquals(first, baseline())
    }
    @Test fun laterRemoteUpdateRemainsPendingAgainstTheFirstSentBaseline() = fixture {
        attached(); val later = verifiedEvent.copy(revision = Revision("later"), content = content.copy(title = "Remote update"))
        incoming(later); val before = providerSnapshot(); assertEquals(CapturedCreateBaselineResult.RETAINED, retain())
        assertEquals(verifiedEvent, currentBase()); assertEquals(later, remoteRecord()); assertEquals(1, pendingCount())
        assertEquals(before, providerSnapshot())
    }
    @Test fun equalOpaqueRevisionCannotConsumeDifferentContent() = fixture {
        attached(); val changed = verifiedEvent.copy(content = content.copy(title = "Same revision different content"))
        incoming(changed); assertEquals(CapturedCreateBaselineResult.RETAINED, retain())
        assertEquals(changed, remoteRecord()); assertEquals(1, pendingCount()); assertEquals(verifiedEvent, currentBase())
    }
    @Test fun incomingDeletionDoesNotEraseTheHistoricalReference() = fixture {
        attached(); val deletion = RemoteChange.Deleted(verifiedEvent.eventId, Revision("deleted")); incoming(deletion)
        val before = providerSnapshot(); assertEquals(CapturedCreateBaselineResult.RETAINED, retain())
        assertEquals(deletion, remoteRecord()); assertEquals(1, pendingCount()); assertEquals(verifiedEvent, currentBase())
        assertEquals(before, providerSnapshot())
    }
    @Test fun laterLocalTitleAndReminderStayIntactDuringReferenceRetention() = fixture {
        attached(); mutate(ContentValues().apply { put(Events.TITLE, "Local title after send") })
        val child = CalendarContract.Reminders.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()
        context.contentResolver.insert(child, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, row); put(CalendarContract.Reminders.MINUTES, 30)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })!!
        val before = providerSnapshot(); incoming(verifiedEvent); assertEquals(CapturedCreateBaselineResult.RETAINED, retain())
        assertEquals(before, providerSnapshot()); assertEquals(verifiedEvent, currentBase()); assertEquals("1", field(Events.DIRTY))
        context.contentResolver.query(child, arrayOf(CalendarContract.Reminders.MINUTES), "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(30, it.getInt(0))
        }
    }
    @Test fun laterTombstoneRetainsReferenceWithoutAcknowledgingLocalDeletion() = fixture {
        attached(); mutate(ContentValues().apply { put(Events.DELETED, 1) }); val before = providerSnapshot()
        assertEquals(CapturedCreateBaselineResult.RETAINED, retain()); assertEquals(before, providerSnapshot())
        assertEquals(verifiedEvent, currentBase()); assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }
    @Test fun purgedOriginalCannotEstablishAReferenceOrRecreateARow() = fixture {
        attached(); incoming(verifiedEvent); assertEquals(1, context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString())))
        assertEquals(CapturedCreateBaselineResult.UNRESOLVED, retain()); assertNull(baseline()); assertEquals(1, pendingCount())
        assertEquals(0, rows())
    }
    @Test fun damagedProviderReceiptCannotEstablishAReference() = fixture {
        attached(); mutate(ContentValues().apply { put(Events.SYNC_DATA6, "damaged") }); val before = providerSnapshot()
        assertEquals(CapturedCreateBaselineResult.UNRESOLVED, retain()); assertNull(baseline()); assertEquals(before, providerSnapshot())
    }
    @Test fun observedAccountLossDuringInspectionCannotCommitReference() = fixture {
        attached(); incoming(verifiedEvent); beforeInspection = { coordinator.authenticationChanged(false) }
        assertEquals(CapturedCreateBaselineResult.INACTIVE, retain()); assertNull(baseline()); assertEquals(1, pendingCount())
    }
    @Test fun controlledPermissionLossAfterInspectionCannotCommitReference() = fixture {
        attached(); incoming(verifiedEvent); afterInspection = { permitted = false }
        assertEquals(CapturedCreateBaselineResult.INACTIVE, retain()); assertNull(baseline()); assertEquals(1, pendingCount())
    }
    @Test fun disabledSelectionLeavesReferenceAndCacheUnacknowledged() = fixture {
        attached(); incoming(verifiedEvent); allowStore(); store.setEnabled(target, false)
        assertEquals(CapturedCreateBaselineResult.INACTIVE, retain()); assertNull(baseline()); assertEquals(1, pendingCount())
    }
    @Test fun adapterReplacementCannotRetainAnOldReference() = fixture {
        attached(); currentFormat = AdapterFormat("replacement", 1)
        assertEquals(CapturedCreateBaselineResult.UNRESOLVED, retain()); assertNull(baseline())
    }
    @Test fun pendingAcknowledgementFailureRollsBackReferenceAndProvenanceTogether() = fixture {
        attached(); incoming(verifiedEvent); val before = providerSnapshot()
        store.writableDatabase.execSQL("CREATE TRIGGER fixture_fail_capture_cas BEFORE UPDATE OF pending ON records " +
            "WHEN NEW.pending=0 BEGIN SELECT RAISE(ABORT, 'Synthetic CAS failure'); END")
        assertEquals(CapturedCreateBaselineResult.UNRESOLVED, retain()); assertNull(baseline()); assertNull(currentBase())
        assertEquals(1, pendingCount()); assertEquals(before, providerSnapshot())
        store.writableDatabase.execSQL("DROP TRIGGER fixture_fail_capture_cas")
        assertEquals(CapturedCreateBaselineResult.RETAINED, retain()); assertEquals(0, pendingCount())
    }
    @Test fun accessLossAfterDurableCommitCanResumeWithoutChangingReference() = fixture {
        attached(); incoming(verifiedEvent); onAccount = {
            if (store.admitTransaction(target) && store.capturedBaseline(target, verifiedEvent.eventId) != null)
                coordinator.authenticationChanged(false)
        }
        assertEquals(CapturedCreateBaselineResult.INACTIVE, retain()); val first = baseline(); assertNotNull(first)
        onAccount = {}; reopen(); assertEquals(CapturedCreateBaselineResult.RETAINED, retain()); assertEquals(first, baseline())
        assertEquals(1, attachmentWrites); assertEquals(1, sends)
    }
    @Test fun cancellationDuringInspectionPropagatesWithoutReferenceCommit() = fixture {
        attached(); beforeInspection = { throw CancellationException("Synthetic inspection cancellation") }
        try { retain(); fail("Cancellation must propagate") } catch (_: CancellationException) {}
        assertNull(baseline()); beforeInspection = {}; assertEquals(CapturedCreateBaselineResult.RETAINED, retain())
    }
    @Test fun preexistingGenericBaselineCannotBeAdoptedEvenIfIdentical() = fixture {
        attached(); incoming(verifiedEvent); allowStore(); assertEquals(1, store.acknowledge(ProjectionIntent(target, verifiedEvent)))
        assertEquals(CapturedCreateBaselineResult.UNRESOLVED, retain()); assertNull(baseline()); assertEquals(verifiedEvent, currentBase())
    }
    @Test fun unresolvedOrdinaryProjectionReceiptCannotBeConsumed() = fixture {
        attached(); incoming(verifiedEvent); allowStore(); val ordinary = store.prepareProjection(ProjectionIntent(target, verifiedEvent), format)
        assertEquals(CapturedCreateBaselineResult.UNRESOLVED, retain()); assertNull(baseline()); assertEquals(1, pendingCount())
        allowStore(); assertEquals(ordinary, store.projectionReceipt(target, verifiedEvent.eventId))
    }
    @Test fun missingAttachmentPlanNeverPreparesOrWritesDuringReferenceRetention() = fixture {
        admitted(); bind(); val before = providerSnapshot()
        assertEquals(CapturedCreateBaselineResult.UNRESOLVED, retain()); assertNull(baseline()); assertEquals(before, providerSnapshot())
        assertEquals(0, attachmentWrites)
    }
    @Test fun duplicateLocalMarkerCannotQualifyHistoricalReference() = fixture {
        attached(); context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)) })!!
        assertEquals(CapturedCreateBaselineResult.UNRESOLVED, retain()); assertNull(baseline()); assertEquals(2, rows())
    }
    private class Fixture(val context: Context) {
        private val token = UUID.randomUUID().toString()
        private val importName = "dispatch-integration-import-$token.db"
        private val dispatchName = "captured-provider-dispatch-$token.db"
        private val journalName = "dispatch-integration-capture-$token.db"
        val target = CalendarScope(AccountId("dispatch-integration-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("dispatch-integration-import", 1)
        var currentFormat = format
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
        var attachmentWrites = 0
        var permitted = true
        var beforeInspection: suspend () -> Unit = {}
        var afterInspection: suspend () -> Unit = {}
        var onAccount: suspend () -> Unit = {}
        var beforeAttachment: suspend () -> Unit = {}
        var afterAttachment: suspend () -> Unit = {}
        val attachmentProjection = object : CalendarProjection by projection {
            override fun hasPermissions() = permitted && projection.hasPermissions()
            override val capturedAttachmentProtocol get() = projection.capturedAttachmentProtocol
            override suspend fun inspectCapturedAttachment(receipt: CapturedCreateAttachmentReceipt): AppliedCapturedCreateAttachment? {
                beforeInspection()
                val proof = projection.inspectCapturedAttachment(receipt)
                afterInspection()
                return proof
            }
            override suspend fun attachCapturedCreate(receipt: CapturedCreateAttachmentReceipt): AppliedCapturedCreateAttachment? {
                beforeAttachment()
                val proof = projection.attachCapturedCreate(receipt)
                if (proof != null) attachmentWrites++
                afterAttachment()
                return proof
            }
        }
        val store = ImportStore(context, importName)
        val creates = CreateAttemptStore(context, dispatchName)
        var journal = LocalCreateStore(context, journalName)
        var calendar = -1L
        var row = -1L
        var sends = 0
        var inspections = 0
        var beforeClaim: suspend () -> Unit = { }
        val verifiedEvent get() = RemoteChange.Upsert(EventId("synthetic-remote"), Revision("synthetic-revision"), content)
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
            override val format get() = currentFormat
            override suspend fun currentAccount(): AccountId { onAccount(); return target.account }
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
        var coordinator = ImportCoordinator(context, store, remote, attachmentProjection, jobs, scheduler, creates = creates, localCreates = journal)
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
        suspend fun admitted() { assertEquals(LocalCreateSubmissionResult.RETAINED, prepare()); assertTrue(submit() is CreateSubmissionObservation.Verified) }
        suspend fun bind() = coordinator.bindLocalCreateObservation(target, localId, id, attempt, port)
        suspend fun attached() { admitted(); assertEquals(CapturedCreateBindingResult.RETAINED, bind()); assertEquals(CapturedCreateAttachmentResult.ATTACHED, attach()) }
        suspend fun retain() = coordinator.retainLocalCreateBaseline(target, localId, id, attempt)
        fun allowStore() { store.admitTransaction = { it == target } }
        suspend fun baseline(): CapturedCreateBaseline? { allowStore(); return store.capturedBaseline(target, verifiedEvent.eventId) }
        suspend fun currentBase(): RemoteChange.Upsert? { allowStore(); return store.projectedBaseline(target, verifiedEvent.eventId) }
        suspend fun remoteRecord(): RemoteChange? { allowStore(); return store.transaction(target) { remote(verifiedEvent.eventId) } }
        suspend fun expectedBaseline() = CapturedCreateBaseline(retainedReceipt(), verifiedEvent)
        suspend fun attach() = coordinator.attachLocalCreateBinding(target, localId, id, attempt)
        suspend fun retainedReceipt(): CapturedCreateAttachmentReceipt {
            val identity = checkNotNull(creates.capturedIdentity(target, attempt))
            val binding = checkNotNull(creates.capturedBinding(identity))
            return checkNotNull(creates.capturedAttachment(binding))
        }
        fun dataSnapshot() = providerSnapshot().filterKeys { it !in setOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA4, Events.SYNC_DATA6) }
        suspend fun incoming(vararg changes: RemoteChange) { store.admitTransaction = { it == target }; store.transaction(target) { changes.forEach { putRemote(it); putProjection(ProjectionIntent(target, it)) } } }
        suspend fun pendingCount() = store.selections(target.account).single().pendingProjectionCount
        fun rows() = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { it.count }
        fun remoteRows() = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA4} IS NOT NULL", arrayOf(calendar.toString()), null)!!.use { cursor ->
            var count = 0
            while (cursor.moveToNext()) if (cursor.getLong(0) == row) count++
            count
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
        fun providerSnapshot() = listOf(Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND,
            Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY,
            Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA, Events.STATUS, Events.AVAILABILITY, Events.DIRTY, Events.DELETED,
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
            coordinator = ImportCoordinator(context, store, remote, attachmentProjection, jobs, scheduler, creates = creates, localCreates = journal); start()
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
