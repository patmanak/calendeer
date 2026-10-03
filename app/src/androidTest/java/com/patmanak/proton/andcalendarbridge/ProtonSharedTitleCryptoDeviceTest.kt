package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTitleCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic native signatures and local plaintext patches; no account, network or Provider writes. */
@RunWith(AndroidJUnit4::class)
class ProtonSharedTitleCryptoDeviceTest {
    @Test fun binarySignedSharedPartsRetainOpaqueDataAndRejectUnboundOrUnverifiedTitles() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runBinary())
    }

    @Test fun textSignedSharedPartsRetainExactWhitespaceAndRejectUnboundOrUnverifiedTitles() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runText())
    }

    @Test fun binarySignedTitlesReencryptWithOriginalPacketAndRejectInvalidWriteContext() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runReencryptedBinary())
    }

    @Test fun textSignedTitlesReencryptWithOriginalPacketAndPreserveOpaqueSpans() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonSharedTitleCryptoProbe.runReencryptedText())
    }
}
