// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Patmanak
// Adapted from Contako; see app/AUTH_UI_PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.ui.auth

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationController
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationMessage
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationState
import com.patmanak.proton.andcalendarbridge.auth.PasswordPurpose

/** Embeddable connection panel; the owner retains the controller across activity recreation. */
@Composable
fun AuthenticationPanel(
    controller: AuthenticationController,
    modifier: Modifier = Modifier,
    onOpenAppSettings: (() -> Unit)? = null,
    connectedAccountLabel: String? = null,
    nativeRemovalPending: Boolean = false,
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val displayState = when {
        state.message == AuthenticationMessage.NATIVE_CLEANUP_PENDING && !nativeRemovalPending -> state.copy(message = null)
        nativeRemovalPending && state.destination == AuthenticationDestination.SIGN_IN && state.message == null ->
            state.copy(message = AuthenticationMessage.NATIVE_CLEANUP_PENDING)
        else -> state
    }
    AuthenticationContent(
        displayState,
        controller::submitSignIn, controller::submitCode, controller::submitPassword,
        controller::cancelAuthentication,
        onOpenAppSettings = onOpenAppSettings ?: {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        modifier = modifier,
        connectedAccountLabel = connectedAccountLabel,
    )
}

@Composable
internal fun AuthenticationContent(
    state: AuthenticationState,
    onSignIn: (CharArray, CharArray) -> Unit,
    onCode: (CharArray) -> Unit,
    onPassword: (CharArray) -> Unit,
    onCancel: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
    connectedAccountLabel: String? = null,
) {
    // Deliberately excluded from saved state. Dropping Strings cannot guarantee heap zeroization;
    // only the transferred mutable arrays have the controller's explicit clearing guarantee.
    var username by remember(state.destination, state.passwordPurpose) { mutableStateOf("") }
    var secret by remember(state.destination, state.passwordPurpose) { mutableStateOf("") }
    var confirmSignOut by remember(state.destination, state.isCleanupRequired) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    fun clearInputs() { username = ""; secret = ""; keyboard?.hide() }
    fun cancel() {
        clearInputs()
        if (state.destination == AuthenticationDestination.AUTHENTICATED) confirmSignOut = true else onCancel()
    }
    val canSubmit = !state.isSubmitting && !state.isCleanupRequired && !state.isRetryBlocked
    fun submit() {
        if (!canSubmit) return
        val usernameInput = username.toCharArray()
        val secretInput = secret.toCharArray()
        clearInputs()
        try {
            when (state.destination) {
                AuthenticationDestination.SIGN_IN -> onSignIn(usernameInput, secretInput)
                AuthenticationDestination.CODE -> onCode(secretInput)
                AuthenticationDestination.PASSWORD -> onPassword(secretInput)
                else -> Unit
            }
        } finally {
            usernameInput.fill('\u0000')
            secretInput.fill('\u0000')
        }
    }
    DisposableEffect(view, state.destination, state.passwordPurpose) {
        val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) clearInputs()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); clearInputs() }
    }
    BackHandler(enabled = !state.isCleanupInProgress && (
        state.destination == AuthenticationDestination.CODE ||
        state.destination == AuthenticationDestination.PASSWORD ||
        state.destination == AuthenticationDestination.SECURITY_KEY_UNSUPPORTED ||
        state.destination == AuthenticationDestination.HUMAN_VERIFICATION_UNAVAILABLE ||
        (state.destination == AuthenticationDestination.SIGN_IN && state.isSubmitting)
    )) { cancel() }
    AuthenticationWindowProtection(state.destination)
    val connected = state.destination == AuthenticationDestination.AUTHENTICATED
    if (connected && confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text(stringResource(R.string.auth_sign_out_title)) },
        text = { Text(stringResource(R.string.auth_sign_out_warning)) },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; onCancel() },
            modifier = Modifier.testTag("auth_sign_out_confirm")) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false },
            modifier = Modifier.testTag("auth_sign_out_cancel")) { Text(stringResource(android.R.string.cancel)) } },
    )
    Column(modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(if (connected) R.string.auth_redesign_account else titleResource(state)),
                style = if (connected) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            if (connected) {
                if (!state.isCleanupRequired && !state.isCleanupInProgress && !state.isSubmitting) {
                    connectedAccountLabel?.takeIf { it.isNotBlank() }?.let { label ->
                        Text(label, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag(AuthenticationTags.ACCOUNT_LABEL))
                    }
                }
                Text(stringResource(R.string.auth_redesign_connected),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            val description = when (state.destination) {
                AuthenticationDestination.SIGN_IN -> R.string.auth_redesign_sign_in_help
                AuthenticationDestination.CODE -> R.string.auth_redesign_code_help
                AuthenticationDestination.PASSWORD -> if (state.passwordPurpose == PasswordPurpose.MAILBOX)
                    R.string.auth_redesign_mailbox_help else R.string.auth_redesign_unlock_help
                AuthenticationDestination.SECURITY_KEY_UNSUPPORTED -> R.string.auth_security_key_help
                AuthenticationDestination.HUMAN_VERIFICATION_UNAVAILABLE -> R.string.auth_human_help
                AuthenticationDestination.AUTHENTICATED -> R.string.auth_redesign_connected_help
                AuthenticationDestination.RESTORING -> R.string.auth_restoring_help
            }
            Text(stringResource(description), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.message != null || state.isRetryBlocked) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.message?.let { message ->
                        Text(stringResource(messageResource(message)), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(AuthenticationTags.MESSAGE).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (state.isRetryBlocked) Text(stringResource(R.string.auth_retry_wait),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (state.message == AuthenticationMessage.OFFLINE || state.message == AuthenticationMessage.NATIVE_CLEANUP_PENDING) {
                        TextButton(onClick = { clearInputs(); onOpenAppSettings() },
                            modifier = Modifier.heightIn(min = 48.dp).testTag(AuthenticationTags.APP_SETTINGS)) {
                            Text(stringResource(R.string.auth_open_app_settings))
                        }
                    }
                }
            }
        }
        if (state.destination == AuthenticationDestination.SIGN_IN) {
            OutlinedTextField(
                shape = RoundedCornerShape(14.dp),
                value = username,
                onValueChange = { if (it.length <= MAX_SECRET_LENGTH) username = it },
                enabled = canSubmit,
                label = { Text(stringResource(R.string.auth_username)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(AuthenticationTags.USERNAME),
            )
        }
        if (state.destination in INPUT_DESTINATIONS) {
            val isCode = state.destination == AuthenticationDestination.CODE
            OutlinedTextField(
                shape = RoundedCornerShape(14.dp),
                value = secret,
                onValueChange = { if (it.length <= if (isCode) 128 else MAX_SECRET_LENGTH) secret = it },
                enabled = canSubmit,
                label = { Text(stringResource(if (isCode) R.string.auth_code else R.string.auth_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(AuthenticationTags.SECRET).semantics { password() },
            )
        }
        if (state.isSubmitting || state.destination == AuthenticationDestination.RESTORING) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(when {
                    state.isCleanupInProgress -> R.string.auth_cleaning
                    state.destination == AuthenticationDestination.RESTORING -> R.string.auth_redesign_restoring
                    else -> R.string.auth_working
                }), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (state.destination in INPUT_DESTINATIONS && !state.isCleanupRequired) {
            Button(onClick = ::submit, enabled = canSubmit, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(AuthenticationTags.SUBMIT)) {
                Text(stringResource(if (state.destination == AuthenticationDestination.SIGN_IN)
                    R.string.auth_sign_in else R.string.auth_continue))
            }
        }
        if (state.destination != AuthenticationDestination.SIGN_IN || state.isSubmitting || state.isCleanupRequired) {
            TextButton(onClick = ::cancel, enabled = !state.isCleanupInProgress,
                modifier = Modifier.align(if (connected) Alignment.Start else Alignment.CenterHorizontally)
                    .heightIn(min = 48.dp).testTag(AuthenticationTags.CANCEL)) {
                Text(stringResource(when {
                    state.isCleanupRequired && !state.isCleanupInProgress -> R.string.auth_cleanup_retry
                    state.destination == AuthenticationDestination.AUTHENTICATED -> R.string.auth_sign_out
                    else -> R.string.auth_cancel
                }))
            }
        }
    }
}

private fun titleResource(state: AuthenticationState): Int = when (state.destination) {
    AuthenticationDestination.RESTORING -> R.string.auth_restoring_title
    AuthenticationDestination.SIGN_IN -> R.string.auth_sign_in_title
    AuthenticationDestination.CODE -> R.string.auth_code_title
    AuthenticationDestination.PASSWORD -> if (state.passwordPurpose == PasswordPurpose.MAILBOX)
        R.string.auth_mailbox_title else R.string.auth_unlock_title
    AuthenticationDestination.SECURITY_KEY_UNSUPPORTED -> R.string.auth_security_key_title
    AuthenticationDestination.HUMAN_VERIFICATION_UNAVAILABLE -> R.string.auth_human_title
    AuthenticationDestination.AUTHENTICATED -> R.string.auth_connected_title
}

private fun messageResource(message: AuthenticationMessage): Int = when (message) {
    AuthenticationMessage.INPUT_REQUIRED -> R.string.auth_input_required
    AuthenticationMessage.REJECTED -> R.string.auth_rejected
    AuthenticationMessage.ACCOUNT_ALREADY_CONNECTED -> R.string.auth_already_connected
    AuthenticationMessage.OFFLINE -> R.string.auth_offline
    AuthenticationMessage.TIMED_OUT -> R.string.auth_timeout
    AuthenticationMessage.RATE_LIMITED -> R.string.auth_rate_limited
    AuthenticationMessage.CRYPTOGRAPHIC_FAILURE -> R.string.auth_crypto_failure
    AuthenticationMessage.CLIENT_UNSUPPORTED -> R.string.auth_client_unsupported
    AuthenticationMessage.CLEANUP_FAILED -> R.string.auth_cleanup_failed
    AuthenticationMessage.NATIVE_CLEANUP_PENDING -> R.string.auth_native_cleanup_pending
    AuthenticationMessage.TRY_AGAIN -> R.string.auth_try_again
}

private const val MAX_SECRET_LENGTH = 16_384
private val INPUT_DESTINATIONS = setOf(AuthenticationDestination.SIGN_IN, AuthenticationDestination.CODE,
    AuthenticationDestination.PASSWORD)

internal object AuthenticationTags {
    const val USERNAME = "auth_username"
    const val SECRET = "auth_secret"
    const val SUBMIT = "auth_submit"
    const val CANCEL = "auth_cancel"
    const val MESSAGE = "auth_message"
    const val APP_SETTINGS = "auth_app_settings"
    const val ACCOUNT_LABEL = "auth_account_label"
}
