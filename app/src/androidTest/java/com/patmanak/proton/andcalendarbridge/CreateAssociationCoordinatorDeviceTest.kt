// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated SQLite, controlled read-only ports; no account, network or Provider mutations. */
@RunWith(AndroidJUnit4::class)
class CreateAssociationCoordinatorDeviceTest {
    private class Fixture(val context: Context) {
        private val token = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("association-fixture-$token"), CalendarId("calendar"), 0)
        private val importName = "association-import-$token.db"
        private val journalName = "association-journal-$token.db"
        val format = AdapterFormat("association-fixture", 1)
        val record = CreateAttemptRecord(MutationId("attempt"), target, format, "sealed-fixture")
        val identity = CreateAttemptIdentity.of(record)
        val initial = RemoteChange.Upsert(EventId("created"), Revision("creation"), EventContent(
            "Create association fixture", EventTime.AllDay(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2))))
        val latest = initial.copy(revision = Revision("later-import"),
            content = initial.content.copy(title = "Later imported fixture"))
        var store = ImportStore(context, importName)
        var journal = CreateAttemptStore(context, journalName)
        var permission = true
        var providerRevision = latest.revision
        var providerPresent = true
        var providerRow = 51L
        val lookups = mutableListOf<Revision>()
        var applyCount = 0
        var allowDraining = false
        var onLookup: suspend () -> Unit = {}
        var inspectCount = 0
        var inspection: suspend (CreateAttemptRecord) -> CreateSubmissionObservation = {
            CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(it), initial)
        }
        val submissions = object : CalendarCreateSubmissionPort {
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate =
                error("Association must never prepare an upload")
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation =
                error("Association must never submit an upload")
            override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation {
                inspectCount++
                return inspection(record)
            }
        }
        private val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { true }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("association-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar {
                check(allowDraining && admitOperation())
                return EnsuredCalendar(7, false)
            }
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) {
                check(allowDraining && admitOperation())
                applyCount++
            }
            override suspend fun inspectOwnedEvent(scope: CalendarScope, eventId: EventId, revision: Revision): OwnedEventWitness? {
                assertEquals(target, scope)
                assertEquals(initial.eventId, eventId)
                lookups.add(revision)
                onLookup()
                return if (providerPresent && providerRevision == revision)
                    OwnedEventWitness(7, providerRow, providerRevision) else null
            }
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = target.account
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No uploads")
            override suspend fun fetch(request: PageRequest): RemotePage = error("No downloads")
        }
        val networkSchedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { networkSchedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
        suspend fun start() {
            val previous = networkSchedules.get()
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (networkSchedules.get() == previous) delay(10) }
        }
        suspend fun seed(claimed: Boolean) {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Association fixture")))
            store.setEnabled(target, true)
            journal.remember(record)
            if (claimed) assertTrue(journal.claim(record))
            cache(latest)
            start()
        }
        suspend fun cache(change: RemoteChange, pending: Boolean = false) {
            store.transaction(target) {
                putRemote(change)
                if (pending) putProjection(ProjectionIntent(target, change))
                checkpoint = PageCheckpoint(target, format, traversalComplete = true)
            }
            if (!pending) store.acknowledge(ProjectionIntent(target, change))
        }
        suspend fun associate(attempt: CreateAttemptRecord = record) =
            coordinator.associateCreate(attempt, journal, submissions)
        suspend fun binding() = journal.binding(identity)
        suspend fun witness() = journal.projectionWitness(identity)
        suspend fun reopen() {
            coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close()
            store = ImportStore(context, importName)
            journal = CreateAttemptStore(context, journalName)
            jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
            start()
        }
        suspend fun close() {
            coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close()
            context.deleteDatabase(importName); context.deleteDatabase(journalName)
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(projection.accountFor(target.account).name + ":" + target.calendar.value).commit()
        }
    }

    private fun fixture(claimed: Boolean = true, block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        try { fixture.seed(claimed); fixture.block() } finally { fixture.close() }
    }

    @Test fun durableBindingResumesOfflineAndResolvesANewProviderLocatorAfterReopening() = fixture {
        assertEquals(CreateAssociationResult.ASSOCIATED, associate())
        assertEquals(initial.revision, binding()!!.verifiedRevision)
        assertEquals(OwnedEventWitness(7, 51, latest.revision), witness())
        assertEquals(1, inspectCount)
        reopen()
        inspection = { error("An existing binding must resume without transport") }
        providerRow = 52
        assertEquals(CreateAssociationResult.ASSOCIATED, associate())
        assertEquals(1, inspectCount)
        assertEquals(OwnedEventWitness(7, 52, latest.revision), witness())
        assertEquals(listOf(latest.revision, latest.revision), lookups)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, journal.read(target, record.id)!!.state)
        assertEquals(0, applyCount)
    }

    @Test fun olderCreationProofCannotOverwriteTheLaterImportedSnapshot() = fixture {
        assertEquals(CreateAssociationResult.ASSOCIATED, associate())
        assertEquals(initial.revision, binding()!!.verifiedRevision)
        assertEquals(latest, store.transaction(target) { remote(initial.eventId) })
        assertEquals(latest.revision, witness()!!.revision)
        assertTrue(store.pending(target).isEmpty())
        assertEquals(0, applyCount)
    }

    @Test fun pendingProjectionClearsAnOldWitnessAndWaitsForImportWithoutProviderLookup() = fixture {
        assertEquals(CreateAssociationResult.ASSOCIATED, associate())
        cache(latest, pending = true)
        val previousLookups = lookups.size
        val previousSchedules = networkSchedules.get()
        assertEquals(CreateAssociationResult.WAITING_IMPORT, associate())
        assertNull(witness())
        assertEquals(previousLookups, lookups.size)
        assertTrue(networkSchedules.get() > previousSchedules)
        assertEquals(1, store.pending(target).size)
        assertEquals(0, applyCount)
    }

    @Test fun importedTombstoneWaitsWithoutRecreatingAnAndroidEvent() = fixture {
        cache(RemoteChange.Deleted(initial.eventId, Revision("deleted")))
        assertEquals(CreateAssociationResult.WAITING_IMPORT, associate())
        assertNotNull(binding()); assertNull(witness())
        assertTrue(lookups.isEmpty()); assertEquals(0, applyCount)
        assertTrue(store.transaction(target) { remote(initial.eventId) } is RemoteChange.Deleted)
    }

    @Test fun deferredNewerEvidencePreventsAssociationWithTheOlderCachedProjection() = fixture {
        store.transaction(target) {
            (this as StagedSyncTransaction).defer(DeferredCalendarEvent(initial.eventId, Revision("unmapped"),
                format, DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-deferred-fixture"))
        }
        assertEquals(CreateAssociationResult.WAITING_IMPORT, associate())
        assertNotNull(binding()); assertNull(witness())
        assertTrue(lookups.isEmpty()); assertEquals(0, applyCount)
        assertEquals(1, store.deferredCount(target))
    }

    @Test fun foreignObservationAndAlteredCallerPayloadCannotBindTheAttempt() = fixture {
        inspection = {
            CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(it.copy(protectedPayload = "other-sealed-fixture")), initial)
        }
        assertEquals(CreateAssociationResult.UNRESOLVED, associate())
        assertNull(binding()); assertNull(witness()); assertTrue(lookups.isEmpty())
        assertEquals(1, inspectCount)
        assertEquals(CreateAssociationResult.UNRESOLVED, associate(record.copy(protectedPayload = "other-sealed-fixture")))
        assertEquals(1, inspectCount)
    }

    @Test fun preparedPersistedAttemptCannotBeInspectedOrAssociated() = fixture(claimed = false) {
        inspection = { error("Prepared work has no remote observation to inspect") }
        assertEquals(CreateAssociationResult.UNRESOLVED, associate())
        assertEquals(0, inspectCount); assertNull(binding()); assertNull(witness())
        assertTrue(lookups.isEmpty())
        assertEquals(CreateAttemptState.PREPARED, journal.read(target, record.id)!!.state)
    }

    @Test fun authenticationLossDuringInspectionDoesNotCommitABindingOrProviderWitness() = fixture {
        inspection = {
            coordinator.authenticationChanged(false)
            CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(it), initial)
        }
        assertEquals(CreateAssociationResult.INACTIVE, associate())
        assertNull(binding()); assertNull(witness()); assertTrue(lookups.isEmpty())
    }

    @Test fun authenticationLossDuringProviderLookupDoesNotCommitItsWitness() = fixture {
        onLookup = { coordinator.authenticationChanged(false) }
        assertEquals(CreateAssociationResult.INACTIVE, associate())
        assertNotNull(binding()); assertNull(witness())
        assertEquals(listOf(latest.revision), lookups)
    }

    @Test fun absentPermissionMissingRowAndRevisionMismatchRetainBindingWithoutStaleWitness() = fixture {
        assertEquals(CreateAssociationResult.ASSOCIATED, associate())
        permission = false
        assertEquals(CreateAssociationResult.WAITING_PROVIDER, associate())
        assertNull(witness()); assertEquals(1, lookups.size)
        permission = true; providerPresent = false
        assertEquals(CreateAssociationResult.WAITING_PROVIDER, associate())
        assertNull(witness()); assertEquals(2, lookups.size)
        providerPresent = true; providerRevision = initial.revision
        assertEquals(CreateAssociationResult.WAITING_PROVIDER, associate())
        assertNull(witness()); assertEquals(3, lookups.size)
        assertEquals(1, inspectCount); assertNotNull(binding()); assertEquals(0, applyCount)
    }

    @Test fun pausedAndUnavailableCalendarScopesCannotInspectOrAssociate() = fixture {
        store.setEnabled(target, false)
        assertEquals(CreateAssociationResult.INACTIVE, associate())
        store.setEnabled(target, true)
        store.remember(target.account, emptyList())
        assertEquals(CreateAssociationResult.INACTIVE, associate())
        assertEquals(0, inspectCount); assertTrue(lookups.isEmpty()); assertNull(binding())
    }

    @Test fun importDrainUsesTheSameCoordinatorMutexAsAssociation() = fixture {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        inspection = {
            entered.complete(Unit); release.await()
            CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(it), initial)
        }
        allowDraining = true
        coroutineScope {
            val association = async { associate() }
            withTimeout(10_000) { entered.await() }
            cache(latest, pending = true)
            val drain = async { coordinator.run(network = false) }
            try {
                assertNull(withTimeoutOrNull(150) { drain.await() })
                assertEquals(0, applyCount)
            } finally { release.complete(Unit) }
            assertEquals(CreateAssociationResult.WAITING_IMPORT, association.await())
            assertEquals(ImportRunResult.COMPLETE, drain.await())
        }
        assertEquals(1, applyCount)
        assertTrue(store.pending(target).isEmpty())
        assertEquals(CreateAssociationResult.ASSOCIATED, associate())
        assertEquals(1, inspectCount)
    }
}
