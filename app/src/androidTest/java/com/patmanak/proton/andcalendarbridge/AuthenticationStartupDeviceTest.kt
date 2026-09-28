package com.patmanak.proton.andcalendarbridge

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** Uses production composition, without submitting input or deleting any retained session. */
@RunWith(AndroidJUnit4::class)
class AuthenticationStartupDeviceTest {
    @Test fun productionRestorationFinishesAndSurvivesActivityRecreation() {
        val application = ApplicationProvider.getApplicationContext<BridgeApplication>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val controller = application.authentication
            val state = runBlocking {
                withTimeout(30_000) {
                    controller.state.first {
                        !it.isSubmitting && it.destination != AuthenticationDestination.RESTORING
                    }
                }
            }
            // Do not assert an account identity or reset an existing session to force sign-in.
            assertNull("Production restoration reported a closed failure", state.message)
            assertFalse(state.isCleanupRequired)
            scenario.recreate()
            scenario.onActivity {
                assertSame(controller, (it.application as BridgeApplication).authentication)
            }
        }
    }
}
