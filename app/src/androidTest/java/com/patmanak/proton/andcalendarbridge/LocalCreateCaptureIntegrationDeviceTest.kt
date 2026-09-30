// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
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

/** Real Native Provider + independent SQLite journals + owner; no live CREATE, import, or dispatch. */
@RunWith(AndroidJUnit4::class)
class LocalCreateCaptureIntegrationDeviceTest {
    @Test fun simpleCreatedRowIsRetainedOfflineWithItsDirtyFlagAfterReopening() = fixture {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        assertEquals(LocalCreateIntent(reservation, content), retained())
        val marker = field(Events.SYNC_DATA5)
        assertNotNull(marker); assertEquals("1", field(Events.DIRTY)); assertEquals("0", field(Events.DELETED))
        mutate(ContentValues().apply { put(Events.TITLE, "Create integration subsequent edit") })
        reopen()
        assertEquals(LocalEditCaptureResult.RETAINED, capture(hint = Long.MAX_VALUE, time = timestamp.plusSeconds(60)))
        assertEquals(LocalCreateIntent(reservation, content), retained())
        assertEquals("Create integration subsequent edit", field(Events.TITLE))
        assertEquals(marker, field(Events.SYNC_DATA5)); assertEquals("1", field(Events.DIRTY))
    }

    @Test fun allDayCreateRetainsExclusiveDatesWithoutAcknowledgingTheProvider() = fixture(allDay = true) {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        assertEquals(content, retained()!!.content)
        assertEquals("UTC", field(Events.EVENT_TIMEZONE)); assertEquals("1", field(Events.ALL_DAY))
        reopen(); assertEquals(LocalEditCaptureResult.RETAINED, capture(time = timestamp.plusSeconds(60)))
        assertEquals(reservation, retained()!!.reservation)
        assertEquals("1", field(Events.DIRTY)); assertEquals("0", field(Events.DELETED))
    }

    @Test fun lostRetainAfterARealStampReopensUsingOnlyTheMarkerAndFrozenIdentity() = fixture {
        failRetain(true)
        try { assertEquals(LocalEditCaptureResult.UNAVAILABLE, capture()) } finally { failRetain(false) }
        val marker = field(Events.SYNC_DATA5)
        assertNotNull(marker); assertNull(retained())
        journal.admitCapture = { it == target }; assertEquals(reservation, journal.readReservation(target, localId))
        mutate(ContentValues().apply { put(Events.TITLE, "Create integration first committed recovery") })
        reopen()
        assertEquals(LocalEditCaptureResult.RETAINED, capture(hint = Long.MAX_VALUE, time = timestamp.plusSeconds(60)))
        assertEquals(LocalCreateIntent(reservation, content.copy(title = "Create integration first committed recovery")), retained())
        assertEquals(marker, field(Events.SYNC_DATA5)); assertEquals("1", field(Events.DIRTY))
    }

