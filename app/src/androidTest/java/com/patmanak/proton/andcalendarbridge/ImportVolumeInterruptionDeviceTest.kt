package com.patmanak.proton.andcalendarbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated SQLite, synthetic staged contents and acknowledgements; no Provider or HTTP.
 * Reopening and injected cancellation do not simulate a device reboot or power loss.
 */
@RunWith(AndroidJUnit4::class)
class ImportVolumeInterruptionDeviceTest {
    private val target = CalendarScope(AccountId("synthetic-volume"), CalendarId("fixture"), 0)
    private val format = AdapterFormat("synthetic-volume", 1)
    private fun event(index: Int) = RemoteChange.Upsert(EventId("event-$index"), Revision("v1"),
        EventContent("Synthetic volume $index",
            EventTime.AllDay(LocalDate.of(2030, 10, 26), LocalDate.of(2030, 10, 27)),
            description = "Synthetic multibyte notes é😀\n".repeat(32)))

    private suspend fun <T> ImportStore.staged(action: StagedSyncTransaction.() -> T): T =
        transaction(target) { action(this as StagedSyncTransaction) }

    @Test fun thousandsOfEventsRetainCancelledPageAndHeldWorkAcrossReopening() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "volume-interruption-${UUID.randomUUID()}.db"
        var store = ImportStore(context, name)
        fun reopen() { store.close(); store = ImportStore(context, name) }
        val total = 4097
        val held = 137 // More than one runnable batch must not starve later rows.
        try {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic volume")))
            store.setEnabled(target, true)
            var applied = PageCheckpoint(target, format)
            store.staged { checkpoint = applied }
            for (start in 0 until total step 100) {
                val changes = (start until minOf(start + 100, total)).map(::event)
                val next = applied.copy(cursor = if (start + 100 < total) OpaqueCursor(format, "next-${start + 100}") else null,
                    traversalComplete = start + 100 >= total)
                val received = ReceivedCalendarPage(PageRequest(applied, 100), "synthetic-page-$start:" + "x".repeat(65536))
                store.staged { this.received = received }
                if (start == 2000) {
                    var attempted = 0
                    supervisorScope {
                        val interrupted = async {
                            val operation = currentCoroutineContext()
                            store.staged {
                                changes.forEachIndexed { offset, change ->
                                    putRemote(change); putProjection(ProjectionIntent(target, change)); attempted++
                                    if (offset == 49) operation.cancel()
                                }
                                checkpoint = next; this.received = null
                            }
                        }
                        try { interrupted.await(); fail("Cancelled page must not commit") }
                        catch (_: CancellationException) { }
                    }
                    assertEquals(100, attempted) // Exercise the commit-time cancellation fence.
                    reopen()
                    assertEquals(applied, store.staged { checkpoint })
                    assertEquals(received, store.staged { this.received })
                    store.staged { changes.forEach { assertNull(remote(it.eventId)) } }
                    store.readableDatabase.rawQuery("SELECT COUNT(*) FROM records", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(2000, it.getInt(0))
                    }
                }
                store.staged {
                    assertEquals(received, this.received)
                    changes.forEach { putRemote(it); putProjection(ProjectionIntent(target, it)) }
                    checkpoint = next; this.received = null
                }
                applied = next
            }
            reopen()
            assertEquals(applied, store.staged { checkpoint }); assertTrue(applied.traversalComplete)
            assertNull(store.staged { received })

            // Hold the oldest rows without acknowledging or deleting their contents.
            var heldCount = 0
            while (heldCount < held) {
                val batch = store.pending(target, minOf(100, held - heldCount))
                assertTrue(batch.isNotEmpty())
                batch.forEach { assertTrue(store.holdLocalProjection(it)); heldCount++ }
            }
            reopen()
            assertEquals(held, store.heldProjectionCount(target))
            val acknowledged = mutableSetOf<EventId>()
            var reopenedDuringDrain = false
            while (true) {
                val batch = store.pending(target)
                if (batch.isEmpty()) break
                assertTrue(batch.size <= 100)
                batch.forEach { intent ->
                    val index = intent.change.eventId.value.removePrefix("event-").toInt()
                    assertEquals(event(index), intent.change)
                    assertTrue(index >= held)
                    assertTrue("Each independent event is acknowledged once", acknowledged.add(intent.change.eventId))
                    assertEquals(1, store.acknowledge(intent))
                }
                if (!reopenedDuringDrain && acknowledged.size >= 1000) {
                    reopen(); reopenedDuringDrain = true
                }
                assertTrue("Drain must terminate within its finite event set", acknowledged.size <= total - held)
            }
            assertTrue(reopenedDuringDrain)
            assertEquals((held until total).map { EventId("event-$it") }.toSet(), acknowledged)
            reopen()
            assertTrue(store.pending(target).isEmpty()); assertEquals(held, store.heldProjectionCount(target))
            assertEquals(applied, store.staged { checkpoint }); assertNull(store.staged { received })
            store.staged { repeat(total) { assertEquals(event(it), remote(EventId("event-$it"))) } }
            repeat(held) { assertNull(store.projectedBaseline(target, EventId("event-$it"))) }
            for (index in held until total) assertEquals(event(index), store.projectedBaseline(target, EventId("event-$index")))
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
