// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic native crypto and local protected text only; no account, network or Provider access. */
@RunWith(AndroidJUnit4::class)
class ProtonSharedTextCryptoDeviceTest {
    @Test fun binarySignedTextEditsRetainOriginalKeyAndRecoverExactProtectedBinding() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runProtectedSharedTextBinary())
    }

    @Test fun textSignedTextEditsPreserveRawPartsAndRejectUnsafeOrChangedBindings() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runProtectedSharedTextText())
    }
}
