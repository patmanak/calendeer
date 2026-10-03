// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled authenticated-read fixtures and real Core crypto; no service or Provider writes. */
@RunWith(AndroidJUnit4::class)
class ProtonAuthenticatedTitlePreparationDeviceTest {
    @Test fun binarySignedTitleUsesFreshContextAndRefusesChangedOrReadOnlyObservations() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runAuthenticatedBinary())
    }
    @Test fun textSignedTitleUsesOneAddressReadAndRecoversVerifiedNativeFragments() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runAuthenticatedText())
    }
}
