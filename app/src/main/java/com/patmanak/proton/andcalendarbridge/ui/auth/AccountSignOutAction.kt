// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Confirmation only; the existing authentication controller owns cleanup and session clearing. */
@Composable
fun AccountSignOutAction(state: AuthenticationState, onSignOut: () -> Unit,
    readPendingWork: suspend () -> Int? = { null }) {
    if (state.destination != AuthenticationDestination.AUTHENTICATED) return
    var request by remember(state.destination, state.isCleanupRequired) { mutableStateOf<Any?>(null) }
    val token = request
    var count by remember(token) { mutableStateOf<Int?>(null) }
    var loaded by remember(token) { mutableStateOf(false) }
    val readLatest by rememberUpdatedState(readPendingWork)
    LaunchedEffect(token) {
        if (token != null) {
            val result = try { withTimeoutOrNull(5_000) { readLatest() }?.takeIf { it >= 0 } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
            if (request === token) { count = result; loaded = true }
        }
    }
    TextButton(onClick = { request = Any() }, enabled = !state.isCleanupInProgress && !state.isSubmitting,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(AuthenticationTags.CANCEL)) {
        Text(stringResource(if (state.isCleanupRequired) R.string.auth_cleanup_retry else R.string.auth_sign_out))
    }
    if (token != null) AlertDialog(
        onDismissRequest = { request = null },
        title = { Text(stringResource(R.string.auth_sign_out_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.auth_sign_out_warning))
                if (!loaded) Text(stringResource(R.string.auth_sign_out_loading), Modifier.testTag("sign_out_count_loading"))
                else if (count == null || count!! > 0) {
                    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                    Surface(color = Color(if (dark) 0xff4d3300 else 0xffffe0b2),
                        contentColor = Color(if (dark) 0xffffdca8 else 0xff4d2900), shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("sign_out_pending_warning")) {
                        Text(if (count == null) stringResource(R.string.auth_sign_out_count_unavailable)
                            else stringResource(R.string.auth_sign_out_count, count!!), Modifier.padding(12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { request = null; onSignOut() }, enabled = loaded && !state.isCleanupInProgress && !state.isSubmitting,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("auth_sign_out_confirm")) { Text(stringResource(R.string.auth_sign_out)) } },
        dismissButton = { TextButton(onClick = { request = null }, modifier = Modifier.testTag("auth_sign_out_cancel")) {
            Text(stringResource(android.R.string.cancel))
        } },
    )
}
