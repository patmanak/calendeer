// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
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
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated SQL/Provider and a synthetic transport. No session, HTTP or cryptographic claim. */
@RunWith(AndroidJUnit4::class)
class SystemTextOwnerGatewayDeviceTest {
    private class Projection(private val source: AndroidCalendarProjection) : CalendarProjection by source {
        var acknowledgements = 0
        var inspections = 0
        var projectionInspections = 0
        var loseAcknowledgementReply = false
        override suspend fun acknowledgeTextUpdate(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? {
            acknowledgements++
            val witness = source.acknowledgeTextUpdate(receipt)
            if (loseAcknowledgementReply) throw java.io.IOException("Synthetic lost Provider reply")
            return witness
        }
        override suspend fun inspectTextUpdateAcknowledgement(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? {
            inspections++; return source.inspectTextUpdateAcknowledgement(receipt)
        }
        override suspend fun inspectProjectionReceipt(receipt: ProjectionReceipt): AppliedProjectionWitness? {
            projectionInspections++; return source.inspectProjectionReceipt(receipt)
        }
    }
    private class Fixture(val allDay: Boolean, val transferred: Boolean, val redundantIncoming: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        private val names = listOf("imports", "attempts", "captures", "creates").map { "text-gateway-$it-$token.db" }
        val target = CalendarScope(AccountId("text-gateway-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("text-gateway-import", 1)
        val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("before"), EventContent("Original",
            if (allDay) EventTime.AllDay(LocalDate.of(2030, 10, 26), LocalDate.of(2030, 10, 29)) else
                EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            "Original description", "Original room", details = EventDetails()))
        val intent = LocalEditIntent(MutationId("local-edit"), target, format, base,
            base.content.copy(title = "Changed title", description = "Changed\r\nnotes", location = "Changed room"))
        val preparation = LocalTextEditPreparation(intent, Instant.parse("2030-10-01T12:00:00Z"),
            AdapterFormat("text-gateway-capsule", 1), "synthetic-capsule")
        private val wireFormat = AdapterFormat("text-gateway-wire", 1)
        val record = TextUpdateAttemptRecord(TextUpdateAttemptIdentity.requestId(preparation, wireFormat), preparation,
            wireFormat, "synthetic-wire")
        val observed = base.copy(revision = Revision("after"), content = intent.content.copy(description = "Changed\nnotes"))
        val store = ImportStore(context, names[0])
        val attempts = TitleUpdateAttemptStore(context, names[1])
        val journal get() = attempts.textJournal
        val captures = LocalCreateStore(context, names[2])
        val creates = CreateAttemptStore(context, names[3])
        val native = AndroidCalendarProjection(context)
        val projection = Projection(native)
        val authMutex = Mutex()
        var inAuth = false
        var accountHook: suspend () -> Unit = {}
        var textEnabled = true
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val scheduled = CompletableDeferred<Unit>()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { scheduled.complete(Unit) }
            override fun continueWork(network: Boolean) = Unit
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId {
                check(!inAuth) { "Local source must not re-enter authentication" }
                accountHook()
                return authMutex.withLock { target.account }
            }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No service fetch")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No service dispatch")
        }
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            creates = if (transferred) creates else null, localCreates = if (transferred) captures else null,
            systemCreatesEnabled = { it == target }, systemTextsEnabled = { it == target && textEnabled })
        val owners get() = coordinator.localTextOwners
        var calendar = -1L
        var row = -1L
        var initialFrame: CapturedCreateHandoffFrame? = null
        var originalSubmission: LocalCreateSubmission? = null
        var redundantReceipt: ProjectionReceipt? = null
        fun uri(value: Uri = Events.CONTENT_URI): Uri = native.accountFor(target.account).let { account ->
            value.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        }
        fun allowSql() { store.admitTransaction = { it == target } }
        fun toggleText(value: Boolean) { textEnabled = value; coordinator.systemCreatePolicyChanged(target, value) }
        fun edit(content: EventContent) {
            assertEquals(1, context.contentResolver.update(uri(), ContentValues().apply {
                put(Events.TITLE, content.title); put(Events.DESCRIPTION, content.description)
                put(Events.EVENT_LOCATION, content.location); put(Events.DIRTY, 1)
            }, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())))
        }
        fun values(): Map<String, String?> = context.contentResolver.query(uri(), null, "${Events._ID}=?",
            arrayOf(row.toString()), null)!!.use { cursor ->
            assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst())
            cursor.columnNames.associateWith { name -> cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getString(it) } }
        }
        suspend fun seed() {
            native.admitOperation = { true }; allowSql()
            val summary = CalendarSummary(target.calendar, "Synthetic text gateway")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            calendar = native.ensureCalendarState(target, summary, true).id
            store.transaction(target) {
                putRemote(base); putProjection(ProjectionIntent(target, base))
                checkpoint = PageCheckpoint(target, format, traversalComplete = true)
            }
            if (transferred) seedTransfer() else {
                native.apply(ProjectionIntent(target, base)); assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            }
            context.contentResolver.query(uri(), arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use { assertTrue(it.moveToFirst()); assertEquals(1, it.count); row = it.getLong(0) }
            if (redundantIncoming) {
                check(!transferred)
                // Incoming preparation precedes the editor's Save. No native batch has
                // installed this new receipt marker, so later recovery cannot claim it did.
                store.transaction(target) { putProjection(ProjectionIntent(target, base)) }
                redundantReceipt = store.prepareProjection(ProjectionIntent(target, base), format)
                assertEquals(base, redundantReceipt!!.change); assertEquals(base, redundantReceipt!!.previousBase)
                assertEquals("0", values()[Events.DIRTY])
            }
            edit(intent.content)
            if (redundantIncoming) {
                assertNull(store.localEdit(target, base.eventId))
                assertEquals(listOf(ProjectionIntent(target, base)), store.pending(target))
            } else if (transferred) {
                val snapshot = native.captureCapturedHandoffCurrent(initialFrame!!) as CapturedHandoffCurrentObservation.Supported
                assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(snapshot.snapshot, intent.id))
            } else assertEquals(intent, store.captureLocalEdit(intent))
            if (!redundantIncoming) {
                assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(preparation))
                assertTrue(journal.remember(record) { it == target })
            }
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { scheduled.await() }
            if (redundantIncoming) {
                val dirty = values()
                assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, intent.id))
                assertEquals(dirty, values()); assertEquals(1, projection.projectionInspections)
                allowSql()
                assertEquals(intent, store.localEdit(target, base.eventId))
                assertEquals(redundantReceipt, store.projectionReceipt(target, base.eventId))
                assertTrue(store.pending(target).isEmpty())
                val lease = coordinator.systemTextRunAdmission(target)
                val retained = owners.retain(preparation, lease)!!
                assertEquals(preparation.intent, retained.intent); assertEquals(preparation.timestamp, retained.timestamp)
                assertEquals(preparation.format, retained.format); assertEquals(preparation.protectedPayload, retained.protectedPayload)
                assertTrue(journal.remember(record, lease))
            }
        }
        private suspend fun seedTransfer() {
            val localId = LocalEventId(UUID.randomUUID().toString())
            val capture = LocalCreateIntent(LocalCreateReservation(MutationId("original-capture"), localId, target,
                format, preparation.timestamp), base.content)
            val submitted = LocalCreateSubmission(LocalCreateSubmissionReservation(capture,
                MutationId(UUID(0, 9).toString()), AdapterFormat("synthetic-create", 1)), "original-create-wire")
            captures.admitCapture = { it == target }
            assertTrue(captures.reserve(capture.reservation)); assertEquals(capture, captures.retain(capture))
            val permit = requireNotNull(captures.reserveSubmission(submitted.reservation))
            assertEquals(submitted, captures.retainSubmission(permit, submitted))
            creates.admitDispatch = { it == target }; creates.admitAssociation = { it == target }
            val identity = creates.rememberCaptured(submitted)
            val attempt = CreateAttemptRecord(identity.attempt.id, target, identity.attempt.format, submitted.protectedPayload)
            assertTrue(creates.claimCaptured(attempt, identity))
            val binding = creates.bindCaptured(attempt.copy(state = CreateAttemptState.OUTCOME_UNKNOWN), identity,
                CreateSubmissionObservation.Verified(identity.attempt, base))
            val attachment = creates.prepareCapturedAttachment(binding)
            originalSubmission = submitted
            creates.admitDispatch = { error("No second CREATE") }; captures.admitCapture = { false }
            val candidate = ContentValues().apply {
                put(Events.CALENDAR_ID, calendar); put(Events.TITLE, base.content.title)
                put(Events.DESCRIPTION, base.content.description); put(Events.EVENT_LOCATION, base.content.location)
                put(Events.DIRTY, 1); put(Events.DELETED, 0); put(Events.STATUS, Events.STATUS_CONFIRMED)
                put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY); putNull(Events.ORGANIZER)
                for (flag in listOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
                    Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM, Events.ACCESS_LEVEL)) put(flag, 0)
                when (val time = base.content.time) {
                    is EventTime.Timed -> {
                        put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
                        put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
                    }
                    is EventTime.AllDay -> {
                        put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
                        put(Events.DTSTART, time.start.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
                        put(Events.DTEND, time.endExclusive.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
                    }
                }
            }
            row = ContentUris.parseId(requireNotNull(context.contentResolver.insert(uri(), candidate)))
            assertEquals(base.content, native.captureAndStampSimpleCreate(target, row, localId)!!.content)
            val origin = CapturedCreateBaseline(attachment, base)
            assertTrue(store.retainCapturedBaseline(origin, native.attachCapturedCreate(attachment)!!))
            val acknowledgement = CapturedCreateAcknowledgementReceipt(origin, UUID.randomUUID().toString())
            store.reserveCapturedAcknowledgement(acknowledgement)
            assertEquals(CapturedCreateAcknowledgementState.PROVEN,
                store.retainCapturedAcknowledgement(native.acknowledgeCapturedCreate(acknowledgement)!!).state)
            val transfer = store.reserveCapturedHandoff(CapturedCreateHandoffReceipt(acknowledgement, UUID.randomUUID().toString()))
            initialFrame = store.retainCapturedHandoff(transfer.frame.receipt, native.inspectAcknowledgedCreateClean(acknowledgement)!!).frame
        }
        suspend fun submit(port: Port, value: TextUpdateAttemptRecord = record,
            admission: (CalendarScope) -> Boolean = coordinator.systemTextRunAdmission(target)) =
            owners.submit(value, journal, port, admission, coordinator.systemTextEvidenceAdmission(target))
        suspend fun close() {
            accountHook = {}; coordinator.authenticationChanged(false); jobs.coroutineContext.job.cancelAndJoin()
            try {
                native.admitOperation = { true }; native.removeOwnedCalendar(target)
                val account = native.accountFor(target.account)
                val manager = AccountManager.get(context)
                if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
                    assertTrue(manager.removeAccountExplicitly(account))
            } finally {
                attempts.close(); store.close(); captures.close(); creates.close(); names.forEach { context.deleteDatabase(it) }
            }
        }
    }
    private class Port(private val fixture: Fixture) : CalendarTextUpdateSubmissionPort {
        override val textUpdateFormat = fixture.record.format
        var calls = 0
        var sends = 0
        var beforeClaim: suspend () -> Unit = {}
        var afterSend: suspend () -> Unit = {}
        var swallowCancellation = false
        var savedSending: ((CalendarScope) -> Boolean)? = null
        var savedEvidence: ((CalendarScope) -> Boolean)? = null
        var savedSource: (suspend (CalendarScope) -> Boolean)? = null
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalTextEditPreparation,
            admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? = error("No preparation transport")
        override suspend fun inspect(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation = error("No service inspection")
        override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation = fixture.authMutex.withLock {
            calls++; fixture.inAuth = true
            savedSending = admit; savedEvidence = admitEvidence; savedSource = qualifySource
            try {
                val target = fixture.target
                if (!admit(target) || !qualifySource(target)) return@withLock TextUpdateSubmissionObservation.Unknown
                beforeClaim()
                if (swallowCancellation) return@withLock TextUpdateSubmissionObservation.Unknown
                if (!qualifySource(target) || !journal.claim(record, admit)) return@withLock TextUpdateSubmissionObservation.Unknown
                val unknown = record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
                if (!qualifySource(target) || !admit(target)) return@withLock TextUpdateSubmissionObservation.Unknown
                sends++; afterSend()
                if (!journal.retainEvidence(unknown, "synthetic-response", admitEvidence)) return@withLock TextUpdateSubmissionObservation.Unknown
                TextUpdateSubmissionObservation.Verified(unknown.copy(protectedEvidence = "synthetic-response"), fixture.observed)
            } finally { fixture.inAuth = false }
        }
    }
    private fun fixture(allDay: Boolean = false, transferred: Boolean = false, redundantIncoming: Boolean = false,
        block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(AndroidCalendarProjection(context).hasPermissions())
        val fixture = Fixture(allDay, transferred, redundantIncoming)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }

    @Test fun ordinaryTimedAndAllDayRetainRawProofAcknowledgeNormalizedTextAndCompleteOffline() {
        for (allDay in listOf(false, true)) fixture(allDay) { fullJourney() }
    }
    @Test fun transferredCreateKeepsFullOriginAndAdvancesItsFrameThroughTextCompletion() = fixture(transferred = true) {
        fullJourney()
        allowSql()
        val receipt = store.textUpdateAcknowledgement(target, base.eventId, record.id)!!.receipt
        assertEquals(initialFrame, receipt.handoff)
        assertEquals(observed, store.capturedHandoff(target, base.eventId)!!.frame.base)
        val original = requireNotNull(originalSubmission)
        captures.admitCapture = { it == target }
        assertEquals(original, captures.readSubmission(target, original.reservation.intent.reservation.localId))
        assertEquals(initialFrame!!.receipt.acknowledgement.baseline.receipt.binding,
            creates.capturedBinding(CapturedCreateDispatchIdentity.of(original)))
    }
    private suspend fun Fixture.fullJourney() {
        val port = Port(this)
        val observation = submit(port) as? TextUpdateSubmissionObservation.Verified
        assertNotNull(observation); assertEquals(1, port.sends); assertEquals("1", values()[Events.DIRTY])
        val result = TextUpdateVerifiedResult.of(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), observation!!)
        assertTrue(journal.retainVerified(result, coordinator.systemTextEvidenceAdmission(target)))
        toggleText(false)
        val proofLease = coordinator.systemTextProofAdmission(target)
        assertTrue(owners.acknowledge(result, journal, proofLease)); assertTrue(owners.complete(result, journal, proofLease))
        assertEquals("0", values()[Events.DIRTY]); assertEquals("Changed\nnotes", values()[Events.DESCRIPTION])
        assertEquals(row.toString(), values()[Events._ID]); assertEquals(1, projection.acknowledgements)
        allowSql(); assertNull(store.localEdit(target, base.eventId)); assertNull(store.localTextEditPreparation(target, base.eventId))
        assertEquals(observed, store.projectedBaseline(target, base.eventId))
        val completion = journal.readCompletion(target, record.id, proofLease)
        assertNotNull(completion); assertEquals(result, completion!!.result)
        edit(observed.content.copy(description = "Later native edit")); val later = values()
        assertTrue(owners.acknowledge(result, journal, proofLease)); assertTrue(owners.complete(result, journal, proofLease))
        assertEquals(later, values()); assertEquals(1, projection.acknowledgements); assertEquals(1, port.calls)
        assertEquals(TextUpdateAttemptState.OUTCOME_UNKNOWN, journal.read(target, record.id, proofLease)!!.state)
    }
    @Test fun importLockPrecedesAuthenticationWithTwoBarrierControlledCoroutines() = fixture {
        val enteredImport = CompletableDeferred<Unit>(); val releaseImport = CompletableDeferred<Unit>()
        accountHook = { enteredImport.complete(Unit); releaseImport.await() }
        coroutineScope {
            val importing = async(start = CoroutineStart.UNDISPATCHED) { coordinator.configureSystemCalendar(target) }
            enteredImport.await()
            val port = Port(this@fixture)
            val sending = async(start = CoroutineStart.UNDISPATCHED) { submit(port) }
            assertEquals(0, port.calls)
            releaseImport.complete(Unit)
            withTimeout(10_000) { assertTrue(importing.await()); assertTrue(sending.await() is TextUpdateSubmissionObservation.Verified) }
            assertEquals(1, port.calls); assertEquals(1, port.sends)
        }
    }
    @Test fun revocationWhileWaitingForImportLockCannotRenewItsEpoch() = fixture {
        val enteredImport = CompletableDeferred<Unit>(); val releaseImport = CompletableDeferred<Unit>()
        accountHook = { enteredImport.complete(Unit); releaseImport.await() }
        coroutineScope {
            val importing = async(start = CoroutineStart.UNDISPATCHED) { coordinator.configureSystemCalendar(target) }
            enteredImport.await()
            val port = Port(this@fixture); val lease = coordinator.systemTextRunAdmission(target)
            val sending = async(start = CoroutineStart.UNDISPATCHED) { submit(port, admission = lease) }
            toggleText(false); toggleText(true); releaseImport.complete(Unit)
            withTimeout(10_000) { importing.await(); assertEquals(TextUpdateSubmissionObservation.Unknown, sending.await()) }
            assertEquals(0, port.calls); assertEquals(record, journal.read(target, record.id) { true })
        }
    }
    @Test fun modeOffAfterSendPreservesFactualVerifiedEvidenceButCannotAcknowledge() = fixture {
        val port = Port(this)
        val runLease = coordinator.systemTextRunAdmission(target)
        port.afterSend = { toggleText(false) }
        val observation = submit(port, admission = runLease) as? TextUpdateSubmissionObservation.Verified
        assertNotNull(observation); assertEquals("synthetic-response", journal.read(target, record.id) { true }!!.protectedEvidence)
        val result = TextUpdateVerifiedResult.of(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), observation!!)
        assertTrue(journal.retainVerified(result, coordinator.systemTextEvidenceAdmission(target)))
        assertFalse(owners.acknowledge(result, journal, runLease)); assertEquals(0, projection.acknowledgements)
        assertEquals("1", values()[Events.DIRTY])
    }
    @Test fun retainedSubmissionCallbacksExpireAndUnknownCannotResend() = fixture {
        val port = Port(this)
        assertTrue(submit(port) is TextUpdateSubmissionObservation.Verified)
        assertFalse(port.savedSending!!(target)); assertFalse(port.savedEvidence!!(target)); assertFalse(port.savedSource!!(target))
        val unknown = journal.read(target, record.id) { true }!!
        assertEquals(TextUpdateSubmissionObservation.Unknown, submit(port, unknown))
        assertEquals(TextUpdateSubmissionObservation.Unknown, submit(port))
        assertEquals(1, port.calls); assertEquals(1, port.sends); assertEquals(unknown, journal.read(target, record.id) { true })
    }
    @Test fun laterNativeChangeBeforeClaimHoldsTheOriginalPreparedAttempt() = fixture {
        val port = Port(this)
        port.beforeClaim = { edit(intent.content.copy(location = "Later room")) }
        assertEquals(TextUpdateSubmissionObservation.Unknown, submit(port)); assertEquals(0, port.sends)
        assertEquals(record, journal.read(target, record.id) { true }); assertEquals("Later room", values()[Events.EVENT_LOCATION])
    }
    @Test fun cancelledPortReturningNormallyCannotHideCancellationOrClaimCompletion() = fixture {
        var cancellations = 0; var ordinary = 0; var returns = 0
        val port = Port(this)
        port.swallowCancellation = true
        port.beforeClaim = { currentCoroutineContext().cancel() }
        supervisorScope {
            val child = async {
                try { submit(port); returns++ } catch (_: CancellationException) { cancellations++ }
                catch (_: Exception) { ordinary++ }
            }
            try { child.await() } catch (_: CancellationException) { }
        }
        assertEquals(1, cancellations); assertEquals(0, ordinary); assertEquals(0, returns)
        assertEquals(0, port.sends); assertEquals(record, journal.read(target, record.id) { true })
    }

    @Test fun bareOwnerRetainsOnlyItsExactFirstCapsuleAndRequalifiesNativeContents() = fixture {
        assertEquals(1, store.writableDatabase.delete("local_text_edit_preparations", null, null))
        val lease = coordinator.systemTextRunAdmission(target)
        val bare = owners.observe(target, base.eventId, intent.id, lease)
        assertEquals(intent, bare!!.intent); assertNull(bare.preparation)
        val retained = owners.retain(preparation, lease)!!
        assertEquals(preparation.intent, retained.intent); assertEquals(preparation.timestamp, retained.timestamp)
        assertEquals(preparation.format, retained.format); assertEquals(preparation.protectedPayload, retained.protectedPayload)
        assertTrue(owners.qualify(retained, lease))
        assertNull(owners.retain(LocalTextEditPreparation(intent, preparation.timestamp, preparation.format, "different-capsule"), lease))
        assertEquals(record, journal.read(target, record.id) { true })
        edit(intent.content.copy(location = "Newer native location"))
        assertFalse(owners.qualify(retained, lease))
        assertEquals(0, projection.acknowledgements)
    }

    @Test fun lostNativeReplyReopensOnlyTheHistoricalReceiptAndNeverWritesAgain() = fixture {
        val port = Port(this)
        val observation = submit(port) as TextUpdateSubmissionObservation.Verified
        val result = TextUpdateVerifiedResult.of(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), observation)
        assertTrue(journal.retainVerified(result, coordinator.systemTextEvidenceAdmission(target)))
        val proofLease = coordinator.systemTextProofAdmission(target)
        projection.loseAcknowledgementReply = true
        assertFalse(owners.acknowledge(result, journal, proofLease))
        allowSql()
        assertEquals(TextUpdateAcknowledgementState.PENDING, store.textUpdateAcknowledgement(target, base.eventId, record.id)!!.state)
        assertEquals("0", values()[Events.DIRTY])
        assertTrue(owners.acknowledge(result, journal, proofLease)); assertTrue(owners.complete(result, journal, proofLease))
        assertEquals(1, projection.acknowledgements); assertEquals(1, projection.inspections); assertEquals(1, port.sends)
    }

    @Test fun redundantIncomingReceiptSurvivesCaptureAndSubmissionUntilTextAcknowledgementIsProven() = fixture(redundantIncoming = true) {
        val pending = checkNotNull(redundantReceipt)
        val dirty = values()
        assertEquals("1", dirty[Events.DIRTY])
        assertNotEquals("calendeer-projection-receipt-v1:${pending.nonce}", dirty[Events.SYNC_DATA3])
        val port = Port(this)
        val observation = submit(port) as TextUpdateSubmissionObservation.Verified
        val result = TextUpdateVerifiedResult.of(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), observation)
        assertTrue(journal.retainVerified(result, coordinator.systemTextEvidenceAdmission(target)))
        allowSql()
        assertEquals(pending, store.projectionReceipt(target, base.eventId))
        assertEquals(base, store.projectedBaseline(target, base.eventId))
        assertEquals(intent, store.localEdit(target, base.eventId)); assertEquals(dirty, values())
        assertEquals(0, projection.acknowledgements); assertNull(journal.readCompletion(target, record.id) { true })

        toggleText(false)
        val proofLease = coordinator.systemTextProofAdmission(target)
        assertTrue(owners.acknowledge(result, journal, proofLease))
        assertTrue(owners.complete(result, journal, proofLease))
        allowSql()
        assertNull(store.projectionReceipt(target, base.eventId)); assertNull(store.localEdit(target, base.eventId))
        assertNull(store.localTextEditPreparation(target, base.eventId))
        assertEquals(observed, store.projectedBaseline(target, base.eventId))
        assertEquals(result, journal.readCompletion(target, record.id, proofLease)!!.result)
        assertEquals(result, journal.readVerified(target, record.id, proofLease))
        assertEquals(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "synthetic-response"),
            journal.read(target, record.id, proofLease))
        val restored = values()
        assertEquals(row.toString(), restored[Events._ID]); assertEquals("0", restored[Events.DIRTY])
        assertEquals("Changed\nnotes", restored[Events.DESCRIPTION])
        assertTrue(owners.acknowledge(result, journal, proofLease)); assertTrue(owners.complete(result, journal, proofLease))
        assertEquals(restored, values()); assertEquals(1, projection.acknowledgements)
        assertEquals(0, projection.inspections); assertEquals(1, port.calls); assertEquals(1, port.sends)
    }

    @Test fun redundantIncomingReceiptAfterLostNativeReplyRequiresHistoricalInspectionBeforeCleanup() = fixture(redundantIncoming = true) {
        val pending = checkNotNull(redundantReceipt)
        val port = Port(this)
        val observation = submit(port) as TextUpdateSubmissionObservation.Verified
        val result = TextUpdateVerifiedResult.of(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), observation)
        assertTrue(journal.retainVerified(result, coordinator.systemTextEvidenceAdmission(target)))
        val proofLease = coordinator.systemTextProofAdmission(target)
        projection.loseAcknowledgementReply = true
        assertFalse(owners.acknowledge(result, journal, proofLease))
        allowSql()
        assertEquals(pending, store.projectionReceipt(target, base.eventId))
        assertEquals(base, store.projectedBaseline(target, base.eventId))
        assertEquals(intent, store.localEdit(target, base.eventId))
        assertEquals(TextUpdateAcknowledgementState.PENDING, store.textUpdateAcknowledgement(target, base.eventId, record.id)!!.state)
        assertNull(journal.readCompletion(target, record.id, proofLease))
        val applied = values()
        assertEquals("0", applied[Events.DIRTY]); assertEquals("Changed\nnotes", applied[Events.DESCRIPTION])
        assertEquals(1, projection.acknowledgements); assertEquals(0, projection.inspections)

        // The still-enabled loss hook would fail another fresh native write. Only the
        // historical marker inspection may close the two pending SQL obligations.
        assertTrue(owners.acknowledge(result, journal, proofLease))
        assertTrue(owners.complete(result, journal, proofLease))
        allowSql()
        assertNull(store.projectionReceipt(target, base.eventId)); assertNull(store.localEdit(target, base.eventId))
        assertNull(store.localTextEditPreparation(target, base.eventId))
        assertEquals(observed, store.projectedBaseline(target, base.eventId))
        assertEquals(TextUpdateAcknowledgementState.RESTORED, store.textUpdateAcknowledgement(target, base.eventId, record.id)!!.state)
        assertEquals(result, journal.readCompletion(target, record.id, proofLease)!!.result)
        assertEquals(result, journal.readVerified(target, record.id, proofLease)); assertEquals(applied, values())
        assertEquals(1, projection.acknowledgements); assertEquals(1, projection.inspections)
        assertEquals(1, port.calls); assertEquals(1, port.sends)
    }
}
