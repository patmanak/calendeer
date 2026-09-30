// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production SQLite/Provider/owner with a controlled remote; no live service deletion. */
@RunWith(AndroidJUnit4::class)
class LocalDeleteCaptureIntegrationDeviceTest {
    @Test fun simpleDeletedRowIsRetainedOfflineWithoutAcknowledgingItsFlags() = fixture {
        tombstone()
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        allowReads()
        assertEquals(LocalDeleteIntent(id, target, format, base, base.content), store.localDelete(target, base.eventId))
        assertTrue(store.pending(target).isEmpty())
        assertEquals(base, store.projectedBaseline(target, base.eventId))
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
        reopen()
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        allowReads(); assertEquals(base.content, store.localDelete(target, base.eventId)!!.content)
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun editedThenDeletedSnapshotKeepsTheLastLocalContent() = fixture {
        mutate(ContentValues().apply { put(Events.TITLE, "Delete fixture modified before deletion") })
        tombstone()
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        allowReads()
        val retained = store.localDelete(target, base.eventId)!!
        assertEquals(base, retained.base)
        assertEquals(base.content.copy(title = "Delete fixture modified before deletion"), retained.content)
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun newerImportAndRemoteDeletionKeepTheLocalDeletionInStickyConflict() = fixture {
        tombstone(); queue(latest)
        assertEquals(LocalEditCaptureResult.CONFLICT, capture())
        allowReads()
        assertEquals(LocalEditState.CONFLICT, store.localDelete(target, base.eventId)!!.state)
        store.transaction(target) { putRemote(RemoteChange.Deleted(base.eventId, Revision("remote-deleted"))) }
        store.transaction(target) { putRemote(base) }
        store.requeueAll(target)
        assertTrue(store.pending(target).isEmpty())
        assertEquals(LocalEditState.CONFLICT, store.localDelete(target, base.eventId)!!.state)
        reopen(); assertEquals(LocalEditCaptureResult.CONFLICT, capture())
        allowReads(); assertEquals(base, store.localDelete(target, base.eventId)!!.base)
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun lostAcknowledgementRecoversTheProjectedBaseBeforeRetainingATombstone() = fixture {
        projectWithoutAck(latest)
        tombstone(); reopen()
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        allowReads()
        assertEquals(latest, store.localDelete(target, base.eventId)!!.base)
        assertEquals(latest.content, store.localDelete(target, base.eventId)!!.content)
        assertNull(store.projectionReceipt(target, base.eventId))
        assertTrue(store.pending(target).isEmpty())
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun missingNonceAndEqualOpaqueRevisionLeaveTheDeletionUnqualified() = fixture {
        val ambiguous = base.copy(content = base.content.copy(title = "Delete fixture different content same revision"))
        projectWithoutAck(ambiguous)
        val receipt = store.projectionReceipt(target, base.eventId)!!
        mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) }); tombstone(); reopen()
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture())
        allowReads()
        assertNull(store.localDelete(target, base.eventId))
        assertEquals(base, store.projectedBaseline(target, base.eventId))
        assertEquals(ambiguous, store.transaction(target) { remote(base.eventId) })
        assertEquals(receipt, store.projectionReceipt(target, base.eventId))
        assertEquals(ambiguous.content.title, field(Events.TITLE))
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    private class Fixture(val context: Context) {
        private val token = UUID.randomUUID().toString()
        private val name = "delete-integration-$token.db"
        val target = CalendarScope(AccountId("delete-integration-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("delete-integration-fixture", 1)
        val id = MutationId("delete")
        val base = RemoteChange.Upsert(EventId("event"), Revision("base"), EventContent("Delete integration fixture",
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"),
                ZoneId.of("Europe/Paris")), details = EventDetails()))
        val latest = base.copy(revision = Revision("newer"), content = base.content.copy(title = "Delete integration newer remote"))
        val projection = AndroidCalendarProjection(context)
        var store = ImportStore(context, name)
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
            override suspend fun fetch(request: PageRequest): RemotePage = error("No downloads")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No uploads or service deletions")
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
        private var calendar = -1L
        private val uri get() = Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()
        suspend fun seed() {
            val summary = CalendarSummary(target.calendar, "Calendeer local delete integration fixture")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            calendar = projection.ensureCalendar(target, summary)
            queue(base); store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true) }
            start(); assertEquals(ImportRunResult.COMPLETE, coordinator.run(false))
            assertEquals(base, store.projectedBaseline(target, base.eventId))
        }
        private suspend fun start() {
            val before = schedules.get(); coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
        }
        suspend fun queue(change: RemoteChange) = store.transaction(target) {
            putRemote(change); putProjection(ProjectionIntent(target, change))
        }
        fun allowReads() { store.admitTransaction = { it == target } }
        suspend fun capture() = coordinator.captureLocalDelete(target, base.eventId, id)
        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(uri, values,
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString())))
        }
        fun tombstone() = mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        suspend fun projectWithoutAck(change: RemoteChange.Upsert) {
            queue(change)
            fun trigger(enabled: Boolean) = SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (enabled) db.execSQL("CREATE TRIGGER fixture_fail_delete_ack BEFORE UPDATE OF pending ON records " +
                    "WHEN NEW.pending=0 BEGIN SELECT RAISE(ABORT, 'fixture projection ack interrupted'); END")
                else db.execSQL("DROP TRIGGER IF EXISTS fixture_fail_delete_ack")
            }
            trigger(true); try { coordinator.run(false) } finally { trigger(false) }
            assertEquals(change.content.title, field(Events.TITLE))
            assertEquals(base, store.projectedBaseline(target, base.eventId))
        }
        suspend fun reopen() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin(); store.close()
            store = ImportStore(context, name); jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler); start()
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin(); store.close()
            context.deleteDatabase(name); projection.admitOperation = { true }; projection.removeOwnedCalendar(target)
            val account = projection.accountFor(target.account)
            AccountManager.get(context).removeAccountExplicitly(account)
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(account.name + ":" + target.calendar.value).commit()
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
