package com.patmanak.proton.andcalendarbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonEventPreparationCryptoProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Native synthetic preparation, no account data, network, Provider writes or remote mutation. */
@RunWith(AndroidJUnit4::class)
class ProtonEventPreparationCryptoDeviceTest {
    @Test fun simpleCreatePreparationRoundTripsAndRejectsBothTamperedSignatures() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonEventPreparationCryptoProbe.run())
    }
    @Test fun knownEmptyPreparationUsesExplicitNotificationsThroughAdapterKeystoreAndNativeDecoder() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonEventPreparationCryptoProbe.runKnownEmpty())
    }
    @Test fun knownEmptySubmissionRoundTripsAndUsesStrictReadbackAfterReopeningWithoutDispatch() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonEventPreparationCryptoProbe.runKnownEmptySubmission())
    }
    @Test fun textSignedFoldedWhitespaceRetainsExactContentAndRejectsAlteredSignatures() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonEventPreparationCryptoProbe.runKnownEmptySubmissionWithFoldedWhitespace())
    }
    @Test fun capturedSubmissionRequiresItsFrozenExpectationBeforeNativeReadback() = runBlocking {
        assertEquals("RESULT=SUCCESS", ProtonEventPreparationCryptoProbe.runCapturedSubmissionReadback())
    }
}
