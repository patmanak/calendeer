// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonOccurrenceCapsuleProbe
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OccurrenceCapsuleCryptoDeviceTest {
    @Test fun protectedOccurrenceCapsuleReopensWithTheExactRetainedCaptureOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        assertTrue(ProtonOccurrenceCapsuleProbe.verify())
    }
}
