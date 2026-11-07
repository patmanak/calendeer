// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
/** Synthetic native crypto only: no account/network, durable journal or Calendar Provider IO. */
@RunWith(AndroidJUnit4::class)
class ProtonPersonalCryptoDeviceTest {
    @Test fun binaryOriginalSignaturesSupportCombinedTransitionsAndPreservedMetadata() = runBlocking {
        assertEquals("RESULT=SUCCESS",ProtonSharedTitleCryptoProbe.runPersonalBinary())
    }
    @Test fun textOriginalSignaturesSupportCombinedTransitionsAndPreservedMetadata() = runBlocking {
        assertEquals("RESULT=SUCCESS",ProtonSharedTitleCryptoProbe.runPersonalText())
    }
}
