// SPDX-License-Identifier: GPL-3.0-or-later
// Theme/locale wiring adapted from Contako. Copyright (C) 2026 Patmanak.
// See app/PROVENANCE.md and app/AUTH_UI_PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.patmanak.proton.andcalendarbridge.ui.BridgeScreen
import com.patmanak.proton.andcalendarbridge.ui.auth.AuthenticationPanel
import com.patmanak.proton.andcalendarbridge.ui.auth.HumanVerificationPanel
import com.patmanak.proton.andcalendarbridge.ui.calendars.CalendarCatalogPanel
import com.patmanak.proton.andcalendarbridge.ui.calendars.CalendarImportPanel
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.ui.locale.AndroidLanguagePreferenceStore
import com.patmanak.proton.andcalendarbridge.ui.theme.BridgeTheme
import com.patmanak.proton.andcalendarbridge.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {
    private val calendarPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        (application as BridgeApplication).imports.permissionsChanged()
    }
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AndroidLanguagePreferenceStore.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = application as BridgeApplication
        val authentication = preferences.authentication
        val catalog = preferences.calendarCatalog
        setContent {
            val mode by preferences.themePreferences.mode.collectAsState()
            val language by preferences.languagePreferences.language.collectAsState()
            val darkTheme = resolveDarkTheme(mode, isSystemInDarkTheme())
            val verification by ProtonAuthentication.verification.state.collectAsState()
            val catalogState by catalog.state.collectAsState()
            val importState by preferences.imports.state.collectAsState()
            val authenticationState by authentication.state.collectAsState()
            SideEffect {
                val style = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BridgeTheme(darkTheme) {
                BridgeScreen(
                    mode, preferences.themePreferences::setMode, language,
                    signedIn = authenticationState.destination == com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination.AUTHENTICATED,
                    verificationRequired = verification.isRequired,
                    settingsEnabled = !verification.isRequired && !authenticationState.isSubmitting &&
                        !authenticationState.isCleanupRequired && authenticationState.destination in setOf(
                            com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination.SIGN_IN,
                            com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination.AUTHENTICATED),
                    onLanguageChange = { selected ->
                        if (selected != language) {
                            preferences.languagePreferences.setLanguage(selected)
                            if (Build.VERSION.SDK_INT < 33) recreate()
                        }
                    },
                    authenticationContent = {
                        if (verification.isRequired) {
                            HumanVerificationPanel(ProtonAuthentication.verification, darkTheme)
                        } else {
                            AuthenticationPanel(authentication)
                        }
                    },
                    calendarContent = {
                        CalendarCatalogPanel(catalogState, catalog::refresh)
                        CalendarImportPanel(importState, preferences.imports::setEnabled,
                            preferences.imports::requestSync,
                            onRequestPermission = { calendarPermissions.launch(arrayOf(
                                Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
                            onOpenSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName"))) })
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as BridgeApplication).languagePreferences.refresh()
        (application as BridgeApplication).imports.permissionsChanged()
    }
}
