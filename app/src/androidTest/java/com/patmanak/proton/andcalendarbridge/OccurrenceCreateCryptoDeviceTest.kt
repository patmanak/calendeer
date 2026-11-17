// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonOccurrenceCreateCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OccurrenceCreateCryptoDeviceTest {
    @Test fun titleOnlyAllDayChildPreservesItsSourceAndSequence() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonOccurrenceCreateCryptoProbe.run())
    }
    @Test fun timedChildCrossesDstWithFreshKeysAndOriginalTimezone() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonOccurrenceCreateCryptoProbe.run(timed = true))
    }
    @Test fun allDayOriginalCanProduceAMovedTimedChild() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonOccurrenceCreateCryptoProbe.run(changeKind = true))
    }
    @Test fun reminderAndColorOnlyChildPreservesDefaultEmailsAndPersonalFlag() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonOccurrenceCreateCryptoProbe.run(personalOnly = true))
    }
}
