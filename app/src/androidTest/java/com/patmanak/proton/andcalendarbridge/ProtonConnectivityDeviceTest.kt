package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonConnectivityProbe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opted-in anonymous service check; never submits credentials or opens account data. */
@RunWith(AndroidJUnit4::class)
class ProtonConnectivityDeviceTest {
    @Test fun anonymousProductionTransportReachesProton() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("calendarNetworkProbe") == "true")
        val result = withTimeout(60_000) {
            ProtonConnectivityProbe.run(ApplicationProvider.getApplicationContext())
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("calendar_connectivity", result)
        })
        assertTrue("Anonymous transport: $result", result.contains("RESULT=SUCCESS"))
    }
}
