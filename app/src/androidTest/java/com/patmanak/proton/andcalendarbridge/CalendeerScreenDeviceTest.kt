package com.patmanak.proton.andcalendarbridge

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.ui.BridgeScreen
import com.patmanak.proton.andcalendarbridge.ui.locale.AppLanguage
import com.patmanak.proton.andcalendarbridge.ui.theme.BridgeTheme
import com.patmanak.proton.andcalendarbridge.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI only: no session, credentials, permissions, provider data or network. */
@RunWith(AndroidJUnit4::class)
class CalendeerScreenDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val signedIn = mutableStateOf(false)
    private val mode = mutableStateOf(ThemeMode.LIGHT)
    private val settingsEnabled = mutableStateOf(true)
    private val verificationRequired = mutableStateOf(false)
    private fun start() {
        compose.setContent {
            BridgeTheme(mode.value == ThemeMode.DARK) {
                BridgeScreen(mode.value, { mode.value = it }, AppLanguage.SYSTEM, {},
                    authenticationContent = { Text("Synthetic connection") },
                    calendarContent = { Text("Synthetic calendars") }, signedIn = signedIn.value,
                    settingsEnabled = settingsEnabled.value, verificationRequired = verificationRequired.value)
            }
        }
    }
    @Test fun signedOutConnectionHasNoCalendarOrPreferenceClutter() {
        start()
        compose.onNodeWithText("Calendeer").assertIsDisplayed()
        compose.onNodeWithText("Synthetic connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic calendars").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.appearance)).assertDoesNotExist()
        compose.onNodeWithTag("brand_settings").performScrollTo().performClick()
        compose.onNodeWithText("Synthetic connection").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.appearance)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("brand_settings").performScrollTo().performClick()
        compose.onNodeWithText("Synthetic connection").performScrollTo().assertIsDisplayed()
    }
    @Test fun signedInHomeKeepsAccountActionsInSettingsAndReturnsToCalendars() {
        signedIn.value = true; start()
        compose.onNodeWithText("Synthetic calendars").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic connection").assertDoesNotExist()
        compose.onNodeWithTag("brand_settings").performScrollTo().performClick()
        compose.onNodeWithText("Synthetic connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic calendars").assertDoesNotExist()
        compose.onNodeWithTag("brand_settings").performScrollTo().performClick()
        compose.onNodeWithText("Synthetic calendars").performScrollTo().assertIsDisplayed()
    }
    @Test fun signingOutFromSettingsReturnsToConnection() {
        signedIn.value = true; start()
        compose.onNodeWithTag("brand_settings").performClick()
        compose.runOnIdle { signedIn.value = false }
        compose.onNodeWithText("Synthetic connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.brand_welcome)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic calendars").assertDoesNotExist()
    }
    @Test fun appearanceChoiceRemainsReachableFromTheNewSettingsScreen() {
        start()
        compose.onNodeWithTag("brand_settings").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.appearance)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.theme_dark)).performClick()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, mode.value) }
        compose.onNodeWithTag("brand_settings").performScrollTo().performClick()
        compose.onNodeWithText("Synthetic connection").performScrollTo().assertIsDisplayed()
    }
    @Test fun activeAuthenticationCannotBeHiddenBySettingsNavigation() {
        settingsEnabled.value = false; start()
        compose.onNodeWithTag("brand_settings").assertIsNotEnabled()
        compose.onNodeWithText("Synthetic connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.appearance)).assertDoesNotExist()
    }
    @Test fun serverVerificationIsVisibleEvenFromSignedInHome() {
        signedIn.value = true; settingsEnabled.value = false; verificationRequired.value = true; start()
        compose.onNodeWithText("Synthetic connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic calendars").assertDoesNotExist()
        compose.onNodeWithTag("brand_settings").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { verificationRequired.value = false; settingsEnabled.value = true }
        compose.onNodeWithText("Synthetic calendars").performScrollTo().assertIsDisplayed()
    }
    @Test fun incomingVerificationDismissesAnOpenPreferenceDialog() {
        signedIn.value = true; start()
        compose.onNodeWithTag("brand_settings").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.appearance)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.theme_dark)).assertIsDisplayed()
        compose.runOnIdle { verificationRequired.value = true; settingsEnabled.value = false }
        compose.onNodeWithText(compose.activity.getString(R.string.theme_dark)).assertDoesNotExist()
        compose.onNodeWithText("Synthetic connection").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { verificationRequired.value = false; settingsEnabled.value = true }
        compose.onNodeWithText(compose.activity.getString(R.string.theme_dark)).assertDoesNotExist()
    }
}
