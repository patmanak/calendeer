// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.patmanak.proton.andcalendarbridge.data.importing.CalendarSyncSettingsState
import com.patmanak.proton.andcalendarbridge.ui.settings.CalendarSyncSettingsContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SyncSettingsPresentationUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun intervalAndAndroidActionsRequireExplicitClicksWithoutAManualSyncButton() {
        val state = mutableStateOf(CalendarSyncSettingsState(accountPresent = true, automatic = false))
        val intervals = mutableListOf<Int>()
        var android = 0; var battery = 0
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CalendarSyncSettingsContent(state.value, { intervals += it }, { android++ }, { battery++ })
        } } }
        compose.onNodeWithTag("sync_now").assertDoesNotExist()
        for (minutes in listOf(15, 30, 60, 120, 360)) compose.onNodeWithTag("sync_interval_$minutes").assertExists()
        compose.runOnIdle { assertEquals(emptyList<Int>(), intervals); assertEquals(0, android); assertEquals(0, battery) }
        compose.onNodeWithTag("sync_interval_15").performScrollTo().performClick()
        compose.onNodeWithTag("sync_interval_60").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(15), intervals); state.value = state.value.copy(intervalMinutes = 15) }
        compose.onNodeWithTag("sync_interval_15").assertIsSelected()
        compose.onNodeWithTag("sync_android_settings").performScrollTo().performClick()
        compose.onNodeWithTag("sync_battery_adjust").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, android); assertEquals(1, battery) }
    }
}
