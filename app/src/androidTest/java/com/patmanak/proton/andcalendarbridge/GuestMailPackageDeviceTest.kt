package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonGuestMailCryptoProbe
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestMailPackageDeviceTest {
    @Test fun internalRecipientOpensAndVerifiesSyntheticInvitationAndCancellation() {
        assertEquals("VERIFIED", ProtonGuestMailCryptoProbe.verify())
    }
}
