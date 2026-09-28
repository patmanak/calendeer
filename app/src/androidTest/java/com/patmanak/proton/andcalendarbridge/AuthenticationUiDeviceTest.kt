package com.patmanak.proton.andcalendarbridge

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationController
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationFailure
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationFlowPort
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationMonotonicClock
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationSecret
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationStep
import com.patmanak.proton.andcalendarbridge.ui.auth.AuthenticationPanel
import com.patmanak.proton.andcalendarbridge.ui.auth.AuthenticationTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Sanitized fake boundary only. Never loads a WebView, reads a credential or calls Proton. */
@RunWith(AndroidJUnit4::class)
class AuthenticationUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: AuthenticationController
    private val port = FakePort()

    private fun start(onOpenAppSettings: () -> Unit = {}) {
        compose.runOnUiThread {
            controller = AuthenticationController(port,
                AuthenticationMonotonicClock { android.os.SystemClock.elapsedRealtime() }, scope)
        }
        compose.setContent { MaterialTheme { AuthenticationPanel(controller, onOpenAppSettings = onOpenAppSettings) } }
        compose.waitForIdle()
    }

    @After fun cleanup() {
        if (::controller.isInitialized) compose.runOnUiThread {
            port.failCleanup = false
            runBlocking { controller.dispose() }
        }
        scope.cancel()
    }

    @Test fun rejectedSubmissionClearsBothInputsAndKeepsPasswordMasked() {
        start()
        compose.onNodeWithTag(AuthenticationTags.USERNAME).performTextInput("fixture@example.test")
        compose.onNodeWithTag(AuthenticationTags.SECRET).performTextInput("fixture-password")
        compose.onNodeWithTag(AuthenticationTags.SECRET)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag(AuthenticationTags.SUBMIT).performClick()
        assertEmpty(AuthenticationTags.USERNAME)
        assertEmpty(AuthenticationTags.SECRET)
        compose.onNodeWithTag(AuthenticationTags.APP_SETTINGS).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, port.signInCalls) }
    }

    @Test fun secondFactorAndCancellationDoNotCarryCredentialsBackToSignIn() {
        port.result = AuthenticationStep.CodeRequired
        start()
        compose.onNodeWithTag(AuthenticationTags.USERNAME).performTextInput("fixture@example.test")
        compose.onNodeWithTag(AuthenticationTags.SECRET).performTextInput("fixture-password")
        compose.onNodeWithTag(AuthenticationTags.SUBMIT).performClick()
        compose.onNodeWithTag(AuthenticationTags.USERNAME).assertDoesNotExist()
        assertEmpty(AuthenticationTags.SECRET)
        compose.onNodeWithTag(AuthenticationTags.SECRET).performTextInput("123456")
        compose.onNodeWithTag(AuthenticationTags.CANCEL).performClick()
        assertEmpty(AuthenticationTags.USERNAME)
        assertEmpty(AuthenticationTags.SECRET)
    }

    @Test fun failedCleanupBlocksCredentialsUntilExplicitRetrySucceeds() {
        port.result = AuthenticationStep.CodeRequired
        start()
        compose.runOnIdle { controller.submitSignIn("fixture".toCharArray(), "fixture".toCharArray()) }
        port.failCleanup = true
        compose.onNodeWithTag(AuthenticationTags.CANCEL).performClick()
        compose.onNodeWithTag(AuthenticationTags.USERNAME).assertIsNotEnabled()
        compose.onNodeWithTag(AuthenticationTags.SECRET).assertIsNotEnabled()
        compose.onNodeWithTag(AuthenticationTags.SUBMIT).assertDoesNotExist()
        port.failCleanup = false
        compose.onNodeWithTag(AuthenticationTags.CANCEL).performClick()
        compose.runOnIdle { assertTrue(!controller.state.value.isCleanupRequired) }
    }

    @Test fun backgroundClearsInputsAndWindowBlocksCapture() {
        start()
        compose.onNodeWithTag(AuthenticationTags.USERNAME).performTextInput("fixture@example.test")
        compose.onNodeWithTag(AuthenticationTags.SECRET).performTextInput("fixture-password")
        compose.runOnIdle {
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEmpty(AuthenticationTags.USERNAME)
        assertEmpty(AuthenticationTags.SECRET)
    }

    @Test fun authenticatedIdentityHasNoCredentialFieldsAndDoesNotClaimCalendarReadiness() {
        port.result = AuthenticationStep.Authenticated
        start()
        compose.runOnIdle { controller.submitSignIn("fixture".toCharArray(), "fixture".toCharArray()) }
        compose.onNodeWithTag(AuthenticationTags.USERNAME).assertDoesNotExist()
        compose.onNodeWithTag(AuthenticationTags.SECRET).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.auth_connected_help)).assertExists()
        compose.onNodeWithTag(AuthenticationTags.APP_SETTINGS).assertDoesNotExist()
        compose.runOnIdle { assertEquals(AuthenticationDestination.AUTHENTICATED, controller.state.value.destination) }
    }

    @Test fun serverRetryDelayDisablesAnotherSubmission() {
        port.result = AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED, retryAfterMillis = 60_000)
        start()
        compose.runOnIdle { controller.submitSignIn("fixture".toCharArray(), "fixture".toCharArray()) }
        compose.onNodeWithTag(AuthenticationTags.SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(AuthenticationTags.USERNAME).assertIsNotEnabled()
        compose.onNodeWithTag(AuthenticationTags.SECRET).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, port.signInCalls) }
    }

    @Test fun unreachableProtonOffersExplicitAppSettingsActionOnlyAfterFailure() {
        port.result = AuthenticationStep.Failure(AuthenticationFailure.OFFLINE)
        var settingsRequests = 0
        start(onOpenAppSettings = { settingsRequests++ })
        compose.onNodeWithTag(AuthenticationTags.APP_SETTINGS).assertDoesNotExist()
        compose.runOnIdle { controller.submitSignIn("fixture".toCharArray(), "fixture".toCharArray()) }
        compose.onNodeWithText(compose.activity.getString(R.string.auth_offline)).assertExists()
        compose.onNodeWithTag(AuthenticationTags.USERNAME).performTextInput("fixture@example.test")
        compose.onNodeWithTag(AuthenticationTags.SECRET).performTextInput("fixture-password")
        compose.onNodeWithTag(AuthenticationTags.APP_SETTINGS).performClick()
        assertEmpty(AuthenticationTags.USERNAME)
        assertEmpty(AuthenticationTags.SECRET)
        compose.runOnIdle { assertEquals(1, settingsRequests) }
    }

    private fun assertEmpty(tag: String) {
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    private class FakePort : AuthenticationFlowPort {
        var result: AuthenticationStep = AuthenticationStep.Failure(AuthenticationFailure.REJECTED)
        var failCleanup = false
        var signInCalls = 0
        override suspend fun restore(): AuthenticationStep = AuthenticationStep.SignIn
        override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret): AuthenticationStep {
            username.consume { }
            password.consume { }
            signInCalls++
            return result
        }
        override suspend fun submitCode(code: AuthenticationSecret): AuthenticationStep {
            code.consume { }
            return result
        }
        override suspend fun unlock(password: AuthenticationSecret): AuthenticationStep {
            password.consume { }
            return result
        }
        override suspend fun cancel() { check(!failCleanup) }
    }
}
