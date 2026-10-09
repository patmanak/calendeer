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
class TitleUpdatePreparationCoordinatorDeviceTest {
    private class NamedJournal(private val source: TitleUpdateAttemptStore) : TitleUpdatePreparationJournal {
        var reserves = 0
        var remembers = 0
        var reads = 0
        var legacyRemembers = 0
        var claims = 0
        var afterReserve: suspend () -> Unit = {}
        var duringCommit: () -> Unit = {}
        var afterRemember: suspend () -> Unit = {}
        var afterRead: suspend (TitleUpdateAttemptRecord?) -> Unit = {}
        override suspend fun reservePreparation(candidate: TitleUpdatePreparationReservation,
            admit: (CalendarScope) -> Boolean): TitleUpdatePreparationReservation? {
            reserves++
            val result = source.reservePreparation(candidate, admit)
            afterReserve()
            return result
        }
        override suspend fun readPreparationReservation(scope: CalendarScope, eventId: EventId) =
            source.readPreparationReservation(scope, eventId)
        override suspend fun remember(record: TitleUpdateAttemptRecord): Unit {
            legacyRemembers++; error("No unrestricted retention")
        }
        override suspend fun remember(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            remembers++
            var checks = 0
            val result = source.remember(record) { scope ->
                if (++checks == 2) {
                    assertTrue(source.writableDatabase.inTransaction())
                    duringCommit()
                }
                admit(scope)
            }
            afterRemember()
            return result
        }
        override suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? {
            reads++
            val result = source.read(scope, id)
            afterRead(result)
            return result
        }
        override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean { claims++; error("No dispatch claim") }
        override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String): Unit =
            error("No submission evidence")
    }
    private class Fixture(allDay: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val importName = "title-prepare-owner-$token.db"
        val journalName = "title-prepare-attempt-$token.db"
        val target = CalendarScope(AccountId("title-prepare-$token"), CalendarId("fixture"), 0)
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
        val guarded = NamedJournal(journal)
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
        val candidate get() = TitleUpdatePreparationReservation.of(record)
        var guardedPrepares = 0
        var duringPreparation: suspend () -> Unit = {}
        var returnNull = false
        val port = object : CalendarTitleUpdateSubmissionPort {
            override val titleUpdateFormat get() = wireFormat
            override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
                admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
                guardedPrepares++
                assertTrue(admit(target))
                assertFalse(admit(target.copy(generation = target.generation + 1)))
                duringPreparation()
                return if (returnNull) null else TitleUpdateAttemptRecord(reservation.id,
                    reservation.preparation, reservation.format, record.protectedPayload)
            }
            override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord {
                networkCalls++; error("No legacy preparation")
            }
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation {
                networkCalls++; error("Preparation cannot submit")
            }
            override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation {
                networkCalls++; error("Preparation cannot inspect the service")
            }
            override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                networkCalls++; error("Preparation cannot inspect the service")
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
        suspend fun prepare(proposed: TitleUpdatePreparationReservation = candidate,
            targetJournal: TitleUpdatePreparationJournal = guarded,
            targetPort: CalendarTitleUpdateSubmissionPort = port, allowNetwork: Boolean = false) =
            coordinator.prepareLocalTitleAttempt(proposed, targetJournal, targetPort, allowNetwork)
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
    @Test fun timedAndAllDayPreparationUsesCanonicalReservationAndReopensOffline() {
        for (allDay in listOf(false, true)) fixture(allDay) {
            val canonical = candidate.copy(id = MutationId("canonical-first-attempt"))
            assertEquals(canonical, journal.reservePreparation(canonical) { true })
            val expected = record.copy(id = canonical.id)
            assertEquals(LocalTitleAttemptPreparation.Prepared(expected), prepare(allowNetwork = true))
            assertEquals(1, guardedPrepares); assertEquals(1, guarded.remembers)
            assertEquals(expected, journal.read(target, canonical.id)); assertNull(journal.read(target, record.id))
            journal.close()
            TitleUpdateAttemptStore(context, journalName).use { reopened ->
                val wrapped = NamedJournal(reopened)
                assertEquals(LocalTitleAttemptPreparation.Prepared(expected), prepare(targetJournal = wrapped))
                assertEquals(0, wrapped.remembers)
                assertEquals(canonical, reopened.readPreparationReservation(target, base.eventId))
            }
            assertEquals(1, guardedPrepares)
            assertEquals(intent, owner()); assertEquals(preparation, store.localEditPreparation(target, base.eventId))
        }
    }

    @Test fun offlineBareReservationNeverCallsGuardedPreparation() = fixture {
        assertEquals(LocalTitleAttemptPreparation.Unresolved, prepare())
        assertEquals(candidate, journal.readPreparationReservation(target, base.eventId))
        assertNull(journal.read(target, record.id)); assertEquals(0, guardedPrepares); assertEquals(0, guarded.remembers)
    }

    @Test fun substitutedOwnerCapsuleTimestampBaseOrFormatCannotReserveOrPrepare() = fixture {
        val substitutions = listOf(
            candidate.copy(preparation = preparation.copy(protectedPayload = "different-capsule")),
            candidate.copy(preparation = preparation.copy(timestamp = preparation.timestamp.plusSeconds(1))),
            candidate.copy(preparation = preparation.copy(intent = intent.copy(id = MutationId("different-owner")))),
            candidate.copy(preparation = preparation.copy(intent = intent.copy(content = intent.content.copy(title = "Different title")))),
            candidate.copy(preparation = preparation.copy(intent = intent.copy(base = base.copy(revision = Revision("different-base"))))),
            candidate.copy(format = AdapterFormat("different-wire", 1)),
        )
        for (substitution in substitutions)
            assertEquals(LocalTitleAttemptPreparation.Unqualified, prepare(substitution, allowNetwork = true))
        assertEquals(0, guarded.reserves); assertEquals(0, guardedPrepares)
        assertNull(journal.read(target, record.id)); assertEquals(intent, owner())
    }

    @Test fun missingSqlOwnerOrCapsuleCannotBeRecreatedByPreparation() {
        for (table in listOf("local_edits", "local_edit_preparations")) fixture {
            store.writableDatabase.execSQL("DELETE FROM $table")
            assertEquals(LocalTitleAttemptPreparation.Unqualified, prepare(allowNetwork = true))
            assertEquals(0, guarded.reserves); assertEquals(0, guardedPrepares)
            assertNull(journal.readPreparationReservation(target, base.eventId))
        }
    }

    @Test fun incompatibleCheckpointAndConflictedOwnerDenyBeforeReservation() {
        fixture {
            store.admitTransaction = { true }
            store.transaction(target) { checkpoint = PageCheckpoint(target, AdapterFormat("different-import", 2)) }
            assertEquals(LocalTitleAttemptPreparation.Unqualified, prepare(allowNetwork = true))
            assertEquals(0, guarded.reserves); assertEquals(0, guardedPrepares)
        }
        fixture {
            store.writableDatabase.execSQL("UPDATE local_edits SET state='CONFLICT'")
            assertEquals(LocalTitleAttemptPreparation.Conflict, prepare(allowNetwork = true))
            assertEquals(LocalEditState.CONFLICT, owner()!!.state)
            assertEquals(0, guarded.reserves); assertEquals(0, guardedPrepares)
        }
    }

    @Test fun laterNativeTitleOrDeletedNativeRowDenyBeforeReservation() {
        for (absent in listOf(false, true)) fixture {
            nativeAbsent = absent
            if (!absent) nativeContent = intent.content.copy(title = "Synthetic later edit")
            assertEquals(LocalTitleAttemptPreparation.Unresolved, prepare(allowNetwork = true))
            assertEquals(0, guarded.reserves); assertEquals(0, guardedPrepares); assertEquals(0, guarded.remembers)
            assertEquals(intent, owner())
        }
    }

    @Test fun permissionModeAccountAndSelectionDenyBeforeNativeCapture() = fixture {
        permission = false
        assertEquals(LocalTitleAttemptPreparation.Inactive, prepare(allowNetwork = true))
        permission = true; mode = false
        assertEquals(LocalTitleAttemptPreparation.Inactive, prepare(allowNetwork = true))
        mode = true; account = AccountId("foreign-account")
        assertEquals(LocalTitleAttemptPreparation.Inactive, prepare(allowNetwork = true))
        account = target.account; store.setEnabled(target, false)
        assertEquals(LocalTitleAttemptPreparation.Inactive, prepare(allowNetwork = true))
        assertEquals(0, captures); assertEquals(0, guarded.reserves); assertEquals(0, guardedPrepares)
    }

    @Test fun epochRevocationDuringPreparationCannotBeRestoredByPermissionOrModeReenable() {
        for (revocation in 0..3) fixture {
            duringPreparation = {
                when (revocation) {
                    0 -> {
                        permission = false; coordinator.permissionsChanged()
                        permission = true; coordinator.permissionsChanged()
                    }
                    1 -> {
                        coordinator.systemCreatePolicyChanged(target, false)
                        coordinator.systemCreatePolicyChanged(target, true)
                    }
                    2 -> account = AccountId("foreign-account")
                    3 -> store.setEnabled(target, false)
                }
            }
            assertEquals(LocalTitleAttemptPreparation.Inactive, prepare(allowNetwork = true))
            assertEquals(1, guardedPrepares); assertEquals(0, guarded.remembers)
            assertNull(journal.read(target, record.id))
            assertEquals(candidate, journal.readPreparationReservation(target, base.eventId))
        }
    }

    @Test fun nativeOrSqlChangesDuringPreparationPreventRetainingTheReturnedWire() {
        fixture {
            duringPreparation = { nativeContent = intent.content.copy(title = "Synthetic later edit") }
            assertEquals(LocalTitleAttemptPreparation.Unresolved, prepare(allowNetwork = true))
            assertEquals(1, guardedPrepares); assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id))
        }
        fixture {
            duringPreparation = { store.writableDatabase.execSQL("UPDATE local_edits SET state='CONFLICT'") }
            assertEquals(LocalTitleAttemptPreparation.Conflict, prepare(allowNetwork = true))
            assertEquals(1, guardedPrepares); assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id))
        }
    }

    @Test fun sqlChangeAfterReservationStopsBeforeGuardedNetworkPreparation() = fixture {
        guarded.afterReserve = { store.writableDatabase.execSQL("UPDATE local_edits SET state='CONFLICT'") }
        assertEquals(LocalTitleAttemptPreparation.Conflict, prepare(allowNetwork = true))
        assertEquals(1, guarded.reserves); assertEquals(0, guardedPrepares); assertEquals(0, guarded.remembers)
        assertEquals(candidate, journal.readPreparationReservation(target, base.eventId))
    }

    @Test fun nativeEditAfterCommitRetainsHistoryWithoutReportingQualifiedPreparation() = fixture {
        guarded.afterRemember = { nativeContent = intent.content.copy(title = "Synthetic later edit") }
        assertEquals(LocalTitleAttemptPreparation.Unresolved, prepare(allowNetwork = true))
        assertEquals(1, guarded.remembers); assertEquals(record, journal.read(target, record.id)); assertEquals(intent, owner())
    }

    @Test fun independentlyConflictedSqlOwnerDuringWireCommitPreservesWireButReturnsConflict() = fixture {
        ImportStore(context, importName).use { concurrent ->
            concurrent.writableDatabase
            guarded.duringCommit = { concurrent.writableDatabase.execSQL("UPDATE local_edits SET state='CONFLICT'") }
            assertEquals(LocalTitleAttemptPreparation.Conflict, prepare(allowNetwork = true))
        }
        assertEquals(record, journal.read(target, record.id)); assertEquals(LocalEditState.CONFLICT, owner()!!.state)
        assertEquals(1, guardedPrepares); assertEquals(1, guarded.remembers)
    }

    @Test fun durableReadbackRaceCannotReturnAQualifiedWireWithLaterNativeContent() = fixture {
        journal.remember(record)
        guarded.afterRead = { if (it != null) nativeContent = intent.content.copy(title = "Synthetic later edit") }
        assertEquals(LocalTitleAttemptPreparation.Unresolved, prepare())
        assertEquals(record, journal.read(target, record.id)); assertEquals(0, guardedPrepares); assertEquals(0, guarded.remembers)
    }

    @Test fun unknownAttemptHoldsEventAcrossANewCandidateIdentity() = fixture {
        val previous = record.copy(id = MutationId("previous-unknown"))
        journal.remember(previous); journal.admitDispatch = { it == target }; assertTrue(journal.claim(previous))
        journal.admitDispatch = { false }
        assertEquals(LocalTitleAttemptPreparation.Unresolved, prepare(allowNetwork = true))
        assertEquals(previous.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), journal.read(target, previous.id))
        assertNull(journal.read(target, record.id)); assertEquals(0, guardedPrepares); assertEquals(0, guarded.remembers)
        assertEquals(intent, owner())
    }

    @Test fun missingFormatAndDefaultGuardedCapabilityNeverCallLegacyPreparation() {
        fixture {
            wireFormat = null
            assertEquals(LocalTitleAttemptPreparation.Unsupported, prepare(allowNetwork = true))
            assertEquals(0, guarded.reserves); assertEquals(0, guardedPrepares)
        }
        fixture {
            val legacy = object : CalendarTitleUpdateSubmissionPort {
                override val titleUpdateFormat = record.format
                override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
                    error("Default guarded preparation must not fall back to legacy")
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
                    error("No submit")
                override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("No inspect")
            }
            assertEquals(LocalTitleAttemptPreparation.Unresolved, prepare(targetPort = legacy, allowNetwork = true))
            assertEquals(0, guarded.remembers); assertNull(journal.read(target, record.id))
        }
    }

    @Test fun typedPreparationFailureMapsStatusWithoutMutatingOwnerOrCreatingWire() {
        for (reason in listOf(LocalEditPreparationFailure.NETWORK, LocalEditPreparationFailure.STORAGE,
            LocalEditPreparationFailure.CONFLICT)) fixture {
            duringPreparation = { throw LocalEditPreparationException(reason) }
            val expected = if (reason == LocalEditPreparationFailure.CONFLICT) LocalTitleAttemptPreparation.Conflict
                else LocalTitleAttemptPreparation.Unavailable
            assertEquals(expected, prepare(allowNetwork = true))
            assertEquals(intent, owner()); assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(candidate, journal.readPreparationReservation(target, base.eventId))
            assertNull(journal.read(target, record.id)); assertEquals(0, guarded.remembers)
        }
    }

    @Test fun cancelledPreparationReturningNullMustNotReturnAnOrdinaryStatus() = fixture {
        val normalReturns = AtomicInteger()
        returnNull = true
        duringPreparation = { currentCoroutineContext().cancel(CancellationException("Synthetic cancellation")) }
        supervisorScope {
            val task = async { prepare(allowNetwork = true); normalReturns.incrementAndGet() }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertEquals(1, guardedPrepares); assertEquals(0, normalReturns.get()); assertEquals(0, guarded.remembers)
        assertNull(journal.read(target, record.id)); assertEquals(intent, owner())
    }
}