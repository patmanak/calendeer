// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Real native Core, synthetic keys/events only; no account, service, journal or Provider access. */
@RunWith(AndroidJUnit4::class)
class ProtonLocalTimeCryptoDeviceTest {
    @Test fun binaryOriginalSignaturesBecomeVerifiedTimedAndAllDayPreparations() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runLocalTimeBinary())
    }
    @Test fun textOriginalSignaturesBecomeVerifiedTimedAndAllDayPreparations() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runLocalTimeText())
    }
}
