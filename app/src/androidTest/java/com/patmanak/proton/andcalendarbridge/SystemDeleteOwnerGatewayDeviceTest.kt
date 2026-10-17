// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real SQLite and controlled native/auth boundaries; no Provider or service claims. */
@RunWith(AndroidJUnit4::class)
class SystemDeleteOwnerGatewayDeviceTest {
    private class Projection : CalendarProjection {
        override var admitOperation: () -> Boolean = { false }
        override val deleteAcknowledgementProtocol = 1
        var current: EventContent? = null
        var deleted = true
        var dirty = true
        var acknowledgements = 0
        var inspections = 0
        var loseReply = false
        private var applied: DeleteAcknowledgementReceipt? = null
        override fun hasPermissions() = true
        override fun accountFor(accountId: AccountId) = Account("synthetic-delete-gateway", "fixture")
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary) = EnsuredCalendar(1, false)
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary, systemCreates: Boolean): EnsuredCalendar {
            check(admitOperation()); return EnsuredCalendar(1, false)
        }
        override suspend fun makeCalendarReadOnly(scope: CalendarScope) = Unit
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No incoming projection")
        override suspend fun captureOwnedSimpleDelete(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            check(admitOperation())
            return if (deleted && dirty) current?.let { OwnedLocalEditSnapshot(1, 1, it) } else null
        }
        override suspend fun acknowledgeDelete(receipt: DeleteAcknowledgementReceipt): AppliedDeleteAcknowledgement? {
            check(admitOperation()); acknowledgements++
            if (!deleted || !dirty || current != receipt.intent.content || applied != null) return null
            applied = receipt.snapshot(); dirty = false
            if (loseReply) throw java.io.IOException("Synthetic lost native reply")
            return AppliedDeleteAcknowledgement(receipt)
        }
        override suspend fun inspectDeleteAcknowledgement(receipt: DeleteAcknowledgementReceipt): AppliedDeleteAcknowledgement? {
            check(admitOperation()); inspections++
            return if (applied == receipt) AppliedDeleteAcknowledgement(receipt) else null
        }
    }

    private class Fixture(private val baseContext: Context, explicitConsent: Boolean) {
        private val token = UUID.randomUUID().toString()
        private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "delete-gateway-$token-$name"; preferences.add(isolated)
                return baseContext.getSharedPreferences(isolated, mode)
            }
        }
        private val names = listOf("imports", "attempts").map { "delete-gateway-$it-$token.db" }
        val target = CalendarScope(AccountId("delete-gateway-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("delete-gateway-import", 1)
        val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("before"), EventContent("Synthetic deletion",
            EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 3)), details = EventDetails()))
        val intent = LocalDeleteIntent(MutationId("local-delete"), target, format, base, base.content)
        val wireFormat = AdapterFormat("delete-gateway-wire", 1)
        val record = DeleteAttemptRecord(DeleteAttemptIdentity.requestId(intent, wireFormat), intent, wireFormat, "synthetic-wire")
        val store = ImportStore(context, names[0])
        val attempts = TitleUpdateAttemptStore(context, names[1])
        val journal get() = attempts.deleteJournal
        val projection = Projection()
        val authMutex = Mutex()
        var inAuth = false
        var deleteEnabled = true
        var createEnabled = true
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val ready = CompletableDeferred<Unit>()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { ready.complete(Unit) }
            override fun continueWork(network: Boolean) = Unit
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format get() = this@Fixture.format
            override suspend fun currentAccount(): AccountId {
                check(!inAuth) { "Local source must not re-enter authentication" }
                return authMutex.withLock { target.account }
            }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No service fetch")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No generic dispatch")
        }
        val coordinator = if (explicitConsent) ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            systemCreatesEnabled = { it == target && createEnabled }, systemTitlesEnabled = { it == target },
            systemTextsEnabled = { it == target }, systemTimesEnabled = { it == target },
            systemDeletesEnabled = { it == target && deleteEnabled }) else
            ImportCoordinator(context, store, remote, projection, jobs, scheduler,
                systemCreatesEnabled = { it == target && createEnabled }, systemTitlesEnabled = { it == target },
                systemTextsEnabled = { it == target }, systemTimesEnabled = { it == target })
        val owners get() = coordinator.localDeleteOwners
        fun allowSql() { store.admitTransaction = { it == target } }
        fun toggleDelete(enabled: Boolean) {
            deleteEnabled = enabled; coordinator.systemCreatePolicyChanged(target, createEnabled)
        }
        suspend fun start() {
            allowSql()
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic delete gateway")))
            store.setEnabled(target, true)
            store.transaction(target) {
                checkpoint = PageCheckpoint(target, format, traversalComplete = true)
                putRemote(base); putProjection(ProjectionIntent(target, base))
            }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            assertEquals(intent, store.captureLocalDelete(intent)); projection.current = intent.content
            assertTrue(journal.remember(record) { it == target })
            coordinator.authenticationChanged(true)
            withTimeout(10_000) {
                ready.await()
                while (!coordinator.state.value.authenticated || !coordinator.state.value.hasPermission ||
                    coordinator.state.value.selections.none { it.scope == target && it.enabled && it.available }) delay(10)
            }
        }
        suspend fun reserve(): Boolean = owners.reserve(record, coordinator.systemDeleteRunAdmission(target))
        suspend fun submit(port: Port) = owners.submit(record, journal, port,
            coordinator.systemDeleteRunAdmission(target), coordinator.systemDeleteEvidenceAdmission(target))
        suspend fun verified(port: Port): DeleteVerifiedResult {
            assertTrue(reserve())
            val observation = submit(port) as DeleteSubmissionObservation.Verified
            val result = DeleteVerifiedResult.of(record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN), observation)
            assertTrue(journal.retainVerified(result, coordinator.systemDeleteEvidenceAdmission(target)))
            return result
        }
        fun operations(port: Port) = CoordinatorSystemDeleteOperations(coordinator, journal, port)
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext.job.cancelAndJoin()
            attempts.close(); store.close(); names.forEach(context::deleteDatabase)
            preferences.toList().forEach { baseContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        }
    }

    private class Port(private val f: Fixture) : CalendarDeleteSubmissionPort {
        override val deleteFormat get() = f.wireFormat
        var calls = 0
        var sends = 0
        var inspections = 0
        var beforeClaim: suspend () -> Unit = {}
        var afterSend: suspend () -> Unit = {}
        var foreignResult = false
        override suspend fun prepareSubmission(id: MutationId, intent: LocalDeleteIntent,
            admit: (CalendarScope) -> Boolean): DeleteAttemptRecord? = error("First wire is already retained")
        override suspend fun inspect(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
            admit: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
            inspections++; error("Offline recovery cannot reach HTTP inspection")
        }
        override suspend fun submit(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation = f.authMutex.withLock {
            calls++; f.inAuth = true
            try {
                if (!admit(f.target) || !qualifySource(f.target)) return@withLock DeleteSubmissionObservation.Unknown
                beforeClaim()
                if (!qualifySource(f.target) || !journal.claim(record, admit)) return@withLock DeleteSubmissionObservation.Unknown
                val unknown = record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
                if (!qualifySource(f.target) || !admit(f.target)) return@withLock DeleteSubmissionObservation.Unknown
                sends++; afterSend()
                if (!journal.retainEvidence(unknown, "synthetic-response", admitEvidence)) return@withLock DeleteSubmissionObservation.Unknown
                val observed = unknown.copy(protectedEvidence = "synthetic-response")
                DeleteSubmissionObservation.Verified(if (foreignResult) observed.copy(protectedPayload = "foreign-wire") else observed)
            } finally { f.inAuth = false }
        }
    }

    private fun fixture(explicitConsent: Boolean = true, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val f = Fixture(ApplicationProvider.getApplicationContext(), explicitConsent)
        try { f.start(); f.block() } finally { f.close() }
    }

    @Test fun deletionDefaultsOffDespiteOtherWriteConsents() = fixture(explicitConsent = false) {
        assertTrue(coordinator.systemTimeRunAdmission(target)(target))
        assertFalse(coordinator.systemDeleteRunAdmission(target)(target))
        assertNull(owners.observe(target, base.eventId, intent.id) { true })
        assertFalse(reserve()); val port = Port(this)
        assertEquals(DeleteSubmissionObservation.Unknown, submit(port)); assertEquals(0, port.calls)
    }

    @Test fun exactOwnerReservesBeforeSourceCallbackUnderAuthenticationLock() = fixture {
        assertNull(owners.observe(target, base.eventId, MutationId("foreign-owner")) { true })
        assertNull(owners.observe(target.copy(generation = 1), base.eventId, intent.id) { true })
        assertEquals(intent, owners.observe(target, base.eventId, intent.id, coordinator.systemDeleteRunAdmission(target)))
        val port = Port(this)
        assertEquals(DeleteSubmissionObservation.Unknown, submit(port)); assertEquals(0, port.calls)
        assertTrue(reserve()); allowSql()
        assertEquals(DeleteAcknowledgementState.PENDING, store.deleteAcknowledgement(target, base.eventId, record.id)?.state)
        assertTrue(withTimeout(10_000) { submit(port) } is DeleteSubmissionObservation.Verified)
        assertEquals(1, port.sends); assertEquals(0, projection.acknowledgements)
    }

    @Test fun nativeRestorationOrChangedContentBeforeClaimPreventsHttp() = fixture {
        assertTrue(reserve()); val port = Port(this)
        port.beforeClaim = { projection.deleted = false; projection.dirty = true }
        assertEquals(DeleteSubmissionObservation.Unknown, submit(port)); assertEquals(0, port.sends)
        assertEquals(record, journal.read(target, record.id) { true })
        projection.deleted = true; projection.current = intent.content.copy(title = "Later native title")
        assertNull(owners.observe(target, base.eventId, intent.id) { true })
        assertEquals(DeleteSubmissionObservation.Unknown, submit(port)); assertEquals(0, port.sends)
    }

    @Test fun lostNativeAckReplyResumesOfflineWithoutDuplicateWriteAndCompletes() = fixture {
        val port = Port(this); val result = verified(port); projection.loseReply = true
        assertFalse(owners.acknowledge(result, journal, coordinator.systemDeleteProofAdmission(target)))
        assertEquals(1, projection.acknowledgements)
        allowSql()
        assertEquals(DeleteAcknowledgementState.PENDING, store.deleteAcknowledgement(target, base.eventId, record.id)?.state)
        val progress = operations(port).recoverOffline(TitleUpdateWorkQuery(target), coordinator.systemDeleteProofAdmission(target))
        assertEquals(1, progress.completed); assertEquals(1, projection.acknowledgements)
        assertEquals(1, port.sends); assertEquals(0, port.inspections)
        assertNotNull(journal.readCompletion(target, record.id) { true })
        allowSql()
        assertTrue(store.transaction(target) { hasPendingMutation(base.eventId) })
    }

    @Test fun restoredNativeEventAfterCompletionBecomesVisibleConflictAndSurvives() = fixture {
        val port = Port(this); val result = verified(port)
        val admission = coordinator.systemDeleteProofAdmission(target)
        assertTrue(owners.acknowledge(result, journal, admission)); assertTrue(owners.complete(result, journal, admission))
        allowSql(); assertEquals(0, store.retainedLocalChanges(RetainedLocalChangeQuery(target)).entries.size)
        projection.deleted = false; projection.dirty = true; projection.current = intent.content.copy(title = "Restored native title")
        assertTrue(coordinator.reconcileReservedDeleteChange(target, base.eventId, deleted = false, dirty = true))
        allowSql(); assertEquals(LocalEditState.CONFLICT, store.localDelete(target, base.eventId)?.state)
        assertEquals(1, store.retainedLocalChanges(RetainedLocalChangeQuery(target)).entries.size)
        assertEquals("Restored native title", projection.current!!.title)
        assertEquals(1, projection.acknowledgements); assertEquals(1, port.sends)
    }

    @Test fun nativeRestorationBetweenAckAndSqlRecoveryIsNeverOverwritten() = fixture {
        val port = Port(this); val result = verified(port); projection.loseReply = true
        assertFalse(owners.acknowledge(result, journal, coordinator.systemDeleteProofAdmission(target)))
        projection.deleted = false; projection.dirty = true
        projection.current = intent.content.copy(location = "Restored before SQL recovery")
        val progress = operations(port).recoverOffline(TitleUpdateWorkQuery(target), coordinator.systemDeleteProofAdmission(target))
        assertEquals(1, progress.completed)
        assertEquals("Restored before SQL recovery", projection.current!!.location)
        assertTrue(projection.dirty); assertFalse(projection.deleted)
        assertEquals(1, projection.acknowledgements); assertEquals(1, port.sends)
        assertTrue(coordinator.reconcileReservedDeleteChange(target, base.eventId, deleted = false, dirty = true))
        allowSql(); assertEquals(LocalEditState.CONFLICT, store.localDelete(target, base.eventId)?.state)
        assertEquals(1, store.retainedLocalChanges(RetainedLocalChangeQuery(target)).entries.size)
    }

    @Test fun reservedRemoteDeletionEchoDoesNotInvalidateExactLocalOwner() = fixture {
        assertTrue(reserve()); allowSql()
        store.transaction(target) {
            val gone = RemoteChange.Deleted(base.eventId, Revision("opaque-echo"))
            putRemote(gone); putProjection(ProjectionIntent(target, gone))
        }
        assertEquals(intent, owners.observe(target, base.eventId, intent.id, coordinator.systemDeleteRunAdmission(target)))
        assertTrue(reserve()); val port = Port(this)
        assertTrue(submit(port) is DeleteSubmissionObservation.Verified); assertEquals(1, port.sends)
    }

    @Test fun revokedSendingConsentRetainsLateFactsAndOfflineProofStillAcknowledges() = fixture {
        val port = Port(this)
        port.afterSend = { toggleDelete(false) }
        val result = verified(port)
        assertFalse(coordinator.systemDeleteRunAdmission(target)(target))
        assertTrue(coordinator.systemDeleteProofAdmission(target)(target))
        val progress = operations(port).recoverOffline(TitleUpdateWorkQuery(target), coordinator.systemDeleteProofAdmission(target))
        assertEquals(1, progress.completed); assertEquals(1, projection.acknowledgements)
        assertEquals(result, journal.readVerified(target, record.id) { true })
        assertEquals(1, port.sends)
    }

    @Test fun foreignVerifiedWireCannotReachNativeAck() = fixture {
        val port = Port(this); port.foreignResult = true
        assertTrue(reserve())
        assertEquals(DeleteSubmissionObservation.Unknown, submit(port))
        assertNull(journal.readVerified(target, record.id) { true }); assertEquals(0, projection.acknowledgements)
        assertEquals(1, port.sends)
    }
}
