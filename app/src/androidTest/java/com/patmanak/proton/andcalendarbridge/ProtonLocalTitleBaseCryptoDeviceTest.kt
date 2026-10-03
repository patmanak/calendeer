// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic native base qualification only; no account, service or Provider writes. */
@RunWith(AndroidJUnit4::class)
class ProtonLocalTitleBaseCryptoDeviceTest {
    @Test fun binarySignedLocalTitleRequiresTheExactImportedBaseBeforeSealing() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runQualifiedBinary())
    }
    @Test fun textSignedLocalTitleUsesFrozenObservationsAndOneLocalAddressRead() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runQualifiedText())
    }
}
