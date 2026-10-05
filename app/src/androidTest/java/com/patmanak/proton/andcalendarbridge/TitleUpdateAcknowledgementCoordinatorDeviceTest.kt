// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated SQLite and controlled native ports. No account, Provider or service mutation. */
@RunWith(AndroidJUnit4::class)
class TitleUpdateAcknowledgementCoordinatorDeviceTest {
    private open class CompletionPort(private val journal: TitleUpdateCompletionJournal) : TitleUpdateCompletionJournal {
        override val completionProtocol: Int? get() = journal.completionProtocol
        override suspend fun retainVerified(result: TitleUpdateVerifiedResult) = journal.retainVerified(result)
        override suspend fun readVerified(scope: CalendarScope, id: MutationId) = journal.readVerified(scope, id)
        override suspend fun readCompletion(scope: CalendarScope, id: MutationId) = journal.readCompletion(scope, id)
        override suspend fun retainCompletion(completion: TitleUpdateCompletion, admit: (CalendarScope) -> Boolean) =
            journal.retainCompletion(completion, admit)
    }
    private class Fixture(val transferred: Boolean = false) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val name = "title-ack-owner-$token.db"
        val journalName = "title-ack-result-$token.db"
        val target = CalendarScope(AccountId("title-ack-owner-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("controlled-ack-import", 1)
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("base"), EventContent("Original fixture",
            EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails()))
        val intent = LocalEditIntent(MutationId("original-intent"), target, format, base, base.content.copy(title = "Submitted fixture"))
        val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-05T12:00:00Z"),
            AdapterFormat("controlled-ack-preparation", 1), "synthetic-sealed-preparation")
        val prepared = TitleUpdateAttemptRecord(MutationId("original-attempt"), preparation,
            AdapterFormat("controlled-ack-wire", 1), "synthetic-sealed-wire")
        val unknown = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        val verified = base.copy(revision = Revision("verified"), content = intent.content)
        val result = TitleUpdateVerifiedResult.of(unknown,
            TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(unknown), verified))
        val newer = base.copy(revision = Revision("newer-remote"), content = base.content.copy(title = "Newer remote fixture"))
        val frame: CapturedCreateHandoffFrame by lazy {
            val capture = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-create-capture"),
                LocalEventId(UUID(0, 2).toString()), target, format, Instant.parse("2030-10-01T00:00:00Z")), base.content)
            val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(capture,
                MutationId(UUID(0, 3).toString()), AdapterFormat("synthetic-create-wire", 1)), "synthetic-create-opaque")
            val attachment = CapturedCreateAttachmentReceipt(CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission),
                base.eventId, base.revision), UUID(0, 4).toString())
            val ack = CapturedCreateAcknowledgementReceipt(CapturedCreateBaseline(attachment, base), UUID(0, 5).toString())
            CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(ack, UUID(0, 6).toString()), base)
        }
        val store = ImportStore(context, name)
        val journal = TitleUpdateAttemptStore(context, journalName)
        var permission = true
        var mode = true
        var account: AccountId? = target.account
        var nativeContent = intent.content
        var nativeRevision = base.revision
        var dirty = true
        var marker: TitleUpdateAcknowledgementReceipt? = null
        var writes = 0
        var inspections = 0
        var captures = 0
        var afterWrite: suspend () -> Unit = {}
        var writeReply: (TitleUpdateAcknowledgementReceipt) -> AppliedTitleUpdateAcknowledgement? = { AppliedTitleUpdateAcknowledgement(it) }
        var protocol: Int? = 1
        val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { false }
            override val titleUpdateAcknowledgementProtocol get() = protocol
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("title-ack-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("No Provider access")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No generic projection")
            override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
                check(admitOperation()); captures++
                if (transferred) error("Transferred events must use their full handoff frame")
                return if (scope == target && nativeRevision == base.revision && dirty) OwnedLocalEditSnapshot(1, 1, nativeContent) else null
            }
            override suspend fun captureCapturedHandoffCurrent(frame: CapturedCreateHandoffFrame): CapturedHandoffCurrentObservation {
                check(admitOperation()); captures++
                return if (transferred && frame == this@Fixture.frame && nativeRevision == frame.base.revision && dirty)
                    CapturedHandoffCurrentObservation.Supported(CapturedHandoffCurrent(frame, nativeContent, false))
                else CapturedHandoffCurrentObservation.Unresolved
            }
            override suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                check(admitOperation()); writes++
                if (!dirty || nativeContent != receipt.intent.content || nativeRevision != receipt.intent.base.revision) return null
                dirty = false; nativeRevision = receipt.projection.change.revision; marker = receipt
                afterWrite()
                return writeReply(receipt)
            }
            override suspend fun inspectTitleUpdateAcknowledgement(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                check(admitOperation()); inspections++
                return marker?.takeIf { it == receipt }?.let(::AppliedTitleUpdateAcknowledgement)
            }
        }
        val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No network import")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No dispatch")
        }
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, systemCreatesEnabled = { mode })
        suspend fun seed() {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Acknowledgement fixture")))
            store.setEnabled(target, true)
            store.transaction(target) { putRemote(base); putProjection(ProjectionIntent(target, base)); checkpoint = PageCheckpoint(target, format) }
            if (transferred) {
                val ack = frame.receipt.acknowledgement
                assertTrue(store.retainCapturedBaseline(ack.baseline, AppliedCapturedCreateAttachment(ack.baseline.receipt)))
                store.reserveCapturedAcknowledgement(ack); store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(ack))
                store.reserveCapturedHandoff(frame.receipt); store.retainCapturedHandoff(frame.receipt, CapturedCreateCleanWitness(ack))
                assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(CapturedHandoffCurrent(frame, intent.content, false), intent.id))
            } else {
                assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
                assertEquals(intent, store.captureLocalEdit(intent))
            }
            assertTrue(store.retainLocalEditPreparation(preparation))
            journal.remember(prepared); journal.admitDispatch = { it == target }; assertTrue(journal.claim(prepared))
            journal.admitDispatch = { error("Acknowledgement must never admit dispatch") }
            journal.retainVerified(result)
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
        }
        suspend fun acknowledge(port: TitleUpdateResultJournal = journal) =
            coordinator.acknowledgeLocalTitle(target, base.eventId, prepared.id, port)
        suspend fun complete(port: TitleUpdateCompletionJournal = journal) =
            coordinator.completeLocalTitle(target, base.eventId, prepared.id, port)
        suspend fun owner(): LocalEditIntent? { store.admitTransaction = { true }; return store.localEdit(target, base.eventId) }
        suspend fun receipt(): TitleUpdateAcknowledgementRecord? { store.admitTransaction = { true }; return store.titleUpdateAcknowledgement(target, base.eventId) }
        suspend fun cached(): RemoteChange? { store.admitTransaction = { true }; return store.transaction(target) { remote(base.eventId) } }
        suspend fun cache(change: RemoteChange) {
            store.admitTransaction = { true }
            store.transaction(target) { putRemote(change); putProjection(ProjectionIntent(target, change)) }
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            journal.close(); store.close(); context.deleteDatabase(name); context.deleteDatabase(journalName)
        }
    }
    private fun fixture(transferred: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(transferred)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }

    @Test fun durableResultClosesExactIntentAndAdvancesBaselineWithoutDispatch() = fixture {
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals(1, writes); assertEquals(0, inspections); assertFalse(dirty)
        assertNull(owner()); assertEquals(verified, cached())
        assertEquals(verified, store.projectedBaseline(target, base.eventId))
        assertNull(store.localEditPreparation(target, base.eventId))
        assertEquals(TitleUpdateAcknowledgementState.RESTORED, receipt()!!.state)
        assertEquals(unknown, journal.read(target, prepared.id)); assertEquals(result, journal.readVerified(target, prepared.id))
        assertFalse(projection.admitOperation())
    }

    @Test fun completionRequiresActualRestoredSqlReceiptAndQualifiedJournal() = fixture {
        assertEquals(TitleUpdateCompletionResult.UNRESOLVED, complete())
        store.admitTransaction = { true }
        val pending = store.prepareTitleUpdateAcknowledgement(result)!!
        assertEquals(TitleUpdateCompletionResult.UNRESOLVED, complete())
        assertNull(journal.readCompletion(target, prepared.id)); assertEquals(0, writes); assertEquals(0, inspections)
        marker = pending.record.receipt
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        val unsupported = object : CompletionPort(journal) { override val completionProtocol: Int? = null }
        assertEquals(TitleUpdateCompletionResult.UNRESOLVED, complete(unsupported))
        assertNull(journal.readCompletion(target, prepared.id))
        assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
        assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
        assertEquals(unknown, journal.read(target, prepared.id)); assertEquals(0, writes); assertEquals(1, inspections)
    }

    @Test fun completionChecksPermissionsPolicyAccountAndSelectionWithoutNativeAccess() = fixture {
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        permission = false; assertEquals(TitleUpdateCompletionResult.INACTIVE, complete()); permission = true
        mode = false; assertEquals(TitleUpdateCompletionResult.INACTIVE, complete()); mode = true
        account = AccountId("different-account"); assertEquals(TitleUpdateCompletionResult.INACTIVE, complete()); account = target.account
        store.setEnabled(target, false); assertEquals(TitleUpdateCompletionResult.INACTIVE, complete())
        assertNull(journal.readCompletion(target, prepared.id)); assertEquals(1, writes); assertEquals(0, inspections)
    }

    @Test fun selectionRevokedDuringCompletionResultReadPreventsClosure() = fixture {
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        var reads = 0
        val changing = object : CompletionPort(journal) {
            override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult? {
                if (++reads == 2) store.setEnabled(target, false)
                return journal.readVerified(scope, id)
            }
        }
        assertEquals(TitleUpdateCompletionResult.INACTIVE, complete(changing))
        assertNull(journal.readCompletion(target, prepared.id)); assertEquals(1, writes); assertEquals(0, inspections)
    }

    @Test fun corruptArchiveBeforeSlotRotationCannotReleaseAttemptThroughValidCurrentCopy() = fixture {
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        store.writableDatabase.execSQL("UPDATE title_update_acknowledgement_history SET receipt='not-json'")
        assertEquals(TitleUpdateCompletionResult.UNRESOLVED, complete())
        assertNull(journal.readCompletion(target, prepared.id))
        assertEquals(unknown, journal.read(target, prepared.id)); assertEquals(1, writes); assertEquals(0, inspections)
    }

    @Test fun completionLeaseRevokedInsideJournalRollsBackAndCanRecoverOffline() = fixture {
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        val changing = object : CompletionPort(journal) {
            override suspend fun retainCompletion(completion: TitleUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean {
                var checks = 0
                return journal.retainCompletion(completion) {
                    if (++checks == 2) permission = false
                    admit(it)
                }
            }
        }
        assertEquals(TitleUpdateCompletionResult.INACTIVE, complete(changing))
        assertNull(journal.readCompletion(target, prepared.id)); assertEquals(unknown, journal.read(target, prepared.id))
        permission = true
        assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
        assertEquals(1, writes); assertEquals(0, inspections)
    }

    @Test fun missingDurableResultAndChangedNativeContentNeverReserveOrWrite() = fixture {
        val missing = object : TitleUpdateResultJournal {
            override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult? = null
            override suspend fun retainVerified(result: TitleUpdateVerifiedResult) = error("No retention here")
        }
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge(missing))
        nativeContent = intent.content.copy(title = "Later native title")
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertNull(receipt()); assertEquals(0, writes); assertTrue(dirty); assertEquals(intent, owner())
    }

    @Test fun lostNativeReplyThenLaterEditRecoversByInspectionAndNewCaptureUsesAdvancedBase() = fixture {
        afterWrite = { throw IllegalStateException("Synthetic lost native reply") }
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
        nativeContent = intent.content.copy(title = "Later native title"); dirty = true
        afterWrite = {}
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals(1, writes); assertEquals(1, inspections); assertTrue(dirty)
        assertEquals("Later native title", nativeContent.title)
        assertEquals(LocalEditCaptureResult.RETAINED,
            coordinator.captureLocalEdit(target, base.eventId, MutationId("next-intent")))
        val next = owner()!!
        assertEquals(verified, next.base); assertEquals(nativeContent, next.content)
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals(next, owner()); assertEquals(1, writes)
    }

    @Test fun failedSqlRestorationKeepsReceiptAndRetryNeverRepeatsNativeWrite() = fixture {
        store.writableDatabase.execSQL("CREATE TRIGGER fixture_ack_failure BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'synthetic restore loss'); END")
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertFalse(dirty); assertEquals(1, writes)
        assertEquals(intent, owner()); assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
        store.writableDatabase.execSQL("DROP TRIGGER fixture_ack_failure")
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals(1, writes); assertEquals(1, inspections); assertNull(owner())
    }

    @Test fun newRemoteObservationAfterNativeAckClosesOnlyOriginalIntentAndPreservesNewerRecord() = fixture {
        afterWrite = { cache(newer); assertEquals(LocalEditState.CONFLICT, owner()!!.state) }
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertNull(owner()); assertEquals(newer, cached())
        assertEquals(verified, store.projectedBaseline(target, base.eventId))
        // The incoming projection was held by the original local owner. Restoration
        // preserves that pending state; a later projection trigger can requeue it.
        assertTrue(store.pending(target).isEmpty())
        store.requeueAll(target)
        assertEquals(listOf(ProjectionIntent(target, newer)), store.pending(target))
    }

    @Test fun exactResultEchoAfterNativeAckIsRestoredWithoutResettingConflict() = fixture {
        afterWrite = { cache(verified); assertEquals(LocalEditState.CONFLICT, owner()!!.state) }
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertNull(owner()); assertEquals(verified, cached()); assertTrue(store.pending(target).isEmpty())
    }

    @Test fun reopenedReservationThatNeverExecutedCannotRearmNativeWrite() = fixture {
        store.admitTransaction = { true }
        assertTrue(store.prepareTitleUpdateAcknowledgement(result)!!.fresh)
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertEquals(0, writes); assertEquals(1, inspections); assertTrue(dirty)
        assertEquals(intent, owner()); assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
    }

    @Test fun permissionsPolicyAccountSelectionAndProtocolGateAllNativeWrites() = fixture {
        permission = false; assertEquals(TitleUpdateAcknowledgementResult.INACTIVE, acknowledge())
        permission = true; mode = false; assertEquals(TitleUpdateAcknowledgementResult.INACTIVE, acknowledge())
        mode = true; account = AccountId("foreign"); assertEquals(TitleUpdateAcknowledgementResult.INACTIVE, acknowledge())
        account = target.account; store.setEnabled(target, false); assertEquals(TitleUpdateAcknowledgementResult.INACTIVE, acknowledge())
        store.setEnabled(target, true); protocol = null; assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertEquals(0, writes); assertNull(receipt()); assertTrue(dirty)
    }

    @Test fun policyOffAndOnDuringHistoricalReadStillInvalidatesTheCall() = fixture {
        val changing = object : TitleUpdateResultJournal {
            override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult {
                coordinator.systemCreatePolicyChanged(target, false)
                coordinator.systemCreatePolicyChanged(target, true)
                return result
            }
            override suspend fun retainVerified(result: TitleUpdateVerifiedResult) = error("No retention here")
        }
        assertEquals(TitleUpdateAcknowledgementResult.INACTIVE, acknowledge(changing))
        assertEquals(0, writes); assertNull(receipt()); assertTrue(dirty)
    }

    @Test fun wrongWitnessCannotConsumeIntentAndRecoveryInspectsOriginalReceipt() = fixture {
        writeReply = { receipt -> AppliedTitleUpdateAcknowledgement(receipt.copy(projection = receipt.projection.copy(
            nonce = "00000000-0000-0000-0000-000000000099"))) }
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertEquals(intent, owner()); assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertNull(owner()); assertEquals(1, writes); assertEquals(1, inspections)
    }

    @Test fun selectionRevokedDuringFinalResultReadPreventsTheNativeWrite() = fixture {
        var reads = 0
        val changing = object : TitleUpdateResultJournal {
            override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult {
                if (++reads == 3) store.setEnabled(target, false)
                return result
            }
            override suspend fun retainVerified(result: TitleUpdateVerifiedResult) = error("No retention here")
        }
        assertEquals(TitleUpdateAcknowledgementResult.INACTIVE, acknowledge(changing))
        assertEquals(0, writes); assertTrue(dirty); assertEquals(intent, owner())
    }

    @Test fun accountChangedDuringFinalResultReadPreventsTheNativeWrite() = fixture {
        var reads = 0
        val changing = object : TitleUpdateResultJournal {
            override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult {
                if (++reads == 3) account = AccountId("foreign")
                return result
            }
            override suspend fun retainVerified(result: TitleUpdateVerifiedResult) = error("No retention here")
        }
        assertEquals(TitleUpdateAcknowledgementResult.INACTIVE, acknowledge(changing))
        assertEquals(0, writes); assertTrue(dirty); assertEquals(intent, owner())
    }

    @Test fun transferredTitleSelectsFullFrameAndRestoresItAlongsideItsOriginalOwner() = fixture(transferred = true) {
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertNull(owner()); assertEquals(frame, receipt()!!.receipt.handoff)
        val current = store.capturedHandoff(target, base.eventId)!!
        assertEquals(CapturedCreateHandoffFrame(frame.receipt, verified, receipt()!!.receipt.projection), current.frame)
        assertFalse(current.waiting); assertEquals(1, writes); assertEquals(verified, cached())
    }

    @Test fun transferredLaterTitleBeforeReservationIsNeverAcknowledged() = fixture(transferred = true) {
        nativeContent = intent.content.copy(title = "A later synthetic transferred edit")
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertEquals(0, writes); assertNull(receipt()); assertTrue(dirty); assertEquals(intent, owner())
    }

    @Test fun transferredPendingReservationWithoutNativeCommitNeverRearmsWrite() = fixture(transferred = true) {
        assertTrue(store.prepareTitleUpdateAcknowledgement(result, frame)!!.fresh)
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertEquals(0, writes); assertEquals(1, inspections); assertTrue(dirty); assertEquals(intent, owner())
    }

    @Test fun transferredWaitingChangedDuringFinalReadPreventsNativeWrite() = fixture(transferred = true) {
        var reads = 0
        val changing = object : TitleUpdateResultJournal {
            override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult {
                if (++reads == 3) store.writableDatabase.execSQL("UPDATE captured_create_handoffs SET waiting=0")
                return result
            }
            override suspend fun retainVerified(result: TitleUpdateVerifiedResult) = error("No retention here")
        }
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge(changing))
        assertEquals(0, writes); assertTrue(dirty); assertEquals(intent, owner())
    }

    @Test fun transferredOriginCorruptionRefusesWithoutOrdinaryFallback() = fixture(transferred = true) {
        store.writableDatabase.execSQL("UPDATE captured_create_acknowledgements SET fingerprint='synthetic-corruption'")
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertEquals(0, writes); assertEquals(0, captures); assertTrue(dirty); assertEquals(intent, owner())
    }

    @Test fun transferredRemoteObservationAfterNativeCommitClosesOnlyOriginalWork() = fixture(transferred = true) {
        afterWrite = { cache(newer); assertEquals(LocalEditState.CONFLICT, owner()!!.state) }
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertNull(owner()); assertEquals(newer, cached()); assertTrue(store.pending(target).isEmpty())
        assertEquals(verified, store.capturedHandoff(target, base.eventId)!!.frame.base)
        assertEquals(frame.receipt, store.capturedHandoff(target, base.eventId)!!.frame.receipt)
        assertFalse(store.capturedHandoff(target, base.eventId)!!.waiting)
    }
}
