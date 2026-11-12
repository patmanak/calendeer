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
    private val modeChanges = mutableListOf<Boolean>()

    private fun start() {
        compose.setContent {
            MaterialTheme {
                CalendarDashboard(CalendarCatalogState(CalendarCatalogStatus.READY, listOf(summary),
                    canRefresh = true, account = selection.scope.account), imports.value, creates.value,
                    onRefresh = {}, onSetEnabled = { _, _ -> }, onSync = {}, onRequestPermission = {}, onOpenSettings = {},
                    onMode = { selected, value -> assertEquals(selection.scope, selected.scope); modeChanges += value },
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        }
        compose.onNodeWithTag(CalendarDashboardTags.OPTIONS).performScrollTo().performClick()
    }

    @Test fun unavailableTextSupportKeepsOneEditableChoiceWithoutSeparateTextConsent() {
        creates.value = creates.value.copy(textSupportAvailable = false, enabled = setOf(selection.scope),
            textsEnabled = setOf(selection.scope))
        start()
        compose.onNodeWithTag("system-text-toggle").assertDoesNotExist()
        compose.onNodeWithTag("system-create-toggle").assertIsOn().assertIsEnabled()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), modeChanges) }
    }

    @Test fun openingOptionsShowsWarningAndKeepsReadOnlyUntilAnExplicitChoice() {
        start()
        compose.onNodeWithTag("system-write-concurrency-warning").performScrollTo().assertIsDisplayed()
            .assertTextEquals(compose.activity.getString(R.string.system_write_concurrency_warning))
        compose.onNodeWithTag("system-create-toggle").assertIsOff().assertIsEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.calendar_mode_description)).assertExists()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), modeChanges) }
        compose.onNodeWithTag("system-create-toggle").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(true), modeChanges) }
        compose.onNodeWithTag("system-title-toggle").assertDoesNotExist()
        compose.onNodeWithTag("system-text-toggle").assertDoesNotExist()
    }

    @Test fun partialLegacyTextConsentExplainsReadOnlyWithoutSilentlyEnablingIt() {
        creates.value = creates.value.copy(textsEnabled = setOf(selection.scope), modeReview = setOf(selection.scope))
        start()
        compose.onNodeWithTag("system-create-toggle").assertIsOff().assertIsEnabled()
        compose.onNodeWithTag("system-text-toggle").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.calendar_mode_migration)).assertExists()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), modeChanges) }
        compose.onNodeWithTag("system-create-toggle").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(true), modeChanges)
            creates.value = creates.value.copy(enabled = setOf(selection.scope), modeReview = emptySet())
        }
        compose.onNodeWithTag("system-create-toggle").assertIsOn()
        compose.onNodeWithText(compose.activity.getString(R.string.calendar_mode_migration)).assertDoesNotExist()
    }

    @Test fun signingOutRemovesTheOptionWithoutChangingRetainedConsent() {
        creates.value = creates.value.copy(enabled = setOf(selection.scope), textsEnabled = setOf(selection.scope))
        start()
        compose.onNodeWithTag("system-create-toggle").assertIsOn()
        compose.runOnIdle { imports.value = imports.value.copy(authenticated = false) }
        compose.onNodeWithTag("system-create-toggle").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), modeChanges) }
    }
}
