// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemPushAdmissionTest {
    @Test fun periodicSwitchOffDuringPreparationStillReceives() = runBlocking {
        val steps = mutableListOf<String>()
        var automatic = true
        executeCalendarPass(manual = false, automaticAllowed = { automatic }, incoming = {
            steps += "receive"
            ImportRunResult.COMPLETE
        }, outgoing = { pushAllowed ->
            steps += "prepare"
            automatic = false
            if (pushAllowed()) steps += "send"
            ImportRunResult.COMPLETE
        })
        assertEquals(listOf("prepare", "receive"), steps)
    }

    @Test fun manualAuthorityWithPushOffIsLimitedToThatRequest() = runBlocking {
        val steps = mutableListOf<String>()
        for (manual in listOf(true, false)) {
            executeCalendarPass(manual, automaticAllowed = { false }, incoming = {
                steps += "receive"
                ImportRunResult.COMPLETE
            }, outgoing = { pushAllowed ->
                if (pushAllowed()) steps += "send"
                ImportRunResult.COMPLETE
            })
        }
        assertEquals(listOf("send", "receive", "receive"), steps)
    }
}
