// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreatePauseReason
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real SQLite and controlled ports; no real account, network or Calendar Provider use. */
@RunWith(AndroidJUnit4::class)
class CreateLifecycleCoordinatorDeviceTest {
    private class Fixture(val context: Context) {
        private val token = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("create-lifecycle-$token"), CalendarId("calendar"), 0)
        val other = target.copy(account = AccountId("other-lifecycle-$token"))
        val format = AdapterFormat("lifecycle-fixture", 1)
        val content = EventContent("Create lifecycle fixture", EventTime.AllDay(
            LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2)))
        val draft = CalendarCreateDraft(target, MutationId("attempt"), Instant.parse("2029-12-01T00:00:00Z"), content)
        val legacy = CreateAttemptRecord(draft.correlation, target, format, "sealed-fixture")
        private val importName = "create-lifecycle-import-$token.db"
        private val journalName = "create-lifecycle-journal-$token.db"
        var store = ImportStore(context, importName)
        var journal = CreateAttemptStore(context, journalName)
        @Volatile var permission = true
        @Volatile var currentAccount: AccountId? = target.account
        var prepareCount = 0
        var submitCount = 0
        var inspectCount = 0
        var dispatchCount = 0
        var catalogLoadCount = 0
        var fetchCount = 0
        var controlledCatalog: CalendarCatalogResult = CalendarCatalogResult.Success(target.account,
            listOf(CalendarSummary(target.calendar, "Lifecycle fixture")))
        var preparedScope = target
        var onPrepare: suspend () -> Unit = {}
        var beforeClaim: suspend () -> Unit = {}
        var afterClaim: suspend () -> Unit = {}
        var onInspect: suspend () -> Unit = {}
        val port = object : CalendarCreateSubmissionPort {
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate {
                prepareCount++
                onPrepare()
                return PreparedCalendarCreate(preparedScope, format, "sealed-fixture")
            }
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation {
                submitCount++
                beforeClaim()
                if (!journal.claim(record)) return CreateSubmissionObservation.Unknown
                dispatchCount++
                afterClaim()
                journal.retainEvidence(record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN), "sealed-response")
                return CreateSubmissionObservation.Unknown
            }
            override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation {
                inspectCount++
                onInspect()
                return CreateSubmissionObservation.Unknown
            }
        }
        private val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { true }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("lifecycle-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar =
                error("Lifecycle tests never drain the Provider")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) =
                error("Lifecycle tests never change the Provider")
        }
        private val remote = object : ReadOnlyCalendarRemote, CalendarCatalogPort {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = this@Fixture.currentAccount
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No general uploads")
            override suspend fun fetch(request: PageRequest): RemotePage {
                fetchCount++
                error("No downloads")
            }
            override suspend fun load(): CalendarCatalogResult {
                catalogLoadCount++
                return controlledCatalog
            }
        }
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() = Unit
            override fun continueWork(network: Boolean) = Unit
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var coordinator: ImportCoordinator
        suspend fun start(seed: suspend Fixture.() -> Unit = {}) {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Lifecycle fixture")))
            store.setEnabled(target, true)
            store.remember(other.account, listOf(CalendarSummary(other.calendar, "Other fixture")))
            store.setEnabled(other, true)
            seed()
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, journal)
            coordinator.initializeCreates()
            coordinator.authenticationChanged(true)
            coordinator.initializeCreates()
            awaitCondition { coordinator.state.value.selections.any { it.scope == target } }
        }
        suspend fun reconnect() {
            coordinator.authenticationChanged(true)
            coordinator.initializeCreates()
        }
        suspend fun selection() = store.selections(target.account).single { it.scope == target }
        suspend fun pause(id: MutationId = draft.correlation) = journal.pauseReason(target, id)
        suspend fun record(id: MutationId = draft.correlation) = journal.read(target, id)
        fun rejectPauseWrites() {
            journal.writableDatabase.execSQL("""CREATE TRIGGER reject_lifecycle_pause
                BEFORE INSERT ON create_pauses BEGIN
                SELECT RAISE(ABORT, 'controlled pause failure'); END""".trimIndent())
        }
        fun allowPauseWrites() {
            journal.writableDatabase.execSQL("DROP TRIGGER reject_lifecycle_pause")
        }
        suspend fun reopen() {
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close()
            store = ImportStore(context, importName)
            journal = CreateAttemptStore(context, journalName)
            jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, journal)
            coordinator.initializeCreates()
            coordinator.authenticationChanged(true)
            coordinator.initializeCreates()
        }
        suspend fun close() {
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close()
            context.deleteDatabase(importName); context.deleteDatabase(journalName)
        }
    }

    private fun fixture(seed: suspend Fixture.() -> Unit = {}, block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        try { fixture.start(seed); fixture.block() } finally { fixture.close() }
    }

    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("The lifecycle gate must refuse this operation") }
        catch (failure: Exception) { if (failure is CancellationException) throw failure }
    }

    private companion object {
        suspend fun awaitCondition(predicate: suspend () -> Boolean) = withTimeout(10_000) {
            while (!predicate()) delay(10)
        }
    }

    @Test fun qualifiedNewWorkIsDispatchedOnceAndUnknownRetriesOnlyInspect() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        assertEquals(legacy, staged)
        assertNull(pause())
        assertFalse(journal.claim(staged))
        assertEquals(CreateAttemptState.PREPARED, record()!!.state)
        assertSame(CreateSubmissionObservation.Unknown, coordinator.submitCreate(staged, port))
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, record()!!.state)
        assertEquals("sealed-response", record()!!.protectedEvidence)
        assertSame(CreateSubmissionObservation.Unknown, coordinator.submitCreate(staged, port))
        assertEquals(1, prepareCount); assertEquals(1, submitCount)
        assertEquals(1, dispatchCount); assertEquals(1, inspectCount)
        assertFalse(journal.claim(staged))
    }

    @Test fun startupPausesLegacyPreparedWorkButPreservesUnknownEvidenceAndBinding() = fixture(seed = {
        journal.remember(legacy)
        val unknown = legacy.copy(id = MutationId("unknown"))
        journal.remember(unknown); assertTrue(journal.claim(unknown))
        val admitted = unknown.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        journal.retainEvidence(admitted, "sealed-historical-response")
        val observed = RemoteChange.Upsert(EventId("historical"), Revision("first"), content)
        journal.bind(admitted.copy(protectedEvidence = "sealed-historical-response"),
            CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(admitted), observed))
    }) {
        assertEquals(CreatePauseReason.PROCESS_RESTART, pause())
        refused { coordinator.submitCreate(legacy, port) }
        val unknown = record(MutationId("unknown"))!!
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, unknown.state)
        assertEquals("sealed-historical-response", unknown.protectedEvidence)
        assertNull(pause(unknown.id))
        assertNotNull(journal.binding(CreateAttemptIdentity.of(unknown)))
        reopen()
        assertEquals(CreatePauseReason.PROCESS_RESTART, pause())
        assertNotNull(journal.binding(CreateAttemptIdentity.of(unknown)))
        coordinator.submitCreate(unknown, port)
        assertEquals(1, inspectCount); assertEquals(0, dispatchCount)
    }

    @Test fun aNewOwnerPausesPreviouslyStagedWorkInsteadOfAutomaticallyDispatching() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        reopen()
        assertEquals(CreatePauseReason.PROCESS_RESTART, pause())
        assertEquals(staged, record())
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(0, submitCount); assertEquals(0, dispatchCount)
    }

    @Test fun logoutDuringPreparationCannotPersistOrSendNewWork() = fixture {
        onPrepare = { coordinator.authenticationChanged(false) }
        refused { coordinator.stageCreate(draft, port) }
        coordinator.initializeCreates()
        assertNull(record()); assertEquals(0, dispatchCount)
        assertEquals(1, prepareCount); assertEquals(0, submitCount)
    }

    @Test fun foreignPreparationReceiptCannotBecomeScopedJournalWork() = fixture {
        preparedScope = other
        refused { coordinator.stageCreate(draft, port) }
        assertNull(record()); assertNull(journal.read(other, draft.correlation))
        assertEquals(0, dispatchCount)
    }

    @Test fun logoutBeforeAdmissionDurablyPausesPreparedWorkAndReconnectDoesNotResumeIt() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        beforeClaim = { coordinator.authenticationChanged(false) }
        refused { coordinator.submitCreate(staged, port) }
        coordinator.initializeCreates()
        assertEquals(CreateAttemptState.PREPARED, record()!!.state)
        assertEquals(CreatePauseReason.SESSION_CHANGED, pause())
        assertEquals(0, dispatchCount)
        reconnect(); beforeClaim = {}
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.SESSION_CHANGED, pause())
        assertEquals(1, submitCount); assertEquals(0, dispatchCount)
    }

    @Test fun logoutAfterAdmissionKeepsUnknownAndAllowsLateEvidenceWithoutResend() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        afterClaim = { coordinator.authenticationChanged(false) }
        coordinator.submitCreate(staged, port)
        coordinator.initializeCreates()
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, record()!!.state)
        assertEquals("sealed-response", record()!!.protectedEvidence)
        assertNull(pause()); assertEquals(1, dispatchCount)
        reconnect()
        coordinator.submitCreate(staged, port)
        assertEquals(1, dispatchCount); assertEquals(1, inspectCount)
    }

    @Test fun accountReplacementWithoutFalseAuthenticationPausesOldAccountWork() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        currentAccount = other.account
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.SESSION_CHANGED, pause())
        assertEquals(CreateAttemptState.PREPARED, record()!!.state)
        preparedScope = other
        val replacement = coordinator.stageCreate(draft.copy(scope = other, correlation = MutationId("replacement")), port)
        assertEquals(other, replacement.scope)
        assertNull(journal.pauseReason(other, replacement.id))
        assertEquals(0, dispatchCount)
        currentAccount = target.account
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.SESSION_CHANGED, pause())
    }

    @Test fun disablingWhilePreparationRunsImmediatelyFencesAndRetainsEarlierWork() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        onPrepare = { coordinator.setEnabled(selection(), false) }
        val second = draft.copy(correlation = MutationId("second"))
        refused { coordinator.stageCreate(second, port) }
        awaitCondition { pause() == CreatePauseReason.CALENDAR_DISABLED && !selection().enabled }
        assertNull(record(second.correlation)); assertEquals(staged, record())
        coordinator.setEnabled(selection(), true)
        awaitCondition { selection().enabled }
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.CALENDAR_DISABLED, pause())
        reopen()
        assertEquals(CreatePauseReason.CALENDAR_DISABLED, pause())
        assertEquals(0, dispatchCount)
    }

    @Test fun onlySuccessfulCatalogAbsencePausesWorkAndRestorationDoesNotResumeIt() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        coordinator.rememberCatalog(CalendarCatalogState(status = CalendarCatalogStatus.ERROR,
            account = target.account, failure = CalendarCatalogFailure.UNREACHABLE))
        assertNull(pause()); assertTrue(selection().available)
        coordinator.rememberCatalog(CalendarCatalogState(status = CalendarCatalogStatus.READY,
            account = target.account, calendars = emptyList()))
        assertEquals(CreatePauseReason.CALENDAR_UNAVAILABLE, pause())
        assertFalse(selection().available)
        coordinator.rememberCatalog(CalendarCatalogState(status = CalendarCatalogStatus.READY,
            account = target.account, calendars = listOf(CalendarSummary(target.calendar, "Restored fixture"))))
        assertTrue(selection().available)
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.CALENDAR_UNAVAILABLE, pause()); assertEquals(0, dispatchCount)
    }

    @Test fun permissionLossBeforeClaimPausesWorkAndGrantDoesNotAuthorizeRetry() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        beforeClaim = { permission = false; coordinator.permissionsChanged() }
        refused { coordinator.submitCreate(staged, port) }
        awaitCondition { pause() == CreatePauseReason.PERMISSION_LOST }
        assertEquals(CreateAttemptState.PREPARED, record()!!.state)
        permission = true; coordinator.permissionsChanged(); beforeClaim = {}
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.PERMISSION_LOST, pause())
        assertEquals(0, dispatchCount)
    }

    @Test fun forgedCallerPayloadCannotReachSubmissionOrChangeDurablePreparation() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        refused { coordinator.submitCreate(staged.copy(protectedPayload = "foreign-sealed-fixture"), port) }
        assertEquals(staged, record()); assertNull(pause())
        assertEquals(0, submitCount); assertEquals(0, dispatchCount)
    }

    @Test fun authenticationLossDuringUnknownInspectionDiscardsObservationWithoutResettingWork() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        coordinator.submitCreate(staged, port)
        val admitted = record()!!
        onInspect = { coordinator.authenticationChanged(false) }
        refused { coordinator.submitCreate(staged, port) }
        coordinator.initializeCreates()
        assertEquals(admitted, record()); assertNull(pause())
        assertEquals(1, inspectCount); assertEquals(1, dispatchCount)
        reconnect(); onInspect = {}
        coordinator.submitCreate(staged, port)
        assertEquals(2, inspectCount); assertEquals(1, dispatchCount)
    }

    @Test fun readyCatalogAbsenceDuringPreparationFencesBeforeItsSerializedStorageUpdate() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        onPrepare = { entered.complete(Unit); release.await() }
        coroutineScope {
            val preparation = async { runCatching {
                coordinator.stageCreate(draft.copy(correlation = MutationId("second")), port)
            } }
            withTimeout(10_000) { entered.await() }
            // UNDISPATCHED executes the immediate admission fence before waiting on the owner mutex.
            val removed = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.rememberCatalog(CalendarCatalogState(status = CalendarCatalogStatus.READY,
                    account = target.account, calendars = emptyList()))
            }
            release.complete(Unit)
            assertTrue(preparation.await().isFailure)
            removed.await()
        }
        assertEquals(CreatePauseReason.CALENDAR_UNAVAILABLE, pause())
        assertEquals(staged, record()); assertNull(record(MutationId("second")))
        assertEquals(0, dispatchCount)
    }

    @Test fun failedPauseCommitDuringCatalogAbsenceCannotBeForgottenByRestoredCatalog() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        rejectPauseWrites()
        refused {
            coordinator.rememberCatalog(CalendarCatalogState(status = CalendarCatalogStatus.READY,
                account = target.account, calendars = emptyList()))
        }
        assertNull(pause()); assertTrue(selection().available)
        assertEquals(staged, record()); assertEquals(0, dispatchCount)
        allowPauseWrites()
        coordinator.rememberCatalog(CalendarCatalogState(status = CalendarCatalogStatus.READY,
            account = target.account, calendars = listOf(CalendarSummary(target.calendar, "Restored fixture"))))
        assertTrue(selection().available)
        assertEquals(CreatePauseReason.CALENDAR_UNAVAILABLE, pause())
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(0, submitCount); assertEquals(0, dispatchCount)
    }

    @Test fun failedPauseCommitDuringAccountReplacementCannotBeForgottenOnReturningToOldAccount() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        rejectPauseWrites(); currentAccount = other.account
        refused { coordinator.submitCreate(staged, port) }
        assertNull(pause()); assertEquals(staged, record())
        currentAccount = target.account; allowPauseWrites()
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.SESSION_CHANGED, pause())
        assertEquals(staged, record()); assertEquals(0, submitCount); assertEquals(0, dispatchCount)
    }

    @Test fun permissionLossObservedByImportIsStickyWithoutAnExplicitPermissionCallback() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        permission = false
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = false))
        permission = true
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.PERMISSION_LOST, pause())
        assertEquals(staged, record()); assertEquals(0, submitCount); assertEquals(0, dispatchCount)
    }

    @Test fun failedPauseCommitDuringObservedPermissionLossCannotBeForgottenOnGrantRestoration() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        rejectPauseWrites(); permission = false
        refused { coordinator.submitCreate(staged, port) }
        assertNull(pause()); assertEquals(staged, record())
        permission = true; allowPauseWrites()
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.PERMISSION_LOST, pause())
        assertEquals(staged, record()); assertEquals(0, submitCount); assertEquals(0, dispatchCount)
    }

    @Test fun workerCatalogAbsenceRetainsPauseIntentWhenSqliteFailsBeforeSelectionUpdate() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        controlledCatalog = CalendarCatalogResult.Success(target.account, emptyList())
        rejectPauseWrites()
        refused { coordinator.run(network = true) }
        assertEquals(1, catalogLoadCount); assertEquals(0, fetchCount)
        assertNull(pause()); assertTrue(selection().available); assertEquals(staged, record())
        controlledCatalog = CalendarCatalogResult.Success(target.account,
            listOf(CalendarSummary(target.calendar, "Restored fixture")))
        allowPauseWrites()
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.CALENDAR_UNAVAILABLE, pause())
        assertTrue(selection().available); assertEquals(staged, record())
        assertEquals(0, submitCount); assertEquals(0, dispatchCount); assertEquals(0, fetchCount)
    }

    @Test fun accountReplacementObservedByImportRemainsStickyWhenTheOldAccountReturns() = fixture {
        val staged = coordinator.stageCreate(draft, port)
        // The replacement identity has no available selection, so this only exercises ownership.
        store.remember(other.account, emptyList())
        currentAccount = other.account
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = false))
        currentAccount = target.account
        refused { coordinator.submitCreate(staged, port) }
        assertEquals(CreatePauseReason.SESSION_CHANGED, pause())
        assertEquals(staged, record()); assertEquals(0, submitCount); assertEquals(0, dispatchCount)
        assertEquals(0, catalogLoadCount); assertEquals(0, fetchCount)
    }
}
