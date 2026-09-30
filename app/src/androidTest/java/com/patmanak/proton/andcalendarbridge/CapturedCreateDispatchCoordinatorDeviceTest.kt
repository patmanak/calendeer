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
class CapturedCreateDispatchCoordinatorDeviceTest {
    @Test fun freshlyStagedCaptureAdmitsOnceThenOnlyInspectsWithoutProviderEvidence() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        assertTrue(submit() is CreateSubmissionObservation.Verified)
        val unknown = checkNotNull(record()); assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, unknown.state)
        assertEquals(1, sends); assertEquals(1, submitCalls); assertEquals(1, calls)
        snapshot = null
        assertTrue(submit() is CreateSubmissionObservation.Verified)
        assertEquals(1, sends); assertEquals(1, submitCalls); assertEquals(1, inspectCalls)
        assertEquals(unknown, record())
    }

    @Test fun cachedStagingAfterOwnerRestartDoesNotAuthorizeTransferOrUpload() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged()); reopenOwner()
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        assertEquals(CreateSubmissionObservation.Unknown, submit())
        assertNull(record()); assertEquals(0, sends); assertEquals(0, submitCalls)
    }

    @Test fun changedProviderDuringFreshRightsReadsPreventsClaimAndSend() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        beforeClaim = { snapshot = snapshot!!.copy(content = content.copy(title = "Concurrent captured edit")) }
        assertEquals(CreateSubmissionObservation.Unknown, submit())
        assertEquals(CreateAttemptState.PREPARED, checkNotNull(record()).state); assertEquals(0, sends)
    }

    @Test fun cancellationAfterClaimRetainsUnknownAcrossRestartWithoutResend() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        afterClaim = { throw CancellationException("Synthetic captured response loss") }
        try { submit(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, checkNotNull(record()).state)
        reopenOwner(); snapshot = null
        assertTrue(submit() is CreateSubmissionObservation.Verified)
        assertEquals(1, sends); assertEquals(1, calls); assertEquals(1, submitCalls)
    }

    @Test fun anIdenticalGenericAttemptCannotBeAdoptedByCapturedDispatch() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        creates.admitDispatch = { true }
        creates.remember(CreateAttemptRecord(attempt, target, preparationFormat, payload))
        assertEquals(CreateSubmissionObservation.Unknown, submit())
        assertEquals(CreateAttemptState.PREPARED, checkNotNull(record()).state); assertEquals(0, sends)
        assertNull(creates.capturedIdentity(target, attempt))
    }

    @Test fun linkedCapturedWorkRefusesGenericSubmissionAndAssociation() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged()); assertTrue(submit() is CreateSubmissionObservation.Verified)
        val unknown = checkNotNull(record())
        try { coordinator.submitCreate(unknown, port); fail("Generic dispatch route accepted captured work") }
        catch (_: IllegalStateException) { }
        assertEquals(CreateAssociationResult.UNRESOLVED, coordinator.associateCreate(unknown, creates, port))
        assertEquals(1, sends); assertEquals(0, inspectCalls)
    }

    @Test fun fabricatedVerifiedContentDoesNotAcknowledgeCapturedWork() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        observedContent = content.copy(title = "Wrong verified expectation")
        assertEquals(CreateSubmissionObservation.Unknown, submit())
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, checkNotNull(record()).state)
        assertEquals(1, sends)
    }

    @Test fun permissionLossBeforeClaimCannotBeRepairedByCachedStaging() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        beforeClaim = { permission = false }
        assertEquals(CreateSubmissionObservation.Unknown, submit()); assertEquals(0, sends)
        permission = true; beforeClaim = { }
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        assertEquals(CreateSubmissionObservation.Unknown, submit()); assertEquals(0, sends)
        assertEquals(CreatePauseReason.PERMISSION_LOST, creates.pauseReason(target, attempt))
    }

    @Test fun policyInvalidationBeforeTransferCannotBeResetWhenSelectionReturns() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        val selected = store.selections(target.account).single()
        coordinator.setEnabled(selected, false)
        withTimeout(10_000) { while (store.selections(target.account).single().enabled) delay(10) }
        store.setEnabled(target, true)
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        assertEquals(CreateSubmissionObservation.Unknown, submit()); assertNull(record()); assertEquals(0, sends)
    }

    @Test fun failedClaimAfterLeaseConsumptionCannotBeRetriedEvenInTheSameOwner() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        creates.writableDatabase.execSQL("CREATE TRIGGER fail_captured_claim BEFORE UPDATE OF state ON create_attempts " +
            "BEGIN SELECT RAISE(ABORT,'synthetic claim failure'); END")
        assertEquals(CreateSubmissionObservation.Unknown, submit())
        assertEquals(CreateAttemptState.PREPARED, checkNotNull(record()).state); assertEquals(0, sends)
        creates.writableDatabase.execSQL("DROP TRIGGER fail_captured_claim")
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        assertEquals(CreateSubmissionObservation.Unknown, submit()); assertEquals(1, submitCalls); assertEquals(0, sends)
    }

    @Test fun restartedTransferredPreparedWorkRemainsPermanentlyPaused() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        beforeClaim = { throw CancellationException("Synthetic gap after journal transfer") }
        try { submit(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(CreateAttemptState.PREPARED, checkNotNull(record()).state)
        reopenOwner(); beforeClaim = { }
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        assertEquals(CreateSubmissionObservation.Unknown, submit()); assertEquals(0, sends)
        assertNotNull(creates.pauseReason(target, attempt))
    }

    @Test fun aSecondClaimAndAnIncorrectVerifiedAttemptCannotAuthorizeOrAcknowledgeWork() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
        repeatClaim = true
        observedAttempt = MutationId("foreign-observation")
        assertEquals(CreateSubmissionObservation.Unknown, submit())
        assertEquals(1, sends); assertFalse(secondClaim)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, checkNotNull(record()).state)
    }

    @Test fun observedAccountOrPermissionLossBeforeTransferInvalidatesThePreparationLease() {
        for (replaceAccount in listOf(true, false)) fixture {
            assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
            if (replaceAccount) account = AccountId("replacement-synthetic-account") else permission = false
            assertEquals(CreateSubmissionObservation.Unknown, submit()); assertNull(record())
            account = target.account; permission = true
            // Observing the return to the original account changes policy once more. The first
            // cache request cannot outlive that observation; a stable second request may read it.
            if (replaceAccount) assertEquals(LocalCreateSubmissionResult.INACTIVE, staged())
            assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
            assertEquals(CreateSubmissionObservation.Unknown, submit()); assertNull(record()); assertEquals(0, sends)
        }
    }

    @Test fun checkpointAndMarkerChangesDuringFreshReadsRefuseAdmission() {
        for (changeCheckpoint in listOf(true, false)) fixture {
            assertEquals(LocalCreateSubmissionResult.RETAINED, staged())
            beforeClaim = {
                if (changeCheckpoint) store.transaction(target) { checkpoint = PageCheckpoint(target, AdapterFormat("foreign-import", 2)) }
                else snapshot = null
            }
            assertEquals(CreateSubmissionObservation.Unknown, submit())
            assertEquals(CreateAttemptState.PREPARED, checkNotNull(record()).state); assertEquals(0, sends)
        }
    }
    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        private val importName = "dispatch-owner-import-$token.db"
        private val dispatchName = "captured-dispatch-$token.db"
        private val journalName = "dispatch-owner-capture-$token.db"
        val target = CalendarScope(AccountId("dispatch-owner-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("dispatch-owner-import", 1)
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
                inspectCalls++; return verified(record)
            }
            override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = error("No remote inspection")
        }
        private val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { true }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("preparation-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("Preparation must not import")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("Preparation must not mutate Provider")
            override suspend fun inspectStampedSimpleCreate(scope: CalendarScope, localId: LocalEventId): OwnedLocalEditSnapshot? {
                assertEquals(target, scope); assertEquals(this@Fixture.localId, localId)
                check(admitOperation()); inspections++; onInspect(); return snapshot
            }
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId? { check(!authHeld) { "Authentication mutex is non-reentrant" }; return account }
            override suspend fun fetch(request: PageRequest): RemotePage = error("Preparation must not import")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("Preparation must not dispatch")
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
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
