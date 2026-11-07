// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual reopened SQLite/coordinator; controlled native ports, no HTTP or system UI. */
class PersonalAcknowledgementRecoveryCoordinatorDeviceTest {
    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        private val name = "personal-ack-recovery-$token.db"
        private val journalName = "personal-ack-recovery-journal-$token.db"
        val scope = CalendarScope(AccountId("personal-ack-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("synthetic-personal-ack", 2)
        val base = RemoteChange.Upsert(EventId("event"), Revision("base"), EventContent("Before",
            EventTime.Timed(Instant.parse("2030-05-01T09:00:00Z"), Instant.parse("2030-05-01T10:00:00Z"), ZoneOffset.UTC),
            details = EventDetails()))
        val intent = LocalEditIntent(MutationId("local"), scope, format, base, base.content.copy(title = "After",
            description = "Changed", time = EventTime.AllDay(LocalDate.of(2030, 5, 2), LocalDate.of(2030, 5, 5))))
        val preparation = PersonalEditPreparation(intent, Instant.parse("2030-05-01T00:00:00Z"), format, "synthetic-personal-capsule")
        val prepared = PersonalUpdateAttemptRecord(MutationId("attempt"), preparation, format, "synthetic-personal-wire")
        val unknown = prepared.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        val result = PersonalUpdateVerifiedResult.of(unknown, PersonalUpdateSubmissionObservation.Verified(unknown,
            base.copy(revision = Revision("verified"), content = intent.content)))
        var store = ImportStore(context, name)
        val shared = TitleUpdateAttemptStore(context, journalName)
        val journal = shared.personalJournal
        var permission = true
        var general = true
        var nativeContent = intent.content
        var applied = false
        var recoveryProtocol: Int? = 1
        var resumeCalls = 0
        var inspectCalls = 0
        var nativeWrites = 0
        val networkSchedules = AtomicInteger()
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { true }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("synthetic-personal-ack", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("No native import")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No native import")
            override val personalUpdateAcknowledgementProtocol get() = 1
            override val personalUpdateAcknowledgementRecoveryProtocol get() = recoveryProtocol
            override suspend fun captureOwnedPersonalEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot {
                check(admitOperation()); assertEquals(this@Fixture.scope, scope); assertEquals(this@Fixture.base, base)
                return OwnedLocalEditSnapshot(7, 51, nativeContent)
            }
            override suspend fun acknowledgePersonalUpdate(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? =
                error("The reopened receipt must not use the fresh-only path")
            override suspend fun inspectPersonalUpdateAcknowledgement(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? {
                check(admitOperation()); inspectCalls++
                return if (applied) AppliedPersonalUpdateAcknowledgement(receipt) else null
            }
            override suspend fun resumePersonalUpdateAcknowledgement(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? {
                check(admitOperation()); resumeCalls++
                if (!applied && nativeContent != intent.content) return null
                if (!applied) { applied = true; nativeWrites++ }
                return AppliedPersonalUpdateAcknowledgement(receipt)
            }
        }
        val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = scope.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No HTTP during ACK recovery")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No send during ACK recovery")
        }
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { networkSchedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        lateinit var coordinator: ImportCoordinator
        lateinit var reservation: PersonalUpdateAcknowledgementReservation
        suspend fun seed() {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic ACK recovery")))
            store.setEnabled(scope, true)
            store.transaction(scope) { checkpoint = PageCheckpoint(scope, format); putRemote(base); putProjection(ProjectionIntent(scope, base)) }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
            assertEquals(intent, store.captureLocalEdit(intent))
            assertEquals(PersonalPreparationAdmission.STORED, store.retainCurrentPersonalPreparation(preparation))
            assertTrue(journal.remember(prepared) { true }); assertTrue(journal.claim(prepared) { true })
            assertTrue(journal.retainVerified(result) { true })
            reservation = checkNotNull(store.preparePersonalUpdateAcknowledgement(result))
            assertTrue(reservation.fresh)
            store.close(); store = ImportStore(context, name)
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler,
                systemCreatesEnabled = { general }, systemTitlesEnabled = { false },
                systemTextsEnabled = { false }, systemTimesEnabled = { false })
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (networkSchedules.get() == 0) delay(10) }
        }
        suspend fun recover() = coordinator.personalOwners.acknowledge(result, journal, coordinator.systemPersonalProofAdmission(scope))
        suspend fun close() {
            if (::coordinator.isInitialized) coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); shared.close(); context.deleteDatabase(name); context.deleteDatabase(journalName)
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture()
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    @Test fun reopenedPendingBeforeNativeWriteFinishesWithOriginalNonceAndNoTransport() = fixture {
        assertTrue(recover()); assertEquals(1, resumeCalls); assertEquals(1, nativeWrites)
        store.admitTransaction = { true }
        val ack = checkNotNull(store.personalUpdateAcknowledgement(scope, base.eventId, prepared.id))
        assertEquals(reservation.record.receipt, ack.receipt); assertEquals(PersonalUpdateAcknowledgementState.RESTORED, ack.state)
        assertTrue(coordinator.personalOwners.complete(result, journal) { true })
        assertTrue(recover()); assertEquals(1, nativeWrites)
        assertEquals(unknown, journal.read(scope, prepared.id) { true }); assertEquals(result, journal.readVerified(scope, prepared.id) { true })
    }
    @Test fun aChangedNativeOwnerRetainsPendingReceiptAndNeverWrites() = fixture {
        nativeContent = nativeContent.copy(title = "Later local edit")
        assertFalse(recover()); assertEquals(0, resumeCalls); assertEquals(0, nativeWrites)
        store.admitTransaction = { true }
        assertEquals(reservation.record, store.personalUpdateAcknowledgement(scope, base.eventId, prepared.id))
        assertEquals(intent, store.localEdit(scope, base.eventId))
    }
    @Test fun historicalMarkerRecoversAfterNativeWriteWithoutConsumingANewerEdit() = fixture {
        applied = true; nativeContent = nativeContent.copy(title = "Newer local edit")
        assertTrue(recover()); assertEquals(1, inspectCalls); assertEquals(0, resumeCalls); assertEquals(0, nativeWrites)
        assertEquals("Newer local edit", nativeContent.title)
    }
    @Test fun legacyOrUnknownRecoveryCapabilityRemainsInspectionOnly() = fixture {
        for (value in listOf(null, 0, 2)) {
            recoveryProtocol = value; assertFalse(recover())
            assertEquals(0, resumeCalls); assertEquals(0, nativeWrites)
        }
    }
}
