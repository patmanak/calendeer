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

/** Real capture/preparation journals with controlled read-only ports; no Proton compatibility claim. */
@RunWith(AndroidJUnit4::class)
class LocalCreatePreparationCoordinatorDeviceTest {
    @Test fun capturedCreatePreparationUsesItsFrozenDraftAndRetainsTheProtectedPayload() = fixture {
        assertEquals(LocalCreatePreparationResult.RETAINED, prepare())
        assertEquals(LocalCreatePreparation(intent, preparationFormat, payload), retainedPreparation())
        assertEquals(1, calls); assertTrue(inspections >= 2)
        journal.admitCapture = { it == target }; assertEquals(intent, journal.readIntent(target, localId))
    }

    @Test fun currentCachedPreparationNeverCallsThePortOrReplacesTheFirstPayload() = fixture {
        assertEquals(LocalCreatePreparationResult.RETAINED, prepare())
        onPrepare = { error("Committed preparation must not be regenerated") }
        assertEquals(LocalCreatePreparationResult.RETAINED, prepare())
        assertEquals(1, calls); assertTrue(inspections >= 3)
        assertEquals(LocalCreatePreparation(intent, preparationFormat, payload), retainedPreparation())
    }

    @Test fun cachedPreparationStillRefusesChangedOrMissingProviderContent() = fixture {
        assertEquals(LocalCreatePreparationResult.RETAINED, prepare())
        snapshot = snapshot!!.copy(content = content.copy(title = "Preparation later local edit"))
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare())
        snapshot = null
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare())
        assertEquals(1, calls)
        assertEquals(LocalCreatePreparation(intent, preparationFormat, payload), retainedPreparation())
    }

    @Test fun contentChangedDuringPreparationAndAForeignResponseCannotCommit() = fixture {
        onPrepare = { snapshot = snapshot!!.copy(content = content.copy(title = "Preparation concurrent edit")); receipt() }
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare()); assertNull(retainedPreparation())
        snapshot = OwnedLocalEditSnapshot(3, 7, content)
        onPrepare = { PreparedCalendarCreate(target.copy(calendar = CalendarId("foreign")), preparationFormat, payload) }
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare()); assertNull(retainedPreparation())
        onPrepare = { PreparedCalendarCreate(target, AdapterFormat("foreign-preparation-format", 1), payload) }
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare()); assertNull(retainedPreparation())
    }

    @Test fun sessionInvalidationWhileTheReadOnlyPortRunsPreventsCommit() = fixture {
        onPrepare = { coordinator.authenticationChanged(false); receipt() }
        assertEquals(LocalCreatePreparationResult.INACTIVE, prepare())
        assertNull(retainedPreparation())
    }

    @Test fun accountChangedDuringPreparationCannotCommit() = fixture {
        onPrepare = { account = AccountId("preparation-replacement-account"); receipt() }
        assertEquals(LocalCreatePreparationResult.INACTIVE, prepare())
        assertNull(retainedPreparation())
    }

    @Test fun calendarDeselectedDuringPreparationCannotCommit() = fixture {
        onPrepare = { store.setEnabled(target, false); receipt() }
        assertEquals(LocalCreatePreparationResult.INACTIVE, prepare())
        assertNull(retainedPreparation())
    }

    @Test fun checkpointFormatChangedDuringPreparationCannotCommit() = fixture {
        onPrepare = {
            store.transaction(target) { checkpoint = PageCheckpoint(target, AdapterFormat("replacement-import", 2)) }
            receipt()
        }
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare())
        assertNull(retainedPreparation())
    }

    @Test fun declaredAdapterFormatChangedDuringPreparationCannotCommit() = fixture {
        onPrepare = { declaredPreparationFormat = preparationFormat.copy(version = 2); receipt() }
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare())
        assertNull(retainedPreparation())
    }

    @Test fun permissionsAccountAndDeselectionGatePreparationBeforeProviderAccess() = fixture {
        permission = false; assertEquals(LocalCreatePreparationResult.INACTIVE, prepare())
        permission = true; account = AccountId("preparation-foreign-account")
        assertEquals(LocalCreatePreparationResult.INACTIVE, prepare())
        account = target.account; store.setEnabled(target, false)
        assertEquals(LocalCreatePreparationResult.INACTIVE, prepare())
        assertEquals(0, calls); assertEquals(0, inspections); assertNull(retainedPreparation())
    }

    @Test fun identityFormatAndAnUncapturedReservationCannotBePrepared() = fixture {
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare(MutationId("foreign-mutation")))
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED,
            coordinator.prepareLocalCreate(target, LocalEventId(UUID.randomUUID().toString()), id, port))
        // The owner closes its lease on return. Admit only this fixture's deliberate state change.
        store.admitTransaction = { it == target }
        store.transaction(target) { checkpoint = PageCheckpoint(target, AdapterFormat("foreign-format", 1)) }
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare())
        assertEquals(0, calls); assertEquals(0, inspections); assertNull(retainedPreparation())
    }

    @Test fun unsupportedAndNetworkFailuresRemainRetryableReadOnlyPreparation() = fixture {
        onPrepare = { throw CreatePreparationException(CreatePreparationFailure.UNSUPPORTED) }
        assertEquals(LocalCreatePreparationResult.UNSUPPORTED, prepare()); assertNull(retainedPreparation())
        onPrepare = { throw CreatePreparationException(CreatePreparationFailure.NETWORK) }
        assertEquals(LocalCreatePreparationResult.UNAVAILABLE, prepare()); assertNull(retainedPreparation())
        onPrepare = { receipt() }
        assertEquals(LocalCreatePreparationResult.RETAINED, prepare()); assertEquals(3, calls)
    }

    @Test fun cancellationNeverCommitsAPreparationOrConsumesTheCapturedIntent() = fixture {
        onPrepare = { throw CancellationException("fixture interrupted preparation") }
        var cancelled = false
        try { prepare() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertNull(retainedPreparation())
        journal.admitCapture = { it == target }; assertEquals(intent, journal.readIntent(target, localId))
        onPrepare = { receipt() }
        assertEquals(LocalCreatePreparationResult.RETAINED, prepare())
    }

    @Test fun missingMarkerAndPermissionsLostDuringPreparationCannotCommit() = fixture {
        snapshot = null
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare()); assertEquals(0, calls)
        snapshot = OwnedLocalEditSnapshot(3, 7, content)
        onPrepare = { permission = false; receipt() }
        assertEquals(LocalCreatePreparationResult.INACTIVE, prepare()); assertNull(retainedPreparation())
    }

    @Test fun undeclaredCapabilityAndAChangedAdapterFormatCannotReusePreparation() = fixture {
        declaredPreparationFormat = null
        assertEquals(LocalCreatePreparationResult.UNSUPPORTED, prepare())
        assertEquals(0, calls); assertNull(retainedPreparation())
        declaredPreparationFormat = preparationFormat
        assertEquals(LocalCreatePreparationResult.RETAINED, prepare())
        declaredPreparationFormat = preparationFormat.copy(version = 2)
        assertEquals(LocalCreatePreparationResult.UNQUALIFIED, prepare())
        assertEquals(1, calls)
        assertEquals(LocalCreatePreparation(intent, preparationFormat, payload), retainedPreparation())
    }

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        private val importName = "preparation-owner-import-$token.db"
        private val journalName = "preparation-owner-capture-$token.db"
        val target = CalendarScope(AccountId("preparation-owner-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("preparation-owner-import", 1)
        val preparationFormat = AdapterFormat("controlled-paused-preparation", 1)
        var declaredPreparationFormat: AdapterFormat? = preparationFormat
        val localId = LocalEventId(UUID.randomUUID().toString())
        val id = MutationId("create")
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
        var snapshot: OwnedLocalEditSnapshot? = OwnedLocalEditSnapshot(3, 7, content)
        var onPrepare: suspend (CalendarCreateDraft) -> PreparedCalendarCreate = { receipt() }
        fun receipt() = PreparedCalendarCreate(target, preparationFormat, payload)
        val port = object : CalendarCreatePreparationPort {
            override val localCreateFormat: AdapterFormat? get() = declaredPreparationFormat
            override suspend fun prepareCreate(draft: CalendarCreateDraft): PreparedCalendarCreate {
                assertEquals(CalendarCreateDraft(target, id, timestamp, content), draft)
                calls++; return onPrepare(draft)
            }
        }
        private val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { true }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("preparation-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("Preparation must not import")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("Preparation must not mutate Provider")
            override suspend fun inspectStampedSimpleCreate(scope: CalendarScope, localId: LocalEventId): OwnedLocalEditSnapshot? {
                assertEquals(target, scope); assertEquals(this@Fixture.localId, localId)
                check(admitOperation()); inspections++; return snapshot
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
        suspend fun prepare(mutation: MutationId = id) = coordinator.prepareLocalCreate(target, localId, mutation, port)
        suspend fun retainedPreparation(): LocalCreatePreparation? {
            journal.admitCapture = { it == target }; return journal.readPreparation(target, localId)
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
