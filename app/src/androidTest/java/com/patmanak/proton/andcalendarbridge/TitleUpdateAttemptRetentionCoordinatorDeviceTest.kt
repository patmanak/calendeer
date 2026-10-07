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
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated journals and controlled native snapshots; no real Provider or service access. */
@RunWith(AndroidJUnit4::class)
class TitleUpdateAttemptRetentionCoordinatorDeviceTest {
    private class JournalPort(private val source: TitleUpdateAttemptStore) : TitleUpdateAttemptJournal {
        var remembers = 0
        var legacyRemembers = 0
        var claims = 0
        var duringCommit: () -> Unit = {}
        override suspend fun remember(record: TitleUpdateAttemptRecord): Unit {
            legacyRemembers++; error("Unrestricted retention must not be used")
        }
        override suspend fun remember(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            remembers++
            var checks = 0
            return source.remember(record) { scope ->
                if (++checks == 2) {
                    assertTrue(source.writableDatabase.inTransaction())
                    duringCommit()
                }
                admit(scope)
            }
        }
        override suspend fun read(scope: CalendarScope, id: MutationId) = source.read(scope, id)
        override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean { claims++; error("No dispatch claim") }
        override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String): Unit =
            error("No submission evidence")
    }
    private class LegacyJournal(private val source: TitleUpdateAttemptStore) : TitleUpdateAttemptJournal {
        var unguardedCalls = 0
        override suspend fun remember(record: TitleUpdateAttemptRecord): Unit { unguardedCalls++; error("No legacy fallback") }
        override suspend fun read(scope: CalendarScope, id: MutationId) = source.read(scope, id)
        override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean = error("No dispatch claim")
        override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String): Unit = error("No evidence")
    }
    private class Fixture(allDay: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val importName = "title-retain-owner-$token.db"
        val journalName = "title-retain-attempt-$token.db"
        val target = CalendarScope(AccountId("title-retain-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("fixture-import", 1)
        val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), EventContent(
            "Synthetic original", if (allDay) EventTime.AllDay(LocalDate.of(2030, 10, 27), LocalDate.of(2030, 10, 28)) else
                EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            "Synthetic description", "Synthetic location", details = EventDetails()))
        val intent = LocalEditIntent(MutationId("fixture-local-intent"), target, format, base,
            base.content.copy(title = "Synthetic prepared title"))
        val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-07T12:00:00Z"),
            AdapterFormat("fixture-preparation", 1), "synthetic-protected-capsule")
        val record = TitleUpdateAttemptRecord(MutationId("fixture-attempt"), preparation,
            AdapterFormat("fixture-wire", 1), "synthetic-protected-wire")
        val store = ImportStore(context, importName)
        val journal = TitleUpdateAttemptStore(context, journalName)
        val guarded = JournalPort(journal)
        var account: AccountId? = target.account
        var permission = true
        var mode = true
        var nativeContent = intent.content
        var nativeAbsent = false
        var captures = 0
        var nativeWrites = 0
        var networkCalls = 0
        var wireFormat: AdapterFormat? = record.format
        var duringCapture: suspend () -> Unit = {}
        var duringAccount: suspend () -> Unit = {}
        val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { false }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("fixture-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar {
                nativeWrites++; error("No native calendar mutation")
            }
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) { nativeWrites++; error("No native projection") }
            override suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                nativeWrites++; error("Retention cannot acknowledge native content")
            }
            override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
                check(admitOperation()); assertEquals(target, scope); assertEquals(this@Fixture.base, base)
                captures++; duringCapture()
                return if (nativeAbsent) null else OwnedLocalEditSnapshot(1, 1, nativeContent)
            }
        }
        val port = object : CalendarTitleUpdateSubmissionPort {
            override val titleUpdateFormat get() = wireFormat
            override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord {
                networkCalls++; error("Retention cannot prepare wire data")
            }
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation {
                networkCalls++; error("Retention cannot submit")
            }
            override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation {
                networkCalls++; error("Retention cannot inspect the service")
            }
            override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                networkCalls++; error("Retention cannot inspect the service")
            }
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId? { duringAccount(); return account }
            override suspend fun fetch(request: PageRequest): RemotePage { networkCalls++; error("No download") }
            override suspend fun dispatch(mutation: Mutation): DispatchResult { networkCalls++; error("No dispatch") }
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
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic attempt fixture")))
            store.setEnabled(target, true)
            store.transaction(target) { putRemote(base); putProjection(ProjectionIntent(target, base)); checkpoint = PageCheckpoint(target, format) }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            assertEquals(intent, store.captureLocalEdit(intent)); assertTrue(store.retainLocalEditPreparation(preparation))
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
        }
        suspend fun retain(candidate: TitleUpdateAttemptRecord = record, targetJournal: TitleUpdateAttemptJournal = guarded) =
            coordinator.retainLocalTitleAttempt(candidate, targetJournal, port)
        suspend fun owner(): LocalEditIntent? { store.admitTransaction = { true }; return store.localEdit(target, base.eventId) }
        fun assertNoWritesOrNetwork() {
            assertEquals(0, nativeWrites); assertEquals(0, networkCalls); assertEquals(0, guarded.claims); assertEquals(0, guarded.legacyRemembers)
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            journal.close(); store.close(); context.deleteDatabase(importName); context.deleteDatabase(journalName)
        }
    }
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(allDay)
        try { fixture.seed(); fixture.block(); fixture.assertNoWritesOrNetwork() } finally { fixture.close() }
    }

    @Test fun exactPreparedTimedAndAllDayAttemptsAreRetainedAndReopenWithoutSending() {
        for (allDay in listOf(false, true)) fixture(allDay) {
            assertEquals(LocalTitleAttemptRetentionResult.RETAINED, retain())
            assertEquals(1, guarded.remembers); assertEquals(record, journal.read(target, record.id))
            TitleUpdateAttemptStore(context, journalName).use { reopened -> assertEquals(record, reopened.read(target, record.id)) }
            assertEquals(intent, owner()); assertEquals(preparation, store.localEditPreparation(target, base.eventId))
        }
    }

    @Test fun exactPreparedReplayIsHistoricalAndNeverReplacesWireData() = fixture {
        assertEquals(LocalTitleAttemptRetentionResult.RETAINED, retain())
        val initialRemembers = guarded.remembers
        journal.writableDatabase.execSQL("CREATE TRIGGER reject_replay_replacement BEFORE UPDATE ON title_update_attempts BEGIN SELECT RAISE(ABORT,'No replacement'); END")
        journal.writableDatabase.execSQL("CREATE TRIGGER reject_replay_insertion BEFORE INSERT ON title_update_attempts BEGIN SELECT RAISE(ABORT,'No insertion'); END")
        assertEquals(LocalTitleAttemptRetentionResult.HISTORICAL, retain())
        assertEquals(initialRemembers + 1, guarded.remembers)
        assertEquals(LocalTitleAttemptRetentionResult.UNQUALIFIED, retain(record.copy(protectedPayload = "different-protected-wire")))
        assertEquals(record, journal.read(target, record.id)); assertEquals(intent, owner())
    }

    @Test fun existingUnknownIsUnresolvedBeforeGuardedRememberEvenForSubstitutedCandidate() = fixture {
        journal.remember(record); journal.admitDispatch = { it == target }; assertTrue(journal.claim(record))
        journal.admitDispatch = { false }
        assertEquals(LocalTitleAttemptRetentionResult.UNRESOLVED, retain())
        assertEquals(LocalTitleAttemptRetentionResult.UNRESOLVED, retain(record.copy(protectedPayload = "different-wire")))
        assertEquals(0, guarded.remembers); assertEquals(record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), journal.read(target, record.id))
        assertEquals(intent, owner())
    }

    @Test fun anotherUnknownAttemptForTheEventCannotBeReplacedByANewIdentity() = fixture {
        val previous = record.copy(id = MutationId("previous-attempt"))
        journal.remember(previous); journal.admitDispatch = { it == target }; assertTrue(journal.claim(previous))
        journal.admitDispatch = { false }
        assertEquals(LocalTitleAttemptRetentionResult.UNRESOLVED, retain())
        assertNull(journal.read(target, record.id))
        assertEquals(previous.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), journal.read(target, previous.id))
        assertEquals(intent, owner())
    }

    @Test fun candidateOwnerCapsuleAndWireBindingSubstitutionsAreUnqualified() = fixture {
        val candidates = listOf(
            record.copy(preparation = preparation.copy(protectedPayload = "different-capsule")),
            record.copy(preparation = preparation.copy(timestamp = preparation.timestamp.plusSeconds(1))),
            record.copy(preparation = preparation.copy(intent = intent.copy(id = MutationId("different-owner")))),
            record.copy(preparation = preparation.copy(intent = intent.copy(content = intent.content.copy(title = "Different candidate title")))),
            record.copy(preparation = preparation.copy(intent = intent.copy(base = base.copy(revision = Revision("different-base"))))),
            record.copy(format = AdapterFormat("different-wire", 1)),
            record.copy(protectedEvidence = "unexpected-response-evidence"),
        )
        for (candidate in candidates) assertEquals(LocalTitleAttemptRetentionResult.UNQUALIFIED, retain(candidate))
        assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id)); assertEquals(intent, owner())
    }

    @Test fun incompatibleCheckpointCannotBindAHistoricalCapsule() = fixture {
        store.admitTransaction = { true }
        store.transaction(target) { checkpoint = PageCheckpoint(target, AdapterFormat("different-import", 2)) }
        assertEquals(LocalTitleAttemptRetentionResult.UNQUALIFIED, retain())
        assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id))
    }

    @Test fun missingOwnerOrCapsuleCannotBeRecreatedByRetainingAnAttempt() {
        for (table in listOf("local_edits", "local_edit_preparations")) fixture {
            store.writableDatabase.execSQL("DELETE FROM $table")
            assertEquals(LocalTitleAttemptRetentionResult.UNQUALIFIED, retain())
            assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id))
        }
    }

    @Test fun conflictedOwnerRemainsConflictedAndDoesNotAcquireAnAttempt() = fixture {
        store.writableDatabase.execSQL("UPDATE local_edits SET state='CONFLICT'")
        assertEquals(LocalTitleAttemptRetentionResult.CONFLICT, retain())
        assertEquals(LocalEditState.CONFLICT, owner()!!.state); assertNull(journal.read(target, record.id)); assertEquals(0, guarded.remembers)
    }

    @Test fun laterNativeContentAndMissingOrDeletedNativeRowRemainUnresolved() {
        for (absent in listOf(false, true)) fixture {
            nativeAbsent = absent
            if (!absent) nativeContent = intent.content.copy(title = "Synthetic later system edit")
            assertEquals(LocalTitleAttemptRetentionResult.UNRESOLVED, retain())
            assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id)); assertEquals(intent, owner())
        }
    }

    @Test fun permissionModeAccountAndSelectionGateBeforeNativeCapture() = fixture {
        permission = false; assertEquals(LocalTitleAttemptRetentionResult.INACTIVE, retain()); permission = true
        mode = false; assertEquals(LocalTitleAttemptRetentionResult.INACTIVE, retain()); mode = true
        account = AccountId("foreign-account"); assertEquals(LocalTitleAttemptRetentionResult.INACTIVE, retain()); account = target.account
        store.setEnabled(target, false); assertEquals(LocalTitleAttemptRetentionResult.INACTIVE, retain())
        assertEquals(0, captures); assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id))
    }

    @Test fun revocationDuringCaptureInvalidatesEpochEvenWhenPermissionOrModeReturns() {
        for (revocation in 0..3) fixture {
            duringCapture = {
                when (revocation) {
                    0 -> { permission = false; coordinator.permissionsChanged(); permission = true; coordinator.permissionsChanged() }
                    1 -> { coordinator.systemCreatePolicyChanged(target, false); coordinator.systemCreatePolicyChanged(target, true) }
                    2 -> account = AccountId("foreign-account")
                    else -> store.setEnabled(target, false)
                }
            }
            assertEquals(LocalTitleAttemptRetentionResult.INACTIVE, retain())
            assertEquals(1, captures); assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id))
        }
    }

    @Test fun cancellationDuringCapturePropagatesWithoutRememberingOrMutatingOwner() = fixture {
        duringCapture = { throw CancellationException("Synthetic capture cancellation") }
        try { retain(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id)); assertEquals(intent, owner())
    }

    @Test fun cancelledAccountLookupReturningNullMustThrowBeforeAnyNormalRetentionResult() = fixture {
        val normalReturns = AtomicInteger()
        val accountLookups = AtomicInteger()
        duringAccount = {
            accountLookups.incrementAndGet()
            currentCoroutineContext().cancel(CancellationException("Synthetic account lookup cancellation"))
            account = null
        }
        try {
            supervisorScope {
                val request = async {
                    retain()
                    // Await also throws for a cancelled Deferred whose body returned normally;
                    // this sentinel distinguishes that false positive from API propagation.
                    normalReturns.incrementAndGet()
                }
                try { request.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertEquals(1, accountLookups.get())
            assertEquals("The retention API must not return a status after cancellation", 0, normalReturns.get())
            assertEquals(0, captures); assertEquals(0, guarded.remembers)
            assertNull(journal.read(target, record.id)); assertEquals(intent, owner())
        } finally {
            duringAccount = {}; account = target.account
        }
    }

    @Test fun absentAdapterCapabilityAndLegacyJournalNeverFallBackToUnrestrictedRemember() {
        fixture {
            wireFormat = null
            assertEquals(LocalTitleAttemptRetentionResult.UNSUPPORTED, retain())
            assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id))
        }
        fixture {
            val legacy = LegacyJournal(journal)
            assertEquals(LocalTitleAttemptRetentionResult.UNRESOLVED, retain(targetJournal = legacy))
            assertEquals(0, legacy.unguardedCalls); assertNull(journal.read(target, record.id))
        }
    }

    @Test fun independentlyChangedOwnerDuringCommitPreventsQualificationDespiteRetainedWire() = fixture {
        ImportStore(context, importName).use { concurrent ->
            concurrent.writableDatabase
            guarded.duringCommit = {
                concurrent.writableDatabase.execSQL("UPDATE local_edits SET state='CONFLICT' WHERE mutation=?", arrayOf(intent.id.value))
            }
            assertEquals(LocalTitleAttemptRetentionResult.CONFLICT, retain())
        }
        assertEquals(1, guarded.remembers)
        assertEquals(record, journal.read(target, record.id))
        assertEquals(LocalEditState.CONFLICT, owner()!!.state)
        assertEquals(LocalTitleAttemptRetentionResult.CONFLICT, retain())
        assertEquals(record, journal.read(target, record.id))
    }
}
