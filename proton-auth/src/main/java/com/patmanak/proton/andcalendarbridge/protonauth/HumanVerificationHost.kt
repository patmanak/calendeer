// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import android.webkit.WebView
import kotlinx.coroutines.flow.StateFlow

/** UI metadata only. Hosted challenge URLs and solution tokens never cross this boundary. */
data class HumanVerificationState(val isRequired: Boolean = false, val generation: Long = 0)

class HumanVerificationHost internal constructor() {
    internal val delegate = GateCInteractiveHumanVerification()
    val state: StateFlow<HumanVerificationState> get() = delegate.uiState

    /** Invoke on the UI thread. False means the installed WebView cannot safely host verification. */
    fun load(webView: WebView, generation: Long, darkTheme: Boolean): Boolean =
        delegate.load(webView, generation, darkTheme)

    /** Releases this view, while allowing the pending challenge to attach to a replacement view. */
    fun release(webView: WebView) = delegate.release(webView)
    fun cancel() = delegate.cancel()
}
