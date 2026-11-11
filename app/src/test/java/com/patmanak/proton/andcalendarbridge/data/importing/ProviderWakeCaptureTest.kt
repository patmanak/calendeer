// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.ProviderDirtyFingerprint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderWakeCaptureTest {
    private class Fixture {
        val calls = mutableListOf<String>()
        var rows = 1
        var hash: String? = "changed"
        var result = ImportRunResult.COMPLETE
        var failHandoff = false
        suspend fun run(previous: String? = null, continued: String? = null, resuming: Boolean = continued != null) = captureProviderWake(
            "pending", previous, continued,
            { calls += "fingerprint"; ProviderDirtyFingerprint(hash, rows) },
            { calls += "capture"; result },
            { pending, fingerprint -> calls += "local:$pending:$fingerprint"; if (failHandoff) error("fixture enqueue") },
            { calls += "network"; if (failHandoff) error("fixture enqueue") },
            { pending, fingerprint -> calls += "ack:$pending:$fingerprint" },
            continued = resuming,
        )
    }
    @Test fun ownImportOrAcknowledgementWithoutDirtyRowsDoesNotStartCaptureOrNetwork() = runBlocking {
        val f = Fixture(); f.rows = 0
        assertEquals(ImportRunResult.COMPLETE, f.run())
        assertEquals(listOf("fingerprint", "ack:pending:changed"), f.calls)
    }
    @Test fun repeatedNotificationForUnchangedHeldContentsDoesNotStartNetwork() = runBlocking {
        val f = Fixture()
        f.run(previous = "changed")
        assertEquals(listOf("fingerprint", "ack:pending:changed"), f.calls)
    }
    @Test fun changedDirtyContentsAreCapturedBeforeDurableNetworkHandoffAndAcknowledgement() = runBlocking {
        val f = Fixture()
        f.run(previous = "old")
        assertEquals(listOf("fingerprint", "capture", "network", "ack:pending:changed"), f.calls)
    }
    @Test fun paginatedCaptureCarriesOriginalSignalWithoutAcknowledgingOrStartingNetwork() = runBlocking {
        val f = Fixture(); f.result = ImportRunResult.CONTINUE
        assertEquals(ImportRunResult.COMPLETE, f.run())
        assertEquals(listOf("fingerprint", "capture", "local:pending:changed"), f.calls)
        f.calls.clear(); f.result = ImportRunResult.COMPLETE
        f.run(previous = "changed", continued = "changed")
        assertEquals(listOf("capture", "network", "ack:pending:changed"), f.calls)
    }
    @Test fun oversizedSnapshotFallsBackToPaginatedCaptureWithoutCachingTruncatedContents() = runBlocking {
        val f = Fixture(); f.hash = null
        f.run()
        assertEquals(listOf("fingerprint", "capture", "network", "ack:pending:null"), f.calls)
        f.calls.clear(); f.result = ImportRunResult.CONTINUE
        f.run()
        assertEquals(listOf("fingerprint", "capture", "local:pending:null"), f.calls)
        f.calls.clear(); f.result = ImportRunResult.COMPLETE
        f.run(resuming = true)
        assertEquals(listOf("capture", "network", "ack:pending:null"), f.calls)
    }
    @Test fun failedCaptureRetainsSignalWithoutHandoff() = runBlocking {
        val f = Fixture(); f.result = ImportRunResult.RETRY
        assertEquals(ImportRunResult.RETRY, f.run())
        assertEquals(listOf("fingerprint", "capture"), f.calls)
    }
    @Test fun failedNetworkOrContinuationHandoffCannotConsumeSignal() = runBlocking {
        for (result in listOf(ImportRunResult.COMPLETE, ImportRunResult.CONTINUE)) {
            val f = Fixture(); f.failHandoff = true; f.result = result
            assertTrue(runCatching { f.run() }.isFailure)
            assertTrue(f.calls.none { it.startsWith("ack:") })
        }
    }
}
