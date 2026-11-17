// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportRunResult
import com.patmanak.proton.andcalendarbridge.data.importing.executeCalendarPass
import com.patmanak.proton.andcalendarbridge.sync.CreateAttemptState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemPushAdmissionDeviceTest {
    @Test fun switchOffBeforeClaimPreservesPreparedCreateAndManualCanSendIt() =
        SystemCreatePipelineDeviceTest().fixture {
            var automatic = true
            var receives = 0
            beforeClaim = { automatic = false }
            suspend fun pass(manual: Boolean) = executeCalendarPass(manual, { automatic }, {
                receives++
                ImportRunResult.COMPLETE
            }, { pushAllowed -> controller.run(network = true, fresh = false, providerWake = true,
                expectedAccount = target.account, pushAllowed = pushAllowed) })

            assertEquals(ImportRunResult.COMPLETE, pass(manual = false))
            assertEquals(0, sends)
            assertEquals(1, receives)
            val retained = work.read(target, row)!!
            assertEquals(CreateAttemptState.PREPARED, attempts.read(target, retained.attempt)?.state)
            assertEquals(ImportRunResult.COMPLETE, pass(manual = true))
            assertEquals(1, sends)
            assertEquals(2, receives)
            assertEquals(retained.attempt, work.read(target, row)?.attempt)
        }

    @Test fun switchOffAfterClaimPreservesUnknownAndManualCannotResend() =
        SystemCreatePipelineDeviceTest().fixture {
            var automatic = true
            var receives = 0
            beforeTransport = { automatic = false }
            suspend fun pass(manual: Boolean) = executeCalendarPass(manual, { automatic }, {
                receives++
                ImportRunResult.COMPLETE
            }, { pushAllowed -> controller.run(network = true, fresh = false, providerWake = true,
                expectedAccount = target.account, pushAllowed = pushAllowed) })

            assertEquals(ImportRunResult.COMPLETE, pass(manual = false))
            assertEquals(1, receives)
            assertEquals(0, sends)
            val retained = work.read(target, row)!!
            assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, attempts.read(target, retained.attempt)?.state)
            assertEquals(ImportRunResult.COMPLETE, pass(manual = true))
            assertEquals(0, sends)
            assertEquals(1, inspections)
            assertEquals(2, receives)
        }
}
