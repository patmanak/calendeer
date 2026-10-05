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
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
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
    private class Fixture(val context: Context, allDay: Boolean, val transferred: Boolean = false) {
        private val token = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("title-ack-integration-$token"), CalendarId("fixture"), 0)
        private val importName = "title-ack-integration-$token.db"
        private val attemptName = "title-ack-integration-attempt-$token.db"
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
        private val projection = object : CalendarProjection by native {
            override suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                writes.incrementAndGet()
                return native.acknowledgeTitleUpdate(receipt)
            }
            override suspend fun inspectTitleUpdateAcknowledgement(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                inspections.incrementAndGet()
                return native.inspectTitleUpdateAcknowledgement(receipt)
            }
        }
        val store = ImportStore(context, importName)
        val journal = TitleUpdateAttemptStore(context, attemptName)
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = target.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("Synthetic result fixture cannot download")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("Synthetic result fixture cannot upload")
        }
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            systemCreatesEnabled = { it == target })
        private var calendar = -1L
        var row = -1L
            private set
        private val uri get() = syncUri(Events.CONTENT_URI)
        var frame: CapturedCreateHandoffFrame? = null
            private set

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
            val attachment = CapturedCreateAttachmentReceipt(CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission),
                base.eventId, base.revision), UUID.randomUUID().toString())
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
        suspend fun assertSqlRestored() {
            allowSql()
            assertEquals(verified, store.projectedBaseline(target, base.eventId))
            assertEquals(verified, store.transaction(target) { remote(base.eventId) })
            assertNull(store.localEdit(target, base.eventId)); assertNull(store.localEditPreparation(target, base.eventId))
            assertEquals(TitleUpdateAcknowledgementState.RESTORED, receipt()!!.state)
            assertEquals(unknown, journal.read(target, prepared.id)); assertEquals(result, journal.readVerified(target, prepared.id))
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
                    journal.close(); store.close()
                    context.deleteDatabase(importName); context.deleteDatabase(attemptName)
                }
            }
        }
    }

    private fun fixture(allDay: Boolean, transferred: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(AndroidCalendarProjection(context).hasPermissions())
        val fixture = Fixture(context, allDay, transferred)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
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
