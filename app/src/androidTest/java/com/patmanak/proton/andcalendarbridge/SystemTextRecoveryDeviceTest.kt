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
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated SQL and controller composition, with a controlled native boundary and no service.
 * These tests qualify recovery ordering/admission, not Android Provider or Proton behavior.
 */
@RunWith(AndroidJUnit4::class)
class SystemTextRecoveryDeviceTest {
    private class Projection : CalendarProjection {
        override var admitOperation: () -> Boolean = { false }
        override val textUpdateAcknowledgementProtocol = 1
        var permission = true
        var scanFails = false
        var scans = 0
        var writes = 0
        var captures = 0
        var beforeScan: suspend () -> Unit = {}
        var afterWrite: suspend () -> Unit = {}
        val order = mutableListOf<String>()
        val contents = mutableMapOf<EventId, EventContent>()
        private val receipts = mutableMapOf<EventId, TextUpdateAcknowledgementReceipt>()
        override fun hasPermissions() = permission
        override fun accountFor(accountId: AccountId) = Account("synthetic-text-recovery", "fixture")
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary) = EnsuredCalendar(1, false)
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary, systemCreates: Boolean): EnsuredCalendar {
            check(admitOperation()); return EnsuredCalendar(1, false)
        }
        override suspend fun makeCalendarReadOnly(scope: CalendarScope) = Unit
        override suspend fun discoverSystemChanges(scope: CalendarScope, afterRow: Long, limit: Int): SystemCalendarChangePage? {
            check(admitOperation()); beforeScan(); scans++; order += "scan"
            return if (scanFails) null else SystemCalendarChangePage(emptyList(), 0, false)
        }
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No incoming projection in recovery fixture")
        override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            check(admitOperation()); captures++
            return contents[base.eventId]?.let { OwnedLocalEditSnapshot(1, 1, it) }
        }
        override suspend fun acknowledgeTextUpdate(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? {
            check(admitOperation())
            if (contents[receipt.intent.base.eventId] != receipt.intent.content) return null
            check(receipts.putIfAbsent(receipt.intent.base.eventId, receipt) == null)
            order += "ack"; writes++
            contents[receipt.intent.base.eventId] = receipt.projection.change.content
            afterWrite()
            return AppliedTextUpdateAcknowledgement(receipt)
        }
        override suspend fun inspectTextUpdateAcknowledgement(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? {
            check(admitOperation())
            return if (receipts[receipt.intent.base.eventId] == receipt) AppliedTextUpdateAcknowledgement(receipt) else null
        }
    }

    private class ForbiddenPorts : CalendarTextUpdateSubmissionPort, CalendarLocalTextPreparationPort, CalendarCreateSubmissionPort {
        var calls = 0
        private fun forbidden(): Nothing { calls++; error("Offline recovery must not call a preparation or transport port") }
        override val textUpdateFormat = AdapterFormat("synthetic-text-wire", 1)
        override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant): LocalTextEditPreparation = forbidden()
        override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant, admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? = forbidden()
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalTextEditPreparation,
            admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? = forbidden()
        override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation = forbidden()
        override suspend fun inspect(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation = forbidden()
        override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate = forbidden()
        override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = forbidden()
        override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = forbidden()
        override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft,
            journal: CreateAttemptJournal): CreateSubmissionObservation = forbidden()
        override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft): CreateSubmissionObservation = forbidden()
    }

    private class Fixture(private val base: Context) {
        private val token = UUID.randomUUID().toString()
        private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "text-recovery-$token-$name"
                preferences.add(isolated)
                return base.getSharedPreferences(isolated, mode)
            }
        }
        private val names = listOf("imports", "attempts", "captures", "creates", "work").map { "text-recovery-$token-$it.db" }
        val target = CalendarScope(AccountId("text-recovery-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("synthetic-text-recovery", 1)
        val store = ImportStore(context, names[0])
        var attempts = TitleUpdateAttemptStore(context, names[1])
        val journal get() = attempts.textJournal
        val captures = LocalCreateStore(context, names[2])
        val creates = CreateAttemptStore(context, names[3])
        val work = SystemCreateWorkStore(context, names[4])
        val projection = Projection()
        val ports = ForbiddenPorts()
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val ready = CompletableDeferred<Unit>()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { ready.complete(Unit) }
            override fun continueWork(network: Boolean) = Unit
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = target.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No service fetch")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No generic dispatch")
        }
        val imports = ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            creates = creates, localCreates = captures, systemCreatesEnabled = work::modeEnabled,
            systemTextsEnabled = work::textsEnabled)
        var beforeRecovery: suspend (TitleUpdateWorkQuery, () -> Boolean) -> Unit = { _, _ -> }
        val queries = mutableListOf<TitleUpdateWorkQuery>()
        val reports = mutableListOf<TitleUpdateOfflineRecoveryProgress>()
        var recoveryPageSize = 1
        val cursors get() = AndroidTitleRecoveryCursorStore(context, "offline-text-proof-cursors")
        lateinit var controller: SystemCreateController

        suspend fun start() {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic text recovery")))
            store.setEnabled(target, true)
            store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true) }
            work.setMode(target, true)
            assertFalse(work.textsEnabled(target))
            rebuild()
            imports.authenticationChanged(true)
            withTimeout(10_000) {
                ready.await()
                while (!imports.state.value.authenticated || !imports.state.value.hasPermission ||
                    imports.state.value.selections.none { it.scope == target && it.enabled && it.available }) delay(10)
            }
        }

        fun rebuild(reopenJournal: Boolean = false) {
            if (reopenJournal) { attempts.close(); attempts = TitleUpdateAttemptStore(context, names[1]) }
            val operations = CoordinatorSystemTextOperations(imports, journal, ports, ports)
            val recovery = SystemTitleRecoveryController(cursors, { error("Guarded recovery is required") }, pageSize = recoveryPageSize,
                guardedRecover = { query, active ->
                    queries += query
                    beforeRecovery(query, active)
                    operations.recoverOffline(query) { requested -> requested == query.scope && active() }.also { reports += it }
                })
            val updates = SystemUpdateController(AndroidTitleRecoveryCursorStore(context, "text-changes"),
                AndroidTitleRecoveryCursorStore(context, "text-inspection"), operations, { error("Offline recovery never wakes network proof") }, 1)
            // SystemCreateController's Android dependency is never called directly. All native
            // operations go through the controlled CalendarProjection owned by ImportCoordinator.
            controller = SystemCreateController(context, imports, AndroidCalendarProjection(context), work,
                captures, creates, ports, jobs, scheduler, textRecovery = recovery, textUpdates = updates)
        }

        fun prepared(event: String, scope: CalendarScope = target, allDay: Boolean = false): TextUpdateAttemptRecord {
            val base = RemoteChange.Upsert(EventId(event), Revision("original"), EventContent("Synthetic original",
                if (allDay) EventTime.AllDay(LocalDate.of(2030, 10, 26), LocalDate.of(2030, 10, 29)) else
                    EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
                "Original notes", "Original room", details = EventDetails()))
            val intent = LocalEditIntent(MutationId("owner-$event"), scope, format, base,
                base.content.copy(title = "Synthetic changed", description = "Changed\r\nnotes", location = "Changed room"))
            val capsule = LocalTextEditPreparation(intent, Instant.parse("2030-10-01T12:00:00Z"),
                AdapterFormat("synthetic-text-capsule", 1), "opaque-capsule-$event")
            return TextUpdateAttemptRecord(TextUpdateAttemptIdentity.requestId(capsule, ports.textUpdateFormat),
                capsule, ports.textUpdateFormat, "opaque-wire-$event")
        }

        suspend fun seed(record: TextUpdateAttemptRecord, unknown: Boolean = false, verified: Boolean = false): TextUpdateVerifiedResult? {
            assertTrue(journal.remember(record) { true })
            if (!unknown && !verified) return null
            assertTrue(journal.claim(record) { true })
            val claimed = record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
            if (!verified) return null
            assertTrue(journal.retainEvidence(claimed, "synthetic-response-evidence") { true })
            val result = TextUpdateVerifiedResult.of(claimed, TextUpdateSubmissionObservation.Verified(
                claimed.copy(protectedEvidence = "synthetic-response-evidence"), record.preparation.intent.base.copy(
                    revision = Revision("verified"), content = record.preparation.intent.content.copy(description = "Changed\nnotes"))))
            assertTrue(journal.retainVerified(result) { true })
            val intent = record.preparation.intent
            store.admitTransaction = { it == target }
            store.transaction(target) { putRemote(intent.base); putProjection(ProjectionIntent(target, intent.base)) }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, intent.base)))
            assertEquals(intent, store.captureLocalEdit(intent))
            assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(record.preparation))
            projection.contents[record.eventId] = intent.content
            return result
        }

        suspend fun assertRestored(record: TextUpdateAttemptRecord, result: TextUpdateVerifiedResult) {
            assertEquals(result, journal.readVerified(target, record.id) { true })
            val completion = journal.readCompletion(target, record.id) { true }
            assertNotNull(completion); assertEquals(result, completion!!.result)
            store.admitTransaction = { it == target }
            assertEquals(TextUpdateAcknowledgementState.RESTORED, store.textUpdateAcknowledgement(target, record.eventId, record.id)!!.state)
            assertNull(store.localEdit(target, record.eventId)); assertNull(store.localTextEditPreparation(target, record.eventId))
            assertEquals(result.event, store.projectedBaseline(target, record.eventId))
            assertEquals(TextUpdateAttemptState.OUTCOME_UNKNOWN, journal.read(target, record.id) { true }!!.state)
            assertEquals("synthetic-response-evidence", journal.read(target, record.id) { true }!!.protectedEvidence)
            assertEquals(result.event.content, projection.contents[record.eventId])
        }

        suspend fun close() {
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); attempts.close(); captures.close(); creates.close(); work.close()
            names.forEach(context::deleteDatabase)
            preferences.toList().forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        try { fixture.start(); fixture.block(); assertEquals(0, fixture.ports.calls) }
        finally { fixture.close() }
    }

    @Test fun verifiedTextProofRestoresBeforeAFailedNativeScanWithTextConsentOff() = fixture {
        val record = prepared("verified")
        val result = seed(record, verified = true)!!
        projection.scanFails = true
        projection.beforeScan = { assertNotNull(journal.readCompletion(target, record.id) { true }) }
        assertFalse(work.textsEnabled(target))
        assertEquals(ImportRunResult.RETRY, controller.run(network = false))
        assertRestored(record, result)
        assertEquals(listOf("ack", "scan"), projection.order)
        assertEquals(1, reports.single().completed); assertEquals(0, reports.single().failed)
        assertEquals(1, projection.writes)
    }

    @Test fun offlineAllDayRecoveryDoesNotPrepareSendOrInspectEvenWithTextConsentOn() = fixture {
        work.setTextsMode(target, true)
        val record = prepared("all-day", allDay = true)
        val result = seed(record, verified = true)!!
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false))
        assertRestored(record, result)
        rebuild(reopenJournal = true)
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false, fresh = false))
        assertRestored(record, result)
        assertEquals(1, projection.writes); assertEquals(1, queries.size)
    }

    @Test fun heldPagesResumeDurablyWithoutStarvingProofOrLoopingAfterExhaustion() = fixture {
        val prepared = prepared("a-prepared"); val unknown = prepared("b-unknown"); val verified = prepared("c-verified")
        seed(prepared); seed(unknown, unknown = true); val result = seed(verified, verified = true)!!
        assertEquals(ImportRunResult.CONTINUE, controller.run(network = false))
        assertEquals(1, reports.single().held); assertEquals(0, projection.writes)
        assertEquals(TitleUpdateWorkCursor(target, prepared.eventId), cursors.read(target))
        rebuild(reopenJournal = true)
        assertEquals(ImportRunResult.CONTINUE, controller.run(network = false, fresh = false))
        assertEquals(TitleUpdateWorkCursor(target, unknown.eventId), cursors.read(target))
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false, fresh = false))
        assertRestored(verified, result); assertTrue(cursors.exhausted(target))
        repeat(3) { assertEquals(ImportRunResult.COMPLETE, controller.run(network = false, fresh = false)) }
        assertEquals(3, queries.size); assertEquals(listOf(1, 1, 0), reports.map { it.held })
        assertEquals(prepared, journal.read(target, prepared.id) { true })
        assertEquals(unknown.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), journal.read(target, unknown.id) { true })
        assertEquals(ImportRunResult.CONTINUE, controller.run(network = false, fresh = true))
        assertNull(queries.last().cursor); assertEquals(1, projection.writes)
    }

    @Test fun foreignAccountAndGenerationProofMetadataNeverEnterTheActiveScopePage() = fixture {
        val local = prepared("same-event")
        val foreign = prepared("same-event", target.copy(account = AccountId("foreign-synthetic-account")))
        val otherGeneration = prepared("other-event", target.copy(generation = 1))
        seed(local, unknown = true); seed(foreign, unknown = true); seed(otherGeneration)
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false))
        assertEquals(1, queries.size); assertEquals(target, queries.single().scope)
        assertEquals(1, reports.single().held); assertEquals(0, projection.captures); assertEquals(0, projection.writes)
        assertEquals(foreign.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), journal.read(foreign.scope, foreign.id) { true })
        assertEquals(otherGeneration, journal.read(otherGeneration.scope, otherGeneration.id) { true })
        assertFalse(cursors.exhausted(foreign.scope)); assertFalse(cursors.exhausted(otherGeneration.scope))
    }

    @Test fun createModeOffDoesNotPageOrAcknowledgeEvenWhenProofIsDurable() = fixture {
        val record = prepared("held-create-mode"); seed(record, verified = true)
        work.setMode(target, false)
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false))
        assertTrue(queries.isEmpty()); assertEquals(0, projection.writes); assertEquals(0, projection.scans)
        assertNull(journal.readCompletion(target, record.id) { true })
        assertNotNull(journal.readVerified(target, record.id) { true })
    }

    @Test fun permissionRevocationDuringRecoveryCannotUseTheEarlierProofLease() = fixture {
        val record = prepared("permission-revoked"); seed(record, verified = true)
        beforeRecovery = { _, _ -> projection.permission = false }
        controller.run(network = false)
        assertTrue(reports.single().inactive); assertNull(cursors.read(target))
        assertEquals(0, projection.writes); assertNull(journal.readCompletion(target, record.id) { true })
        assertNotNull(journal.readVerified(target, record.id) { true })
    }

    @Test fun policyOffOnDuringRecoveryDoesNotRenewTheCapturedEpoch() = fixture {
        val record = prepared("policy-revoked"); seed(record, verified = true)
        beforeRecovery = { _, _ ->
            imports.systemCreatePolicyChanged(target, false)
            imports.systemCreatePolicyChanged(target, true)
        }
        controller.run(network = false)
        assertTrue(reports.single().inactive); assertEquals(0, projection.writes)
        assertNull(journal.readCompletion(target, record.id) { true }); assertNull(cursors.read(target))
        beforeRecovery = { _, _ -> }
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false))
        assertNotNull(journal.readCompletion(target, record.id) { true }); assertEquals(1, projection.writes)
    }

    @Test fun cancellationReturningFromRecoveryHookCannotAdvanceOrAcknowledge() = fixture {
        val record = prepared("cancelled"); seed(record, verified = true)
        beforeRecovery = { _, _ -> currentCoroutineContext().cancel() }
        var cancellations = 0; var ordinaryFailures = 0; var returns = 0
        supervisorScope {
            val child = async {
                try { controller.run(network = false); returns++ }
                catch (_: CancellationException) { cancellations++ }
                catch (_: Exception) { ordinaryFailures++ }
            }
            try { child.await() } catch (_: CancellationException) { }
        }
        assertEquals(1, cancellations); assertEquals(0, ordinaryFailures); assertEquals(0, returns)
        assertEquals(0, projection.writes); assertEquals(0, projection.scans)
        assertNull(cursors.read(target)); assertNull(journal.readCompletion(target, record.id) { true })
    }

    @Test fun multiPageRecoveryResumesAfterNativeWriteWithoutRepeatingWritesOrSendingUnknownRequests() = fixture {
        recoveryPageSize = 25
        rebuild()
        val records = (0 until 67).map { prepared("volume-${it.toString().padStart(3, '0')}") }
        val unknownIndices = setOf(7, 32, 61)
        val results = records.mapIndexed { index, record ->
            if (index in unknownIndices) { seed(record, unknown = true); null }
            else seed(record, verified = true)!!
        }
        assertEquals(ImportRunResult.CONTINUE, controller.run(network = false))
        val savedCursor = TitleUpdateWorkCursor(target, records[24].eventId)
        assertEquals(savedCursor, cursors.read(target)); assertEquals(24, projection.writes)
        projection.afterWrite = {
            if (projection.writes == 31) { currentCoroutineContext().cancel(); yield() }
        }
        supervisorScope {
            val interrupted = async { controller.run(network = false, fresh = false) }
            try { interrupted.await(); fail("Cancellation after native write must escape") }
            catch (_: CancellationException) { }
        }
        assertEquals(31, projection.writes)
        assertEquals(savedCursor, cursors.read(target)) // The partial page cannot advance discovery.
        val interrupted = records[31]
        assertEquals(results[31], journal.readVerified(target, interrupted.id) { true })
        assertNull(journal.readCompletion(target, interrupted.id) { true })
        store.admitTransaction = { it == target }
        assertEquals(TextUpdateAcknowledgementState.PENDING,
            store.textUpdateAcknowledgement(target, interrupted.eventId, interrupted.id)!!.state)

        projection.afterWrite = {}
        rebuild(reopenJournal = true)
        var passes = 0
        do {
            val progress = controller.run(network = false, fresh = false)
            assertTrue(progress == ImportRunResult.CONTINUE || progress == ImportRunResult.COMPLETE)
            passes++
            assertTrue("Recovery must exhaust its bounded pages", passes <= 4)
        } while (progress != ImportRunResult.COMPLETE)
        assertTrue(cursors.exhausted(target))
        records.forEachIndexed { index, record ->
            if (index in unknownIndices) {
                assertEquals(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), journal.read(target, record.id) { true })
                assertNull(journal.readVerified(target, record.id) { true })
                assertNull(journal.readCompletion(target, record.id) { true })
            } else assertRestored(record, results[index]!!)
        }
        assertEquals(64, projection.writes); assertEquals(0, ports.calls)
        val pages = queries.size
        repeat(3) { assertEquals(ImportRunResult.COMPLETE, controller.run(network = false, fresh = false)) }
        assertEquals(pages, queries.size); assertEquals(64, projection.writes)
        rebuild(reopenJournal = true)
        assertEquals(ImportRunResult.COMPLETE, controller.run(network = false, fresh = false))
        assertEquals(pages, queries.size); assertEquals(64, projection.writes)
    }
}
