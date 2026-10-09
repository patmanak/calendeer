package com.patmanak.proton.andcalendarbridge

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.importing.ImportUiState
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import com.patmanak.proton.andcalendarbridge.ui.calendars.SystemCreatePanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated presentation fixtures: no controller, Provider, permissions or live submission. */
@RunWith(AndroidJUnit4::class)
class SystemTitleModeUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val selection = ImportStore.Selection(
        CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0),
        CalendarSummary(CalendarId("fixture-calendar"), "Fixture calendar"), true, "IMPORTED", null)
    private val imports = mutableStateOf(ImportUiState(
        selections = listOf(selection), hasPermission = true, authenticated = true))
    private val state = mutableStateOf(SystemCreateUiState())
    private val createRequests = mutableListOf<Boolean>()
    private val titleRequests = mutableListOf<Boolean>()

    private fun start() {
        compose.setContent {
            MaterialTheme {
                SystemCreatePanel(imports.value, state.value,
                    onMode = { selected, value -> assertEquals(selection.scope, selected.scope); createRequests += value },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    onTitlesMode = { selected, value -> assertEquals(selection.scope, selected.scope); titleRequests += value })
            }
        }
    }

    @Test fun titleModeDefaultsOffAndCreationOptInDoesNotOptIntoTitles() {
        start()
        compose.onNodeWithTag("system-create-toggle").assertIsOff()
        compose.onNodeWithTag("system-title-toggle").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("system-create-toggle").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(true), createRequests)
            assertEquals(emptyList<Boolean>(), titleRequests)
            state.value = state.value.copy(enabled = setOf(selection.scope))
        }
        compose.onNodeWithTag("system-title-toggle").assertIsOff().assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(true), titleRequests) }
    }

    @Test fun newTitleOptInRequiresCreationPermissionSelectionAndAvailability() {
        state.value = SystemCreateUiState(enabled = setOf(selection.scope))
        start()
        for (blocked in listOf(
            imports.value.copy(hasPermission = false),
            imports.value.copy(selections = listOf(selection.copy(enabled = false))),
            imports.value.copy(selections = listOf(selection.copy(available = false))),
        )) {
            compose.runOnIdle { imports.value = blocked }
            compose.onNodeWithTag("system-title-toggle").assertIsOff().assertIsNotEnabled()
        }
        compose.runOnIdle {
            imports.value = ImportUiState(listOf(selection), hasPermission = true, authenticated = true)
            state.value = state.value.copy(enabled = emptySet())
        }
        compose.onNodeWithTag("system-title-toggle").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), titleRequests) }
    }

    @Test fun retainedTitleOptInCanAlwaysBeTurnedOffWhenAuthorityIsMissing() {
        imports.value = imports.value.copy(hasPermission = false,
            selections = listOf(selection.copy(enabled = false, available = false)))
        state.value = SystemCreateUiState(titlesEnabled = setOf(selection.scope))
        start()
        compose.onNodeWithTag("system-title-toggle").assertIsOn().assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(false), titleRequests)
            assertEquals(emptyList<Boolean>(), createRequests)
        }
    }

    @Test fun signedOutDoesNotExposeSettingsOrTriggerOptIn() {
        imports.value = imports.value.copy(authenticated = false)
        start()
        compose.onNodeWithTag("system-create-panel").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), createRequests)
            assertEquals(emptyList<Boolean>(), titleRequests)
        }
    }
}
