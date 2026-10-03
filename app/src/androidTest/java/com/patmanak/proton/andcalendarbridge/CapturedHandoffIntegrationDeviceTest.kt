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



/** Native Provider and three real journals; controlled incoming data, no network upload. */
@RunWith(AndroidJUnit4::class)
class CapturedHandoffIntegrationDeviceTest {
    @Test fun cleanOriginalCanTransferWithoutAnotherAcknowledgementOrSubmission() = fixture {
        ready(); acknowledge(); incoming(verifiedEvent)
        val before = providerSnapshot()
        assertEquals(CapturedCreateHandoffResult.HANDED_OFF, coordinator.handoffLocalCreate(target, localId, id, attempt))
        assertEquals(before, providerSnapshot()); assertEquals(1, sends); assertEquals(1, ackAttempts)
        assertEquals(1, rawPending()); assertHold()
    }
    @Test fun incomingUpdateAdvancesCurrentReferenceWithoutChangingOriginalOrDuplicating() = fixture {
        transferred(); val original = role()!!.frame.receipt; val markers = providerSnapshot().filterKeys { it in setOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7) }
        val newer = next(); incoming(newer); assertEquals(ImportRunResult.COMPLETE, drain())
        assertEquals(newer, currentBase()); assertEquals(original, role()!!.frame.receipt); assertEquals(newer, role()!!.frame.base)
        assertEquals(markers, providerSnapshot().filterKeys { it in markers.keys }); assertEquals(1, rows()); assertEquals(0, rawPending())
        assertEquals(1, handoffWrites); assertNull(prepared()); assertEquals(1, sends); assertEquals(1, ackAttempts); assertHold()
    }
    @Test fun reopenedTransferUsesAdvancedReferenceAndCannotRestoreOriginal() = fixture {
        transferred(); val newer = next(); incoming(newer); drain(); val saved = role(); val before = providerSnapshot(); reopen()
        assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()); assertEquals(saved, role()); assertEquals(before, providerSnapshot())
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, acknowledge()); assertEquals(CapturedCreateCleanResult.UNRESOLVED, qualify())
        assertEquals(CapturedCreateBaselineResult.UNRESOLVED, retain()); assertEquals(newer, currentBase()); assertEquals(1, ackAttempts)
    }
    @Test fun dirtyOriginalLeavesPendingTransferAndIncomingIntact() = fixture {
        ready(); acknowledge(); mutate(ContentValues().apply { put(Events.TITLE, "Original local change"); put(Events.DIRTY, 1) }); incoming(next())
        assertEquals(CapturedCreateHandoffResult.UNRESOLVED, handoff()); assertEquals(CapturedCreateHandoffState.PENDING, role()!!.state)
        drain(); assertEquals("Original local change", field(Events.TITLE)); assertEquals(1, rawPending()); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun editAfterInitialCleanObservationCannotBeOverwrittenByTransfer() = fixture {
        ready(); acknowledge(); afterClean = { afterClean = {}; mutate(ContentValues().apply { put(Events.TITLE, "After observation"); put(Events.DIRTY, 1) }) }
        assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()); incoming(next()); drain()
        assertEquals("After observation", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY)); assertTrue(role()!!.waiting)
        assertEquals(0, handoffWrites); assertEquals(0, continuations.get()); assertEquals(1, rawPending())
    }
    @Test fun promotionFailureReopensTheSamePendingTransferAndDoesNotReAck() = fixture {
        ready(); acknowledge(); afterClean = { failPromotion(true) }
        assertEquals(CapturedCreateHandoffResult.UNRESOLVED, handoff()); val saved = role()!!.frame.receipt
        failPromotion(false); afterClean = {}; reopen(); assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff())
        assertEquals(saved, role()!!.frame.receipt); assertEquals(1, ackAttempts); assertEquals(1, sends)
    }
    @Test fun lostProviderResponseRecoversHistoricallyWithoutAnotherWriteOrCleaningLaterEdit() = fixture {
        transferred(); val newer = next(); incoming(newer)
        afterHandoffWrite = { throw IllegalStateException("Controlled lost handoff response") }; drain()
        assertNotNull(prepared()); assertEquals(verifiedEvent, currentBase()); mutate(ContentValues().apply { put(Events.TITLE, "After committed write"); put(Events.DIRTY, 1) })
        afterHandoffWrite = {}; reopen(); assertEquals(CapturedCreateHandoffResult.WAITING, handoff())
        assertEquals(newer, currentBase()); assertNull(prepared()); assertEquals(1, handoffWrites); assertEquals("After committed write", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred()); assertEquals(newer, edit()!!.base)
    }
    @Test fun lostSqlRestoreRecoversTheWrittenReferenceAfterReopen() = fixture {
        transferred(); val newer = next(); incoming(newer); beforeHandoffWrite = { failRestore(true) }; drain()
        assertNotNull(prepared()); assertEquals(verifiedEvent, currentBase()); assertEquals(newer.content.title, field(Events.TITLE))
        failRestore(false); beforeHandoffWrite = {}; reopen(); assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff())
        assertEquals(newer, currentBase()); assertNull(prepared()); assertEquals(1, handoffWrites); assertEquals(0, rawPending())
    }
    @Test fun newerPendingAfterWriteSurvivesRestoreAndProjectsOnNextRun() = fixture {
        transferred(); val first = next(); val second = next("Second remote version", "second-reference"); incoming(first)
        afterHandoffWrite = { afterHandoffWrite = {}; incoming(second) }
        assertEquals(ImportRunResult.CONTINUE, drain()); assertEquals(first, currentBase()); assertEquals(second, remoteRecord()); assertEquals(1, rawPending())
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(second, currentBase()); assertEquals(second.content.title, field(Events.TITLE)); assertEquals(2, handoffWrites); assertEquals(1, rows())
    }
    @Test fun recoveryReloadsANewerPendingInsteadOfReusingTheOldLoadedIntent() = fixture {
        transferred(); val first = next(); incoming(first); afterHandoffWrite = { throw IllegalStateException("Lost old response") }; drain()
        afterHandoffWrite = {}; val second = next("Newer before recovery", "second-reference"); incoming(second); reopen()
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(second, currentBase()); assertEquals(second.content.title, field(Events.TITLE)); assertEquals(2, handoffWrites); assertNull(prepared())
    }
    @Test fun localEditAfterWriteUsesAdvancedBaseAndPreservesHistoricalMarkers() = fixture {
        transferred(); val newer = next(); incoming(newer)
        afterHandoffWrite = { afterHandoffWrite = {}; mutate(ContentValues().apply { put(Events.TITLE, "Later local intention"); put(Events.DIRTY, 1) }) }; drain()
        val before = providerSnapshot(); assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred())
        assertEquals(newer, edit()!!.base); assertEquals("Later local intention", edit()!!.content.title); assertEquals(before, providerSnapshot()); assertTrue(role()!!.waiting)
    }
    @Test fun remoteDeletionRemainsHeldAndDoesNotRemoveTheOriginal() = fixture {
        transferred(); incoming(RemoteChange.Deleted(verifiedEvent.eventId, Revision("deleted"))); val before = providerSnapshot()
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(before, providerSnapshot()); assertEquals(1, rawPending()); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun unknownRemoteDetailsRemainHeldWithoutDiscardingThem() = fixture {
        transferred(); val unknown = next().copy(content = content.copy(details = null)); incoming(unknown)
        drain(); assertEquals(unknown, remoteRecord()); assertEquals(content.title, field(Events.TITLE)); assertEquals(1, rawPending()); assertEquals(0, handoffWrites)
    }
    @Test fun corruptedPreparedReceiptRemainsHeldWhileOtherEventsDrain() = fixture {
        transferred(); incoming(next()); allowStore(); val frame = role()!!.frame
        store.prepareCapturedHandoffProjection(frame, ProjectionIntent(target, next())); sql { it.execSQL("UPDATE projection_receipts SET payload='corrupt'") }
        val other = next("Ordinary beside corrupt receipt").copy(eventId = EventId("ordinary")); incoming(other)
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(2, rows()); assertEquals(content.title, field(Events.TITLE)); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun missingDispatchCannotReturnCapturedRowsToOrdinaryProjection() = fixture {
        transferred(); loseDispatch(); incoming(next(), next("Ordinary beside missing dispatch").copy(eventId = EventId("ordinary")))
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(2, rows()); assertEquals(content.title, field(Events.TITLE)); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun permissionLossBeforeTransferDoesNotReserveARole() = fixture {
        ready(); acknowledge(); permitted = false; assertEquals(CapturedCreateHandoffResult.INACTIVE, handoff())
        assertNull(role()); assertEquals(0, cleanInspections)
    }
    @Test fun permissionLossAfterInitialInspectionKeepsOnlyPendingAdmission() = fixture {
        ready(); acknowledge(); afterClean = { permitted = false }
        assertEquals(CapturedCreateHandoffResult.INACTIVE, handoff()); assertEquals(CapturedCreateHandoffState.PENDING, role()!!.state)
    }
    @Test fun permissionLossAfterWriteCannotAdvanceSqlUntilHistoricalRecovery() = fixture {
        transferred(); val newer = next(); incoming(newer); afterHandoffWrite = { permitted = false }; interruptedDrain()
        assertEquals(verifiedEvent, currentBase()); assertNotNull(prepared()); assertEquals(newer.content.title, field(Events.TITLE))
        permitted = true; afterHandoffWrite = {}; assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()); assertEquals(newer, currentBase()); assertEquals(1, handoffWrites)
    }
    @Test fun disablingBetweenProviderAndSqlPreventsConsumption() = fixture {
        transferred(); val newer = next(); incoming(newer)
        afterHandoffWrite = { coordinator.setEnabled(coordinator.state.value.selections.single { it.scope == target }, false) }; interruptedDrain()
        withTimeout(5_000) { while (store.selections(target.account).single { it.scope == target }.enabled) delay(10) }
        assertEquals(verifiedEvent, currentBase()); assertNotNull(prepared()); assertEquals(1, rawPending()); assertEquals(newer.content.title, field(Events.TITLE))
    }
    @Test fun signOutBetweenProviderAndSqlPreservesPreparedEvidence() = fixture {
        transferred(); incoming(next()); afterHandoffWrite = { coordinator.authenticationChanged(false) }; interruptedDrain()
        assertEquals(verifiedEvent, currentBase()); assertNotNull(prepared()); assertEquals(1, rawPending()); assertEquals(1, handoffWrites)
    }
    @Test fun dirtyPreflightWaitsWithoutPreparingAndOtherEventsContinue() = fixture {
        transferred(); mutate(ContentValues().apply { put(Events.TITLE, "Offline dirty"); put(Events.DIRTY, 1) })
        incoming(next(), next("Ordinary while dirty").copy(eventId = EventId("ordinary"))); drain()
        assertEquals("Offline dirty", field(Events.TITLE)); assertEquals(2, rows()); assertTrue(role()!!.waiting); assertNull(prepared()); assertEquals(0, continuations.get())
        drain(); assertEquals(0, continuations.get()); assertEquals(0, handoffWrites)
    }
    @Test fun editBetweenPreflightAndConditionalWriteKeepsPreparedEvidenceAndWaits() = fixture {
        transferred(); incoming(next()); beforeHandoffWrite = { mutate(ContentValues().apply { put(Events.TITLE, "Racing local edit"); put(Events.DIRTY, 1) }) }
        drain(); assertEquals("Racing local edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY)); assertTrue(role()!!.waiting)
        assertNotNull(prepared()); assertEquals(verifiedEvent, currentBase()); assertEquals(1, rawPending()); assertEquals(0, continuations.get())
    }
    @Test fun missingOriginalNeverInsertsAndWaitsWithoutContinuations() = fixture {
        transferred(); context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString())); incoming(next()); drain()
        assertEquals(0, rows()); assertTrue(role()!!.waiting); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun newReminderIsPreservedAndHeld() = fixture {
        transferred(); addReminder(); val before = providerSnapshot(); incoming(next()); drain()
        assertEquals(before, providerSnapshot()); assertTrue(role()!!.waiting); assertNull(prepared()); assertEquals(0, handoffWrites)
    }
    @Test fun laterEditRetainsImmutableCurrentBaseAndPendingRemote() = fixture {
        transferred(); val current = next(); incoming(current); drain(); mutate(ContentValues().apply { put(Events.TITLE, "Captured later"); put(Events.DIRTY, 1) })
        val pending = next("Concurrent remote", "concurrent"); incoming(pending)
        assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcileTransferred()); val saved = edit(); val before = providerSnapshot()
        assertEquals(current, saved!!.base); assertEquals(CapturedCreateReconciliationResult.CONFLICT, reconcileTransferred()); assertEquals(saved, edit()); assertEquals(pending, remoteRecord()); assertEquals(1, rawPending()); assertEquals(before, providerSnapshot())
    }
    @Test fun presentTombstoneIsCapturedAgainstCurrentBaseWithoutDeletingAndroid() = fixture {
        transferred(); val current = next(); incoming(current); drain(); mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred()); assertEquals(current, deletion()!!.base); assertEquals(1, rows()); assertEquals("1", field(Events.DELETED)); assertTrue(role()!!.waiting)
    }
    @Test fun waitingCanResumeOnlyAfterCurrentCleanReferenceReturnsWithoutAnIntention() = fixture {
        transferred(); mutate(ContentValues().apply { put(Events.TITLE, "Temporary local change"); put(Events.DIRTY, 1) }); incoming(next()); drain(); assertTrue(role()!!.waiting)
        mutate(ContentValues().apply { put(Events.TITLE, content.title); put(Events.DIRTY, 0) }); assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()); assertFalse(role()!!.waiting)
        drain(); assertEquals(next(), currentBase()); assertEquals(1, handoffWrites)
    }
    @Test fun retainedIntentionCannotResumeEvenIfAndroidIsReverted() = fixture {
        transferred(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Retained local change"); put(Events.DIRTY, 1) }); reconcileTransferred(); val saved = edit()
        mutate(ContentValues().apply { put(Events.TITLE, content.title); put(Events.DIRTY, 0) }); handoff(); assertTrue(role()!!.waiting); assertEquals(saved, edit()); assertEquals(1, rawPending())
    }
    @Test fun genericCaptureCannotAdoptAReleasedCreation() = fixture {
        transferred(); incoming(verifiedEvent); drain(); mutate(ContentValues().apply { put(Events.TITLE, "Generic must refuse"); put(Events.DIRTY, 1) })
        assertEquals(LocalEditCaptureResult.UNAVAILABLE, coordinator.captureLocalEdit(target, verifiedEvent.eventId, mutation)); assertNull(edit()); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun interruptedTransferClosesAllCallbacksAndReusesItsReservation() = fixture {
        ready(); acknowledge(); beforeClean = { throw IllegalStateException("Interrupted transfer inspection") }; handoff()
        assertFalse(store.admitTransaction(target)); assertFalse(journal.admitCapture(target)); assertFalse(attachmentProjection.admitOperation())
        val saved = role()!!.frame.receipt; beforeClean = {}; assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()); assertEquals(saved, role()!!.frame.receipt); assertEquals(1, ackAttempts)
    }
    @Test fun malformedRawEventIdentityBlocksScopeWithoutBusyContinuation() = fixture {
        transferred(); sql { it.execSQL("UPDATE captured_create_bases SET event=''") }; incoming(next(), next().copy(eventId = EventId("ordinary")))
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(1, rows()); assertEquals(0, handoffWrites); assertEquals(0, continuations.get())
    }
    @Test fun handoffRequiresExactCaptureAndAttemptAndAdapterFormat() = fixture {
        ready(); acknowledge()
        assertEquals(CapturedCreateHandoffResult.UNRESOLVED, coordinator.handoffLocalCreate(target, localId, MutationId("other"), attempt))
        assertEquals(CapturedCreateHandoffResult.UNRESOLVED, coordinator.handoffLocalCreate(target, localId, id, MutationId("other")))
        currentFormat = format.copy(version = 2); assertEquals(CapturedCreateHandoffResult.UNRESOLVED, handoff()); assertNull(role()); assertEquals(0, cleanInspections)
    }
    @Test fun genericCaptureCannotReplayASpecializedRetainedIntention() = fixture {
        transferred(); incoming(verifiedEvent); mutate(ContentValues().apply { put(Events.TITLE, "Specialized retained edit"); put(Events.DIRTY, 1) })
        assertEquals(CapturedCreateReconciliationResult.RETAINED, reconcileTransferred()); val saved = edit()
        mutate(ContentValues().apply { put(Events.TITLE, "Later than retained edit"); put(Events.DIRTY, 1) })
        assertEquals(LocalEditCaptureResult.UNAVAILABLE, coordinator.captureLocalEdit(target, verifiedEvent.eventId, mutation))
        assertEquals(LocalEditCaptureResult.UNAVAILABLE, coordinator.captureLocalDelete(target, verifiedEvent.eventId, mutation))
        assertEquals(saved, edit()); assertEquals("Later than retained edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY)); assertTrue(role()!!.waiting)
    }
    @Test fun heldDirtyCreationDoesNotStarveAFourHundredEventBacklogOrScheduleAnExtraContinuation() = fixture {
        transferred(); mutate(ContentValues().apply { put(Events.TITLE, "Held during backlog"); put(Events.DIRTY, 1) })
        incoming(next(), *(1..400).map { next("Ordinary backlog $it").copy(eventId = EventId("ordinary-$it")) }.toTypedArray())
        var runs = 0
        var result: ImportRunResult
        do { result = drain(); runs++; assertTrue("Backlog must finish in bounded batches", runs <= 5) }
        while (result == ImportRunResult.CONTINUE)
        assertEquals(ImportRunResult.COMPLETE, result); assertTrue(runs in 4..5); assertEquals(runs - 1, continuations.get()); assertEquals(401, rows()); assertEquals(1, rawPending())
        assertEquals("Held during backlog", field(Events.TITLE)); assertTrue(role()!!.waiting); assertEquals(0, handoffWrites)
        assertEquals(ImportRunResult.COMPLETE, drain()); assertEquals(runs - 1, continuations.get())
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
        var handoffWrites = 0
        var handoffInspections = 0
        var beforeHandoffWrite: suspend () -> Unit = {}
        var afterHandoffWrite: suspend () -> Unit = {}
        var beforeHandoffBase: suspend () -> Unit = {}
        var afterHandoffBase: suspend () -> Unit = {}
        var afterHandoffSnapshot: suspend () -> Unit = {}
        var attachmentWrites = 0
        var ackAttempts = 0
        var ackInspections = 0
        var cleanInspections = 0
        var beforeClean: suspend () -> Unit = {}
        var afterClean: suspend () -> Unit = {}
        var transformClean: (CapturedCreateCleanWitness?) -> CapturedCreateCleanWitness? = { it }
        var beforeAck: suspend () -> Unit = {}
        var afterAck: suspend () -> Unit = {}
        var beforeAckInspection: suspend () -> Unit = {}
        var afterAckInspection: suspend () -> Unit = {}
        var permitted = true
        var beforeCurrent: suspend () -> Unit = {}
        var afterCurrent: suspend () -> Unit = {}
        var beforeInspection: suspend () -> Unit = {}
        var afterInspection: suspend () -> Unit = {}
        var onAccount: suspend () -> Unit = {}
        var beforeAttachment: suspend () -> Unit = {}
        var afterAttachment: suspend () -> Unit = {}
        val attachmentProjection = object : CalendarProjection by projection {
            override suspend fun applyCapturedHandoffProjection(plan: CapturedHandoffProjection): AppliedCapturedHandoffProjection? {
                handoffWrites++; beforeHandoffWrite()
                val result = projection.applyCapturedHandoffProjection(plan)
                afterHandoffWrite(); return result
            }
            override suspend fun inspectCapturedHandoffProjection(plan: CapturedHandoffProjection): AppliedCapturedHandoffProjection? {
                handoffInspections++; return projection.inspectCapturedHandoffProjection(plan)
            }
            override suspend fun inspectCapturedHandoffBase(frame: CapturedCreateHandoffFrame): CapturedHandoffCleanWitness? {
                beforeHandoffBase(); val result = projection.inspectCapturedHandoffBase(frame)
                afterHandoffBase(); return result
            }
            override suspend fun captureCapturedHandoffCurrent(frame: CapturedCreateHandoffFrame): CapturedHandoffCurrentObservation {
                val result = projection.captureCapturedHandoffCurrent(frame); afterHandoffSnapshot(); return result
            }
            override suspend fun inspectAcknowledgedCreateClean(receipt: CapturedCreateAcknowledgementReceipt): CapturedCreateCleanWitness? {
                cleanInspections++; beforeClean()
                val proof = projection.inspectAcknowledgedCreateClean(receipt)
                afterClean(); return transformClean(proof)
            }
            override suspend fun acknowledgeCapturedCreate(receipt: CapturedCreateAcknowledgementReceipt): AppliedCapturedCreateAcknowledgement? {
                ackAttempts++; beforeAck()
                val proof = projection.acknowledgeCapturedCreate(receipt)
                afterAck(); return proof
            }
            override suspend fun inspectCapturedAcknowledgement(receipt: CapturedCreateAcknowledgementReceipt): AppliedCapturedCreateAcknowledgement? {
                ackInspections++; beforeAckInspection()
                val proof = projection.inspectCapturedAcknowledgement(receipt)
                afterAckInspection(); return proof
            }
            override suspend fun captureCapturedCreateCurrent(baseline: CapturedCreateBaseline): CapturedCreateCurrentObservation {
                beforeCurrent()
                val observed = projection.captureCapturedCreateCurrent(baseline)
                afterCurrent()
                return observed
            }
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
        var store = ImportStore(context, importName)
        var creates = CreateAttemptStore(context, dispatchName)
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
        val continuations = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) { continuations.incrementAndGet() }
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
        val mutation = MutationId("after-create-change")
        suspend fun handoff() = coordinator.handoffLocalCreate(target, localId, id, attempt)
        suspend fun role(): CapturedCreateHandoffRecord? { allowStore(); return store.capturedHandoff(target, verifiedEvent.eventId) }
        suspend fun prepared(): ProjectionReceipt? { allowStore(); return store.projectionReceipt(target, verifiedEvent.eventId) }
        suspend fun drain(): ImportRunResult {
            val result = coordinator.run(network = false)
            executeImportWork({ true }, { result }, { scheduler.continueWork(false) })
            return result
        }
        suspend fun interruptedDrain() {
            try { coordinator.run(network = false) }
            catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
        }
        suspend fun transferred() { ready(); acknowledge(); assertEquals(CapturedCreateHandoffResult.HANDED_OFF, handoff()) }
        suspend fun reconcileTransferred() = coordinator.reconcileHandoffLocalCreate(target, localId, id, attempt, mutation)
        fun sql(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(importName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)
        fun failRestore(enabled: Boolean) = sql {
            if (enabled) it.execSQL("CREATE TRIGGER fixture_restore_failure BEFORE UPDATE OF payload ON projection_bases BEGIN SELECT RAISE(ABORT, 'Controlled restore failure'); END")
            else it.execSQL("DROP TRIGGER IF EXISTS fixture_restore_failure")
        }
        fun failPromotion(enabled: Boolean) = sql {
            if (enabled) it.execSQL("CREATE TRIGGER fixture_promote_failure BEFORE UPDATE OF state ON captured_create_handoffs BEGIN SELECT RAISE(ABORT, 'Controlled promotion failure'); END")
            else it.execSQL("DROP TRIGGER IF EXISTS fixture_promote_failure")
        }
        fun loseDispatch() = SQLiteDatabase.openDatabase(context.getDatabasePath(dispatchName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata'", null).use { rows ->
                val tables = mutableListOf<String>(); while(rows.moveToNext()) tables += rows.getString(0)
                tables.forEach { table -> it.execSQL("DELETE FROM $table") }
            }
        }
        fun next(title: String = "Incoming after handoff", revision: String = "later-reference") = verifiedEvent.copy(revision = Revision(revision), content = content.copy(title = title))
        suspend fun acknowledge() = coordinator.acknowledgeLocalCreate(target, localId, id, attempt)
        suspend fun qualify() = coordinator.qualifyAcknowledgedLocalCreate(target, localId, id, attempt)
        suspend fun ackRecord(): CapturedCreateAcknowledgementRecord? { allowStore(); return store.capturedAcknowledgement(target, verifiedEvent.eventId) }
        suspend fun assertHold() = assertTrue(creates.capturedProjectionHold(target, format).holds(verifiedEvent.eventId))
        fun failAckCommit(enabled: Boolean) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(importName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (enabled) db.execSQL("CREATE TRIGGER fixture_fail_ack BEFORE UPDATE OF state ON captured_create_acknowledgements BEGIN SELECT RAISE(ABORT, 'fixture acknowledgement commit interrupted'); END")
                else db.execSQL("DROP TRIGGER IF EXISTS fixture_fail_ack")
            }
        }
        suspend fun ready() { attached(); assertEquals(CapturedCreateBaselineResult.RETAINED, retain()) }
        suspend fun reconcile(request: MutationId = mutation) = coordinator.reconcileLocalCreate(target, localId, id, attempt, request)
        suspend fun edit(): LocalEditIntent? { allowStore(); return store.localEdit(target, verifiedEvent.eventId) }
        suspend fun deletion(): LocalDeleteIntent? { allowStore(); return store.localDelete(target, verifiedEvent.eventId) }
        fun rawPending(): Int? = store.readableDatabase.rawQuery("SELECT pending FROM records WHERE event=?",
            arrayOf(verifiedEvent.eventId.value)).use { if (it.moveToFirst()) it.getInt(0) else null }
        fun addReminder() {
            val child = CalendarContract.Reminders.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()
            assertNotNull(context.contentResolver.insert(child, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, row); put(CalendarContract.Reminders.MINUTES, 30)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }))
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
        suspend fun incoming(vararg changes: RemoteChange) {
            val previous = store.admitTransaction; store.admitTransaction = { it == target }
            try { store.transaction(target) { changes.forEach { putRemote(it); putProjection(ProjectionIntent(target, it)) } } }
            finally { store.admitTransaction = previous }
        }
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
            store.close(); creates.close(); store = ImportStore(context, importName); creates = CreateAttemptStore(context, dispatchName)
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
