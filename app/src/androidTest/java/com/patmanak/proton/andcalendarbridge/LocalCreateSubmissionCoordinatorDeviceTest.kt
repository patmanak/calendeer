// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
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


/** Real journals and controlled ports; no transport. */
@RunWith(AndroidJUnit4::class)
class LocalCreateSubmissionCoordinatorDeviceTest {
    @Test fun frozenCapturedDraftStagesOnceWithoutConvertingHistoricalPreparation() = fixture {
        journal.admitCapture = { it == target }
        val historical = LocalCreatePreparation(intent, AdapterFormat("historical-local-preparation", 1), "historical-protected-blob")
        journal.retainPreparation(historical)
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare())
        assertEquals(LocalCreateSubmission(LocalCreateSubmissionReservation(intent, attempt, preparationFormat), payload), retainedSubmission())
        assertEquals(1, calls); assertTrue(inspections >= 2)
        journal.admitCapture = { it == target }
        assertEquals(historical, journal.readPreparation(target, localId)); assertEquals(intent, journal.readIntent(target, localId))
        onPrepare = { error("Committed submission must not be regenerated") }
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare()); assertEquals(1, calls)
    }

    @Test fun anInterruptedPreparationLeavesAnImmutableNonretryableGap() = fixture {
        onPrepare = { throw CancellationException("Synthetic cancelled context read") }
        try { prepare(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
        assertNull(retainedSubmission())
        journal.admitCapture = { it == target }
        assertEquals(LocalCreateSubmissionReservation(intent, attempt, preparationFormat), journal.readSubmissionReservation(target, localId))
        onPrepare = { error("Existing gap must not prepare") }
        assertEquals(LocalCreateSubmissionResult.UNRESOLVED, prepare()); assertEquals(1, calls)
    }

    @Test fun aReservedGapNeverCallsTheAdapterEvenIfTheFirstCallWasLost() = fixture {
        journal.admitCapture = { it == target }
        assertNotNull(journal.reserveSubmission(LocalCreateSubmissionReservation(intent, attempt, preparationFormat)))
        assertEquals(LocalCreateSubmissionResult.UNRESOLVED, prepare()); assertEquals(0, calls)
    }

    @Test fun contentDriftDuringPreparationLeavesTheReservationWithoutPayload() = fixture {
        onPrepare = { snapshot = snapshot!!.copy(content = content.copy(title = "Concurrent local edit")); receipt() }
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare()); assertNull(retainedSubmission())
        snapshot = OwnedLocalEditSnapshot(3, 7, content)
        assertEquals(LocalCreateSubmissionResult.UNRESOLVED, prepare()); assertEquals(1, calls)
    }

    @Test fun cachedSubmissionStillRefusesChangedOrMissingProviderEvidence() = fixture {
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare())
        val retained = retainedSubmission()
        snapshot = snapshot!!.copy(content = content.copy(title = "Later edit"))
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare())
        snapshot = null; assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare())
        assertEquals(retained, retainedSubmission()); assertEquals(1, calls)
    }

    @Test fun sessionChangeDuringPreparationRefusesRetention() = fixture {
        onPrepare = { coordinator.authenticationChanged(false); receipt() }
        assertEquals(LocalCreateSubmissionResult.INACTIVE, prepare()); assertNull(retainedSubmission())
    }

    @Test fun selectionDriftCannotRetainAndRestorationDoesNotReprepare() = fixture {
        onPrepare = { store.setEnabled(target, false); receipt() }
        assertEquals(LocalCreateSubmissionResult.INACTIVE, prepare()); assertNull(retainedSubmission())
        store.setEnabled(target, true)
        assertEquals(LocalCreateSubmissionResult.UNRESOLVED, prepare()); assertEquals(1, calls)
    }

    @Test fun changedDeclaredFormatCannotRetain() = fixture {
        onPrepare = { declaredPreparationFormat = preparationFormat.copy(version = 3); receipt() }
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare()); assertNull(retainedSubmission())
        declaredPreparationFormat = preparationFormat
        assertEquals(LocalCreateSubmissionResult.UNRESOLVED, prepare()); assertEquals(1, calls)
    }

    @Test fun checkpointChangeAfterReadsRefusesTheFrozenSubmission() = fixture {
        onPrepare = { store.transaction(target) { checkpoint = PageCheckpoint(target, AdapterFormat("changed-import", 2)) }; receipt() }
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare()); assertNull(retainedSubmission())
    }

    @Test fun networkFailurePreservesTheGapWithoutRetrying() = fixture {
        onPrepare = { throw CreatePreparationException(CreatePreparationFailure.NETWORK) }
        assertEquals(LocalCreateSubmissionResult.UNAVAILABLE, prepare()); assertNull(retainedSubmission())
        assertEquals(LocalCreateSubmissionResult.UNRESOLVED, prepare()); assertEquals(1, calls)
    }

    @Test fun currentAdmissionAndCapabilityGateBeforeReservationAndProviderAccess() = fixture {
        permission = false; assertEquals(LocalCreateSubmissionResult.INACTIVE, prepare())
        permission = true; account = AccountId("foreign-synthetic")
        assertEquals(LocalCreateSubmissionResult.INACTIVE, prepare())
        account = target.account; declaredPreparationFormat = null
        assertEquals(LocalCreateSubmissionResult.UNSUPPORTED, prepare())
        assertEquals(0, calls); assertEquals(0, inspections); assertNull(retainedSubmission())
        journal.admitCapture = { it == target }; assertNull(journal.readSubmissionReservation(target, localId))
    }

    @Test fun differentCapturedMutationOrAttemptCannotRebindTheRetainedPayload() = fixture {
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare(MutationId("wrong-capture")))
        assertEquals(LocalCreateSubmissionResult.RETAINED, prepare())
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED,
            coordinator.stageLocalCreateSubmission(target, localId, id, MutationId(UUID.randomUUID().toString()), port))
        assertEquals(1, calls)
    }

    @Test fun foreignScopeLegacyFormatAndBlankResponsesCannotBeRetained() {
        for (mode in 0..2) fixture {
            onPrepare = {
                when (mode) {
                    0 -> PreparedCalendarCreate(target.copy(calendar = CalendarId("foreign")), preparationFormat, payload)
                    1 -> PreparedCalendarCreate(target, AdapterFormat("local-preparation", 1), payload)
                    else -> PreparedCalendarCreate(target, preparationFormat, " ")
                }
            }
            assertEquals(LocalCreateSubmissionResult.UNQUALIFIED, prepare()); assertNull(retainedSubmission())
            onPrepare = { error("Never reconstruct a refused receipt") }
            assertEquals(LocalCreateSubmissionResult.UNRESOLVED, prepare()); assertEquals(1, calls)
        }
    }

    @Test fun accountAndPermissionLossDuringTheAdapterReadsPreventRetention() {
        for (changeAccount in listOf(true, false)) fixture {
            onPrepare = { if (changeAccount) account = AccountId("changed-synthetic-account") else permission = false; receipt() }
            assertEquals(LocalCreateSubmissionResult.INACTIVE, prepare()); assertNull(retainedSubmission())
            account = target.account; permission = true
            assertEquals(LocalCreateSubmissionResult.UNRESOLVED, prepare()); assertEquals(1, calls)
        }
    }

    @Test fun postInspectionSelectionDriftCannotReserveEvenWhenContentMatches() = fixture {
        onInspect = { store.setEnabled(target, false) }
        assertEquals(LocalCreateSubmissionResult.INACTIVE, prepare()); assertEquals(0, calls)
        journal.admitCapture = { it == target }; assertNull(journal.readSubmissionReservation(target, localId))
    }

    @Test fun unknownCapturedIdentityCannotReachMarkerOrAdapter() = fixture {
        assertEquals(LocalCreateSubmissionResult.UNQUALIFIED,
            coordinator.stageLocalCreateSubmission(target, LocalEventId(UUID.randomUUID().toString()), id, attempt, port))
        assertEquals(0, calls); assertEquals(0, inspections)
    }
    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        private val importName = "submission-owner-import-$token.db"
        private val journalName = "submission-owner-capture-$token.db"
        val target = CalendarScope(AccountId("submission-owner-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("submission-owner-import", 1)
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
        var permission = true
        var account: AccountId? = target.account
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
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = error("No submission")
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
            override suspend fun currentAccount() = account
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
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, localCreates = journal)
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
        suspend fun retainedSubmission(): LocalCreateSubmission? {
            journal.admitCapture = { it == target }; return journal.readSubmission(target, localId)
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close(); context.deleteDatabase(importName); context.deleteDatabase(journalName)
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
