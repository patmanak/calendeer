// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
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

/** Isolated production Provider/SQLite/coordinator recovery; controlled remote, no service writes. */
@RunWith(AndroidJUnit4::class)
class ProjectionRecoveryDeviceTest {
    @Test fun dirtyEditAfterLostProjectionAckRetainsTheActuallyProjectedBaseAfterReopening() = runBlocking {
        withFixture { f ->
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            val row = f.row()
            f.queue(f.latest)
            f.ackFailure(true)
            try { f.coordinator.run(false) } finally { f.ackFailure(false) }
            assertEquals(f.latest.content.title, f.field(Events.TITLE))
            assertEquals(row, f.row())
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(f.latest, f.store.transaction(f.target) { remote(f.base.eventId) })
            f.edit("Recovery local edit after projection")
            f.reopen()
            assertEquals(LocalEditCaptureResult.RETAINED,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-edit")))
            f.store.admitTransaction = { it == f.target }
            val retained = f.store.localEdit(f.target, f.base.eventId)!!
            assertEquals(f.latest, retained.base)
            assertEquals(f.latest.content.copy(title = "Recovery local edit after projection"), retained.content)
            assertEquals(LocalEditState.PAUSED, retained.state)
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals(row, f.row())
            assertEquals("Recovery local edit after projection", f.field(Events.TITLE))
            assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun cleanLostAcknowledgementRecoversOfflineWithoutDuplicatingTheRow() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            val row = f.row()
            f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertEquals(f.latest, receipt.change)
            assertNotNull(f.native.inspectProjectionReceipt(receipt))
            f.reopen()
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(row, f.row())
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty())
            assertNull(f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals("0", f.field(Events.DIRTY))
        }
    }

    @Test fun receiptPreparedBeforeARefusedWriteCannotPromoteTheRemoteVersion() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            val row = f.row()
            f.edit("Recovery edit before projection")
            f.queue(f.latest)
            f.coordinator.run(false)
            val unapplied = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertNull(f.native.inspectProjectionReceipt(unapplied))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            f.reopen()
            assertEquals(LocalEditCaptureResult.CONFLICT,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-edit")))
            f.store.admitTransaction = { it == f.target }
            val retained = f.store.localEdit(f.target, f.base.eventId)!!
            assertEquals(f.base, retained.base)
            assertEquals("Recovery edit before projection", retained.content.title)
            assertEquals(f.latest, f.store.transaction(f.target) { remote(f.base.eventId) })
            assertEquals(row, f.row()); assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun newerRemoteCacheDoesNotReplaceTheAppliedReceiptUsedAsAnEditBase() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            val third = f.latest.copy(revision = Revision("v3"), content = f.latest.content.copy(title = "Recovery remote v3"))
            f.queue(third)
            f.edit("Recovery local edit against v2")
            f.reopen()
            assertEquals(LocalEditCaptureResult.CONFLICT,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-edit")))
            f.store.admitTransaction = { it == f.target }
            val retained = f.store.localEdit(f.target, f.base.eventId)!!
            assertEquals(f.latest, retained.base)
            assertEquals(f.latest.content.copy(title = "Recovery local edit against v2"), retained.content)
            assertEquals(third, f.store.transaction(f.target) { remote(f.base.eventId) })
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals("Recovery local edit against v2", f.field(Events.TITLE))
        }
    }

    @Test fun matchingRevisionAloneCannotWitnessANewUnappliedNonce() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.store.requeueAll(f.target)
            val prepared = f.store.prepareProjection(ProjectionIntent(f.target, f.base), f.format)
            assertNull("The old row has the same revision but a different projection nonce",
                f.native.inspectProjectionReceipt(prepared))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals("0", f.field(Events.DIRTY))
            assertEquals(f.base.content.title, f.field(Events.TITLE))
        }
    }

    @Test fun missingNonceLeavesAChangedDirtyProjectionUnqualified() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            f.edit("Recovery local edit without receipt marker")
            f.mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) })
            f.reopen()
            assertEquals(LocalEditCaptureResult.UNQUALIFIED,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-edit")))
            f.store.admitTransaction = { it == f.target }
            assertNull(f.store.localEdit(f.target, f.base.eventId))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
            assertEquals("Recovery local edit without receipt marker", f.field(Events.TITLE))
            assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun tombstoneMarkerCanProvePastProjectionWithoutResurrectingTheEvent() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            val row = f.row()
            f.mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
            assertNotNull(f.native.inspectProjectionReceipt(receipt))
            assertEquals("1", f.field(Events.DELETED)); assertEquals("1", f.field(Events.DIRTY))
            f.reopen()
            f.coordinator.run(false)
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty())
            assertEquals(row, f.row())
            assertEquals("1", f.field(Events.DELETED)); assertEquals("1", f.field(Events.DIRTY))
        }
    }

    @Test fun recreatedMirrorCannotWitnessTheDisappearedProjection() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            f.native.removeOwnedCalendar(f.target)
            f.recreateCalendar()
            assertNull(f.native.inspectProjectionReceipt(receipt))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            f.reopen()
            assertEquals(ImportRunResult.COMPLETE, f.coordinator.run(false))
            assertEquals(f.latest.content.title, f.field(Events.TITLE))
            assertEquals(f.latest, f.store.projectedBaseline(f.target, f.base.eventId))
            assertTrue(f.store.pending(f.target).isEmpty())
        }
    }

    @Test fun duplicateIdentityCannotWitnessOrRecoverAProjection() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            f.duplicate()
            var refused = false
            try { refused = f.native.inspectProjectionReceipt(receipt) == null }
            catch (_: com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException) { refused = true }
            assertTrue("Duplicate ownership cannot prove the applied receipt", refused)
            f.reopen()
            f.coordinator.run(false)
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
            assertEquals(2, f.rowCount())
        }
    }

    @Test fun missingNonceAndReusedOpaqueRevisionCannotChooseAnOlderEditBase() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            val ambiguous = f.base.copy(content = f.base.content.copy(title = "Recovery different content same revision"))
            f.queue(ambiguous)
            f.ackFailure(true)
            try { f.coordinator.run(false) } finally { f.ackFailure(false) }
            assertEquals(ambiguous.content.title, f.field(Events.TITLE))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            assertEquals(ambiguous, receipt.change)
            f.mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) })
            f.edit("Recovery local edit with ambiguous base")
            f.reopen()
            assertEquals("Equal opaque revisions cannot disambiguate two different projected contents",
                LocalEditCaptureResult.UNQUALIFIED,
                f.coordinator.captureLocalEdit(f.target, f.base.eventId, MutationId("fixture-edit")))
            f.store.admitTransaction = { it == f.target }
            assertNull(f.store.localEdit(f.target, f.base.eventId))
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(ambiguous, f.store.transaction(f.target) { remote(f.base.eventId) })
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, ambiguous)), f.store.pending(f.target))
            assertEquals("Recovery local edit with ambiguous base", f.field(Events.TITLE))
            assertEquals("1", f.field(Events.DIRTY))
            assertNull(f.field(Events.SYNC_DATA3))
        }
    }

    @Test fun nonceChangedBeforeProofAssertionCannotRestoreTheProjectionBaseline() = runBlocking {
        withFixture { f ->
            f.coordinator.run(false)
            f.projectLatestWithoutAcknowledgement()
            val receipt = f.store.projectionReceipt(f.target, f.base.eventId)!!
            val real = f.context.contentResolver
            var injected = false
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Receipt inspection must not insert")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
                    error("Receipt inspection must not update")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
                    error("Receipt inspection must not delete")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? =
                    real.query(uri, columns, selection, args, order)
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertTrue("Proof batch must contain assertions only", operations.all { it.isAssertQuery })
                    injected = true
                    f.mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) })
                    return real.applyBatch(CalendarContract.AUTHORITY, operations)
                }
            }
            val wrappedResolver = ContentResolver.wrap(provider)
            val wrappedContext = object : ContextWrapper(f.context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = wrappedResolver
            }
            var refused = false
            try {
                val witness = AndroidCalendarProjection(wrappedContext).inspectProjectionReceipt(receipt)
                refused = witness == null
                if (witness != null) f.store.restoreProjection(witness)
            } catch (_: com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException) { refused = true }
            assertTrue("An invalidated nonce must not qualify a projection witness", refused)
            assertTrue("Race must occur immediately before the atomic proof assertions", injected)
            assertEquals(f.base, f.store.projectedBaseline(f.target, f.base.eventId))
            assertEquals(receipt, f.store.projectionReceipt(f.target, f.base.eventId))
            assertEquals(listOf(ProjectionIntent(f.target, f.latest)), f.store.pending(f.target))
            assertNull(f.field(Events.SYNC_DATA3))
            assertEquals(f.latest.content.title, f.field(Events.TITLE))
        }
    }

    private class Fixture(val context: Context) {
        private val token = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("projection-recovery-$token"), CalendarId("fixture"), 0)
        private val name = "projection-recovery-$token.db"
        val native = AndroidCalendarProjection(context)
        val format = AdapterFormat("projection-recovery-fixture", 1)
        val base = RemoteChange.Upsert(EventId("fixture"), Revision("v1"), EventContent("Recovery base v1",
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"),
                ZoneId.of("Europe/Paris")), details = EventDetails()))
        val latest = base.copy(revision = Revision("v2"), content = base.content.copy(title = "Recovery base v2"))
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
            override suspend fun fetch(request: PageRequest): RemotePage = error("Recovery must not download")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("Recovery must not upload")
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var coordinator = ImportCoordinator(context, store, remote, native, jobs, scheduler)
        private var calendar = -1L
        private val uri get() = Events.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, native.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, native.accountFor(target.account).type).build()

        suspend fun initialize() {
            val summary = CalendarSummary(target.calendar, "Calendeer projection recovery fixture")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            calendar = native.ensureCalendar(target, summary)
            queue(base)
            store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true) }
            start()
        }

        private suspend fun start() {
            val before = schedules.get()
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
        }

        suspend fun queue(change: RemoteChange.Upsert) = store.transaction(target) {
            putRemote(change); putProjection(ProjectionIntent(target, change))
        }

        suspend fun projectLatestWithoutAcknowledgement() {
            queue(latest); ackFailure(true)
            try { coordinator.run(false) } finally { ackFailure(false) }
            assertEquals(latest.content.title, field(Events.TITLE))
            assertEquals(base, store.projectedBaseline(target, base.eventId))
            assertEquals(listOf(ProjectionIntent(target, latest)), store.pending(target))
        }

        suspend fun recreateCalendar() {
            calendar = native.ensureCalendar(target, CalendarSummary(target.calendar, "Calendeer projection recovery fixture"))
        }

        fun ackFailure(enabled: Boolean) {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (enabled) db.execSQL("CREATE TRIGGER fixture_fail_projection_ack BEFORE UPDATE OF pending ON records " +
                    "WHEN NEW.pending=0 BEGIN SELECT RAISE(ABORT, 'fixture interrupted projection acknowledgement'); END")
                else db.execSQL("DROP TRIGGER IF EXISTS fixture_fail_projection_ack")
            }
        }

        fun row(): Long = context.contentResolver.query(uri, arrayOf(Events._ID),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
        }

        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }

        fun edit(title: String) {
            mutate(ContentValues().apply {
                put(Events.TITLE, title); put(Events.DIRTY, 1)
            })
        }

        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(uri, values,
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString())))
        }

        fun rowCount() = context.contentResolver.query(uri, arrayOf(Events._ID),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { it.count }

        fun duplicate() {
            val columns = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
                Events.SYNC_DATA4, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.DTSTART, Events.DTEND)
            val values = context.contentResolver.query(uri, columns, "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); ContentValues().apply {
                    columns.forEachIndexed { index, column -> put(column, it.getString(index)) }
                }
            }.apply { put(Events.CALENDAR_ID, calendar); put(Events.TITLE, "Recovery duplicate fixture") }
            assertNotNull(context.contentResolver.insert(uri, values))
        }

        suspend fun reopen() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin(); store.close()
            store = ImportStore(context, name)
            jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, native, jobs, scheduler)
            start()
        }

        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); context.deleteDatabase(name)
            native.admitOperation = { true }; native.removeOwnedCalendar(target)
            val account = native.accountFor(target.account)
            val manager = AccountManager.get(context)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
                assertTrue(manager.removeAccountExplicitly(account))
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(account.name + ":" + target.calendar.value).commit()
        }
    }

    private suspend fun withFixture(test: suspend (Fixture) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        assumeTrue(fixture.native.hasPermissions())
        try { fixture.initialize(); test(fixture) } finally { fixture.close() }
    }
}
