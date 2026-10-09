// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTitleRecoveryController
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTitleRecoveryProgress
import com.patmanak.proton.andcalendarbridge.data.outgoing.AndroidTitleRecoveryCursorStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Provider, SQLite journals and coordinator; synthetic protected work/result only.
 * No HTTP or cryptographic verification occurs: these fixtures qualify local composition only.
 */
@RunWith(AndroidJUnit4::class)
class TitleUpdateAcknowledgementIntegrationDeviceTest {
    private class Fixture(val context: Context, allDay: Boolean, val transferred: Boolean = false,
        private val preparedOnly: Boolean = false) {
        private val token = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("title-ack-integration-$token"), CalendarId("fixture"), 0)
        private val importName = "title-ack-integration-$token.db"
        private val attemptName = "title-ack-integration-attempt-$token.db"
        private val captureName = "title-ack-integration-capture-$token.db"
        private val createAttemptName = "title-ack-integration-create-attempt-$token.db"
        private val format = AdapterFormat("synthetic-title-ack-integration", 1)
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-original-revision"),
            EventContent("Synthetic imported original", if (allDay)
                EventTime.AllDay(LocalDate.of(2030, 10, 26), LocalDate.of(2030, 10, 28)) else
                EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
                "Synthetic description\nPreserved second line", "Synthetic location", details = EventDetails()))
        val local = base.content.copy(title = "Synthetic submitted system title")
        private val firstId = MutationId("synthetic-first-intent")
        private val intent = LocalEditIntent(firstId, target, format, base, local)
        val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-05T12:00:00Z"),
            AdapterFormat("synthetic-sealed-title-preparation", 1), "synthetic-opaque-preparation-no-crypto-proof")
        val prepared = TitleUpdateAttemptRecord(MutationId("synthetic-first-attempt"), preparation,
            AdapterFormat("synthetic-sealed-title-wire", 1), "synthetic-opaque-wire-no-crypto-proof")
        val unknown = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        val verified = base.copy(revision = Revision("synthetic-verified-revision"), content = local)
        val result = TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(unknown), verified))
        val native = AndroidCalendarProjection(context)
        val writes = AtomicInteger()
        val inspections = AtomicInteger()
        var afterFreshAcknowledgement: (suspend () -> Unit)? = null
        private val projection = object : CalendarProjection by native {
            override suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                writes.incrementAndGet()
                val witness = native.acknowledgeTitleUpdate(receipt)
                if (witness != null) {
                    val hook = afterFreshAcknowledgement
                    afterFreshAcknowledgement = null
                    hook?.invoke()
                }
                return witness
            }
            override suspend fun inspectTitleUpdateAcknowledgement(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                inspections.incrementAndGet()
                return native.inspectTitleUpdateAcknowledgement(receipt)
            }
        }
        var store = ImportStore(context, importName)
            private set
        var journal = TitleUpdateAttemptStore(context, attemptName)
            private set
        private var captures = LocalCreateStore(context, captureName)
        private var createAttempts = CreateAttemptStore(context, createAttemptName)
        private var originalSubmission: LocalCreateSubmission? = null
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId {
                if (submitting) forbiddenAccountReentries++
                check(!submitting) { "Local qualification must not re-enter authentication" }
                return target.account
            }
            override suspend fun fetch(request: PageRequest): RemotePage = error("Synthetic result fixture cannot download")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("Synthetic result fixture cannot upload")
        }
        private fun newCoordinator() = ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            creates = if (transferred) createAttempts else null,
            localCreates = if (transferred) captures else null,
            systemCreatesEnabled = { it == target })
        var coordinator = newCoordinator()
            private set
        private var calendar = -1L
        var row = -1L
            private set
        private val uri get() = syncUri(Events.CONTENT_URI)
        var frame: CapturedCreateHandoffFrame? = null
            private set
        private var submitting = false
        private var forbiddenAccountReentries = 0

        /** Only the remote boundary is synthetic; owner checks and claim/result commits are real. */
        private inner class ControlledTitlePort : CalendarTitleUpdateSubmissionPort {
            override val titleUpdateFormat = prepared.format
            var preparations = 0
            var submissions = 0
            var sends = 0
            var forbiddenCalls = 0
            var beforeClaim: suspend () -> Unit = {}
            var afterSend: suspend () -> Unit = {}
            private fun forbidden(): Nothing { forbiddenCalls++; error("No unrestricted operation or service inspection") }
            override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = forbidden()
            override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
                admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
                assertTrue(admit(target)); assertFalse(admit(target.copy(generation = 1)))
                assertEquals(TitleUpdatePreparationReservation.of(prepared), reservation)
                preparations++
                return prepared
            }
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation = forbidden()
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation = forbidden()
            override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = forbidden()
            override suspend fun inspect(record: TitleUpdateAttemptRecord,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation = forbidden()
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean,
                qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                submissions++; submitting = true
                try {
                    assertEquals(prepared, record)
                    assertTrue(admit(target)); assertFalse(admit(target.copy(generation = 1)))
                    if (!qualifySource(target)) return TitleUpdateSubmissionObservation.Unknown
                    beforeClaim()
                    if (!qualifySource(target) || !journal.claim(record, admit)) return TitleUpdateSubmissionObservation.Unknown
                    // Dispatch starts only after durable UNKNOWN and another current native read.
                    assertEquals(unknown, journal.read(target, record.id))
                    if (!qualifySource(target) || !admit(target)) return TitleUpdateSubmissionObservation.Unknown
                    sends++
                    journal.retainEvidence(unknown, "synthetic-composed-title-response")
                    afterSend()
                    if (!qualifySource(target) || !admit(target)) return TitleUpdateSubmissionObservation.Unknown
                    return TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), verified)
                } finally { submitting = false }
            }
            fun assertOneExchange() {
                assertEquals(1, preparations); assertEquals(1, submissions); assertEquals(1, sends)
                assertEquals(0, forbiddenCalls); assertEquals(0, forbiddenAccountReentries)
            }
        }

        private suspend fun prepareGuarded(port: ControlledTitlePort): TitleUpdateAttemptRecord {
            check(preparedOnly)
            assertNull(journal.read(target, prepared.id))
            val before = values()
            val canonical = coordinator.prepareLocalTitleAttempt(TitleUpdatePreparationReservation.of(prepared),
                journal, port, allowNetwork = true) as LocalTitleAttemptPreparation.Prepared
            assertEquals(prepared, canonical.record)
            assertEquals(before, values()); assertEquals(0, writes.get()); assertEquals(0, inspections.get())
            return canonical.record
        }

        /** Reconstruct every SQL owner/journal and coroutine runtime, keeping only the native row. */
        private suspend fun reopenOwner() {
            coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close(); captures.close(); createAttempts.close()
            store = ImportStore(context, importName); journal = TitleUpdateAttemptStore(context, attemptName)
            captures = LocalCreateStore(context, captureName); createAttempts = CreateAttemptStore(context, createAttemptName)
            jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = newCoordinator()
            val prior = schedules.get()
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == prior) delay(10) }
        }

        suspend fun guardedSubmissionCompletesOfflineAfterReopening(restoreGap: Boolean = false,
            completionGap: Boolean = false) {
            val port = ControlledTitlePort()
            val canonical = prepareGuarded(port)
            val before = values()
            val retained = coordinator.submitAndRetainLocalTitleAttempt(canonical, journal, journal, port, allowNetwork = true)
            assertEquals(result, retained); port.assertOneExchange()
            assertEquals(before, values()); assertEquals("1", before[Events.DIRTY])
            assertEquals(0, writes.get()); assertEquals(0, inspections.get()); assertSingleOriginalRow()
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId)); assertNull(receipt())
            assertOriginalCreateProvenance()
            val evidenced = unknown.copy(protectedEvidence = "synthetic-composed-title-response")
            reopenOwner()
            assertEquals(evidenced, journal.read(target, canonical.id)); assertEquals(result, journal.readVerified(target, canonical.id))
            assertEquals(before, values()); assertNull(journal.readCompletion(target, canonical.id))
            // Neither offline wire reuse nor replay of PREPARED may re-enter the port.
            assertEquals(LocalTitleAttemptPreparation.Unresolved, coordinator.prepareLocalTitleAttempt(
                TitleUpdatePreparationReservation.of(canonical), journal, port))
            assertNull(coordinator.submitAndRetainLocalTitleAttempt(canonical, journal, journal, port, allowNetwork = true))
            port.assertOneExchange()
            if (restoreGap) store.writableDatabase.execSQL("CREATE TRIGGER composed_restore_gap BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'synthetic restoration gap'); END")
            if (completionGap) journal.writableDatabase.execSQL("CREATE TRIGGER composed_completion_gap BEFORE UPDATE OF completion ON title_update_attempts BEGIN SELECT RAISE(ABORT,'synthetic completion gap'); END")
            val first = coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal)
            if (restoreGap || completionGap) {
                assertEquals(TitleUpdateOfflineRecoveryProgress(held = 1), first)
                assertEquals(1, writes.get()); assertEquals(0, inspections.get())
                assertNull(journal.readCompletion(target, canonical.id))
                assertEquals(if (restoreGap) TitleUpdateAcknowledgementState.PENDING else TitleUpdateAcknowledgementState.RESTORED,
                    receipt()!!.state)
                mutateTitle("Synthetic later edit across composed recovery gap")
                val later = values()
                if (restoreGap) store.writableDatabase.execSQL("DROP TRIGGER composed_restore_gap")
                if (completionGap) journal.writableDatabase.execSQL("DROP TRIGGER composed_completion_gap")
                reopenOwner()
                assertEquals(TitleUpdateOfflineRecoveryProgress(completed = 1),
                    coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal))
                assertEquals(later, values()); assertEquals("1", values()[Events.DIRTY])
                assertEquals(if (restoreGap) 1 else 0, inspections.get())
            } else {
                assertEquals(TitleUpdateOfflineRecoveryProgress(completed = 1), first)
                val after = values()
                assertEquals("0", after[Events.DIRTY])
                val metadata = setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3)
                assertEquals(before.filterKeys { it !in metadata }, after.filterKeys { it !in metadata })
                assertEquals(digest("revision-v1", verified.revision.value), after[Events.SYNC_DATA2])
                assertEquals("calendeer-projection-receipt-v1:${receipt()!!.receipt.projection.nonce}", after[Events.SYNC_DATA3])
                mutateTitle("Synthetic later edit after composed completion")
            }
            assertSqlRestored(evidenced); assertOriginalCreateProvenance(); assertSingleOriginalRow()
            assertEquals(TitleUpdateCompletion.of(result, receipt()!!), journal.readCompletion(target, canonical.id))
            val later = values()
            val nextId = MutationId("synthetic-next-composed-owner")
            if (transferred) captureTransferred(nextId) else
                assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, nextId))
            allowSql()
            val next = store.localEdit(target, base.eventId)!!
            assertEquals(nextId, next.id); assertEquals(verified, next.base); assertEquals(later[Events.TITLE], next.content.title)
            assertEquals(TitleUpdateOfflineRecoveryProgress(), coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal))
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            allowSql(); assertEquals(next, store.localEdit(target, base.eventId))
            assertEquals(later, values()); assertEquals(1, writes.get()); port.assertOneExchange()
        }

        suspend fun guardedSubmissionPreservesChangedNativeSource(afterSend: Boolean) {
            val port = ControlledTitlePort()
            val canonical = prepareGuarded(port)
            var changeCalls = 0
            val laterTitle = "Synthetic newer edit during guarded dispatch"
            val change: suspend () -> Unit = { changeCalls++; mutateTitle(laterTitle) }
            if (afterSend) port.afterSend = change else port.beforeClaim = change
            assertNull(coordinator.submitAndRetainLocalTitleAttempt(canonical, journal, journal, port, allowNetwork = true))
            val later = values()
            assertEquals(1, changeCalls); assertEquals(laterTitle, later[Events.TITLE])
            val historical = if (afterSend) unknown.copy(protectedEvidence = "synthetic-composed-title-response") else canonical
            assertEquals(historical, journal.read(target, canonical.id)); assertNull(journal.readVerified(target, canonical.id))
            assertEquals(0, writes.get()); assertEquals(0, inspections.get()); assertNull(receipt())
            reopenOwner()
            assertEquals(TitleUpdateOfflineRecoveryProgress(held = 1), coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal))
            assertEquals(later, values()); assertEquals(laterTitle, values()[Events.TITLE])
            assertEquals("1", values()[Events.DIRTY]); assertSingleOriginalRow()
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId)); assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(1, port.preparations); assertEquals(1, port.submissions); assertEquals(if (afterSend) 1 else 0, port.sends)
            assertEquals(0, port.forbiddenCalls); assertEquals(0, forbiddenAccountReentries)
            assertOriginalCreateProvenance()
        }

        private fun syncUri(value: Uri): Uri = native.accountFor(target.account).let { account -> value.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build() }

        suspend fun seed() {
            val summary = CalendarSummary(target.calendar, "Calendeer synthetic title ACK integration")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            calendar = native.ensureCalendarState(target, summary, true).id
            store.transaction(target) {
                putRemote(base); putProjection(ProjectionIntent(target, base))
                checkpoint = PageCheckpoint(target, format, traversalComplete = true)
            }
            if (transferred) seedTransferred() else {
                native.apply(ProjectionIntent(target, base))
                assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            }
            context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); row = it.getLong(0)
            }
            mutateTitle(local.title)
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
            if (transferred) captureTransferred(firstId) else
                assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, firstId))
            allowSql()
            assertEquals(intent, store.localEdit(target, base.eventId))
            assertTrue(store.retainLocalEditPreparation(preparation))
            if (preparedOnly) {
                journal.admitDispatch = { error("Retaining wire must never claim dispatch") }
                return
            }
            journal.remember(prepared); journal.admitDispatch = { it == target }; assertTrue(journal.claim(prepared))
            journal.admitDispatch = { error("Native acknowledgement must never admit another send") }
            journal.retainVerified(result)
        }

        private suspend fun seedTransferred() {
            val localId = LocalEventId(UUID.randomUUID().toString())
            val captured = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-origin-capture"),
                localId, target, format, Instant.parse("2030-10-01T12:00:00Z")), base.content)
            val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(captured,
                MutationId(UUID(0, 9).toString()), AdapterFormat("synthetic-create-wire", 1)), "synthetic-create-opaque")
            // The transferred drain checks these original journals independently of the mutable
            // import frame. Persist the same synthetic source as a real completed CREATE path.
            captures.admitCapture = { it == target }
            assertTrue(captures.reserve(captured.reservation))
            assertEquals(captured, captures.retain(captured))
            val permit = captures.reserveSubmission(submission.reservation)!!
            assertEquals(submission, captures.retainSubmission(permit, submission))
            createAttempts.admitDispatch = { it == target }
            createAttempts.admitAssociation = { it == target }
            val identity = createAttempts.rememberCaptured(submission)
            val originalAttempt = CreateAttemptRecord(identity.attempt.id, target, identity.attempt.format, submission.protectedPayload)
            assertTrue(createAttempts.claimCaptured(originalAttempt, identity))
            val binding = createAttempts.bindCaptured(originalAttempt.copy(state = CreateAttemptState.OUTCOME_UNKNOWN),
                identity, CreateSubmissionObservation.Verified(identity.attempt, base))
            val attachment = createAttempts.prepareCapturedAttachment(binding)
            originalSubmission = submission
            createAttempts.admitDispatch = { error("Transferred drain must never dispatch another CREATE") }
            captures.admitCapture = { false }
            val origin = CapturedCreateBaseline(attachment, base)
            val candidate = ContentValues().apply {
                put(Events.CALENDAR_ID, calendar); put(Events.TITLE, base.content.title)
                put(Events.DESCRIPTION, base.content.description); put(Events.EVENT_LOCATION, base.content.location)
                put(Events.DIRTY, 1); put(Events.DELETED, 0); put(Events.STATUS, Events.STATUS_CONFIRMED)
                put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
                putNull(Events.ORGANIZER); putNull(Events.EVENT_COLOR); putNull(Events.EVENT_COLOR_KEY)
                for (flag in listOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
                    Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM,
                    Events.ACCESS_LEVEL)) put(flag, 0)
                when (val time = base.content.time) {
                    is EventTime.AllDay -> {
                        put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC")
                        put(Events.EVENT_END_TIMEZONE, "UTC")
                        put(Events.DTSTART, time.start.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
                        put(Events.DTEND, time.endExclusive.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
                    }
                    is EventTime.Timed -> {
                        put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id)
                        put(Events.EVENT_END_TIMEZONE, time.zone.id)
                        put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
                    }
                }
            }
            row = android.content.ContentUris.parseId(context.contentResolver.insert(uri, candidate)!!)
            // Supplying SELF_ATTENDEE_STATUS on insertion creates a self-attendee,
            // even for zero. Leave Android's default and qualify the actual child table.
            context.contentResolver.query(syncUri(CalendarContract.Attendees.CONTENT_URI), arrayOf("_id"),
                "${CalendarContract.Attendees.EVENT_ID}=?", arrayOf(row.toString()), null)!!.use { assertEquals(0, it.count) }
            val snapshot = native.captureAndStampSimpleCreate(target, row, localId)
            assertNotNull("Unmarked synthetic CREATE must be capturable", snapshot)
            assertEquals(base.content, snapshot!!.content)
            val attached = native.attachCapturedCreate(attachment)!!
            assertTrue(store.retainCapturedBaseline(origin, attached))
            val acknowledgement = CapturedCreateAcknowledgementReceipt(origin, UUID.randomUUID().toString())
            store.reserveCapturedAcknowledgement(acknowledgement)
            assertEquals(CapturedCreateAcknowledgementState.PROVEN,
                store.retainCapturedAcknowledgement(native.acknowledgeCapturedCreate(acknowledgement)!!).state)
            val transfer = store.reserveCapturedHandoff(CapturedCreateHandoffReceipt(acknowledgement, UUID.randomUUID().toString()))
            frame = store.retainCapturedHandoff(transfer.frame.receipt,
                native.inspectAcknowledgedCreateClean(acknowledgement)!!).frame
            assertOriginalCreateProvenance()
        }

        private suspend fun assertOriginalCreateProvenance() {
            if (!transferred) return
            val submitted = requireNotNull(originalSubmission)
            val identity = CapturedCreateDispatchIdentity.of(submitted)
            val attachment = requireNotNull(frame).receipt.acknowledgement.baseline.receipt
            LocalCreateStore(context, captureName).use { reopened ->
                reopened.admitCapture = { it == target }
                assertEquals(submitted, reopened.readSubmission(target, identity.localId))
            }
            CreateAttemptStore(context, createAttemptName).use { reopened ->
                assertEquals(identity, reopened.capturedIdentity(target, identity.attempt.id))
                assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, reopened.read(target, identity.attempt.id)!!.state)
                assertEquals(attachment.binding, reopened.capturedBinding(identity))
                assertEquals(attachment, reopened.capturedAttachment(attachment.binding))
            }
        }

        suspend fun captureTransferred(id: MutationId) {
            allowSql(); native.admitOperation = { true }
            val current = store.capturedHandoff(target, base.eventId)!!.frame
            val observed = native.captureCapturedHandoffCurrent(current) as CapturedHandoffCurrentObservation.Supported
            assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(observed.snapshot, id))
        }

        fun allowSql() { store.admitTransaction = { it == target } }
        fun mutateTitle(title: String) {
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply {
                put(Events.TITLE, title); put(Events.DIRTY, 1)
            }, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())))
        }
        fun values(): Map<String, String?> = context.contentResolver.query(uri, null,
            "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst())
            it.columnNames.indices.associate { column -> it.getColumnName(column) to if (it.isNull(column)) null else it.getString(column) }
        }
        fun assertSingleOriginalRow() {
            context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(row, it.getLong(0))
            }
        }
        suspend fun acknowledge() = coordinator.acknowledgeLocalTitle(target, base.eventId, prepared.id, journal)
        suspend fun complete(attempt: MutationId = prepared.id) =
            coordinator.completeLocalTitle(target, base.eventId, attempt, journal)

        suspend fun secondCycle(): Pair<TitleUpdateAttemptRecord, TitleUpdateVerifiedResult> {
            val id = MutationId("synthetic-second-intent")
            mutateTitle("Synthetic second submitted system title")
            if (transferred) captureTransferred(id) else
                assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, id))
            allowSql()
            val next = store.localEdit(target, base.eventId)!!
            assertEquals(verified, next.base)
            val nextPreparation = preparation.copy(intent = next, timestamp = preparation.timestamp.plusSeconds(1))
            assertTrue(store.retainLocalEditPreparation(nextPreparation))
            val nextAttempt = prepared.copy(id = MutationId("synthetic-second-attempt"), preparation = nextPreparation)
            journal.remember(nextAttempt); journal.admitDispatch = { it == target }; assertTrue(journal.claim(nextAttempt))
            journal.admitDispatch = { error("Acknowledgement and completion cannot dispatch") }
            val unknown = nextAttempt.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
            val result = TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
                TitleUpdateAttemptIdentity.of(unknown), verified.copy(revision = Revision("synthetic-second-revision"), content = next.content)))
            journal.retainVerified(result)
            return nextAttempt to result
        }

        suspend fun twoCycles(recoverSecond: Boolean = false) {
            assertEquals(TitleUpdateCompletionResult.UNRESOLVED, complete())
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            val firstReceipt = receipt()!!
            val firstNative = values()
            journal.writableDatabase.execSQL("CREATE TRIGGER fixture_completion_loss BEFORE UPDATE OF completion ON title_update_attempts BEGIN SELECT RAISE(ABORT,'synthetic completion loss'); END")
            assertEquals(TitleUpdateCompletionResult.UNRESOLVED, complete())
            assertNull(journal.readCompletion(target, prepared.id))
            assertEquals(firstNative, values())
            journal.writableDatabase.execSQL("DROP TRIGGER fixture_completion_loss")
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            assertEquals(TitleUpdateCompletion.of(result, firstReceipt), journal.readCompletion(target, prepared.id))
            assertEquals(firstNative, values())
            val (second, secondResult) = secondCycle()
            val nextNative = values()
            allowSql()
            val nextIntent = store.localEdit(target, base.eventId)
            val secondFrame = store.capturedHandoff(target, base.eventId)?.frame
            val pending = if (recoverSecond) store.prepareTitleUpdateAcknowledgement(secondResult, secondFrame)!! else null
            pending?.let { assertTrue(it.fresh) }
            // A late replay belongs to the first attempt, even with a new owner or PENDING receipt.
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            allowSql(); assertEquals(nextIntent, store.localEdit(target, base.eventId))
            assertEquals(pending?.record, store.titleUpdateAcknowledgement(target, base.eventId, second.id))
            assertEquals(nextNative, values()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
            if (pending != null) {
                // Simulate a process loss after the directly reserved fresh native write.
                native.admitOperation = { true }
                assertNotNull(native.acknowledgeTitleUpdate(pending.record.receipt))
            }
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED,
                coordinator.acknowledgeLocalTitle(target, base.eventId, second.id, journal))
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete(second.id))
            allowSql()
            assertEquals(secondResult.event, store.projectedBaseline(target, base.eventId))
            assertNull(store.localEdit(target, base.eventId)); assertNull(store.localEditPreparation(target, base.eventId))
            assertEquals(firstReceipt, store.titleUpdateAcknowledgement(target, base.eventId, prepared.id))
            assertEquals(unknown, journal.read(target, prepared.id))
            assertEquals(second.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), journal.read(target, second.id))
            TitleUpdateAttemptStore(context, attemptName).use { reopened ->
                assertEquals(journal.readCompletion(target, prepared.id), reopened.readCompletion(target, prepared.id))
                assertEquals(journal.readCompletion(target, second.id), reopened.readCompletion(target, second.id))
                reopened.admitDispatch = { true }; assertFalse(reopened.claim(prepared)); assertFalse(reopened.claim(second))
            }
            val finalNative = values()
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            assertEquals(finalNative, values()); assertSingleOriginalRow()
            assertEquals(if (recoverSecond) 1 else 2, writes.get())
            assertEquals(if (recoverSecond) 1 else 0, inspections.get())
            if (transferred) {
                allowSql()
                val final = store.capturedHandoff(target, base.eventId)!!
                assertEquals(frame!!.receipt, final.frame.receipt)
                assertEquals(secondResult.event, final.frame.base)
                assertEquals(secondFrame, journal.readCompletion(target, second.id)!!.receipt.handoff)
            }
        }
        suspend fun receipt(): TitleUpdateAcknowledgementRecord? { allowSql(); return store.titleUpdateAcknowledgement(target, base.eventId) }

        suspend fun retainPreparedWireWithoutConsumingNativeOwner() {
            val port = object : CalendarTitleUpdateSubmissionPort {
                override val titleUpdateFormat = prepared.format
                override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
                    error("Retention cannot prepare remote wire")
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
                    error("Retention cannot submit")
                override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation =
                    error("Retention cannot inspect service state")
                override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation =
                    error("Retention cannot inspect service state")
            }
            val before = values()
            assertEquals("1", before[Events.DIRTY])
            assertEquals(LocalTitleAttemptRetentionResult.RETAINED, coordinator.retainLocalTitleAttempt(prepared, journal, port))
            assertEquals(prepared, journal.read(target, prepared.id)); assertNull(journal.readVerified(target, prepared.id))
            assertNull(journal.readCompletion(target, prepared.id)); assertEquals(before, values())
            assertEquals(0, writes.get()); assertEquals(0, inspections.get()); assertSingleOriginalRow()
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(base, store.projectedBaseline(target, base.eventId)); assertNull(receipt())
            assertOriginalCreateProvenance()
            if (transferred) assertEquals(frame, store.capturedHandoff(target, base.eventId)!!.frame)
            TitleUpdateAttemptStore(context, attemptName).use { reopened ->
                reopened.admitDispatch = { error("Reopened retention cannot claim dispatch") }
                assertEquals(LocalTitleAttemptRetentionResult.HISTORICAL, coordinator.retainLocalTitleAttempt(prepared, reopened, port))
                assertEquals(prepared, reopened.read(target, prepared.id))
            }
            assertEquals(before, values()); assertSingleOriginalRow()
            mutateTitle("Synthetic later title before any submission")
            val later = values()
            assertEquals(LocalTitleAttemptRetentionResult.UNRESOLVED, coordinator.retainLocalTitleAttempt(prepared, journal, port))
            assertEquals(prepared, journal.read(target, prepared.id)); assertEquals(later, values())
            assertEquals(0, writes.get()); assertEquals(0, inspections.get())
        }

        suspend fun missingCreateSourceCannotFallBackToOrdinaryWireRetention(dropFrame: Boolean) {
            val before = values()
            if (dropFrame) assertEquals(1, store.writableDatabase.delete("captured_create_handoffs", null, null))
            else assertEquals(1, createAttempts.writableDatabase.delete("captured_create_attachments", null, null))
            val port = object : CalendarTitleUpdateSubmissionPort {
                override val titleUpdateFormat = prepared.format
                override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = error("No preparation")
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation = error("No send")
                override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("No inspection")
            }
            assertEquals(LocalTitleAttemptRetentionResult.UNRESOLVED, coordinator.retainLocalTitleAttempt(prepared, journal, port))
            assertNull(journal.read(target, prepared.id)); assertEquals(before, values()); assertSingleOriginalRow()
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(0, writes.get()); assertEquals(0, inspections.get())
        }

        suspend fun offlineBatchClosesStoredProofWithoutAnotherSend() {
            val progress = coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal)
            assertEquals(1, progress.completed); assertEquals(0, progress.held); assertEquals(0, progress.failed)
            assertFalse(progress.inactive); assertNull(progress.next)
            assertSqlRestored(); assertNotNull(journal.readCompletion(target, prepared.id))
            assertEquals(1, writes.get()); assertEquals(0, inspections.get()); assertSingleOriginalRow()
            val before = values()
            assertEquals(TitleUpdateOfflineRecoveryProgress(), coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal))
            assertEquals(before, values()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
        }

        suspend fun scheduledOfflineRecoveryPreservesLaterEdit(completionGap: Boolean) {
            val cursorName = "title-recovery-integration-$token"
            fun recovery() = SystemTitleRecoveryController(AndroidTitleRecoveryCursorStore(context, cursorName),
                { query -> coordinator.recoverLocalTitles(query, journal) })
            try {
                journal.admitDispatch = { error("Offline scheduling cannot claim an attempt") }
                val before = values()
                if (completionGap) journal.writableDatabase.execSQL("CREATE TRIGGER scheduled_completion_gap BEFORE UPDATE OF completion ON title_update_attempts BEGIN SELECT RAISE(ABORT,'synthetic completion gap'); END")
                assertEquals(SystemTitleRecoveryProgress(), recovery().run(target) { true })
                assertEquals(1, writes.get()); assertEquals(0, inspections.get())
                assertEquals("0", values()[Events.DIRTY])
                val metadata = setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3)
                assertEquals(before.filterKeys { it !in metadata }, values().filterKeys { it !in metadata })
                assertEquals(TitleUpdateAcknowledgementState.RESTORED, receipt()!!.state)
                allowSql(); assertNull(store.localEdit(target, base.eventId))
                if (completionGap) {
                    assertNull(journal.readCompletion(target, prepared.id))
                    journal.writableDatabase.execSQL("DROP TRIGGER scheduled_completion_gap")
                } else assertNotNull(journal.readCompletion(target, prepared.id))
                mutateTitle("Synthetic later edit across scheduled recovery")
                val later = values()
                reopenOwner()
                journal.admitDispatch = { error("Reopened offline scheduling cannot claim an attempt") }
                assertEquals(SystemTitleRecoveryProgress(), recovery().run(target) { true })
                assertEquals(later, values()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
                assertSqlRestored(); assertOriginalCreateProvenance(); assertSingleOriginalRow()
                assertEquals(TitleUpdateCompletion.of(result, receipt()!!), journal.readCompletion(target, prepared.id))
                val nextId = MutationId("synthetic-next-scheduled-owner")
                if (transferred) captureTransferred(nextId) else
                    assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, nextId))
                allowSql(); val nextOwner = store.localEdit(target, base.eventId)
                assertEquals(SystemTitleRecoveryProgress(), recovery().run(target) { true })
                allowSql(); assertEquals(nextOwner, store.localEdit(target, base.eventId))
                assertEquals(later, values()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
                assertNull(AndroidTitleRecoveryCursorStore(context, cursorName).read(target))
            } finally { context.deleteSharedPreferences(cursorName) }
        }

        suspend fun inspectionBatchRecoversMissingProof(restoreGap: Boolean = false) {
            journal.writableDatabase.execSQL("UPDATE title_update_attempts SET result=NULL")
            assertNull(journal.readVerified(target, prepared.id))
            var serviceInspections = 0
            val port = object : CalendarTitleUpdateSubmissionPort {
                override val titleUpdateFormat = prepared.format
                override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
                    error("Recovery cannot prepare a service request")
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
                    error("Recovery cannot send a service request")
                override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation =
                    error("Recovery cannot use unrestricted inspection")
                override suspend fun inspect(record: TitleUpdateAttemptRecord,
                    admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                    assertEquals(unknown, record); assertTrue(admit(target)); assertFalse(admit(target.copy(generation = 8)))
                    serviceInspections++
                    return TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), verified)
                }
            }
            if (restoreGap) store.writableDatabase.execSQL("CREATE TRIGGER fixture_inspection_restore_gap BEFORE UPDATE OF state ON title_update_acknowledgements WHEN NEW.state='RESTORED' BEGIN SELECT RAISE(ABORT,'synthetic restoration gap'); END")
            val first = coordinator.inspectAndRecoverLocalTitles(TitleUpdateWorkQuery(target), journal, port, allowNetwork = true)
            assertEquals(1, serviceInspections); assertEquals(result, journal.readVerified(target, prepared.id))
            assertEquals(unknown, journal.read(target, prepared.id)); assertEquals(1, writes.get())
            if (restoreGap) {
                assertEquals(TitleUpdateRecoveryProgress(held = 1), first)
                assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
                assertNull(journal.readCompletion(target, prepared.id))
                mutateTitle("Synthetic later edit during inspection restoration gap")
                val later = values()
                store.writableDatabase.execSQL("DROP TRIGGER fixture_inspection_restore_gap")
                TitleUpdateAttemptStore(context, attemptName).use { reopened ->
                    assertEquals(TitleUpdateRecoveryProgress(completed = 1), coordinator.inspectAndRecoverLocalTitles(
                        TitleUpdateWorkQuery(target), reopened, port, allowNetwork = true))
                    assertEquals(result, reopened.readVerified(target, prepared.id))
                    assertNotNull(reopened.readCompletion(target, prepared.id))
                }
                assertEquals(later, values()); assertEquals("1", values()[Events.DIRTY])
                assertEquals(1, inspections.get())
            } else {
                assertEquals(TitleUpdateRecoveryProgress(completed = 1), first)
                assertNotNull(journal.readCompletion(target, prepared.id))
                assertEquals(0, inspections.get())
            }
            assertSqlRestored(); assertSingleOriginalRow()
            assertEquals(1, writes.get()); assertEquals(1, serviceInspections)
            val before = values()
            assertEquals(TitleUpdateRecoveryProgress(), coordinator.inspectAndRecoverLocalTitles(
                TitleUpdateWorkQuery(target), journal, port, allowNetwork = true))
            assertEquals(before, values()); assertEquals(1, serviceInspections); assertEquals(1, writes.get())
        }

        suspend fun offlineBatchRecoversCompletionGapAfterReopeningAndKeepsLaterDirtyEdit() {
            journal.writableDatabase.execSQL("CREATE TRIGGER fixture_completion_gap BEFORE UPDATE OF completion ON title_update_attempts BEGIN SELECT RAISE(ABORT,'synthetic completion gap'); END")
            val held = coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal)
            assertEquals(0, held.completed); assertEquals(1, held.held); assertEquals(0, held.failed)
            assertSqlRestored(); assertNull(journal.readCompletion(target, prepared.id))
            assertEquals(1, writes.get()); assertEquals(0, inspections.get())
            mutateTitle("Synthetic later edit during completion gap")
            val later = values()
            journal.writableDatabase.execSQL("DROP TRIGGER fixture_completion_gap")
            TitleUpdateAttemptStore(context, attemptName).use { reopened ->
                val completed = coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), reopened)
                assertEquals(1, completed.completed); assertEquals(0, completed.held); assertEquals(0, completed.failed)
                assertNotNull(reopened.readCompletion(target, prepared.id))
                assertEquals(unknown, reopened.read(target, prepared.id))
            }
            assertEquals(later, values()); assertEquals("1", values()[Events.DIRTY])
            assertEquals(1, writes.get()); assertEquals(0, inspections.get()); assertSingleOriginalRow()
        }

        private fun pendingFlag(source: ImportStore = store): Int = source.readableDatabase.rawQuery(
            "SELECT pending FROM records WHERE event=?", arrayOf(base.eventId.value)).use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getInt(0)
        }

        private suspend fun cacheDistinctObservation(): RemoteChange.Upsert {
            val observed = verified.copy(revision = Revision("synthetic-distinct-cached-revision"),
                content = verified.content.copy(title = "Synthetic distinct remote title"))
            allowSql()
            store.transaction(target) { putRemote(observed); putProjection(ProjectionIntent(target, observed)) }
            // The local owner is still held: receipt restoration must explicitly rearm this work.
            assertEquals(0, pendingFlag())
            return observed
        }

        private suspend fun assertReopenedPending(observed: RemoteChange) {
            ImportStore(context, importName).use { reopened ->
                reopened.admitTransaction = { it == target }
                assertEquals(1, pendingFlag(reopened))
                assertEquals(observed, reopened.transaction(target) { remote(base.eventId) })
                assertEquals(verified, reopened.projectedBaseline(target, base.eventId))
                assertEquals(TitleUpdateAcknowledgementState.RESTORED,
                    reopened.titleUpdateAcknowledgement(target, base.eventId, prepared.id)!!.state)
            }
        }

        suspend fun cachedObservationDrainsOfflineOnOriginalRow() {
            var observed: RemoteChange.Upsert? = null
            afterFreshAcknowledgement = { observed = cacheDistinctObservation() }
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            val cached = requireNotNull(observed)
            assertEquals(1, writes.get()); assertEquals(0, inspections.get())
            assertEquals(local.title, values()[Events.TITLE]); assertEquals("0", values()[Events.DIRTY])
            assertReopenedPending(cached)
            assertOriginalCreateProvenance()
            val afterAck = values()
            coordinator.run(network = false)
            val drained = values()
            assertEquals(cached.content.title, drained[Events.TITLE]); assertEquals("0", drained[Events.DIRTY])
            assertEquals(digest("revision-v1", cached.revision.value), drained[Events.SYNC_DATA2])
            for (field in listOf(Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND,
                Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7))
                assertEquals(afterAck[field], drained[field])
            assertSingleOriginalRow(); assertEquals(0, pendingFlag())
            allowSql()
            assertEquals(cached, store.projectedBaseline(target, base.eventId))
            assertEquals(cached, store.transaction(target) { remote(base.eventId) })
            assertNull(store.localEdit(target, base.eventId))
            if (transferred) {
                val advanced = store.capturedHandoff(target, base.eventId)!!
                assertEquals(frame!!.receipt, advanced.frame.receipt)
                assertEquals(cached, advanced.frame.base); assertFalse(advanced.waiting)
            }
            // Replaying the first ACK is historical; it cannot rearm the already settled cache.
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(0, pendingFlag()); assertEquals(drained, values())
            assertEquals(1, writes.get()); assertEquals(0, inspections.get())
            coordinator.run(network = false)
            assertEquals(drained, values()); assertEquals(0, pendingFlag()); assertSingleOriginalRow()
        }

        suspend fun ordinaryCachedDeletionDrainsOfflineWithoutRecreatingTheRow() {
            val deleted = RemoteChange.Deleted(base.eventId, Revision("synthetic-later-cached-deletion"))
            afterFreshAcknowledgement = {
                allowSql()
                store.transaction(target) { putRemote(deleted); putProjection(ProjectionIntent(target, deleted)) }
                assertEquals(0, pendingFlag())
            }
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertSingleOriginalRow(); assertReopenedPending(deleted)
            coordinator.run(network = false)
            context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use { assertEquals(0, it.count) }
            assertEquals(0, pendingFlag()); allowSql()
            assertEquals(deleted, store.transaction(target) { remote(base.eventId) })
            assertNull(store.projectedBaseline(target, base.eventId))
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(0, pendingFlag()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
            coordinator.run(network = false)
            context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use { assertEquals(0, it.count) }
        }

        suspend fun cachedObservationSurvivesRollbackAndNeverOverwritesLaterDirtyEdit() {
            var observed: RemoteChange.Upsert? = null
            afterFreshAcknowledgement = { observed = cacheDistinctObservation() }
            // This failure is after the queue update, so queue/base/owner/receipt must roll back together.
            store.writableDatabase.execSQL("CREATE TRIGGER fixture_title_queue_restore_loss BEFORE UPDATE OF state ON title_update_acknowledgements WHEN NEW.state='RESTORED' BEGIN SELECT RAISE(ABORT,'synthetic restore loss'); END")
            assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
            val cached = requireNotNull(observed)
            assertEquals(0, pendingFlag())
            assertEquals("0", values()[Events.DIRTY])
            assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
            allowSql()
            assertEquals(base, store.projectedBaseline(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(cached, store.transaction(target) { remote(base.eventId) })
            val laterTitle = "Synthetic later dirty title retained offline"
            mutateTitle(laterTitle)
            val laterNative = values()
            store.writableDatabase.execSQL("DROP TRIGGER fixture_title_queue_restore_loss")
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(1, writes.get()); assertEquals(1, inspections.get())
            assertEquals(laterNative, values()); assertReopenedPending(cached)
            assertOriginalCreateProvenance()
            if (transferred) {
                allowSql(); assertFalse(store.capturedHandoff(target, base.eventId)!!.waiting)
            }
            coordinator.run(network = false)
            assertEquals(laterNative, values()); assertEquals("1", values()[Events.DIRTY]); assertSingleOriginalRow()
            allowSql()
            // Prove the transferred drain reached native qualification and explicitly held DIRTY.
            if (transferred) assertTrue(store.capturedHandoff(target, base.eventId)!!.waiting)
            assertEquals(verified, store.projectedBaseline(target, base.eventId))
            val nextId = MutationId("synthetic-later-conflicting-title")
            if (transferred) {
                native.admitOperation = { true }
                val current = store.capturedHandoff(target, base.eventId)!!.frame
                val snapshot = native.captureCapturedHandoffCurrent(current) as CapturedHandoffCurrentObservation.Supported
                assertEquals(LocalEditState.CONFLICT, store.retainCapturedHandoffLocalChange(snapshot.snapshot, nextId))
            } else assertEquals(LocalEditCaptureResult.CONFLICT,
                coordinator.captureLocalEdit(target, base.eventId, nextId))
            allowSql()
            val captured = store.localEdit(target, base.eventId)!!
            assertEquals(nextId, captured.id); assertEquals(verified, captured.base)
            assertEquals(verified.content.copy(title = laterTitle), captured.content)
            assertEquals(LocalEditState.CONFLICT, captured.state)
            assertTrue(store.pending(target).isEmpty())
            assertEquals(cached, store.transaction(target) { remote(base.eventId) })
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            allowSql(); assertEquals(captured, store.localEdit(target, base.eventId))
            coordinator.run(network = false)
            assertEquals(laterNative, values()); assertSingleOriginalRow()
            assertEquals(1, writes.get()); assertEquals(1, inspections.get())
        }

        suspend fun assertSqlRestored(expectedUnknown: TitleUpdateAttemptRecord = unknown) {
            allowSql()
            assertEquals(verified, store.projectedBaseline(target, base.eventId))
            assertEquals(verified, store.transaction(target) { remote(base.eventId) })
            assertNull(store.localEdit(target, base.eventId)); assertNull(store.localEditPreparation(target, base.eventId))
            assertEquals(TitleUpdateAcknowledgementState.RESTORED, receipt()!!.state)
            assertEquals(expectedUnknown, journal.read(target, prepared.id)); assertEquals(result, journal.readVerified(target, prepared.id))
            if (transferred) {
                val current = store.capturedHandoff(target, base.eventId)!!
                assertEquals(CapturedCreateHandoffState.RELEASED, current.state); assertFalse(current.waiting)
                assertEquals(frame!!.receipt, current.frame.receipt)
                assertEquals(verified, current.frame.base)
                assertEquals(receipt()!!.receipt.projection, current.frame.lastProjection)
                assertEquals(frame, receipt()!!.receipt.handoff)
            }
        }
        suspend fun close() {
            try { coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin() }
            finally {
                try {
                    native.admitOperation = { true }; native.removeOwnedCalendar(target)
                    val account = native.accountFor(target.account)
                    val manager = AccountManager.get(context)
                    if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
                        assertTrue(manager.removeAccountExplicitly(account))
                } finally {
                    journal.close(); store.close(); captures.close(); createAttempts.close()
                    context.deleteDatabase(importName); context.deleteDatabase(attemptName)
                    context.deleteDatabase(captureName); context.deleteDatabase(createAttemptName)
                }
            }
        }
    }

    private fun fixture(allDay: Boolean, transferred: Boolean = false, preparedOnly: Boolean = false,
        block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(AndroidCalendarProjection(context).hasPermissions())
        val fixture = Fixture(context, allDay, transferred, preparedOnly)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }

    @Test fun ordinaryTimedGuardedSubmissionCompletesOfflineAfterOwnerReopening() = fixture(allDay = false, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening()
    }
    @Test fun ordinaryAllDayGuardedSubmissionCompletesOfflineAfterOwnerReopening() = fixture(allDay = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening()
    }
    @Test fun transferredTimedGuardedSubmissionCompletesOfflineWithOriginalCreateProvenance() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening()
    }
    @Test fun transferredAllDayGuardedSubmissionCompletesOfflineWithOriginalCreateProvenance() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening()
    }
    @Test fun ordinaryGuardedSubmissionRecoversNativeAcknowledgementGapAndKeepsLaterEdit() = fixture(allDay = false, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening(restoreGap = true)
    }
    @Test fun transferredGuardedSubmissionRecoversNativeAcknowledgementGapAndKeepsLaterEdit() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening(restoreGap = true)
    }
    @Test fun ordinaryGuardedSubmissionRecoversCompletionGapWithoutAnotherNativeWrite() = fixture(allDay = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening(completionGap = true)
    }
    @Test fun transferredGuardedSubmissionRecoversCompletionGapWithoutAnotherNativeWrite() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening(completionGap = true)
    }
    @Test fun ordinaryGuardedSubmissionRefusesNativeChangeBeforeClaim() = fixture(allDay = false, preparedOnly = true) {
        guardedSubmissionPreservesChangedNativeSource(afterSend = false)
    }
    @Test fun transferredGuardedSubmissionRefusesNativeChangeBeforeClaim() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        guardedSubmissionPreservesChangedNativeSource(afterSend = false)
    }
    @Test fun ordinaryGuardedSubmissionKeepsEvidenceAndLaterEditAfterSend() = fixture(allDay = true, preparedOnly = true) {
        guardedSubmissionPreservesChangedNativeSource(afterSend = true)
    }
    @Test fun transferredGuardedSubmissionKeepsEvidenceAndLaterEditAfterSend() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        guardedSubmissionPreservesChangedNativeSource(afterSend = true)
    }

    @Test fun ordinaryPreparedTitleWireRetainsExactNativeOwnerWithoutClaimOrAcknowledgement() = fixture(allDay = false, preparedOnly = true) {
        retainPreparedWireWithoutConsumingNativeOwner()
    }
    @Test fun transferredPreparedTitleWireRetainsExactNativeOwnerAndOriginalCreateProvenance() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        retainPreparedWireWithoutConsumingNativeOwner()
    }
    @Test fun transferredPreparedTitleWireWithoutOriginalAttachmentNeverUsesOrdinaryRetention() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        missingCreateSourceCannotFallBackToOrdinaryWireRetention(dropFrame = false)
    }
    @Test fun transferredPreparedTitleWireWithoutHandoffFrameNeverUsesOrdinaryRetention() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        missingCreateSourceCannotFallBackToOrdinaryWireRetention(dropFrame = true)
    }

    @Test fun composedAcknowledgementKeepsSameAllDayRowAndContentWhileRestoringSql() = fixture(allDay = true) {
        val before = values()
        assertEquals("1", before[Events.DIRTY]); assertEquals(local.title, before[Events.TITLE])
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        val retained = receipt()!!
        val expected = before + mapOf(Events.DIRTY to "0",
            Events.SYNC_DATA2 to digest("revision-v1", verified.revision.value),
            Events.SYNC_DATA3 to "calendeer-projection-receipt-v1:${retained.receipt.projection.nonce}")
        assertEquals(expected, values()); assertSingleOriginalRow()
        assertEquals(1, writes.get()); assertEquals(0, inspections.get())
        assertSqlRestored()
    }

    @Test fun twoOrdinaryAllDayTitleCyclesRetainHistoryAndRecoverCompletionLoss() = fixture(allDay = true) { twoCycles(recoverSecond = true) }
    @Test fun twoOrdinaryTimedTitleCyclesRetainHistoryAndRecoverCompletionLoss() = fixture(allDay = false) { twoCycles() }
    @Test fun twoTransferredAllDayTitleCyclesPreserveOriginAndRecoverCompletionLoss() = fixture(allDay = true, transferred = true) { twoCycles(recoverSecond = true) }
    @Test fun twoTransferredTimedTitleCyclesPreserveOriginAndRecoverCompletionLoss() = fixture(allDay = false, transferred = true) { twoCycles() }

    @Test fun ordinaryMissingTitleProofCompletesThroughInspectionBatchOnSameRow() = fixture(allDay = false) {
        inspectionBatchRecoversMissingProof()
    }
    @Test fun transferredMissingTitleProofCompletesThroughInspectionBatchOnSameRow() = fixture(allDay = true, transferred = true) {
        inspectionBatchRecoversMissingProof()
    }
    @Test fun ordinaryInspectionBatchReopensProofToAcknowledgementGapAndKeepsLaterDirtyEdit() = fixture(allDay = true) {
        inspectionBatchRecoversMissingProof(restoreGap = true)
    }
    @Test fun transferredInspectionBatchReopensProofToAcknowledgementGapAndKeepsLaterDirtyEdit() = fixture(allDay = false, transferred = true) {
        inspectionBatchRecoversMissingProof(restoreGap = true)
    }

    @Test fun scheduledOfflineTitleProofKeepsSameRowAndNextOwnerAfterReopening() = fixture(allDay = false) {
        scheduledOfflineRecoveryPreservesLaterEdit(completionGap = false)
    }
    @Test fun scheduledTransferredTitleRecoveryClosesCompletionGapWithoutConsumingLaterOwner() = fixture(allDay = true, transferred = true) {
        scheduledOfflineRecoveryPreservesLaterEdit(completionGap = true)
    }

    @Test fun ordinaryStoredTitleProofCompletesThroughOfflineBatchOnSameRow() = fixture(allDay = false) {
        offlineBatchClosesStoredProofWithoutAnotherSend()
    }
    @Test fun transferredStoredTitleProofCompletesThroughOfflineBatchOnSameRow() = fixture(allDay = true, transferred = true) {
        offlineBatchClosesStoredProofWithoutAnotherSend()
    }
    @Test fun ordinaryOfflineBatchRecoversCompletionGapAndPreservesLaterDirtyEdit() = fixture(allDay = false) {
        offlineBatchRecoversCompletionGapAfterReopeningAndKeepsLaterDirtyEdit()
    }
    @Test fun transferredOfflineBatchRecoversCompletionGapAndPreservesLaterDirtyEdit() = fixture(allDay = false, transferred = true) {
        offlineBatchRecoversCompletionGapAfterReopeningAndKeepsLaterDirtyEdit()
    }

    @Test fun ordinaryTimedCachedObservationAfterNativeAckDrainsOfflineOnSameRow() = fixture(allDay = false) {
        cachedObservationDrainsOfflineOnOriginalRow()
    }
    @Test fun ordinaryAllDayCachedObservationAfterNativeAckDrainsOfflineOnSameRow() = fixture(allDay = true) {
        cachedObservationDrainsOfflineOnOriginalRow()
    }
    @Test fun ordinaryCachedDeletionAfterNativeAckDrainsOfflineWithoutRecreatingTheRow() = fixture(allDay = false) {
        ordinaryCachedDeletionDrainsOfflineWithoutRecreatingTheRow()
    }
    @Test fun transferredTimedCachedObservationAfterNativeAckDrainsOfflineOnSameRow() = fixture(allDay = false, transferred = true) {
        cachedObservationDrainsOfflineOnOriginalRow()
    }
    @Test fun transferredAllDayCachedObservationAfterNativeAckDrainsOfflineOnSameRow() = fixture(allDay = true, transferred = true) {
        cachedObservationDrainsOfflineOnOriginalRow()
    }
    @Test fun ordinaryCachedQueueRollsBackThenRecoversWithoutOverwritingLaterDirtyEdit() = fixture(allDay = false) {
        cachedObservationSurvivesRollbackAndNeverOverwritesLaterDirtyEdit()
    }
    @Test fun transferredCachedQueueRollsBackThenRecoversWithoutOverwritingLaterDirtyEdit() = fixture(allDay = false, transferred = true) {
        cachedObservationSurvivesRollbackAndNeverOverwritesLaterDirtyEdit()
    }

    @Test fun restoredNativeSqlProofCanCloseUnknownWithoutNativeOrNetwork() = fixture(allDay = false) {
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        val before = values()
        assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
        assertNotNull(journal.readCompletion(target, prepared.id))
        assertEquals(before, values()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
    }

    @Test fun transferredAcknowledgementKeepsAllDayOriginAndAdvancesTheSameFrame() = fixture(allDay = true, transferred = true) {
        val before = values()
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        val retained = receipt()!!
        assertEquals(before + mapOf(Events.DIRTY to "0",
            Events.SYNC_DATA2 to digest("revision-v1", verified.revision.value),
            Events.SYNC_DATA3 to "calendeer-projection-receipt-v1:${retained.receipt.projection.nonce}"), values())
        assertSingleOriginalRow(); assertSqlRestored()
        assertEquals(1, writes.get()); assertEquals(0, inspections.get())
    }

    @Test fun transferredSqlLossPreservesLaterTimedEditAndReplayNeverConsumesItsOwner() = fixture(allDay = false, transferred = true) {
        store.writableDatabase.execSQL("CREATE TRIGGER fixture_handoff_ack_loss BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'synthetic restore loss'); END")
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertEquals("0", values()[Events.DIRTY]); assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
        allowSql(); assertEquals(frame, store.capturedHandoff(target, base.eventId)!!.frame)
        val laterTitle = "Synthetic later transferred title"
        mutateTitle(laterTitle); val laterNative = values()
        store.writableDatabase.execSQL("DROP TRIGGER fixture_handoff_ack_loss")
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals(laterNative, values()); assertSqlRestored()
        val nextId = MutationId("synthetic-next-transferred-intent")
        captureTransferred(nextId)
        val next = store.localEdit(target, base.eventId)!!
        assertEquals(nextId, next.id); assertEquals(verified, next.base); assertEquals(laterTitle, next.content.title)
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        allowSql(); assertEquals(next, store.localEdit(target, base.eventId)); assertEquals(laterNative, values())
        assertEquals(1, writes.get()); assertEquals(1, inspections.get()); assertSingleOriginalRow()
    }

    @Test fun realSqlFailureAfterNativeCommitRecoversByInspectionAndPreservesLaterDirtyTimedEdit() = fixture(allDay = false) {
        store.writableDatabase.execSQL("CREATE TRIGGER fixture_title_ack_restore_loss BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'synthetic restore loss'); END")
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        val retained = receipt()!!
        assertEquals(TitleUpdateAcknowledgementState.PENDING, retained.state)
        val committedNative = values()
        assertEquals("0", committedNative[Events.DIRTY]); assertEquals(local.title, committedNative[Events.TITLE])
        assertEquals(digest("revision-v1", verified.revision.value), committedNative[Events.SYNC_DATA2])
        assertEquals("calendeer-projection-receipt-v1:${retained.receipt.projection.nonce}", committedNative[Events.SYNC_DATA3])
        allowSql()
        assertEquals(base, store.projectedBaseline(target, base.eventId))
        assertEquals(preparation, store.localEditPreparation(target, base.eventId))
        val latest = local.copy(title = "Synthetic later unsent system edit")
        mutateTitle(latest.title)
        val laterNative = values()
        store.writableDatabase.execSQL("DROP TRIGGER fixture_title_ack_restore_loss")
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals(laterNative, values()); assertEquals("1", values()[Events.DIRTY]); assertSingleOriginalRow()
        assertEquals(1, writes.get()); assertEquals(1, inspections.get())
        assertSqlRestored()
        val nextId = MutationId(UUID.randomUUID().toString())
        assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, nextId))
        allowSql()
        val next = store.localEdit(target, base.eventId)!!
        assertEquals(nextId, next.id); assertEquals(verified, next.base); assertEquals(latest, next.content)
        assertEquals(LocalEditState.PAUSED, next.state); assertEquals(laterNative, values())
    }

    private companion object {
        fun digest(vararg parts: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
            parts.forEach { part -> val bytes = part.toByteArray(Charsets.UTF_8)
                hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes) }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
