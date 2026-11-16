// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Real native crypto on synthetic keys. No account, transport or Provider access. */
@RunWith(AndroidJUnit4::class)
class ProtonSeriesEditCryptoDeviceTest {
    @Test fun binarySignaturesPreserveFiniteMastersAndPersonalParts() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runSeriesBinary())
    }
    @Test fun textSignaturesPreserveFiniteMastersAndPersonalParts() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runSeriesText())
    }
}
