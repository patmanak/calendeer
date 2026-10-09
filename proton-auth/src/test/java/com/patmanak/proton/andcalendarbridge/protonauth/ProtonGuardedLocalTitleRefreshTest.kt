// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

/** Shared production Core-read ordering with controlled callbacks, not service acceptance. */
class ProtonGuardedLocalTitleRefreshTest {
    private val session = CatalogSession(UserId("synthetic-refresh-user"), SessionId("synthetic-refresh-session"))
    @Test fun userAndAddressReadsAreEachSurroundedByCheckpoints() = runTest {
        val steps = mutableListOf<String>()
        val refresh = localTitleContextRefresh({ assertEquals(session, it); steps += "user" },
            { assertEquals(session, it); steps += "addresses" })
        refresh.refresh(session) { steps += "checkpoint" }
        assertEquals(listOf("checkpoint", "user", "checkpoint", "addresses", "checkpoint"), steps)
    }
    @Test fun deniedInitialCheckpointNeverStartsCoreReads() = runTest {
        val denied = IllegalStateException("Synthetic revoked access"); var reads = 0
        val refresh = localTitleContextRefresh({ reads++ }, { reads++ })
        try { refresh.refresh(session) { throw denied }; fail("Access denial must propagate") }
        catch (observed: IllegalStateException) { assertSame(denied, observed) }
        assertEquals(0, reads)
    }
    @Test fun revocationAfterUserPreventsTheAddressRequest() = runTest {
        var allowed = true; var userReads = 0; var addressReads = 0
        val refresh = localTitleContextRefresh({ userReads++; allowed = false }, { addressReads++ })
        try { refresh.refresh(session) { check(allowed) }; fail("Revocation must stop refresh") }
        catch (_: IllegalStateException) { }
        assertEquals(1, userReads); assertEquals(0, addressReads)
    }
    @Test fun cancelledUserReturningNormallyCannotStartAddressesOrReturnNormally() = runTest {
        var addresses = 0; var normalReturns = 0
        val refresh = localTitleContextRefresh({ currentCoroutineContext().cancel() }, { addresses++ })
        supervisorScope {
            val task = async { refresh.refresh(session) {}; normalReturns++ }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertEquals(0, addresses); assertEquals(0, normalReturns)
    }
}
