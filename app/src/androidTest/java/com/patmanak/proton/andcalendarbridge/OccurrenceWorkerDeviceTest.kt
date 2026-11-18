// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportRunResult
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStatus
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class OccurrenceWorkerDeviceTest {
    @Test fun discoversOneRealExceptionWhileOfflineThenRecoversItsUnknownSendThroughHandoff() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val f = SystemCreatePipelineDeviceTest.Fixture(ApplicationProvider.getApplicationContext())
        try {
            f.seed()
            val port = OccurrenceBindingDeviceTest.Port()
            f.usePort(port)
            val start = Instant.parse("2030-04-01T09:00:00Z")
            val master = RemoteChange.Upsert(EventId("worker-master"), Revision("first"), EventContent("Worker series",
                EventTime.Timed(start, start.plusSeconds(3600), ZoneOffset.UTC), details = EventDetails(),
                recurrence = EventRecurrence("worker-series@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
            val projected = ProjectionIntent(f.target, master)
            f.projection.admitOperation = { true }
            f.projection.apply(projected)
            f.store.admitTransaction = { it == f.target }
            f.store.transaction(f.target) { putRemote(master); putProjection(projected) }
            assertEquals(1, f.store.acknowledge(projected))
            val parent = f.context.contentResolver.query(f.syncUri(Events.CONTENT_URI), arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=? AND ${Events.RRULE} IS NOT NULL", arrayOf(f.calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
            }
            f.mutate(ContentValues().apply {
                put(Events.ORIGINAL_ID, parent); put(Events.ORIGINAL_INSTANCE_TIME, start.plusSeconds(86400).toEpochMilli())
                put(Events.ORIGINAL_ALL_DAY, 0); put(Events.DTSTART, start.plusSeconds(86400 + 3600).toEpochMilli())
                put(Events.DTEND, start.plusSeconds(86400 + 7200).toEpochMilli()); put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.EVENT_END_TIMEZONE, "UTC"); put(Events.DIRTY, 1)
            })
            assertEquals(ImportRunResult.COMPLETE, f.controller.run(network = false))
            val work = checkNotNull(f.work.read(f.target, f.row))
            assertEquals(SystemCreateWorkStatus.CAPTURED, work.status)
            assertEquals(0, port.sends)
            f.captures.admitCapture = { it == f.target }
            assertEquals(master, f.captures.readIntent(f.target, work.localId)?.occurrence?.master)
            assertEquals(ImportRunResult.COMPLETE, f.controller.run(network = true))
            assertEquals(SystemCreateWorkStatus.UNKNOWN, f.work.read(f.target, f.row)?.status)
            assertEquals(1, port.sends)
            assertEquals(ImportRunResult.COMPLETE, f.controller.run(network = true))
            assertEquals(SystemCreateWorkStatus.COMPLETE, f.work.read(f.target, f.row)?.status)
            assertEquals(1, port.sends)
            f.store.admitTransaction = { it == f.target }
            val role = checkNotNull(f.store.capturedHandoff(f.target, port.remoteId))
            assertEquals(CapturedCreateHandoffState.RELEASED, role.state)
            val hold = f.attempts.capturedProjectionHold(f.target, role.frame.format, f.store.capturedOccurrenceTransfers(f.target))
            assertTrue(hold.holds(master.eventId)); assertFalse(hold.scopeBlocked)
            assertFalse(hold.holds(EventId("unrelated")))
        } finally { f.close() }
    }
}
