// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.protonauth.DeleteNetworkInterruptionObservation
import org.junit.Assert.assertTrue

/** Opt-in live journey only. The external protected driver must restore the saved settings
 * even when the runner fails. No SIM, VPN, mobile-data or airplane-mode mutation is allowed.
 */
internal object DeleteNetworkInterruptionJourney {
    fun assertOffline(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        assertTrue("Require actual offline recovery before reconnecting for inspection",
            manager.allNetworks.none { manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true })
    }

    fun cutWifi(context: Context, prefs: SharedPreferences) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun internet() = manager.allNetworks.mapNotNull(manager::getNetworkCapabilities)
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        fun setting(name: String) = Settings.Global.getString(context.contentResolver, name)
        val wifi = setting("wifi_on")
        val mobile = setting("mobile_data")
        val airplane = setting("airplane_mode_on")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val sim = automation.executeShellCommand("getprop gsm.sim.state").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        assertTrue("Only the enrolled no-SIM Wi-Fi-only transport may be interrupted", wifi == "1" &&
            mobile in setOf("0", "1") && airplane in setOf("0", "1") && sim.split(',').all { it.trim() == "ABSENT" } &&
            internet().let { networks -> networks.isNotEmpty() && networks.all { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } })
        assertTrue("Never repeat a radio interruption for an existing request", !prefs.contains("network_cut_attempted"))
        assertTrue(prefs.edit().putBoolean("network_cut_attempted", true).putString("network_original_wifi", wifi)
            .putString("network_original_mobile", mobile).putString("network_original_airplane", airplane).commit())
        automation.executeShellCommand("svc wifi disable").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline && (setting("wifi_on") != "0" || internet().isNotEmpty())) Thread.sleep(50)
        assertTrue("Require real connectivity loss before resuming the paused HTTP body", setting("wifi_on") == "0" &&
            internet().isEmpty() && setting("mobile_data") == mobile && setting("airplane_mode_on") == airplane)
        assertTrue(prefs.edit().putBoolean("network_loss_observed", true).commit())
    }

    fun retainMeasurement(prefs: SharedPreferences, observation: DeleteNetworkInterruptionObservation) {
        assertTrue("Require a natural socket failure of the exact authenticated call after connectivity loss",
            prefs.getBoolean("network_loss_observed", false) && observation.measuredFailure)
        assertTrue(prefs.edit().putBoolean("network_socket_failure", true).putInt("network_calls", observation.calls)
            .putInt("network_body_starts", observation.bodyStarts).commit())
    }
}
