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

/** Isolated SQL and controlled read/crypto ports only, never service or Provider writes. */
@RunWith(AndroidJUnit4::class)
class LocalTitlePreparationCoordinatorDeviceTest {
    private class Fixture(titleOnly: Boolean = true) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val name = "title-owner-$token.db"
        val target = CalendarScope(AccountId("title-owner-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("controlled-import", 1)
        val preparationFormat = AdapterFormat("controlled-title", 1)
        val id = MutationId("synthetic-edit")
        val timestamp = Instant.parse("2026-10-03T12:00:00Z")
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("base"), EventContent("Original fixture",
            EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails()))
        val intent = LocalEditIntent(id, target, format, base, base.content.copy(title = "Local fixture",
            description = if (titleOnly) base.content.description else "Different description"))
        val latest = base.copy(revision = Revision("newer"), content = base.content.copy(title = "Remote fixture"))
        val store = ImportStore(context, name)
        var permission = true
        var account: AccountId? = target.account
        var onAccount: suspend () -> Unit = {}
        var calls = 0
        var declaredFormat: AdapterFormat? = preparationFormat
        var onPrepare: suspend () -> LocalEditPreparation = { record() }
        fun record() = LocalEditPreparation(intent, timestamp, preparationFormat, "synthetic-protected")
        val port = object : CalendarLocalEditPreparationPort {
            override val localTitleFormat get() = declaredFormat
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant): LocalEditPreparation {
                assertEquals(this@Fixture.intent, intent); assertEquals(this@Fixture.timestamp, timestamp)
                calls++; return onPrepare()
            }
        }
        val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { false }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("title-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("No Provider access")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No Provider access")
        }
        val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId? { onAccount(); return account }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No import")
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
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
        suspend fun seed() {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Title fixture")))
            store.setEnabled(target, true)
            store.transaction(target) { putRemote(base); putProjection(ProjectionIntent(target, base)); checkpoint = PageCheckpoint(target, format) }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            assertEquals(intent, store.captureLocalEdit(intent))
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
        }
        suspend fun prepare(mutation: MutationId = id, time: Instant = timestamp) = coordinator.prepareLocalTitle(target, base.eventId, mutation, time, port)
        suspend fun retained(): LocalEditPreparation? { store.admitTransaction = { true }; return store.localEditPreparation(target, base.eventId) }
        suspend fun owner(): LocalEditIntent? { store.admitTransaction = { true }; return store.localEdit(target, base.eventId) }
        suspend fun cache(change: RemoteChange = latest) {
            store.admitTransaction = { true }
            store.transaction(target) { putRemote(change); putProjection(ProjectionIntent(target, change)) }
        }
        suspend fun checkpoint(value: AdapterFormat? = format) {
            store.admitTransaction = { true }
            store.transaction(target) { checkpoint = value?.let { PageCheckpoint(target, it) } }
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); context.deleteDatabase(name)
        }
    }
    private fun fixture(titleOnly: Boolean = true, block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(titleOnly)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    @Test fun capturedTitlePreparationIsRetainedWithoutConsumingItsOwner() = fixture {
        assertEquals(LocalTitlePreparationResult.RETAINED, prepare())
        assertEquals(record(), retained()); assertEquals(intent, owner()); assertEquals(1, calls)
        assertEquals(base, store.projectedBaseline(target, base.eventId))
        assertTrue(store.pending(target).isEmpty()); assertFalse(projection.admitOperation())
    }
    @Test fun replayIsHistoricalWithoutRegenerationOrChangingTheFirstTimestamp() = fixture {
        assertEquals(LocalTitlePreparationResult.RETAINED, prepare())
        onPrepare = { error("Historical preparations must not be regenerated") }
        assertEquals(LocalTitlePreparationResult.HISTORICAL, prepare(time = timestamp.plusSeconds(60)))
        assertEquals(1, calls); assertEquals(record(), retained()); assertEquals(intent, owner())
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(record(), reopened.localEditPreparation(target, base.eventId))
        }
    }
    @Test fun permissionAccountCalendarAndGenerationGateBeforePortReads() = fixture {
        permission = false; assertEquals(LocalTitlePreparationResult.INACTIVE, prepare())
        permission = true; account = AccountId("foreign"); assertEquals(LocalTitlePreparationResult.INACTIVE, prepare())
        account = target.account; store.setEnabled(target, false); assertEquals(LocalTitlePreparationResult.INACTIVE, prepare())
        store.setEnabled(target, true)
        assertEquals(LocalTitlePreparationResult.INACTIVE,
            coordinator.prepareLocalTitle(target.copy(generation = 1), base.eventId, id, timestamp, port))
        assertEquals(0, calls); assertNull(retained()); assertEquals(intent, owner())
    }
    @Test fun unobservedOrDifferentFormatsAndWrongMutationCannotBePrepared() = fixture {
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare(MutationId("foreign")))
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED,
            coordinator.prepareLocalTitle(target, EventId("missing"), id, timestamp, port))
        checkpoint(null); assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare())
        checkpoint(format.copy(version = 2)); assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare())
        checkpoint(); declaredFormat = null; assertEquals(LocalTitlePreparationResult.UNSUPPORTED, prepare())
        assertEquals(0, calls); assertNull(retained())
    }
    @Test fun invalidCapturedSnapshotsAndImpreciseTimestampsAreRefusedBeforePort() = fixture {
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare(time = timestamp.plusNanos(1)))
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare(time = Instant.EPOCH.minusSeconds(1)))
        store.writableDatabase.execSQL("UPDATE local_edits SET local=replace(local, 'Local fixture', 'Original fixture')")
        assertEquals(LocalTitlePreparationResult.UNAVAILABLE, prepare())
        assertEquals(0, calls)
    }
    @Test fun newerImportedContentBlocksBeforePort() = fixture {
        cache()
        assertEquals(LocalTitlePreparationResult.CONFLICT, prepare()); assertEquals(0, calls)
        assertEquals(LocalEditState.CONFLICT, owner()!!.state); assertNull(retained())
    }
    @Test fun importChangedDuringPreparationCannotCommitOrResetConflict() = fixture {
        onPrepare = { cache(); record() }
        assertEquals(LocalTitlePreparationResult.CONFLICT, prepare()); assertNull(retained())
        assertEquals(intent.copy(state = LocalEditState.CONFLICT), owner())
        assertEquals(latest, store.transaction(target) { remote(base.eventId) })
        assertTrue(store.pending(target).isEmpty())
    }
    @Test fun deferredContentDuringPreparationCannotCommit() = fixture {
        onPrepare = {
            store.transaction(target) { (this as StagedSyncTransaction).defer(DeferredCalendarEvent(base.eventId,
                base.revision, format, DeferredEventReason.UNSUPPORTED_CONTENT, "synthetic-evidence")) }
            record()
        }
        assertEquals(LocalTitlePreparationResult.CONFLICT, prepare()); assertNull(retained())
        assertEquals(LocalEditState.CONFLICT, owner()!!.state)
    }
    @Test fun remoteConflictBecomesMonotoneLocalConflictWithoutInventingANewBase() = fixture {
        onPrepare = { throw LocalEditPreparationException(LocalEditPreparationFailure.CONFLICT) }
        assertEquals(LocalTitlePreparationResult.CONFLICT, prepare()); assertNull(retained())
        assertEquals(intent.copy(state = LocalEditState.CONFLICT), owner())
        assertEquals(base, store.projectedBaseline(target, base.eventId))
        assertEquals(base, store.transaction(target) { remote(base.eventId) })
        assertEquals(LocalTitlePreparationResult.CONFLICT, prepare()); assertEquals(1, calls)
    }
    @Test fun cachedEvidenceRemainsHistoricalAfterItsOwnerConflicts() = fixture {
        assertEquals(LocalTitlePreparationResult.RETAINED, prepare()); cache()
        assertEquals(LocalTitlePreparationResult.CONFLICT, prepare())
        assertEquals(record(), retained()); assertEquals(LocalEditState.CONFLICT, owner()!!.state); assertEquals(1, calls)
    }
    @Test fun sessionAccountAndPermissionLossDuringPortPreventRetention() = fixture {
        onPrepare = { permission = false; record() }
        assertEquals(LocalTitlePreparationResult.INACTIVE, prepare()); assertNull(retained())
        permission = true; onPrepare = { account = AccountId("foreign"); record() }
        assertEquals(LocalTitlePreparationResult.INACTIVE, prepare()); assertNull(retained())
        account = target.account; onPrepare = { coordinator.authenticationChanged(false); record() }
        assertEquals(LocalTitlePreparationResult.INACTIVE, prepare()); assertNull(retained())
        assertEquals(intent, owner())
    }
    @Test fun calendarPauseAndCatalogLossDuringPortPreventRetention() = fixture {
        onPrepare = { store.setEnabled(target, false); record() }
        assertEquals(LocalTitlePreparationResult.INACTIVE, prepare()); assertNull(retained())
        store.setEnabled(target, true)
        onPrepare = { store.remember(target.account, emptyList()); record() }
        assertEquals(LocalTitlePreparationResult.INACTIVE, prepare()); assertNull(retained())
    }
    @Test fun pauseAndResumeDuringPortStillInvalidateTheOwnerLease() = fixture {
        val selection = store.selections(target.account).single()
        onPrepare = { coordinator.setEnabled(selection, false); coordinator.setEnabled(selection, true); record() }
        assertEquals(LocalTitlePreparationResult.INACTIVE, prepare()); assertNull(retained())
    }
    @Test fun formatChangesDuringPortAndForeignResultsNeverCommit() = fixture {
        onPrepare = { declaredFormat = preparationFormat.copy(version = 2); record() }
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare()); assertNull(retained())
        declaredFormat = preparationFormat
        onPrepare = { checkpoint(format.copy(version = 2)); record() }
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare()); assertNull(retained()); checkpoint()
        for (wrong in listOf(record().copy(intent = intent.copy(id = MutationId("foreign"))),
            record().copy(timestamp = timestamp.plusSeconds(1)), record().copy(format = preparationFormat.copy(version = 2)))) {
            onPrepare = { wrong }
            assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare()); assertNull(retained())
        }
    }
    @Test fun finalTransactionChecksTheBaselineAfterTheLastAccountRead() = fixture {
        onPrepare = {
            onAccount = { onAccount = {}; store.writableDatabase.execSQL("DELETE FROM projection_bases") }
            record()
        }
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare()); assertNull(retained()); assertEquals(intent, owner())
    }
    @Test fun finalTransactionPreservesARecordInsertedByAnotherOwner() = fixture {
        val first = record().copy(timestamp = timestamp.minusSeconds(1), protectedPayload = "synthetic-first")
        onPrepare = {
            onAccount = { onAccount = {}; store.retainLocalEditPreparation(first) }
            record()
        }
        assertEquals(LocalTitlePreparationResult.HISTORICAL, prepare()); assertEquals(first, retained()); assertEquals(1, calls)
    }
    @Test fun genericOrFutureSavedEvidenceIsNeverRegeneratedOrConverted() = fixture {
        val historical = record().copy(format = AdapterFormat("historical-preparation", 9), protectedPayload = "synthetic-history")
        store.admitTransaction = { true }; assertTrue(store.retainLocalEditPreparation(historical))
        assertEquals(LocalTitlePreparationResult.HISTORICAL, prepare())
        assertEquals(historical, retained()); assertEquals(0, calls)
    }
    @Test fun networkAndMissingReadsKeepTheIntentForExplicitRetry() = fixture {
        for ((failure, result) in listOf(LocalEditPreparationFailure.NETWORK to LocalTitlePreparationResult.UNAVAILABLE,
            LocalEditPreparationFailure.NOT_FOUND to LocalTitlePreparationResult.UNAVAILABLE,
            LocalEditPreparationFailure.UNSUPPORTED to LocalTitlePreparationResult.UNSUPPORTED,
            LocalEditPreparationFailure.PERMISSION to LocalTitlePreparationResult.INACTIVE)) {
            onPrepare = { throw LocalEditPreparationException(failure) }
            assertEquals(result, prepare()); assertNull(retained()); assertEquals(intent, owner())
        }
        onPrepare = { record() }; assertEquals(LocalTitlePreparationResult.RETAINED, prepare())
    }
    @Test fun cancellationLeavesNoPreparationAndClosesTheLease() = fixture {
        onPrepare = { throw CancellationException("synthetic interruption") }
        try { prepare(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        try { store.localEdit(target, base.eventId); fail("Coordinator lease must be closed") } catch (_: IllegalStateException) { }
        assertNull(retained()); assertEquals(intent, owner())
        onPrepare = { record() }; assertEquals(LocalTitlePreparationResult.RETAINED, prepare())
    }
    @Test fun cancelledCoroutineAfterPortCompletionNeverCommitsTheCapsule() = fixture {
        onPrepare = { currentCoroutineContext().cancel(CancellationException("synthetic cancellation")); record() }
        coroutineScope {
            val work = async { prepare() }
            try { work.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertNull(retained()); assertEquals(intent, owner())
    }
    @Test fun aCapturedNonTitleEditIsNotReducedToATitlePreparation() = fixture(titleOnly = false) {
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare()); assertEquals(0, calls)
        assertNull(retained()); assertEquals(intent, owner())
    }
    @Test fun ownerReplacementDuringPortIsRefusedByTheInsertionTransaction() = fixture {
        onPrepare = { store.writableDatabase.execSQL("UPDATE local_edits SET mutation='synthetic-replaced'"); record() }
        assertEquals(LocalTitlePreparationResult.UNQUALIFIED, prepare()); assertNull(retained())
        assertEquals(intent.copy(id = MutationId("synthetic-replaced")), owner())
    }
    @Test fun conflictPersistenceFailureIsSanitizedAndLeavesThePausedOwnerIntact() = fixture {
        store.writableDatabase.execSQL("CREATE TRIGGER reject_title_conflict BEFORE UPDATE OF state ON local_edits " +
            "WHEN NEW.state='CONFLICT' BEGIN SELECT RAISE(ABORT, 'synthetic blocked transition'); END")
        onPrepare = { throw LocalEditPreparationException(LocalEditPreparationFailure.CONFLICT) }
        assertEquals(LocalTitlePreparationResult.UNAVAILABLE, prepare()); assertNull(retained()); assertEquals(intent, owner())
        store.writableDatabase.execSQL("DROP TRIGGER reject_title_conflict")
        assertEquals(LocalTitlePreparationResult.CONFLICT, prepare()); assertEquals(intent.copy(state = LocalEditState.CONFLICT), owner())
    }
}
