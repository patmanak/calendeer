package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonEventCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh synthetic in-memory keys/packets; no account store, credentials, network or event writes. */
@RunWith(AndroidJUnit4::class)
class ProtonEventCryptoDeviceTest {
    @Test fun nativeEncryptedEventImportAndTamperRejection() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonEventCryptoProbe.run())
    }
}