    @Test fun reservationBeforeStampCannotCaptureAReplacementUsingTheOldRowHint() = fixture {
        journal.admitCapture = { it == target }; assertTrue(journal.reserve(reservation))
        assertEquals(1, context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString())))
        row = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues().apply {
            put(Events.TITLE, "Create integration replacement candidate")
        })!!)
        reopen()
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture())
        assertNull(retained()); assertNull(field(Events.SYNC_DATA5))
        assertEquals("Create integration replacement candidate", field(Events.TITLE))
        assertEquals("1", field(Events.DIRTY)); assertEquals("0", field(Events.DELETED))
    }

    @Test fun duplicateStampedTombstonePreventsOrphanRecoveryWithoutChangingEitherRow() = fixture {
        failRetain(true)
        try { assertEquals(LocalEditCaptureResult.UNAVAILABLE, capture()) } finally { failRetain(false) }
        val marker = field(Events.SYNC_DATA5)
        assertNotNull(marker)
        val duplicate = candidateValues().apply {
            put(Events.SYNC_DATA5, marker); put(Events.TITLE, "Create integration duplicate tombstone"); put(Events.DELETED, 1)
        }
        assertNotNull(context.contentResolver.insert(uri, duplicate))
        reopen()
        assertEquals(LocalEditCaptureResult.UNQUALIFIED, capture(hint = Long.MAX_VALUE))
        assertNull(retained()); assertEquals(marker, field(Events.SYNC_DATA5)); assertEquals("1", field(Events.DIRTY))
        context.contentResolver.query(uri, arrayOf(Events._ID, Events.DELETED, Events.SYNC_DATA5),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
            assertEquals(2, it.count)
            var deleted = 0
            while (it.moveToNext()) { deleted += it.getInt(1); assertEquals(marker, it.getString(2)) }
            assertEquals(1, deleted)
        }
    }

    private class Fixture(val context: Context, allDay: Boolean) {
        private val token = UUID.randomUUID().toString()
        private val importName = "create-integration-import-$token.db"
        private val createName = "create-integration-capture-$token.db"
        val target = CalendarScope(AccountId("create-integration-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("create-integration-fixture", 1)
        val localId = LocalEventId(UUID.randomUUID().toString())
        val id = MutationId("create")
        val timestamp = Instant.parse("2030-10-01T12:00:00Z")
        val reservation = LocalCreateReservation(id, localId, target, format, timestamp)
        val content = EventContent("Create integration fixture", if (allDay)
            EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 1)) else
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails())
        val projection = AndroidCalendarProjection(context)
        val store = ImportStore(context, importName)
        var journal = LocalCreateStore(context, createName)
        var calendar = -1L
        var row = -1L
        val uri get() = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(target.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(target.account).type).build()
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount() = target.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("No downloads")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No CREATE dispatch")
        }
        private val schedules = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, localCreates = journal)
        suspend fun seed() {
            val summary = CalendarSummary(target.calendar, "Calendeer local create integration fixture")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            store.transaction(target) { checkpoint = PageCheckpoint(target, format, traversalComplete = true) }
            calendar = projection.ensureCalendar(target, summary)
            row = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues())!!)
            start()
        }
        private suspend fun start() {
            val before = schedules.get(); coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == before) delay(10) }
        }
        fun candidateValues() = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar); put(Events.TITLE, content.title); put(Events.DESCRIPTION, content.description)
            put(Events.EVENT_LOCATION, content.location); put(Events.DIRTY, 1); put(Events.DELETED, 0)
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.HAS_ATTENDEE_DATA, 0); put(Events.HAS_ALARM, 0)
            putNull(Events.ORGANIZER); put(Events.IS_ORGANIZER, 0); put(Events.GUESTS_CAN_MODIFY, 0)
            put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            putNull(Events.EVENT_COLOR); putNull(Events.EVENT_COLOR_KEY)
            when (val time = content.time) {
                is EventTime.Timed -> {
                    put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
                    put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
                }
                is EventTime.AllDay -> {
                    put(Events.DTSTART, time.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.DTEND, time.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
                }
            }
        }
        suspend fun capture(hint: Long = row, time: Instant = timestamp) =
            coordinator.captureLocalCreate(target, hint, localId, id, time)
        suspend fun retained(): LocalCreateIntent? {
            journal.admitCapture = { it == target }; return journal.readIntent(target, localId)
        }
        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(uri, values, "${Events._ID}=?", arrayOf(row.toString())))
        }
        fun failRetain(enabled: Boolean) {
            journal.writableDatabase
            SQLiteDatabase.openDatabase(context.getDatabasePath(createName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                if (enabled) db.execSQL("CREATE TRIGGER fixture_fail_create_retain BEFORE UPDATE OF content ON local_creates " +
                    "WHEN NEW.content IS NOT NULL BEGIN SELECT RAISE(ABORT, 'fixture retain interrupted'); END")
                else db.execSQL("DROP TRIGGER IF EXISTS fixture_fail_create_retain")
            }
        }
        suspend fun reopen() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin(); journal.close()
            journal = LocalCreateStore(context, createName); jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, localCreates = journal); start()
        }
        suspend fun close() {
            coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close(); context.deleteDatabase(importName); context.deleteDatabase(createName)
            projection.admitOperation = { true }; projection.removeOwnedCalendar(target)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
        }
    }
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay)
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
