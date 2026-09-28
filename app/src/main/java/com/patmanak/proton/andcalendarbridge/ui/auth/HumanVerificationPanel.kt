package com.patmanak.proton.andcalendarbridge.ui.auth

import android.view.View
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.protonauth.HumanVerificationHost

/** Render instead of the credential panel while the host requires interactive verification. */
@Composable
fun HumanVerificationPanel(host: HumanVerificationHost, darkTheme: Boolean, modifier: Modifier = Modifier) {
    val state by host.state.collectAsState()
    if (!state.isRequired) return
    ProtectedAuthenticationSurface(true)
    BackHandler { host.cancel() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.auth_human_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        key(host, state.generation, darkTheme) {
            val views = remember { arrayOfNulls<WebView>(1) }
            val view = LocalView.current
            DisposableEffect(view) {
                val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> views[0]?.onPause()
                        Lifecycle.Event.ON_START -> views[0]?.onResume()
                        else -> Unit
                    }
                }
                lifecycle?.addObserver(observer)
                onDispose { lifecycle?.removeObserver(observer) }
            }
            AndroidView(
                factory = { context ->
                    WebView(context).also { webView ->
                        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        webView.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                        views[0] = webView
                        if (!host.load(webView, state.generation, darkTheme)) host.cancel()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(420.dp),
                onRelease = { webView -> views[0] = null; host.release(webView) },
            )
        }
        TextButton(onClick = host::cancel, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.auth_human_cancel))
        }
    }
}
