// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.patmanak.proton.andcalendarbridge.auth.*
import com.patmanak.proton.andcalendarbridge.ui.auth.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** In-memory authentication and counters only. Never signs out a real session. */
class SignOutConfirmationUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(AuthenticationState(destination = AuthenticationDestination.AUTHENTICATED))
    private var pending: Int? = 0
    private var reads = 0
    private var signsOut = 0
    private var reader: suspend () -> Int? = { reads++; pending }
    private fun start() = compose.setContent { MaterialTheme { AccountSignOutAction(state.value, { signsOut++ }, { reader() }) } }
    private fun open() = compose.onNodeWithTag(AuthenticationTags.CANCEL).performClick()
    @Test fun zeroHidesTheWarningAndEachOpeningReadsFreshWork() {
        start(); open()
        compose.onNodeWithTag("sign_out_pending_warning").assertDoesNotExist()
        compose.onNodeWithTag("auth_sign_out_confirm").assertIsEnabled().assertTextEquals(compose.activity.getString(R.string.auth_sign_out))
        compose.onNodeWithTag("auth_sign_out_cancel").performClick()
        compose.runOnIdle { assertEquals(0, signsOut); pending = 3 }
        open()
        compose.onNodeWithText(compose.activity.getString(R.string.auth_sign_out_count, 3)).assertExists()
        compose.runOnIdle { assertEquals(2, reads); assertEquals(0, signsOut) }
        compose.onNodeWithTag("auth_sign_out_confirm").performClick()
        compose.runOnIdle { assertEquals(1, signsOut) }
    }
    @Test fun unavailableCountIsVisibleAndDoesNotPreventSigningOut() {
        reader = { error("Fixture read failure") }
        start(); open()
        compose.onNodeWithText(compose.activity.getString(R.string.auth_sign_out_count_unavailable)).assertExists()
        compose.onNodeWithTag("auth_sign_out_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, signsOut) }
    }
    @Test fun cancelledReadCannotPopulateAReopenedConfirmation() {
        val old = CompletableDeferred<Int?>()
        reader = { reads++; if (reads == 1) old.await() else 0 }
        start(); open()
        compose.onNodeWithTag("auth_sign_out_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("auth_sign_out_cancel").performClick()
        open()
        compose.runOnIdle { old.complete(9) }
        compose.onNodeWithTag("sign_out_pending_warning").assertDoesNotExist()
        compose.onNodeWithTag("auth_sign_out_confirm").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, signsOut); assertEquals(2, reads) }
    }
    @Test fun aStalledReadTimesOutAndLeavesCancellationAndSignOutAvailable() {
        val stalled = CompletableDeferred<Int?>()
        reader = { stalled.await() }
        start(); open()
        compose.onNodeWithTag("auth_sign_out_confirm").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(5_100)
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("sign_out_count_loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(compose.activity.getString(R.string.auth_sign_out_count_unavailable)).assertExists()
        compose.onNodeWithTag("auth_sign_out_confirm").assertIsEnabled()
    }
    @Test fun losingTheSessionClosesTheConfirmationWithoutCallingSignOut() {
        pending = 2
        start(); open()
        compose.runOnIdle { state.value = state.value.copy(destination = AuthenticationDestination.SIGN_IN) }
        compose.onNodeWithTag("auth_sign_out_confirm").assertDoesNotExist()
        compose.onNodeWithTag("sign_out_pending_warning").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, signsOut) }
    }
    @Test fun theSettingsFooterAppearsAfterAboutAndOnlyOnce() {
        compose.setContent { MaterialTheme {
            com.patmanak.proton.andcalendarbridge.ui.BridgeScreen(
                com.patmanak.proton.andcalendarbridge.ui.theme.ThemeMode.LIGHT, {},
                com.patmanak.proton.andcalendarbridge.ui.locale.AppLanguage.SYSTEM, {},
                authenticationContent = { androidx.compose.material3.Text("Fixture account") },
                calendarContent = {}, signedIn = true,
                accountActions = { AccountSignOutAction(state.value, { signsOut++ }, { 0 }) })
        } }
        compose.onNodeWithTag("brand_settings").performClick()
        compose.onAllNodesWithTag(AuthenticationTags.CANCEL).assertCountEquals(1)
        compose.onNodeWithTag(AuthenticationTags.CANCEL).performScrollTo()
        val action = compose.onNodeWithTag(AuthenticationTags.CANCEL).fetchSemanticsNode().boundsInRoot
        val about = compose.onNodeWithText(compose.activity.getString(R.string.ui_about_bridge)).fetchSemanticsNode().boundsInRoot
        assertTrue(action.top >= about.bottom)
        compose.runOnIdle { assertEquals(0, signsOut) }
    }
}
