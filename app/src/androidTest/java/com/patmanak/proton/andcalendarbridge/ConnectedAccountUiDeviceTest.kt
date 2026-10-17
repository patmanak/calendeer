// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationState
import com.patmanak.proton.andcalendarbridge.ui.BridgeScreen
import com.patmanak.proton.andcalendarbridge.ui.auth.AuthenticationContent
import com.patmanak.proton.andcalendarbridge.ui.auth.AuthenticationTags
import com.patmanak.proton.andcalendarbridge.ui.locale.AppLanguage
import com.patmanak.proton.andcalendarbridge.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic presentation only; never reads a session, account database or service. */
@RunWith(AndroidJUnit4::class)
class ConnectedAccountUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(AuthenticationState(AuthenticationDestination.AUTHENTICATED))
    private val label = mutableStateOf<String?>("fixture-account")
    private var actions = 0

    private fun start() {
        compose.setContent {
            MaterialTheme {
                BridgeScreen(ThemeMode.LIGHT, {}, AppLanguage.SYSTEM, {},
                    authenticationContent = {
                        AuthenticationContent(state.value, { _, _ -> actions++ }, { actions++ }, { actions++ },
                            { actions++ }, { actions++ }, connectedAccountLabel = label.value)
                    }, calendarContent = { Text("Synthetic calendar list") },
                    signedIn = state.value.destination == AuthenticationDestination.AUTHENTICATED)
            }
        }
    }

    @Test fun connectedIdentityAppearsInSettingsWithoutInitiatingAnAccountAction() {
        start()
        compose.onNodeWithTag(AuthenticationTags.ACCOUNT_LABEL).assertDoesNotExist()
        compose.onNodeWithTag("brand_settings").performClick()
        compose.onNodeWithTag(AuthenticationTags.ACCOUNT_LABEL).performScrollTo().assertTextEquals("fixture-account")
        compose.onNodeWithText(compose.activity.getString(R.string.auth_redesign_connected)).assertExists()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun metadataUnavailableKeepsConnectionAndSignOutAvailableWithoutAnIdentifierFallback() {
        label.value = null
        start()
        compose.onNodeWithTag("brand_settings").performClick()
        compose.onNodeWithTag(AuthenticationTags.ACCOUNT_LABEL).assertDoesNotExist()
        compose.onNodeWithTag(AuthenticationTags.CANCEL).performScrollTo().assertIsEnabled()
        compose.runOnIdle { label.value = "   " }
        compose.onNodeWithTag(AuthenticationTags.ACCOUNT_LABEL).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun cleanupAndSignOutHideAnOldIdentityEvenIfItsCallerRetainsTheLabel() {
        start()
        compose.onNodeWithTag("brand_settings").performClick()
        compose.onNodeWithTag(AuthenticationTags.ACCOUNT_LABEL).assertExists()
        compose.runOnIdle { state.value = state.value.copy(isCleanupRequired = true) }
        compose.onNodeWithTag(AuthenticationTags.ACCOUNT_LABEL).assertDoesNotExist()
        compose.runOnIdle { state.value = AuthenticationState(AuthenticationDestination.SIGN_IN) }
        compose.onNodeWithTag(AuthenticationTags.ACCOUNT_LABEL).assertDoesNotExist()
        compose.onNodeWithText("fixture-account").assertDoesNotExist()
    }

    @Test fun replacingTheDisplayedAccountRemovesThePreviousName() {
        start()
        compose.onNodeWithTag("brand_settings").performClick()
        compose.onNodeWithTag(AuthenticationTags.ACCOUNT_LABEL).assertTextEquals("fixture-account")
        compose.runOnIdle { label.value = "another-fixture@example.test" }
        compose.onNodeWithTag(AuthenticationTags.ACCOUNT_LABEL).assertTextEquals("another-fixture@example.test")
        compose.onNodeWithText("fixture-account").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }
}
