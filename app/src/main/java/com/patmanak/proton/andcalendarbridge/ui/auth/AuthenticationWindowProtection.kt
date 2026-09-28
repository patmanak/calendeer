// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Patmanak
// Adapted from Contako; see app/AUTH_UI_PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination

@Composable
internal fun AuthenticationWindowProtection(destination: AuthenticationDestination) {
    val blocksCapture = destination == AuthenticationDestination.SIGN_IN ||
        destination == AuthenticationDestination.CODE || destination == AuthenticationDestination.PASSWORD
    ProtectedAuthenticationSurface(blocksCapture)
}

/** Restore existing host policy, never weaken another owner's secure-window setting. */
@Composable
internal fun ProtectedAuthenticationSurface(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = view.context.findActivity()?.window
        val previouslySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        val autofill = view.importantForAutofill
        val capture = view.importantForContentCapture
        if (enabled) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
        onDispose {
            if (enabled) {
                if (!previouslySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                view.importantForAutofill = autofill
                view.importantForContentCapture = capture
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
