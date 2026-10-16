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
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQL and import/capture/text owners; native writes and remote pages are controlled.
 * The dirty-row rejection models the Provider's atomic DIRTY=0 incoming-write assertion.
 * No account, native calendar or service is accessed by this fixture.
 */
@RunWith(AndroidJUnit4::class)
class DirtyProjectionRefreshDeviceTest {
    private class Projection(private val fixture: Fixture) : CalendarProjection {
        override var admitOperation: () -> Boolean = { false }
        override val projectionReceiptProtocol = 1
        override val textUpdateAcknowledgementProtocol = 1
        var dirty = true
        var content = fixture.edited
        var revision = fixture.base.revision
        var incomingAttempts = 0
        var incomingWrites = 0
        var acknowledgements = 0
        var checkpointAtFirstAttempt: PageCheckpoint? = null
        private var incomingReceipt: ProjectionReceipt? = null
        private var textReceipt: TextUpdateAcknowledgementReceipt? = null
        override fun hasPermissions() = true
        override fun accountFor(accountId: AccountId) = Account("synthetic-dirty-refresh", "fixture")
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary) = EnsuredCalendar(1, false)
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary, systemCreates: Boolean) = EnsuredCalendar(1, false)
        override suspend fun makeCalendarReadOnly(scope: CalendarScope) = Unit
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("Tracked projection is required")
        override suspend fun applyTracked(receipt: ProjectionReceipt): AppliedProjectionWitness {
            check(admitOperation())
            if (incomingAttempts == 0) checkpointAtFirstAttempt = fixture.store.transaction(fixture.target) { checkpoint }
            incomingAttempts++; fixture.order += "native"
            if (dirty) throw CalendarProjectionException(CalendarProjectionFailure.PROVIDER_REJECTED)
            incomingWrites++; content = receipt.change.content; revision = receipt.change.revision
            incomingReceipt = receipt
            return AppliedProjectionWitness(receipt)
        }
        override suspend fun inspectProjectionReceipt(receipt: ProjectionReceipt): AppliedProjectionWitness? {
            check(admitOperation())
            return if (incomingReceipt == receipt) AppliedProjectionWitness(receipt) else null
        }
        override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            check(admitOperation())
            return if (scope == fixture.target && dirty && revision == base.revision) OwnedLocalEditSnapshot(1, 1, content) else null
        }
        override suspend fun acknowledgeTextUpdate(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? {
            check(admitOperation())
            if (!dirty || content != receipt.intent.content || revision != receipt.intent.base.revision || textReceipt != null) return null
            acknowledgements++; dirty = false; content = receipt.projection.change.content
            revision = receipt.projection.change.revision; textReceipt = receipt
            return AppliedTextUpdateAcknowledgement(receipt)
        }
        override suspend fun inspectTextUpdateAcknowledgement(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? =
            if (admitOperation() && textReceipt == receipt) AppliedTextUpdateAcknowledgement(receipt) else null
    }

    private class Fixture(private val baseContext: Context) {
        private val token = UUID.randomUUID().toString()
        private val prefs = Collections.synchronizedSet(mutableSetOf<String>())
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "dirty-refresh-$token-$name"; prefs.add(isolated)
                return baseContext.getSharedPreferences(isolated, mode)
            }
        }
        private val names = listOf("import", "attempt").map { "dirty-refresh-$token-$it.db" }
        val target = CalendarScope(AccountId("dirty-refresh-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("synthetic-dirty-refresh", 1)
        val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("original"), EventContent("Synthetic original",
            EventTime.AllDay(LocalDate.of(2030, 10, 26), LocalDate.of(2030, 10, 29)),
            "Original notes", "Original room", details = EventDetails()))
        val edited = base.content.copy(title = "Synthetic edited", description = "New\r\nnotes", location = "New room")
        val store = ImportStore(context, names[0])
        val attempts = TitleUpdateAttemptStore(context, names[1])
        val journal get() = attempts.textJournal
        val projection = Projection(this)
        val order = mutableListOf<String>()
        val requests = mutableListOf<PageRequest>()
        var pages = 1
        var incoming = base
        var emptyFirstPage = false
        var receiveHook: suspend () -> Unit = {}
        var decodeHook: suspend () -> Unit = {}
        private val uncaught = AtomicReference<Throwable?>()
        private val errors = CoroutineExceptionHandler { _, failure -> uncaught.compareAndSet(null, failure) }
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)
        private val ready = CompletableDeferred<Unit>()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { ready.complete(Unit) }
            override fun continueWork(network: Boolean) = Unit
        }
        private val remote = object : StagedCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = target.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("Staged reads only")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No generic writes")
            override suspend fun receive(request: PageRequest): ReceivedCalendarPage {
                requests += request; order += "receive"; receiveHook()
                return ReceivedCalendarPage(request, "synthetic-sealed-page")
            }
            override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage {
                order += "decode"; decodeHook()
                val number = received.request.checkpoint.cursor?.value?.toInt() ?: 0
                val complete = number + 1 >= pages
                return DecodedCalendarPage(RemotePage(target, format, if (number == 0 && !emptyFirstPage) listOf(incoming) else emptyList(),
                    if (complete) null else OpaqueCursor(format, (number + 1).toString()), complete))
            }
            override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = null
        }
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            systemCreatesEnabled = { it == target }, systemTextsEnabled = { it == target })
        lateinit var preparedIncoming: ProjectionReceipt
        fun allowSql() { store.admitTransaction = { it == target } }
        suspend fun checkpoint(): PageCheckpoint? { allowSql(); return store.transaction(target) { checkpoint } }
        suspend fun inbox(): ReceivedCalendarPage? {
            allowSql(); return store.transaction(target) { (this as StagedSyncTransaction).received }
        }
        suspend fun seed() {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic dirty refresh")))
            store.setEnabled(target, true)
            store.transaction(target) {
                putRemote(base); putProjection(ProjectionIntent(target, base))
                checkpoint = PageCheckpoint(target, format, traversalComplete = true)
            }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            store.transaction(target) { putProjection(ProjectionIntent(target, base)) }
            preparedIncoming = store.prepareProjection(ProjectionIntent(target, base), format)
            assertEquals(base, preparedIncoming.change); assertEquals(base, preparedIncoming.previousBase)
            // Exact observed state after beginRefresh, before an incoming batch loses to Save.
            store.transaction(target) { checkpoint = null }
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { ready.await() }
        }
        suspend fun finishText(): TextUpdateVerifiedResult {
            assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureSystemLocalEdit(target, base.eventId))
            allowSql()
            val intent = requireNotNull(store.localEdit(target, base.eventId))
            assertEquals(base, intent.base); assertEquals(edited, intent.content); assertEquals(LocalEditState.PAUSED, intent.state)
            assertEquals(preparedIncoming, store.projectionReceipt(target, base.eventId))
            val preparation = LocalTextEditPreparation(intent, Instant.parse("2030-10-01T12:00:00Z"),
                AdapterFormat("synthetic-text-capsule", 1), "synthetic-capsule")
            val lease = coordinator.systemTextRunAdmission(target)
            val retained = coordinator.localTextOwners.retain(preparation, lease)!!
            assertEquals(preparation.intent, retained.intent); assertEquals(preparation.protectedPayload, retained.protectedPayload)
            val wire = AdapterFormat("synthetic-text-wire", 1)
            val record = TextUpdateAttemptRecord(TextUpdateAttemptIdentity.requestId(retained, wire), retained, wire, "synthetic-wire")
            assertTrue(journal.remember(record, lease))
            val actual = base.copy(revision = Revision("verified-text"), content = edited.copy(description = "New\nnotes"))
            val port = Submission(actual)
            val observation = coordinator.localTextOwners.submit(record, journal, port, lease,
                coordinator.systemTextEvidenceAdmission(target)) as TextUpdateSubmissionObservation.Verified
            val result = TextUpdateVerifiedResult.of(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), observation)
            assertTrue(journal.retainVerified(result, coordinator.systemTextEvidenceAdmission(target)))
            allowSql(); assertEquals(preparedIncoming, store.projectionReceipt(target, base.eventId))
            assertTrue(projection.dirty); assertEquals(edited, projection.content)
            val proofLease = coordinator.systemTextProofAdmission(target)
            assertTrue(coordinator.localTextOwners.acknowledge(result, journal, proofLease))
            assertTrue(coordinator.localTextOwners.complete(result, journal, proofLease))
            assertEquals(1, port.sends); assertEquals(1, projection.acknowledgements)
            assertEquals(result, journal.readCompletion(target, record.id, proofLease)!!.result)
            assertEquals(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "synthetic-response"),
                journal.read(target, record.id, proofLease))
            return result
        }
        suspend fun close() {
            try { jobs.coroutineContext[Job]!!.cancelAndJoin() }
            finally {
                store.close(); attempts.close()
                names.forEach(context::deleteDatabase)
                prefs.toList().forEach { baseContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
            }
            assertNull("Fixture background tasks must not fail", uncaught.get())
        }
    }

    private class Submission(private val observed: RemoteChange.Upsert) : CalendarTextUpdateSubmissionPort {
        override val textUpdateFormat = AdapterFormat("synthetic-text-wire", 1)
        var sends = 0
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalTextEditPreparation,
            admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? = error("Fixture already retained its wire")
        override suspend fun inspect(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation = error("No service inspection")
        override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
            assertTrue(admit(record.scope)); assertTrue(qualifySource(record.scope)); assertTrue(journal.claim(record, admit))
            assertTrue(qualifySource(record.scope)); sends++
            val unknown = record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
            assertTrue(journal.retainEvidence(unknown, "synthetic-response", admitEvidence))
            return TextUpdateSubmissionObservation.Verified(unknown.copy(protectedEvidence = "synthetic-response"), observed)
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }

    @Test fun firstPageCommitsBeforeDirtyDrainAndUnblocksSystemCaptureThroughTextCompletion() = fixture {
        coordinator.run(network = true)
        assertEquals("The first page must not be starved by a dirty native row", 1, requests.size)
        assertEquals(listOf("receive", "decode", "native"), order)
        assertEquals(PageCheckpoint(target, format, traversalComplete = true), projection.checkpointAtFirstAttempt)
        assertEquals(projection.checkpointAtFirstAttempt, checkpoint())
        assertEquals(0, projection.incomingWrites); assertTrue(projection.dirty); assertEquals(edited, projection.content)
        allowSql(); assertEquals(preparedIncoming, store.projectionReceipt(target, base.eventId))
        val result = finishText()
        allowSql()
        assertNull(store.projectionReceipt(target, base.eventId)); assertNull(store.localEdit(target, base.eventId))
        assertNull(store.localTextEditPreparation(target, base.eventId)); assertEquals(result.event, store.projectedBaseline(target, base.eventId))
        val nativeAfterAck = projection.content
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = false, fresh = false))
        assertEquals(nativeAfterAck, projection.content); assertFalse(projection.dirty)
        assertEquals(0, projection.incomingWrites); assertEquals(1, projection.acknowledgements); assertEquals(1, requests.size)
    }

    @Test fun initialPageConsumesOneOfTheFourPageBudgetInsteadOfAddingAFifth() = fixture {
        pages = 7; projection.dirty = false; projection.content = base.content
        assertEquals(ImportRunResult.CONTINUE, coordinator.run(network = true))
        assertEquals(4, requests.size)
        assertEquals(listOf(null, "1", "2", "3"), requests.map { it.checkpoint.cursor?.value })
        assertEquals(OpaqueCursor(format, "4"), checkpoint()!!.cursor)
        assertEquals(listOf("receive", "decode", "native"), order.take(3))
        assertEquals(1, projection.incomingWrites)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = true, fresh = false))
        assertEquals(7, requests.size); assertTrue(checkpoint()!!.traversalComplete)
    }

    @Test fun offlineMissingCheckpointNeverFetchesOrOverwritesDirtyNativeContent() = fixture {
        coordinator.run(network = false)
        assertTrue(requests.isEmpty()); assertNull(checkpoint()); assertTrue(projection.dirty)
        assertEquals(edited, projection.content); assertEquals(0, projection.incomingWrites)
        allowSql(); assertEquals(preparedIncoming, store.projectionReceipt(target, base.eventId))
        assertNull(store.localEdit(target, base.eventId))
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, coordinator.captureSystemLocalEdit(target, base.eventId))
    }

    @Test fun offlineRetainedEmptyPageCommitsBeforeDirtyDrainWithoutAnotherReceive() = fixture {
        emptyFirstPage = true
        val saved = ReceivedCalendarPage(PageRequest(PageCheckpoint(target, format), 100), "synthetic-sealed-page")
        allowSql(); store.transaction(target) { (this as StagedSyncTransaction).received = saved }
        coordinator.run(network = false)
        assertTrue(requests.isEmpty()); assertEquals(listOf("decode", "native"), order)
        assertEquals(PageCheckpoint(target, format, traversalComplete = true), checkpoint())
        assertEquals(checkpoint(), projection.checkpointAtFirstAttempt); assertNull(inbox())
        assertEquals(edited, projection.content); assertTrue(projection.dirty); assertEquals(0, projection.incomingWrites)
        assertEquals(preparedIncoming, store.projectionReceipt(target, base.eventId))
        assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureSystemLocalEdit(target, base.eventId))
    }

    @Test fun differentRemoteObservationRestoresCheckpointButCapturesConflictWithoutOverwritingNative() = fixture {
        incoming = base.copy(revision = Revision("remote-changed"), content = base.content.copy(location = "Different remote room"))
        coordinator.run(network = true)
        assertEquals(1, requests.size); assertNotNull(checkpoint())
        assertEquals(LocalEditCaptureResult.CONFLICT, coordinator.captureSystemLocalEdit(target, base.eventId))
        allowSql()
        val owner = requireNotNull(store.localEdit(target, base.eventId))
        assertEquals(LocalEditState.CONFLICT, owner.state); assertEquals(base, owner.base); assertEquals(edited, owner.content)
        assertEquals(incoming, store.transaction(target) { remote(base.eventId) })
        assertEquals(preparedIncoming, store.projectionReceipt(target, base.eventId))
        assertTrue(projection.dirty); assertEquals(edited, projection.content); assertEquals(0, projection.incomingWrites)
        assertNull(coordinator.localTextOwners.observe(target, base.eventId, owner.id, coordinator.systemTextRunAdmission(target)))
    }

    @Test fun failedFirstReceivePreservesOriginalReceiptAndDoesNotReachNativeWrite() = fixture {
        receiveHook = { throw RemoteImportException(RemoteImportFailure.NETWORK) }
        assertEquals(ImportRunResult.RETRY, coordinator.run(network = true))
        assertEquals(1, requests.size); assertNull(checkpoint()); assertEquals(listOf("receive"), order)
        allowSql(); assertEquals(preparedIncoming, store.projectionReceipt(target, base.eventId))
        assertEquals(0, projection.incomingAttempts); assertEquals(edited, projection.content)
        assertNull(inbox())
    }

    @Test fun failedDecodeRetainsTheAlreadyReceivedPageWithoutCheckpointOrNativeWrite() = fixture {
        decodeHook = { throw java.io.IOException("Synthetic local decoding failure") }
        try { coordinator.run(network = true) } catch (_: java.io.IOException) { }
        assertEquals(1, requests.size); assertEquals(listOf("receive", "decode"), order)
        assertNull(checkpoint()); assertEquals(ReceivedCalendarPage(requests.single(), "synthetic-sealed-page"), inbox())
        assertEquals(0, projection.incomingAttempts); assertEquals(edited, projection.content)
        assertEquals(preparedIncoming, store.projectionReceipt(target, base.eventId))
    }

    @Test fun cancellationDuringDecodePropagatesAndKeepsTheSavedPageWithoutNativeDrain() = fixture {
        decodeHook = { currentCoroutineContext().cancel(); yield() }
        var cancelled = 0; var ordinary = 0; var returned = 0
        supervisorScope {
            val child = async {
                try { coordinator.run(network = true); returned++ }
                catch (_: CancellationException) { cancelled++ }
                catch (_: Exception) { ordinary++ }
            }
            try { child.await() } catch (_: CancellationException) { }
        }
        assertEquals(1, cancelled); assertEquals(0, ordinary); assertEquals(0, returned)
        assertEquals(1, requests.size); assertNull(checkpoint()); assertEquals(0, projection.incomingAttempts)
        assertEquals(ReceivedCalendarPage(requests.single(), "synthetic-sealed-page"), inbox())
        allowSql(); assertEquals(preparedIncoming, store.projectionReceipt(target, base.eventId))
        assertEquals(edited, projection.content); assertTrue(projection.dirty)
    }
}
