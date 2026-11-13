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
import java.time.ZoneId
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated SQL plus a controlled native boundary. No Provider, account, network or crypto. */
@RunWith(AndroidJUnit4::class)
class SystemTimeOwnerGatewayDeviceTest {
    private class Projection : CalendarProjection {
        override var admitOperation: () -> Boolean = { false }
        override val timeUpdateAcknowledgementProtocol = 1
        override val capturedHandoffProtocol get() = originProtocol
        override val capturedAcknowledgementProtocol = 1
        override val capturedAttachmentProtocol = 1
        var originProtocol: Int? = 1
        var permission = true
        var current: EventContent? = null
        var dirty = true
        var captures = 0
        var acknowledgements = 0
        var inspections = 0
        var loseAcknowledgementReply = false
        var beforeCapture: suspend () -> Unit = {}
        val capturedBases = mutableListOf<RemoteChange.Upsert>()
        private var applied: TimeUpdateAcknowledgementReceipt? = null
        override fun hasPermissions() = permission
        override fun accountFor(accountId: AccountId) = Account("synthetic-time-gateway", "fixture")
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary) = EnsuredCalendar(1, false)
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary, systemCreates: Boolean): EnsuredCalendar {
            check(admitOperation()); return EnsuredCalendar(1, false)
        }
        override suspend fun makeCalendarReadOnly(scope: CalendarScope) = Unit
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No incoming projection")
        override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            check(admitOperation()); beforeCapture(); captures++; capturedBases += base
            return current?.let { OwnedLocalEditSnapshot(1, 1, it) }
        }
        override suspend fun captureCapturedHandoffCurrent(frame: CapturedCreateHandoffFrame): CapturedHandoffCurrentObservation {
            check(admitOperation()); beforeCapture(); captures++
            return current?.let { CapturedHandoffCurrentObservation.Supported(CapturedHandoffCurrent(frame, it, false)) }
                ?: CapturedHandoffCurrentObservation.Unresolved
        }
        override suspend fun acknowledgeTimeUpdate(receipt: TimeUpdateAcknowledgementReceipt): AppliedTimeUpdateAcknowledgement? {
            check(admitOperation()); acknowledgements++
            if (!dirty || current != receipt.intent.content || applied != null) return null
            applied = receipt.snapshot(); current = receipt.projection.change.content; dirty = false
            if (loseAcknowledgementReply) throw java.io.IOException("Synthetic lost native reply")
            return AppliedTimeUpdateAcknowledgement(receipt)
        }
        override suspend fun inspectTimeUpdateAcknowledgement(receipt: TimeUpdateAcknowledgementReceipt): AppliedTimeUpdateAcknowledgement? {
            check(admitOperation()); inspections++
            return if (applied == receipt) AppliedTimeUpdateAcknowledgement(receipt) else null
        }
    }

    private class Fixture(private val baseContext: Context, val transferred: Boolean, explicitTimePolicy: Boolean,
        private val seedCapsule: Boolean, private val seedAttempt: Boolean) {
        private val token = UUID.randomUUID().toString()
        private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "time-gateway-$token-$name"; preferences.add(isolated)
                return baseContext.getSharedPreferences(isolated, mode)
            }
        }
        private val names = listOf("imports", "attempts", "captures", "creates").map { "time-gateway-$it-$token.db" }
        val target = CalendarScope(AccountId("time-gateway-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("time-gateway-import", 1)
        var currentFormat = format
        val oldTime = EventTime.Timed(Instant.parse("2030-10-26T00:30:00Z"),
            Instant.parse("2030-10-26T02:30:00Z"), ZoneId.of("Europe/Paris"))
        val newTime = oldTime.copy(start = Instant.parse("2030-10-27T00:30:00Z"), end = Instant.parse("2030-10-27T03:30:00Z"))
        val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("before"),
            EventContent("Unchanged\r\ntitle", oldTime, "Unchanged\r\nnotes", "Unchanged\r\nroom", details = EventDetails()))
        val intent = LocalEditIntent(MutationId("local-time-edit"), target, format, base, base.content.copy(time = newTime))
        val timestamp = Instant.parse("2030-10-01T12:00:00Z")
        val preparation = LocalTimeEditPreparation(intent, timestamp, AdapterFormat("time-gateway-capsule", 1), "synthetic-time-capsule")
        val wireFormat = AdapterFormat("time-gateway-wire", 1)
        val record = TimeUpdateAttemptRecord(TimeUpdateAttemptIdentity.requestId(preparation, wireFormat), preparation, wireFormat, "synthetic-time-wire")
        val observed = base.copy(revision = Revision("after"), content = intent.content.copy(title = "Unchanged\ntitle",
            description = "Unchanged\nnotes", location = "Unchanged\nroom"))
        val store = ImportStore(context, names[0])
        val attempts = TitleUpdateAttemptStore(context, names[1])
        val journal get() = attempts.timeJournal
        val captures = LocalCreateStore(context, names[2])
        val creates = CreateAttemptStore(context, names[3])
        val projection = Projection()
        val authMutex = Mutex()
        var inAuth = false
        var accountHook: suspend () -> Unit = {}
        var timeEnabled = true
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
            override val format get() = currentFormat
            override suspend fun currentAccount(): AccountId {
                check(!inAuth) { "Local source must not re-enter authentication" }
                accountHook(); return authMutex.withLock { target.account }
            }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No service fetch")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No generic dispatch")
        }
        val coordinator = if (explicitTimePolicy) ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            creates = if (transferred) creates else null, localCreates = if (transferred) captures else null,
            systemCreatesEnabled = { it == target && createEnabled }, systemTitlesEnabled = { it == target },
            systemTextsEnabled = { it == target }, systemTimesEnabled = { it == target && timeEnabled }) else
            ImportCoordinator(context, store, remote, projection, jobs, scheduler,
                systemCreatesEnabled = { it == target && createEnabled }, systemTitlesEnabled = { it == target }, systemTextsEnabled = { it == target })
        val owners get() = coordinator.localTimeOwners
        var initialFrame: CapturedCreateHandoffFrame? = null
        var originalSubmission: LocalCreateSubmission? = null
        fun allowSql() { store.admitTransaction = { it == target } }
        fun toggleTime(value: Boolean) { timeEnabled = value; coordinator.systemCreatePolicyChanged(target, value) }
        fun edit(value: EventContent) { projection.current = value; projection.dirty = true }
        fun capsule(value: LocalEditIntent = intent, timestamp: Instant = preparation.timestamp,
            format: AdapterFormat = preparation.format, payload: String = preparation.protectedPayload) =
            LocalTimeEditPreparation(value, timestamp, format, payload)
        suspend fun start() {
            allowSql()
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic time gateway")))
            store.setEnabled(target, true)
            store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true); putRemote(base); putProjection(ProjectionIntent(target, base)) }
            if (transferred) seedTransfer() else {
                assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
                assertEquals(intent, store.captureLocalEdit(intent))
            }
            edit(intent.content)
            if (seedCapsule) assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(preparation))
            if (seedAttempt) assertTrue(journal.remember(record) { it == target })
            coordinator.authenticationChanged(true); awaitReady()
        }
        suspend fun awaitReady() = withTimeout(10_000) {
            ready.await()
            while (!coordinator.state.value.authenticated || !coordinator.state.value.hasPermission ||
                coordinator.state.value.selections.none { it.scope == target && it.enabled && it.available }) delay(10)
        }
        private suspend fun seedTransfer() {
            val capture = LocalCreateIntent(LocalCreateReservation(MutationId("original-capture"), LocalEventId(UUID(0, 42).toString()),
                target, format, timestamp), base.content)
            val submitted = LocalCreateSubmission(LocalCreateSubmissionReservation(capture, MutationId(UUID(0, 43).toString()),
                AdapterFormat("synthetic-create-wire", 1)), "original-create-wire")
            captures.admitCapture = { it == target }
            assertTrue(captures.reserve(capture.reservation)); assertEquals(capture, captures.retain(capture))
            val permit = checkNotNull(captures.reserveSubmission(submitted.reservation))
            assertEquals(submitted, captures.retainSubmission(permit, submitted))
            creates.admitDispatch = { it == target }; creates.admitAssociation = { it == target }
            val identity = creates.rememberCaptured(submitted)
            val pending = CreateAttemptRecord(identity.attempt.id, target, identity.attempt.format, submitted.protectedPayload)
            assertTrue(creates.claimCaptured(pending, identity))
            val binding = creates.bindCaptured(pending.copy(state = CreateAttemptState.OUTCOME_UNKNOWN), identity,
                CreateSubmissionObservation.Verified(identity.attempt, base))
            val attachment = creates.prepareCapturedAttachment(binding)
            val baseline = CapturedCreateBaseline(attachment, base)
            assertTrue(store.retainCapturedBaseline(baseline, AppliedCapturedCreateAttachment(attachment)))
            val ack = store.reserveCapturedAcknowledgement(CapturedCreateAcknowledgementReceipt(baseline, UUID.randomUUID().toString())).record.receipt
            store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(ack))
            val handoff = store.reserveCapturedHandoff(CapturedCreateHandoffReceipt(ack, UUID.randomUUID().toString()))
            val frame = store.retainCapturedHandoff(handoff.frame.receipt, CapturedCreateCleanWitness(ack)).frame
            initialFrame = frame; originalSubmission = submitted
            assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(CapturedHandoffCurrent(frame, intent.content, false), intent.id))
            creates.admitDispatch = { error("No second CREATE") }; captures.admitCapture = { false }
        }
        suspend fun submit(port: Port, value: TimeUpdateAttemptRecord = record,
            admission: (CalendarScope) -> Boolean = coordinator.systemTimeRunAdmission(target)) =
            owners.submit(value, journal, port, admission, coordinator.systemTimeEvidenceAdmission(target))
        suspend fun verified(port: Port): TimeUpdateVerifiedResult {
            val observation = submit(port) as? TimeUpdateSubmissionObservation.Verified
            assertNotNull(observation)
            val result = TimeUpdateVerifiedResult.of(record.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN), observation!!)
            assertTrue(journal.retainVerified(result, coordinator.systemTimeEvidenceAdmission(target)))
            return result
        }
        fun operations(port: Port) = CoordinatorSystemTimeOperations(coordinator, journal, port)
        suspend fun close() {
            accountHook = {}; coordinator.authenticationChanged(false); jobs.coroutineContext.job.cancelAndJoin()
            attempts.close(); store.close(); captures.close(); creates.close(); names.forEach(context::deleteDatabase)
            preferences.toList().forEach { baseContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        }
    }

    private class Port(private val fixture: Fixture) : CalendarTimeUpdateSubmissionPort, CalendarLocalTimePreparationPort {
        override val timeUpdateFormat get() = fixture.wireFormat
        override val localTimeFormat get() = fixture.preparation.format
        var preparations = 0
        var wires = 0
        var calls = 0
        var sends = 0
        var inspections = 0
        var beforeClaim: suspend () -> Unit = {}
        var afterSend: suspend () -> Unit = {}
        var swallowCancellation = false
        var savedSending: ((CalendarScope) -> Boolean)? = null
        var savedEvidence: ((CalendarScope) -> Boolean)? = null
        var savedSource: (suspend (CalendarScope) -> Boolean)? = null
        var savedPreparation: ((CalendarScope) -> Boolean)? = null
        var savedWire: ((CalendarScope) -> Boolean)? = null
        var makeCapsule: (LocalEditIntent, Instant) -> LocalTimeEditPreparation = { intent, timestamp -> fixture.capsule(intent, timestamp) }
        var makeWire: (MutationId, LocalTimeEditPreparation) -> TimeUpdateAttemptRecord = { id, preparation ->
            TimeUpdateAttemptRecord(id, preparation, timeUpdateFormat, fixture.record.protectedPayload) }
        override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant): LocalTimeEditPreparation =
            error("Guarded preparation is required")
        override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant, admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? {
            preparations++; savedPreparation = admit
            return if (admit(intent.scope)) makeCapsule(intent, timestamp) else null
        }
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalTimeEditPreparation,
            admit: (CalendarScope) -> Boolean): TimeUpdateAttemptRecord? {
            wires++; savedWire = admit
            return if (admit(preparation.intent.scope)) makeWire(id, preparation) else null
        }
        override suspend fun inspect(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
            inspections++; error("No service inspection in this fixture")
        }
        override suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation = fixture.authMutex.withLock {
            calls++; fixture.inAuth = true
            savedSending = admit; savedSource = qualifySource; savedEvidence = admitEvidence
            try {
                val target = fixture.target
                if (!admit(target) || !qualifySource(target)) return@withLock TimeUpdateSubmissionObservation.Unknown
                beforeClaim()
                if (swallowCancellation) return@withLock TimeUpdateSubmissionObservation.Unknown
                if (!qualifySource(target) || !journal.claim(record, admit)) return@withLock TimeUpdateSubmissionObservation.Unknown
                val unknown = record.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN)
                if (!qualifySource(target) || !admit(target)) return@withLock TimeUpdateSubmissionObservation.Unknown
                sends++; afterSend()
                if (!journal.retainEvidence(unknown, "synthetic-time-response", admitEvidence)) return@withLock TimeUpdateSubmissionObservation.Unknown
                TimeUpdateSubmissionObservation.Verified(unknown.copy(protectedEvidence = "synthetic-time-response"), fixture.observed)
            } finally { fixture.inAuth = false }
        }
    }
    private fun fixture(transferred: Boolean = false, explicitTimePolicy: Boolean = true,
        seedCapsule: Boolean = true, seedAttempt: Boolean = true, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), transferred, explicitTimePolicy, seedCapsule, seedAttempt)
        try { fixture.start(); fixture.block() } finally { fixture.close() }
    }
    private fun assertPreparation(expected: LocalTimeEditPreparation, actual: LocalTimeEditPreparation?) {
        assertNotNull(actual); actual!!
        assertEquals(expected.intent, actual.intent); assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.format, actual.format); assertEquals(expected.protectedPayload, actual.protectedPayload)
    }

    @Test fun timePolicyDefaultsToDenyEvenWhenCreateTitleAndTextAreEnabled() = fixture(explicitTimePolicy = false) {
        assertTrue(coordinator.systemTitleRunAdmission(target)(target)); assertTrue(coordinator.systemTextRunAdmission(target)(target))
        val lease = coordinator.systemTimeRunAdmission(target)
        assertFalse(lease(target)); assertTrue(coordinator.systemTimeProofAdmission(target)(target))
        assertNull(owners.observe(target, base.eventId, intent.id, lease))
        val port = Port(this); assertEquals(TimeUpdateSubmissionObservation.Unknown, submit(port))
        assertEquals(0, port.calls); assertEquals(record, journal.read(target, record.id) { true })
    }

    @Test fun explicitTimeConsentAndCreateConsentAreBothRequired() = fixture {
        toggleTime(false)
        assertTrue(coordinator.systemTitleRunAdmission(target)(target)); assertTrue(coordinator.systemTextRunAdmission(target)(target))
        assertNull(owners.observe(target, base.eventId, intent.id, coordinator.systemTimeRunAdmission(target)))
        toggleTime(true); createEnabled = false
        assertNull(owners.observe(target, base.eventId, intent.id, coordinator.systemTimeRunAdmission(target)))
        createEnabled = true
        assertNotNull(owners.observe(target, base.eventId, intent.id, coordinator.systemTimeRunAdmission(target)))
    }

    @Test fun bareOwnerRetainsExactFirstCapsuleAndRechecksFullNativeContents() = fixture(seedCapsule = false, seedAttempt = false) {
        val lease = coordinator.systemTimeRunAdmission(target)
        val bare = owners.observe(target, base.eventId, intent.id, lease)!!
        assertEquals(intent, bare.intent); assertNull(bare.preparation)
        assertPreparation(preparation, owners.retain(preparation, lease)); assertTrue(owners.qualify(preparation, lease))
        for (other in listOf(capsule(timestamp = timestamp.plusSeconds(1)), capsule(format = preparation.format.copy(version = 2)),
            capsule(payload = "other-capsule"))) assertNull(owners.retain(other, lease))
        assertTrue(projection.capturedBases.all { it == base })
        edit(intent.content.copy(location = "Later native room"))
        assertFalse(owners.qualify(preparation, lease)); assertNull(owners.observe(target, base.eventId, intent.id, lease))
        allowSql(); assertEquals(intent, store.localEdit(target, base.eventId)); assertPreparation(preparation, store.localTimeEditPreparation(target, base.eventId))
        assertEquals(0, projection.acknowledgements)
    }

    @Test fun permissionAndSessionLossPermanentlyCloseOriginalRunAndEvidenceLeases() = fixture {
        val run = coordinator.systemTimeRunAdmission(target)
        val proof = coordinator.systemTimeProofAdmission(target)
        val evidence = coordinator.systemTimeEvidenceAdmission(target)
        assertTrue(run(target)); projection.permission = false
        assertFalse(run(target)); assertFalse(proof(target)); assertTrue(evidence(target))
        projection.permission = true; assertFalse(run(target)); assertFalse(proof(target))
        assertTrue(coordinator.systemTimeRunAdmission(target)(target))
        coordinator.authenticationChanged(false)
        assertFalse(evidence(target)); coordinator.authenticationChanged(true); awaitReady()
        assertFalse(run(target)); assertFalse(proof(target)); assertFalse(evidence(target))
        assertTrue(coordinator.systemTimeRunAdmission(target)(target))
    }

    @Test fun checkpointFormatAndActualCalendarAvailabilityCannotBeBorrowedFromOwnerIdentity() = fixture {
        val lease = coordinator.systemTimeRunAdmission(target)
        assertNull(owners.observe(target.copy(generation = 1), base.eventId, intent.id) { true })
        assertNull(owners.observe(target, base.eventId, MutationId("foreign-owner"), lease))
        currentFormat = format.copy(version = 2)
        assertFalse(owners.qualify(preparation, lease)); currentFormat = format
        allowSql(); store.transaction(target) { checkpoint = PageCheckpoint(target, format.copy(version = 2)) }
        assertFalse(owners.qualify(preparation, lease))
        allowSql(); store.transaction(target) { checkpoint = PageCheckpoint(target, format) }; store.remember(target.account, emptyList())
        assertNull(owners.observe(target, base.eventId, intent.id, lease))
        assertEquals(0, projection.acknowledgements)
    }

    @Test fun importLockPrecedesAuthenticationAndSourceCallbackNeverReentersEitherLock() = fixture {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        accountHook = { entered.complete(Unit); release.await() }
        coroutineScope {
            val importing = async(start = CoroutineStart.UNDISPATCHED) { coordinator.configureSystemCalendar(target) }
            entered.await(); val port = Port(this@fixture)
            val sending = async(start = CoroutineStart.UNDISPATCHED) { submit(port) }
            assertEquals(0, port.calls); release.complete(Unit)
            withTimeout(10_000) { assertTrue(importing.await()); assertTrue(sending.await() is TimeUpdateSubmissionObservation.Verified) }
            assertEquals(1, port.calls); assertEquals(1, port.sends)
        }
    }

    @Test fun revocationWhileQueuedForImportLockCannotRenewItsOriginalEpoch() = fixture {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        accountHook = { entered.complete(Unit); release.await() }
        coroutineScope {
            val importing = async(start = CoroutineStart.UNDISPATCHED) { coordinator.configureSystemCalendar(target) }
            entered.await(); val port = Port(this@fixture); val lease = coordinator.systemTimeRunAdmission(target)
            val sending = async(start = CoroutineStart.UNDISPATCHED) { submit(port, admission = lease) }
            toggleTime(false); toggleTime(true); release.complete(Unit)
            withTimeout(10_000) { importing.await(); assertEquals(TimeUpdateSubmissionObservation.Unknown, sending.await()) }
            assertEquals(0, port.calls); assertEquals(record, journal.read(target, record.id) { true })
        }
    }

    @Test fun sourceFailureBeforeClaimStaysClosedEvenWhenNativeContentsReturn() = fixture {
        val port = Port(this)
        port.beforeClaim = {
            edit(intent.content.copy(time = newTime.copy(end = newTime.end.plusSeconds(60))))
            assertFalse(port.savedSource!!(target)); edit(intent.content)
            assertFalse(port.savedSource!!(target)); assertFalse(port.savedSending!!(target))
        }
        assertEquals(TimeUpdateSubmissionObservation.Unknown, submit(port))
        assertEquals(0, port.sends); assertEquals(record, journal.read(target, record.id) { true })
    }

    @Test fun retainedCallbacksExpireAndUnknownHistoryCannotResend() = fixture {
        val port = Port(this); assertTrue(submit(port) is TimeUpdateSubmissionObservation.Verified)
        assertFalse(port.savedSending!!(target)); assertFalse(port.savedEvidence!!(target)); assertFalse(port.savedSource!!(target))
        val unknown = journal.read(target, record.id) { true }!!
        assertEquals(TimeUpdateSubmissionObservation.Unknown, submit(port, unknown))
        assertEquals(TimeUpdateSubmissionObservation.Unknown, submit(port))
        assertEquals(1, port.calls); assertEquals(1, port.sends); assertEquals(unknown, journal.read(target, record.id) { true })
    }

    @Test fun capsuleBaseAndWireSubstitutionCannotReachSubmissionPort() = fixture {
        val foreignBase = intent.copy(base = base.copy(revision = Revision("other-base")))
        val foreignCapsule = capsule(foreignBase)
        val variants = listOf(record.copy(protectedPayload = "other-wire"), record.copy(preparation = capsule(payload = "other-capsule")),
            record.copy(id = TimeUpdateAttemptIdentity.requestId(foreignCapsule, wireFormat), preparation = foreignCapsule))
        val port = Port(this)
        for (value in variants) assertEquals(TimeUpdateSubmissionObservation.Unknown, submit(port, value))
        assertEquals(0, port.calls); assertEquals(record, journal.read(target, record.id) { true })
        allowSql(); assertPreparation(preparation, store.localTimeEditPreparation(target, base.eventId))
    }

    @Test fun operationsRejectSubstitutedPreparedFullBaseBeforeRetainingOrBuildingWire() = fixture(seedCapsule = false, seedAttempt = false) {
        val port = Port(this)
        port.makeCapsule = { value, stamp -> capsule(value.copy(base = value.base.copy(revision = Revision("substituted-original"))), stamp) }
        var proofs = 0
        assertFalse(com.patmanak.proton.andcalendarbridge.data.outgoing.LegacyTimeFixtureOperations(coordinator, journal, port, port).process(target, RetainedLocalChangeRef(base.eventId, intent.id, MutationKind.UPDATE, LocalEditState.PAUSED),
            MutationId("unused-traversal-id"), timestamp, coordinator.systemTimeRunAdmission(target)) { proofs++ })
        assertEquals(1, port.preparations); assertEquals(0, port.wires); assertEquals(0, port.sends); assertEquals(0, proofs)
        assertFalse(port.savedPreparation!!(target))
        allowSql(); assertNull(store.localTimeEditPreparation(target, base.eventId)); assertNull(journal.read(target, record.id) { true })
        val wirePort = Port(this)
        wirePort.makeWire = { _, sealed -> TimeUpdateAttemptRecord(MutationId("substituted-request"), sealed, wireFormat, "other-wire") }
        assertFalse(operations(wirePort).process(target, RetainedLocalChangeRef(base.eventId, intent.id, MutationKind.UPDATE, LocalEditState.PAUSED),
            MutationId("unused-second-traversal-id"), timestamp, coordinator.systemTimeRunAdmission(target)) { proofs++ })
        assertEquals(1, wirePort.preparations); assertEquals(1, wirePort.wires); assertEquals(0, wirePort.sends); assertEquals(0, proofs)
        assertFalse(wirePort.savedWire!!(target)); assertNull(journal.read(target, record.id) { true })
        allowSql(); assertPreparation(preparation, store.localTimeEditPreparation(target, base.eventId))
    }

    @Test fun operationsComposePreparedWireVerifiedResultNativeReceiptRestorationAndCompletion() = fixture(seedCapsule = false, seedAttempt = false) {
        val port = Port(this); var proofs = 0
        assertFalse(com.patmanak.proton.andcalendarbridge.data.outgoing.LegacyTimeFixtureOperations(coordinator, journal, port, port).process(target, RetainedLocalChangeRef(base.eventId, intent.id, MutationKind.UPDATE, LocalEditState.PAUSED),
            MutationId("unused-traversal-id"), timestamp, coordinator.systemTimeRunAdmission(target)) { proofs++ })
        assertEquals(1, proofs); assertEquals(1, port.preparations); assertEquals(1, port.wires); assertEquals(1, port.sends)
        assertFalse(port.savedPreparation!!(target)); assertFalse(port.savedWire!!(target)); assertFalse(port.savedSending!!(target))
        assertFalse(port.savedEvidence!!(target)); assertFalse(port.savedSource!!(target))
        assertEquals(observed.content, projection.current); assertFalse(projection.dirty); assertEquals(1, projection.acknowledgements)
        val result = journal.readVerified(target, record.id) { true }!!
        assertEquals(result, journal.readCompletion(target, record.id) { true }!!.result)
        assertEquals(TimeUpdateAttemptState.OUTCOME_UNKNOWN, journal.read(target, record.id) { true }!!.state)
        allowSql(); assertEquals(observed, store.projectedBaseline(target, base.eventId)); assertNull(store.localEdit(target, base.eventId))
        assertNull(store.localTimeEditPreparation(target, base.eventId)); assertEquals(TimeUpdateAcknowledgementState.RESTORED,
            store.timeUpdateAcknowledgement(target, base.eventId, record.id)!!.state)
    }

    @Test fun timeConsentLossAfterSendRetainsFactsAndSeparateProofLeaseCompletesOffline() = fixture {
        val port = Port(this); val run = coordinator.systemTimeRunAdmission(target)
        port.afterSend = { toggleTime(false) }
        val observation = submit(port, admission = run) as? TimeUpdateSubmissionObservation.Verified
        assertNotNull(observation)
        val result = TimeUpdateVerifiedResult.of(record.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN), observation!!)
        assertTrue(journal.retainVerified(result, coordinator.systemTimeEvidenceAdmission(target)))
        assertFalse(owners.acknowledge(result, journal, run)); assertEquals(0, projection.acknowledgements)
        val proof = coordinator.systemTimeProofAdmission(target)
        assertTrue(owners.acknowledge(result, journal, proof)); assertTrue(owners.complete(result, journal, proof))
        assertEquals(result, journal.readCompletion(target, record.id, proof)!!.result)
        assertEquals(1, port.sends); assertEquals(1, projection.acknowledgements)
    }

    @Test fun fullyHandedCreateRequiresCompleteOriginAndAdvancesWithoutAnotherCreate() = fixture(transferred = true) {
        val run = coordinator.systemTimeRunAdmission(target)
        assertNotNull(owners.observe(target, base.eventId, intent.id, run))
        projection.originProtocol = null; assertFalse(owners.qualify(preparation, run)); projection.originProtocol = 1
        val port = Port(this); val result = verified(port); val proof = coordinator.systemTimeProofAdmission(target)
        assertTrue(owners.acknowledge(result, journal, proof)); assertTrue(owners.complete(result, journal, proof))
        allowSql(); val receipt = store.timeUpdateAcknowledgement(target, base.eventId, record.id)!!.receipt
        assertEquals(initialFrame, receipt.handoff)
        assertEquals(observed, store.capturedHandoff(target, base.eventId)!!.frame.base)
        val original = originalSubmission!!; captures.admitCapture = { it == target }
        assertEquals(original, captures.readSubmission(target, original.reservation.intent.reservation.localId))
        assertEquals(initialFrame!!.receipt.acknowledgement.baseline.receipt.binding,
            creates.capturedBinding(CapturedCreateDispatchIdentity.of(original)))
        assertEquals(1, port.sends); assertEquals(1, projection.acknowledgements)
    }

    @Test fun interruptedPendingReceiptInspectsOnlyAndPreservesLaterNativeAndSqlEdits() = fixture {
        val port = Port(this); val result = verified(port)
        projection.loseAcknowledgementReply = true
        assertFalse(owners.acknowledge(result, journal, coordinator.systemTimeProofAdmission(target)))
        allowSql(); assertEquals(TimeUpdateAcknowledgementState.PENDING, store.timeUpdateAcknowledgement(target, base.eventId, record.id)!!.state)
        assertEquals(base, store.projectedBaseline(target, base.eventId)); assertEquals(1, projection.acknowledgements)
        val later = observed.content.copy(time = newTime.copy(end = newTime.end.plusSeconds(120)))
        edit(later); toggleTime(false)
        val progress = operations(port).recoverOffline(TitleUpdateWorkQuery(target), coordinator.systemTimeProofAdmission(target))
        assertEquals(1, progress.completed); assertEquals(0, progress.failed)
        assertEquals(later, projection.current); assertTrue(projection.dirty)
        assertEquals(1, projection.acknowledgements); assertEquals(1, projection.inspections); assertEquals(1, port.sends); assertEquals(0, port.inspections)
        allowSql(); assertEquals(observed, store.projectedBaseline(target, base.eventId))
        val next = intent.copy(id = MutationId("later-time-owner"), base = observed, content = later)
        assertEquals(next, store.captureLocalEdit(next))
        val nextCapsule = capsule(next, timestamp.plusSeconds(1))
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(nextCapsule))
        val proof = coordinator.systemTimeProofAdmission(target)
        assertTrue(owners.acknowledge(result, journal, proof)); assertTrue(owners.complete(result, journal, proof))
        allowSql(); assertEquals(next, store.localEdit(target, base.eventId)); assertPreparation(nextCapsule, store.localTimeEditPreparation(target, base.eventId))
        assertEquals(later, projection.current); assertTrue(projection.dirty); assertEquals(1, projection.acknowledgements)
    }
}
