// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
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

/** Real isolated SQLite and controlled Provider/remote ports. No service writes or editing UI. */
@RunWith(AndroidJUnit4::class)
class LocalEditCaptureCoordinatorDeviceTest {
    private class Fixture(val series: Boolean = false) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        private val name = "capture-coordinator-$token.db"
        val target = CalendarScope(AccountId("capture-fixture-$token"), CalendarId("calendar"), 0)
        val format = AdapterFormat("capture-fixture", 1)
        val id = MutationId("edit")
        val base = RemoteChange.Upsert(EventId("event"), Revision("base"), EventContent("Baseline fixture",
            if (series) EventTime.Timed(Instant.parse("2030-10-01T10:00:00Z"), Instant.parse("2030-10-01T11:00:00Z"), java.time.ZoneId.of("UTC"))
            else EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)),
            recurrence = if (series) EventRecurrence("fixture@calendeer.invalid", "FREQ=DAILY;COUNT=5") else null,
            details = if (series) EventDetails() else null))
        val local = base.content.copy(title = "Local fixture")
        val latest = base.copy(revision = Revision("remote"), content = base.content.copy(title = "Remote fixture"))
        val store = ImportStore(context, name)
        var permission = true
        var systemMode = true
        var currentAccount: AccountId? = target.account
        var reads = 0
        var onRead: suspend () -> Unit = {}
        var snapshot: OwnedLocalEditSnapshot? = OwnedLocalEditSnapshot(7, 51, local)
        private val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { true }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("capture-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar =
                error("No projection during explicit capture")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No projection during explicit capture")
            override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
                assertEquals(target, scope); assertEquals(this@Fixture.base, base)
                check(admitOperation()); reads++; onRead()
                return snapshot
            }
            override suspend fun captureOwnedSeriesEdit(scope: CalendarScope, base: RemoteChange.Upsert) =
                captureOwnedSimpleEdit(scope, base)
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = currentAccount
            override suspend fun fetch(request: PageRequest): RemotePage = error("No downloads during capture")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No uploads during capture")
        }
        private val networkSchedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { networkSchedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            systemCreatesEnabled = { systemMode })
        suspend fun seed() {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Capture fixture")))
            store.setEnabled(target, true)
            cache(base)
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (networkSchedules.get() == 0) delay(10) }
        }
        suspend fun cache(change: RemoteChange) {
            store.transaction(target) {
                putRemote(change); putProjection(ProjectionIntent(target, change))
                checkpoint = PageCheckpoint(target, format, traversalComplete = true)
            }
        }
        suspend fun capture(editId: MutationId = id) = coordinator.captureLocalEdit(target, base.eventId, editId)
        suspend fun captureSeries() = coordinator.captureLocalSeriesEdit(target, base.eventId, id)
        suspend fun observe() = coordinator.captureSystemLocalEdit(target, base.eventId)
        suspend fun retained(): LocalEditIntent? {
            store.admitTransaction = { true }
            return store.localEdit(target, base.eventId)
        }
        suspend fun close() {
            coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); context.deleteDatabase(name)
        }
    }
    private fun fixture(series: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(series)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }

    @Test fun explicitSeriesCaptureRetainsItsProfileAndNeverEntersThePersonalPreparation() = fixture(series = true) {
        assertEquals(LocalEditCaptureResult.RETAINED, captureSeries())
        val retained = retained()!!
        assertEquals(SeriesEditProfile(CreateSeriesRule(5), CreateSeriesRule(5)), retained.seriesProfile)
        assertEquals(local, retained.content); assertEquals(base, retained.base)
        store.admitTransaction = { true }
        assertEquals(PersonalPreparationAdmission.UNQUALIFIED, store.assessPersonalPreparation(retained))
        snapshot = null
        assertEquals(LocalEditCaptureResult.RETAINED, captureSeries()); assertEquals(1, reads)
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture())
    }
    @Test fun materializedSourceBeforeOrDuringNativeReadCannotBecomeACanonicalSeriesEdit() = runBlocking {
        for (duringRead in listOf(false, true)) fixture(series = true) {
            suspend fun materialize() {
                store.admitTransaction = { true }
                cache(base)
                val receipt = store.prepareProjection(ProjectionIntent(target, base), format, ProjectedEventReference.finiteDaily(base))
                assertTrue(store.restoreProjection(AppliedProjectionWitness(receipt)))
            }
            if (duringRead) onRead = { materialize() } else materialize()
            assertEquals(LocalEditCaptureResult.UNAVAILABLE, captureSeries())
            assertEquals(if (duringRead) 1 else 0, reads); assertNull(retained())
            assertEquals(ProjectionRepresentation.FINITE_DAILY_V1, store.projectedReference(target, base.eventId)!!.representation)
        }
    }
    @Test fun seriesCaptureRechecksSelectionAfterTheNativeSuspension() = fixture(series = true) {
        onRead = { store.setEnabled(target, false) }
        assertEquals(LocalEditCaptureResult.INACTIVE, captureSeries()); assertNull(retained())
    }
    @Test fun seriesCaptureCannotBindToABaselineReplacedDuringNativeRead() = fixture(series = true) {
        onRead = { cache(latest); assertEquals(1, store.acknowledge(ProjectionIntent(target, latest))) }
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, captureSeries()); assertNull(retained())
    }
    @Test fun seriesCapturePreservesTheNativeObservationWhenRemoteConflictArrives() = fixture(series = true) {
        onRead = { cache(latest) }
        assertEquals(LocalEditCaptureResult.CONFLICT, captureSeries())
        assertEquals(local, retained()!!.content); assertEquals(LocalEditState.CONFLICT, retained()!!.state)
    }
    @Test fun seriesCaptureLosingTheAccountOrPermissionCannotRetainTheSnapshot() {
        for (loseAccount in listOf(false, true)) fixture(series = true) {
            onRead = { if (loseAccount) currentAccount = null else permission = false }
            assertEquals(LocalEditCaptureResult.INACTIVE, captureSeries()); assertNull(retained())
        }
    }

    @Test fun capturesPausedLocalUpdateWithoutDispatchAndKeepsItsBaseline() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        assertEquals(LocalEditIntent(id, target, format, base, local), retained())
        assertTrue(store.pending(target).isEmpty())
        assertEquals(base, store.projectedBaseline(target, base.eventId))
    }

    @Test fun newerImportBeforeCaptureUsesTheOldProjectedBaseAndMarksConflict() = fixture {
        cache(latest)
        assertEquals(LocalEditCaptureResult.CONFLICT, capture())
        val edit = retained()!!
        assertEquals(base, edit.base); assertEquals(local, edit.content)
        assertEquals(LocalEditState.CONFLICT, edit.state)
        assertEquals(latest, store.transaction(target) { remote(base.eventId) })
        assertTrue(store.pending(target).isEmpty())
    }

    @Test fun importDuringProviderSnapshotIsRetainedAsConflictWithoutProjection() = fixture {
        onRead = { cache(latest) }
        assertEquals(LocalEditCaptureResult.CONFLICT, capture())
        assertEquals(LocalEditState.CONFLICT, retained()!!.state)
        assertTrue(store.pending(target).isEmpty())
    }

    @Test fun repeatedIdReturnsTheRetainedIntentWithoutReadingOrReplacingALaterEdit() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        store.admitTransaction = { true }; cache(latest)
        snapshot = snapshot!!.copy(content = local.copy(title = "Further unsupported edit"))
        assertEquals(LocalEditCaptureResult.CONFLICT, capture())
        assertEquals(1, reads); assertEquals(local, retained()!!.content)
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture(MutationId("different")))
        assertEquals(1, reads); assertEquals(id, retained()!!.id)
    }

    @Test fun unqualifiedProviderDoesNotConsumeAnIncomingProjection() = fixture {
        cache(latest); snapshot = null
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture())
        assertNull(retained())
        assertEquals(latest, store.pending(target).single().change)
    }

    @Test fun sessionInvalidationDuringSnapshotNeverPersistsAnIntent() = fixture {
        onRead = { coordinator.authenticationChanged(false) }
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        assertNull(retained())
    }

    @Test fun disablingTheCalendarDuringSnapshotImmediatelyInvalidatesAdmission() = fixture {
        val selection = store.selections(target.account).single()
        onRead = { coordinator.setEnabled(selection, false) }
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        assertNull(retained())
    }

    @Test fun permissionsAndAccountMismatchRefuseCaptureBeforeProviderRead() = fixture {
        permission = false
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        permission = true; currentAccount = AccountId("foreign-fixture")
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        assertEquals(0, reads); assertNull(retained())
    }

    @Test fun adapterFormatMismatchNeverReinterpretsAnOldBaseline() = fixture {
        store.transaction(target) { checkpoint = PageCheckpoint(target, AdapterFormat("foreign-format", 1)) }
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture())
        assertEquals(0, reads); assertNull(retained())
    }

    @Test fun observerPreservesPreparedOwnerAndConflictWithoutReadingALaterNativeEdit() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, observe())
        val first = retained()!!
        val preparation = LocalEditPreparation(first, Instant.parse("2030-10-01T10:00:00Z"),
            AdapterFormat("synthetic-preparation", 1), "synthetic-protected-preparation")
        assertTrue(store.retainLocalEditPreparation(preparation))
        snapshot = snapshot!!.copy(content = local.copy(title = "Later synthetic native edit"))
        assertEquals(LocalEditCaptureResult.RETAINED, observe())
        assertEquals(first, retained()); assertEquals(1, reads)
        assertEquals(preparation, store.localEditPreparation(target, base.eventId))
        cache(latest)
        assertEquals(LocalEditCaptureResult.CONFLICT, observe())
        val conflict = retained()!!
        assertEquals(first.id, conflict.id); assertEquals(first.content, conflict.content)
        assertEquals(LocalEditState.CONFLICT, conflict.state); assertEquals(1, reads)
        // A remote conflict does not replace the preparation already retained for this identity.
        assertEquals(preparation, store.localEditPreparation(target, base.eventId))
    }

    @Test fun observerRequiresSystemModePermissionsAndMatchingAccountBeforeNativeRead() = fixture {
        systemMode = false
        assertEquals(LocalEditCaptureResult.INACTIVE, observe())
        systemMode = true; permission = false
        assertEquals(LocalEditCaptureResult.INACTIVE, observe())
        permission = true; currentAccount = AccountId("foreign-fixture")
        assertEquals(LocalEditCaptureResult.INACTIVE, observe())
        assertEquals(0, reads); assertNull(retained())
        currentAccount = target.account; systemMode = false
        // Observing the returned account invalidates this invocation's captured policy.
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        assertEquals(0, reads); assertNull(retained())
        // Existing explicit callers retain their original contract, independent of observer opt-in.
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        assertEquals(id, retained()!!.id); assertEquals(1, reads)
    }

    @Test fun disablingSystemModeDuringObserverSnapshotCannotPersistANewOwner() = fixture {
        onRead = { systemMode = false }
        assertEquals(LocalEditCaptureResult.INACTIVE, observe())
        assertEquals(1, reads); assertNull(retained())
    }
}
