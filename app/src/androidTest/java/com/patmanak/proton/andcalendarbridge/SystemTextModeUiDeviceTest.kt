// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.importing.ImportUiState
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.*
import com.patmanak.proton.andcalendarbridge.ui.calendars.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation-only fixtures: no production account, Provider or outgoing work. */
@RunWith(AndroidJUnit4::class)
class SystemTextModeUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val summary = CalendarSummary(CalendarId("text-mode-fixture"), "Fixture calendar", null)
    private val selection = ImportStore.Selection(CalendarScope(AccountId("fixture-account"), summary.id, 0),
        summary, enabled = true, status = "IMPORTED", completedAt = null)
    private val imports = mutableStateOf(ImportUiState(listOf(selection), hasPermission = true, authenticated = true))
    private val creates = mutableStateOf(SystemCreateUiState(textSupportAvailable = true))
    private val textChanges = mutableListOf<Boolean>()
    private val titleChanges = mutableListOf<Boolean>()
    private val createChanges = mutableListOf<Boolean>()

    private fun start() {
        compose.setContent {
            MaterialTheme {
                CalendarDashboard(CalendarCatalogState(CalendarCatalogStatus.READY, listOf(summary),
                    canRefresh = true, account = selection.scope.account), imports.value, creates.value,
                    onRefresh = {}, onSetEnabled = { _, _ -> }, onSync = {}, onRequestPermission = {}, onOpenSettings = {},
                    onMode = { _, value -> createChanges += value },
                    onTitlesMode = { _, value -> titleChanges += value },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    onTextMode = { selected, value -> assertEquals(selection.scope, selected.scope); textChanges += value })
            }
        }
        compose.onNodeWithTag(CalendarDashboardTags.OPTIONS).performScrollTo().performClick()
    }

    @Test fun unavailableFeatureNeverExposesConsentEvenWithRetainedPreference() {
        creates.value = creates.value.copy(textSupportAvailable = false, enabled = setOf(selection.scope),
            textsEnabled = setOf(selection.scope))
        start()
        compose.onNodeWithTag("system-text-toggle").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), textChanges) }
    }

    @Test fun openingOptionsDoesNotOptInAndTextConsentIsIndependentOfTitles() {
        start()
        compose.onNodeWithTag("system-write-concurrency-warning").performScrollTo().assertIsDisplayed()
            .assertTextEquals(compose.activity.getString(R.string.system_write_concurrency_warning))
        compose.onNodeWithTag("system-text-toggle").assertIsOff().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), textChanges)
            assertEquals(emptyList<Boolean>(), titleChanges)
            assertEquals(emptyList<Boolean>(), createChanges)
            creates.value = creates.value.copy(enabled = setOf(selection.scope))
        }
        compose.onNodeWithTag("system-text-toggle").assertIsOff().assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(true), textChanges)
            assertEquals(emptyList<Boolean>(), titleChanges)
            assertEquals(emptyList<Boolean>(), createChanges)
        }
        compose.onNodeWithTag("system-title-toggle").assertIsOff()
    }

    @Test fun revokedPermissionOrSelectionBlocksEnablingButAlwaysAllowsTurningOff() {
        creates.value = creates.value.copy(enabled = setOf(selection.scope))
        imports.value = imports.value.copy(hasPermission = false)
        start()
        compose.onNodeWithTag("system-text-toggle").assertIsNotEnabled()
        compose.runOnIdle { imports.value = imports.value.copy(hasPermission = true,
            selections = listOf(selection.copy(enabled = false))) }
        compose.onNodeWithTag("system-text-toggle").assertIsNotEnabled()
        compose.runOnIdle { imports.value = imports.value.copy(selections = listOf(selection.copy(available = false))) }
        compose.onNodeWithTag("system-text-toggle").assertIsNotEnabled()
        compose.runOnIdle {
            imports.value = imports.value.copy(hasPermission = false)
            creates.value = creates.value.copy(enabled = emptySet(), textsEnabled = setOf(selection.scope))
        }
        compose.onNodeWithTag("system-text-toggle").assertIsOn().assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false), textChanges) }
    }

    @Test fun signingOutRemovesTheOptionWithoutChangingRetainedConsent() {
        creates.value = creates.value.copy(enabled = setOf(selection.scope), textsEnabled = setOf(selection.scope))
        start()
        compose.onNodeWithTag("system-text-toggle").assertIsOn()
        compose.runOnIdle { imports.value = imports.value.copy(authenticated = false) }
        compose.onNodeWithTag("system-text-toggle").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), textChanges) }
    }
}
