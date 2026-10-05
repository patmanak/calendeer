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
import com.patmanak.proton.andcalendarbridge.data.calendar.*
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

/** Actual Provider keys resolve through scoped SQLite ID metadata; no service or upload. */
@RunWith(AndroidJUnit4::class)
class SystemImportedChangeResolutionDeviceTest {
    @Test fun importedNativeEditResolvesTheOriginalRemoteIdBeforeDurableCapture() = fixture {
        mutate(ContentValues().apply { put(Events.TITLE, "Resolution fixture local edit"); put(Events.DIRTY, 1) })
        val raw = native.discoverSystemChanges(target).changes.single()
        assertEquals(SystemCalendarChangeKind.IMPORTED, raw.kind)
        assertNull(raw.event)
        assertEquals(native.eventIdentityKey(target, base.eventId), raw.nativeKey)
        assertNotEquals(base.eventId.value, raw.nativeKey)
        val change = coordinator.discoverSystemChanges(target)!!.changes.single()
        assertEquals(base.eventId, change.event)
        assertEquals(LocalEditCaptureResult.RETAINED,
            coordinator.captureLocalEdit(target, change.event!!, MutationId("resolved-edit")))
        store.admitTransaction = { it == target }
        assertEquals(base.eventId, store.localEdit(target, base.eventId)!!.base.eventId)
        assertNull(store.localEdit(target, EventId(raw.nativeKey!!)))
        assertEquals(1, field(Events.DIRTY)!!.toInt())
    }

    @Test fun importedNativeTombstoneResolvesTheOriginalRemoteIdBeforeDurableDeleteCapture() = fixture {
        mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        val change = coordinator.discoverSystemChanges(target)!!.changes.single()
        assertEquals(base.eventId, change.event); assertTrue(change.deleted)
        assertEquals(LocalEditCaptureResult.RETAINED,
            coordinator.captureLocalDelete(target, change.event!!, MutationId("resolved-delete")))
        store.admitTransaction = { it == target }
        assertEquals(base.eventId, store.localDelete(target, base.eventId)!!.base.eventId)
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun unknownForeignAndSeriesNativeKeysNeverBecomeRemoteIdentifiers() = fixture {
        val foreign = target.copy(account = AccountId("resolution-foreign"))
        for (key in listOf("unrecognized-fixture-key", native.eventIdentityKey(foreign, base.eventId))) {
            mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.SYNC_DATA4, key) })
            val change = coordinator.discoverSystemChanges(target)!!.changes.single()
            assertEquals(SystemCalendarChangeKind.IMPORTED, change.kind)
            assertEquals(key, change.nativeKey); assertNull(change.event)
        }
        mutate(ContentValues().apply { putNull(Events.SYNC_DATA4); put(Events._SYNC_ID, "series-fixture-key") })
        assertNull(coordinator.discoverSystemChanges(target)!!.changes.single().event)
        assertNull(coordinator.discoverSystemChanges(foreign))
        store.admitTransaction = { it == target }
        assertNull(store.localEdit(target, base.eventId)); assertNull(store.localDelete(target, base.eventId))
    }

    @Test fun legacyNativeKeyResolvesAcrossMetadataPagesWithoutDecodingPayloads() = fixture {
        repeat(105) { index ->
            val extra = base.copy(eventId = EventId("a-fixture-${index.toString().padStart(3, '0')}"))
            store.admitTransaction = { it == target }
            store.transaction(target) { putRemote(extra); putProjection(ProjectionIntent(target, extra)) }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, extra)))
        }
        // These baselines serve only as ID metadata; a decoder-based resolution would fail.
        store.writableDatabase.execSQL("UPDATE projection_bases SET payload=? WHERE event LIKE ?",
            arrayOf("malformed fixture payload", "a-fixture-%"))
        mutate(ContentValues().apply { put(Events.DIRTY, 1); putNull(Events.SYNC_DATA4) })
        assertEquals(base.eventId, coordinator.discoverSystemChanges(target, limit = 1)!!.changes.single().event)
        store.admitTransaction = { it == target }
        val first = store.projectedEventIds(target, limit = 100)
        val second = store.projectedEventIds(target, first.last(), 100)
        assertEquals(100, first.size); assertEquals(6, second.size)
        assertEquals(106, (first + second).toSet().size)
        assertEquals(base.eventId, second.last())
    }

    @Test fun ambiguousNativeIdentityKeysAndClosedAdmissionsStayUnresolved() = fixture {
        val extra = base.copy(eventId = EventId("different-fixture-id"))
        store.admitTransaction = { it == target }
        store.transaction(target) { putRemote(extra); putProjection(ProjectionIntent(target, extra)) }
        store.acknowledge(ProjectionIntent(target, extra))
        collision = true
        mutate(ContentValues().apply { put(Events.DIRTY, 1) })
        assertNull(coordinator.discoverSystemChanges(target)!!.changes.single().event)
        mode = false
        assertNull(coordinator.discoverSystemChanges(target))
        mode = true
        store.setEnabled(target, false)
        assertNull(coordinator.discoverSystemChanges(target))
    }

    private class Fixture(val context: Context) {
        val token = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("resolution-fixture-$token"), CalendarId("fixture"), 0)
        val name = "resolution-fixture-$token.db"
        val format = AdapterFormat("resolution-fixture", 1)
        val base = RemoteChange.Upsert(EventId("z-original-proton-fixture-id"), Revision("fixture-base"),
            EventContent("Resolution fixture baseline", EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"),
                Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")), details = EventDetails()))
        val native = AndroidCalendarProjection(context)
        val store = ImportStore(context, name)
        var mode = true
        var collision = false
        val projection = object : CalendarProjection by native {
            override fun eventIdentityKey(scope: CalendarScope, event: EventId) =
                native.eventIdentityKey(scope, if (collision) base.eventId else event)
        }
        val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = target.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No fixture downloads")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No fixture uploads")
        }
        val schedules = AtomicInteger()
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            systemCreatesEnabled = { it == target && mode })
        var calendar = -1L
        fun syncUri(uri: Uri): Uri {
            val account = native.accountFor(target.account)
            return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        }
        suspend fun seed() {
            val summary = CalendarSummary(target.calendar, "Calendeer ID resolution fixture")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            calendar = native.ensureCalendar(target, summary)
            store.transaction(target) {
                putRemote(base); putProjection(ProjectionIntent(target, base))
                checkpoint = PageCheckpoint(target, format, traversalComplete = true)
            }
            native.apply(ProjectionIntent(target, base))
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
        }
        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(syncUri(Events.CONTENT_URI), values,
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString())))
        }
        fun field(column: String): String? = context.contentResolver.query(syncUri(Events.CONTENT_URI), arrayOf(column),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); context.deleteDatabase(name)
            native.admitOperation = { true }
            native.removeOwnedCalendar(target)
            val account = native.accountFor(target.account)
            val manager = AccountManager.get(context)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
                assertTrue(manager.removeAccountExplicitly(account))
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        assumeTrue(fixture.native.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
