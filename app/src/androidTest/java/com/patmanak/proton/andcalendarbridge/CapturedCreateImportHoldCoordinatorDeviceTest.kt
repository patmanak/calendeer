// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreatePauseReason
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith



/** Isolated SQLite, controlled Provider and fake transport; no service mutation. */
@RunWith(AndroidJUnit4::class)
class CapturedCreateImportHoldCoordinatorDeviceTest {
    @Test fun unknownCaptureHoldsIncomingProjectionBeforeAndAfterOwnerRestart() = fixture {
        admitted(); incoming(verifiedEvent)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
        assertTrue(applied.isEmpty()); assertEquals(1, pendingCount())
        assertEquals("CREATE_PENDING", selectionStatus()); assertNull(store.projectedBaseline(target, verifiedEvent.eventId))
        reopenOwner(); assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
        assertTrue(applied.isEmpty()); assertEquals(1, pendingCount()); assertEquals(1, sends)
    }
    @Test fun bindingNarrowsTheHoldAndCacheReopensWithoutAnotherInspection() = fixture {
        admitted(); incoming(verifiedEvent, otherEvent)
        assertEquals(CapturedCreateBindingResult.RETAINED, bind())
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
        assertEquals(listOf(otherEvent), applied); assertEquals(1, pendingCount())
        assertEquals("CREATE_PENDING", selectionStatus()); assertEquals(1, inspectCalls)
        reopenOwner(); onCapturedInspect = { error("A retained binding must not inspect again") }
        assertEquals(CapturedCreateBindingResult.RETAINED, coordinator.bindLocalCreateObservation(target, localId, id, attempt))
        assertEquals(1, inspectCalls)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false)); assertEquals(listOf(otherEvent), applied)
    }
    @Test fun bindingRefusesWrongContentOrAttemptWithoutReleasingTheScope() = fixture {
        admitted()
        observedContent = content.copy(title = "Foreign captured observation")
        assertEquals(CapturedCreateBindingResult.UNRESOLVED, bind())
        observedContent = null; observedAttempt = MutationId("foreign-observation")
        assertEquals(CapturedCreateBindingResult.UNRESOLVED, bind())
        assertTrue(creates.capturedProjectionHold(target, format).scopeBlocked); assertEquals(1, sends)
    }
    @Test fun providerLossDoesNotPreventBindingButNeverAcknowledgesALocalRow() = fixture {
        admitted(); snapshot = null
        assertEquals(CapturedCreateBindingResult.RETAINED, bind())
        assertEquals(1, calls); assertEquals(1, sends); assertTrue(applied.isEmpty())
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, checkNotNull(record()).state)
    }
    @Test fun sessionOrPermissionLossDuringInspectionCannotRetainBinding() {
        for (session in listOf(true, false)) fixture {
            admitted(); onCapturedInspect = { if (session) coordinator.authenticationChanged(false) else permission = false }
            assertEquals(CapturedCreateBindingResult.INACTIVE, bind())
            assertTrue(creates.capturedProjectionHold(target, format).scopeBlocked)
        }
    }
    @Test fun unavailableSelectionOrChangedFormatRefusesBinding() = fixture {
        admitted(); store.setEnabled(target, false)
        assertEquals(CapturedCreateBindingResult.INACTIVE, bind())
        store.setEnabled(target, true); declaredPreparationFormat = AdapterFormat("foreign", 2)
        assertEquals(CapturedCreateBindingResult.UNRESOLVED, bind())
        assertTrue(creates.capturedProjectionHold(target, format).scopeBlocked)
    }
    @Test fun incomingNewRevisionOrDeletionRemainsHeldWithoutReplacingTheFrozenBinding() = fixture {
        admitted(); assertEquals(CapturedCreateBindingResult.RETAINED, bind())
        incoming(verifiedEvent.copy(revision = Revision("newer"), content = content.copy(title = "New server version")))
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false)); assertTrue(applied.isEmpty())
        incoming(RemoteChange.Deleted(verifiedEvent.eventId, Revision("deleted")))
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false)); assertTrue(applied.isEmpty())
        assertEquals(1, pendingCount()); assertEquals(1, sends)
        assertEquals(verifiedEvent.revision, creates.capturedBinding(identity())!!.verifiedRevision)
    }
    @Test fun alreadyPreparedReceiptIsNotInspectedOrAcknowledgedWhileHeld() = fixture {
        incoming(verifiedEvent); tracked = true
        val receipt = store.prepareProjection(ProjectionIntent(target, verifiedEvent), format)
        admitted(); assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
        assertEquals(0, recoveryReads); assertEquals(receipt, store.projectionReceipt(target, verifiedEvent.eventId))
        assertNull(store.projectedBaseline(target, verifiedEvent.eventId)); assertEquals(1, pendingCount())
    }
    @Test fun captureBaselineRecoveryCannotBypassACapturedHold() = fixture {
        incoming(verifiedEvent)
        store.acknowledge(ProjectionIntent(target, verifiedEvent))
        incoming(verifiedEvent.copy(revision = Revision("newer")))
        tracked = true; store.prepareProjection(ProjectionIntent(target, verifiedEvent.copy(revision = Revision("newer"))), format)
        admitted()
        assertEquals(LocalEditCaptureResult.UNAVAILABLE, coordinator.captureLocalEdit(target, verifiedEvent.eventId, MutationId("edit")))
        assertEquals(0, recoveryReads); assertTrue(applied.isEmpty())
    }
    @Test fun onlyHeldWorkDoesNotContinueButNetworkAcquisitionStillAdvances() = fixture {
        admitted(); incoming(otherEvent)
        store.transaction(target) { checkpoint = PageCheckpoint(target, format) }
        fetched = listOf(verifiedEvent)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(true, fresh = false))
        assertEquals(1, fetches); assertTrue(applied.isEmpty()); assertEquals(2, pendingCount())
        assertTrue(store.complete(target)); assertEquals("CREATE_PENDING", selectionStatus())
    }
    @Test fun filteringHeldPrefixDoesNotStarveRunnableWorkPastTheBatchLimit() = fixture {
        admitted(); assertEquals(CapturedCreateBindingResult.RETAINED, bind()); incoming(verifiedEvent)
        repeat(100) { index ->
            val event = otherEvent.copy(eventId = EventId("held-$index"))
            addBoundHold(event); incoming(event)
        }
        incoming(otherEvent)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
        assertEquals(listOf(otherEvent), applied); assertEquals(101, pendingCount())
    }
    @Test fun journalFailureNeverFallsBackToOrdinaryProjection() = fixture {
        admitted(); incoming(verifiedEvent)
        creates.writableDatabase.execSQL("DROP TABLE captured_create_bindings")
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
        assertTrue(applied.isEmpty()); assertEquals(1, pendingCount()); assertEquals("ERROR", selectionStatus())
    }
    @Test fun retainedStagedReceiptDecodesOfflineWhileTheUnknownCreationHoldsProjection() = fixture(staged = true) {
        admitted(); incoming(otherEvent); fetched = listOf(verifiedEvent)
        store.transaction(target) {
            checkpoint = PageCheckpoint(target, format)
            (this as StagedSyncTransaction).received = ReceivedCalendarPage(PageRequest(checkNotNull(checkpoint), 100), "synthetic-page")
        }
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
        assertEquals(0, fetches); assertTrue(store.complete(target)); assertEquals(2, pendingCount())
        assertNull(store.transaction(target) { (this as StagedSyncTransaction).received })
        assertTrue(applied.isEmpty()); assertEquals("CREATE_PENDING", selectionStatus())
    }
    @Test fun cancelledInspectionKeepsTheOriginalUnknownAndWholeScopeHold() = fixture {
        admitted(); val original = record()
        onCapturedInspect = { throw CancellationException("Synthetic interrupted captured readback") }
        try { bind(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(original, record()); assertTrue(creates.capturedProjectionHold(target, format).scopeBlocked)
        assertNull(creates.capturedBinding(identity())); assertEquals(1, sends)
    }
    @Test fun adapterFormatReplacementCannotReuseAnOldBindingToReleaseProjection() = fixture {
        admitted(); assertEquals(CapturedCreateBindingResult.RETAINED, bind())
        declaredImportFormat = AdapterFormat("replacement-import", 2)
        incoming(otherEvent)
        store.transaction(target) { checkpoint = PageCheckpoint(target, declaredImportFormat, traversalComplete = true) }
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
        assertTrue(applied.isEmpty()); assertEquals(1, pendingCount()); assertEquals("CREATE_PENDING", selectionStatus())
        assertEquals(CapturedCreateBindingResult.UNRESOLVED, bind()); assertEquals(1, inspectCalls)
    }
    private class Fixture(staged: Boolean) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        private val importName = "dispatch-owner-import-$token.db"
        private val dispatchName = "captured-dispatch-$token.db"
        private val journalName = "dispatch-owner-capture-$token.db"
        val target = CalendarScope(AccountId("dispatch-owner-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("dispatch-owner-import", 1)
        var declaredImportFormat = format
        val preparationFormat = AdapterFormat("controlled-known-empty-submission", 2)
        var declaredPreparationFormat: AdapterFormat? = preparationFormat
        val localId = LocalEventId(UUID.randomUUID().toString())
        val id = MutationId("create")
        val attempt = MutationId(UUID.randomUUID().toString())
        val timestamp = Instant.parse("2030-10-01T12:00:00Z")
        val content = EventContent("Preparation owner fixture", EventTime.AllDay(LocalDate.of(2030, 10, 1),
            LocalDate.of(2030, 10, 2)), details = EventDetails())
        val intent = LocalCreateIntent(LocalCreateReservation(id, localId, target, format, timestamp), content)
        val payload = "synthetic-protected-preparation-only"
        val store = ImportStore(context, importName)
        val journal = LocalCreateStore(context, journalName)
        val creates = CreateAttemptStore(context, dispatchName)
        var permission = true
        var account: AccountId? = target.account
        var submitCalls = 0
        var inspectCalls = 0
        var sends = 0
        var beforeClaim: suspend () -> Unit = { }
        var afterClaim: suspend () -> Unit = { }
        var observedContent: EventContent? = null
        var observedAttempt: MutationId? = null
        var repeatClaim = false
        var secondClaim = false
        var authHeld = false
        fun verified(record: CreateAttemptRecord) = CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record),
            RemoteChange.Upsert(EventId("synthetic-remote"), Revision("synthetic-revision"), observedContent ?: content)).let {
                if (observedAttempt == null) it else CreateSubmissionObservation.Verified(it.attempt.copy(id = observedAttempt!!), it.event)
            }
        val verifiedEvent get() = RemoteChange.Upsert(EventId("synthetic-remote"), Revision("synthetic-revision"), content)
        val otherEvent get() = verifiedEvent.copy(eventId = EventId("unrelated-remote"), content = content.copy(title = "Unrelated import fixture"))
        val applied = mutableListOf<RemoteChange>()
        var tracked = false
        var recoveryReads = 0
        var fetched: List<RemoteChange> = emptyList()
        var fetches = 0
        var onCapturedInspect: suspend () -> Unit = { }
        var calls = 0
        var inspections = 0
        var onInspect: suspend () -> Unit = { }
        var snapshot: OwnedLocalEditSnapshot? = OwnedLocalEditSnapshot(3, 7, content)
        var onPrepare: suspend (CalendarCreateDraft) -> PreparedCalendarCreate = { receipt() }
        fun receipt() = PreparedCalendarCreate(target, preparationFormat, payload)
        val port = object : CalendarCreateSubmissionPort {
            override val knownEmptySubmissionFormat: AdapterFormat? get() = declaredPreparationFormat
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate {
                assertEquals(CalendarCreateDraft(target, attempt, timestamp, content), draft)
                calls++; return onPrepare(draft)
            }
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = error("No generic submission")
            override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft, journal: CreateAttemptJournal): CreateSubmissionObservation {
                assertEquals(CalendarCreateDraft(target, attempt, timestamp, content), expected)
                authHeld = true
                try {
                    submitCalls++; beforeClaim()
                    if (!journal.claim(record)) return CreateSubmissionObservation.Unknown
                    if (repeatClaim) secondClaim = journal.claim(record)
                    sends++; afterClaim()
                    return verified(record)
                } finally { authHeld = false }
            }
            override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft): CreateSubmissionObservation {
                assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, record.state)
                assertEquals(CalendarCreateDraft(target, attempt, timestamp, content), expected)
                inspectCalls++; onCapturedInspect(); return verified(record)
            }
            override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = error("No remote inspection")
        }
        private val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { true }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("preparation-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary) = EnsuredCalendar(7, false)
                        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) { check(admitOperation()); applied += intent.change }
            override val projectionReceiptProtocol: Int? get() = if (tracked) 1 else null
            override suspend fun inspectProjectionReceipt(receipt: ProjectionReceipt): AppliedProjectionWitness? {
                recoveryReads++; return AppliedProjectionWitness(receipt)
            }
            override suspend fun applyTracked(receipt: ProjectionReceipt): AppliedProjectionWitness {
                applied += receipt.change; return AppliedProjectionWitness(receipt)
            }
            override suspend fun inspectStampedSimpleCreate(scope: CalendarScope, localId: LocalEventId): OwnedLocalEditSnapshot? {
                assertEquals(target, scope); assertEquals(this@Fixture.localId, localId)
                check(admitOperation()); inspections++; onInspect(); return snapshot
            }
        }
        private val legacyRemote = object : ReadOnlyCalendarRemote {
            override val format get() = declaredImportFormat
            override suspend fun currentAccount(): AccountId? { check(!authHeld) { "Authentication mutex is non-reentrant" }; return account }
            override suspend fun fetch(request: PageRequest): RemotePage {
                fetches++; return RemotePage(target, format, fetched, null, true)
            }
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("Preparation must not dispatch")
        }
        private val remote: ReadOnlyCalendarRemote = if (!staged) legacyRemote else object : StagedCalendarRemote {
            override val format get() = declaredImportFormat
            override suspend fun currentAccount() = legacyRemote.currentAccount()
            override suspend fun fetch(request: PageRequest): RemotePage = error("Staged receipt must not fetch legacy pages")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("Import must not dispatch")
            override suspend fun receive(request: PageRequest): ReceivedCalendarPage {
                fetches++; return ReceivedCalendarPage(request, "synthetic-page")
            }
            override suspend fun decode(received: ReceivedCalendarPage) =
                DecodedCalendarPage(RemotePage(target, format, fetched, null, true))
            override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = null
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
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Preparation fixture")))
            store.setEnabled(target, true)
            store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true) }
            journal.admitCapture = { it == target }
            journal.reserve(intent.reservation); journal.retain(intent)
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
        }
        suspend fun prepare(mutation: MutationId = id) = coordinator.stageLocalCreateSubmission(target, localId, mutation, attempt, port)
        suspend fun submit() = coordinator.submitLocalCreate(target, localId, id, attempt, port)
        suspend fun record() = creates.read(target, attempt)
        suspend fun staged() = prepare()
        suspend fun admitted() { assertEquals(LocalCreateSubmissionResult.RETAINED, staged()); assertTrue(submit() is CreateSubmissionObservation.Verified) }
        suspend fun bind() = coordinator.bindLocalCreateObservation(target, localId, id, attempt, port)
        suspend fun identity() = CapturedCreateDispatchIdentity.of(checkNotNull(retainedSubmission()))
        suspend fun incoming(vararg changes: RemoteChange) { store.admitTransaction = { it == target }; store.transaction(target) { changes.forEach { putRemote(it); putProjection(ProjectionIntent(target, it)) } } }
        suspend fun pendingCount() = store.selections(target.account).single().pendingProjectionCount
        suspend fun selectionStatus() = store.selections(target.account).single().status
        suspend fun addBoundHold(event: RemoteChange.Upsert) {
            val capture = intent.copy(reservation = intent.reservation.copy(localId = LocalEventId(UUID.randomUUID().toString()),
                id = MutationId(UUID.randomUUID().toString())), content = event.content)
            val sub = LocalCreateSubmission(LocalCreateSubmissionReservation(capture, MutationId(UUID.randomUUID().toString()), preparationFormat), "isolated-ciphertext")
            creates.admitDispatch = { true }; val link = creates.rememberCaptured(sub)
            val record = checkNotNull(creates.read(target, link.attempt.id)); assertTrue(creates.claimCaptured(record, link))
            val unknown = checkNotNull(creates.read(target, link.attempt.id)); creates.admitAssociation = { true }
            creates.bindCaptured(unknown, link, CreateSubmissionObservation.Verified(link.attempt, event))
        }
        suspend fun reopenOwner() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, creates = creates, localCreates = journal)
            val before = schedules.get(); coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
            coordinator.initializeCreates()
        }
        suspend fun retainedSubmission(): LocalCreateSubmission? {
            journal.admitCapture = { it == target }; return journal.readSubmission(target, localId)
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close(); creates.close(); context.deleteDatabase(dispatchName); context.deleteDatabase(importName); context.deleteDatabase(journalName)
        }
    }
    private fun fixture(staged: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(staged)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
