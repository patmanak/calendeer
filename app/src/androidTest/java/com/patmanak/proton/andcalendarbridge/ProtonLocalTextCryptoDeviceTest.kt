// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic native full-intent crypto only: no account, service, journal or Provider access. */
@RunWith(AndroidJUnit4::class)
class ProtonLocalTextCryptoDeviceTest {
    @Test fun binarySignedLocalTextsBindTheCompleteOriginalAndRecoverWithoutExternalUid() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runLocalTextBinary())
    }

    @Test fun textSignedLocalTextsRetainAbsenceDatesHistoryAndCancellation() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runLocalTextText())
    }
}
