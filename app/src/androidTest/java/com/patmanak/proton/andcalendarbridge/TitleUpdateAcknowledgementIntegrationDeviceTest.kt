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
    private class Fixture(val context: Context, allDay: Boolean) {
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
            native.apply(ProjectionIntent(target, base))
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); row = it.getLong(0)
            }
            mutateTitle(local.title)
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
            assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, firstId))
            allowSql()
            assertEquals(intent, store.localEdit(target, base.eventId))
            assertTrue(store.retainLocalEditPreparation(preparation))
            journal.remember(prepared); journal.admitDispatch = { it == target }; assertTrue(journal.claim(prepared))
            journal.admitDispatch = { error("Native acknowledgement must never admit another send") }
            journal.retainVerified(result)
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
        suspend fun receipt(): TitleUpdateAcknowledgementRecord? { allowSql(); return store.titleUpdateAcknowledgement(target, base.eventId) }
        suspend fun assertSqlRestored() {
            allowSql()
            assertEquals(verified, store.projectedBaseline(target, base.eventId))
            assertEquals(verified, store.transaction(target) { remote(base.eventId) })
            assertNull(store.localEdit(target, base.eventId)); assertNull(store.localEditPreparation(target, base.eventId))
            assertEquals(TitleUpdateAcknowledgementState.RESTORED, receipt()!!.state)
            assertEquals(unknown, journal.read(target, prepared.id)); assertEquals(result, journal.readVerified(target, prepared.id))
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

    private fun fixture(allDay: Boolean, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(AndroidCalendarProjection(context).hasPermissions())
        val fixture = Fixture(context, allDay)
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
