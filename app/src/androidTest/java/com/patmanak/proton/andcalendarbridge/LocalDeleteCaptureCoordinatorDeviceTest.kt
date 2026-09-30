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
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated SQLite and controlled Provider/remote ports. No service writes or editing UI. */
@RunWith(AndroidJUnit4::class)
class LocalDeleteCaptureCoordinatorDeviceTest {
    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        private val name = "delete-capture-coordinator-$token.db"
        val target = CalendarScope(AccountId("delete-capture-fixture-$token"), CalendarId("calendar"), 0)
        val format = AdapterFormat("capture-fixture", 1)
        val id = MutationId("delete")
        val base = RemoteChange.Upsert(EventId("event"), Revision("base"), EventContent("Baseline fixture",
            EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2))))
        val local = base.content.copy(title = "Local fixture")
        val latest = base.copy(revision = Revision("remote"), content = base.content.copy(title = "Remote fixture"))
        val store = ImportStore(context, name)
        var permission = true
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
            override suspend fun captureOwnedSimpleDelete(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
                assertEquals(target, scope); assertEquals(this@Fixture.base, base)
                check(admitOperation()); reads++; onRead()
                return snapshot
            }
            override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
                assertEquals(target, scope); assertEquals(this@Fixture.base, base)
                check(admitOperation()); reads++; onRead()
                return snapshot
            }
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
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
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
        suspend fun capture(editId: MutationId = id) = coordinator.captureLocalDelete(target, base.eventId, editId)
        suspend fun retained(): LocalDeleteIntent? {
            store.admitTransaction = { true }
            return store.localDelete(target, base.eventId)
        }
        suspend fun close() {
            coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); context.deleteDatabase(name)
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }

    @Test fun capturesPausedLocalDeletionWithoutDispatchAndKeepsItsBaseline() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        assertEquals(LocalDeleteIntent(id, target, format, base, local), retained())
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

    @Test fun deletionCanRetainAnUnchangedSnapshot() = fixture {
        snapshot = snapshot!!.copy(content = base.content)
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        assertEquals(base.content, retained()!!.content)
        assertEquals(LocalEditState.PAUSED, retained()!!.state)
    }

    @Test fun anExistingUpdateCannotBeReplacedOrReplayedAsADeletion() = fixture {
        val update = LocalEditIntent(id, target, format, base, local)
        store.captureLocalEdit(update)
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture())
        assertEquals(0, reads)
        store.admitTransaction = { true }
        assertEquals(update, store.localEdit(target, base.eventId))
        assertNull(store.localDelete(target, base.eventId))
    }

    @Test fun retainedDeletionCannotBeReplayedAsAnUpdate() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        assertEquals(LocalEditCaptureResult.UNQUALIFIED,
            coordinator.captureLocalEdit(target, base.eventId, id))
        assertEquals(1, reads)
        assertEquals(LocalDeleteIntent(id, target, format, base, local), retained())
        assertNull(store.localEdit(target, base.eventId))
    }

    @Test fun permissionsLostDuringSnapshotInvalidateTheCommit() = fixture {
        onRead = { permission = false }
        assertEquals(LocalEditCaptureResult.INACTIVE, capture())
        assertNull(retained())
    }

    @Test fun anotherScopeCannotCaptureThisCalendarEvenInTheSameAccount() = fixture {
        assertEquals(LocalEditCaptureResult.INACTIVE,
            coordinator.captureLocalDelete(target.copy(generation = 1), base.eventId, id))
        assertEquals(0, reads); assertNull(retained())
    }

    @Test fun cancellationDuringSnapshotLeavesThePendingProjectionUntouched() = fixture {
        cache(latest)
        onRead = { throw CancellationException("fixture interrupted capture") }
        var cancelled = false
        try { capture() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertNull(retained())
        assertEquals(listOf(ProjectionIntent(target, latest)), store.pending(target))
    }
}
