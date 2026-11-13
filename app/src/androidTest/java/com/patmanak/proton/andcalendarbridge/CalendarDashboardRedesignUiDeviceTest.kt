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
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.*
import com.patmanak.proton.andcalendarbridge.ui.calendars.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Presentation-only fixtures: no real calendar, account, scheduling or network operations. */
class CalendarDashboardRedesignUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val summary = CalendarSummary(CalendarId("ui-calendar"), "Example calendar", 0x7448ce)
    private val selection = ImportStore.Selection(CalendarScope(AccountId("ui-account"), summary.id, 0),
        summary, enabled = true, status = "IMPORTED", completedAt = null)
    private val state = mutableStateOf(ImportUiState(listOf(selection), hasPermission = true, authenticated = true))
    private var refreshes = 0
    private var sends = 0
    private var visibilityChanges = 0
    private var modes = 0
    private fun start() = compose.setContent {
        MaterialTheme {
            CalendarDashboard(CalendarCatalogState(CalendarCatalogStatus.READY, listOf(summary), canRefresh = true,
                account = selection.scope.account), state.value, SystemCreateUiState(),
                { refreshes++ }, { _, _ -> visibilityChanges++ }, { sends++ }, {}, {}, { _, _ -> modes++ },
                Modifier.verticalScroll(rememberScrollState()))
        }
    }
    @Test fun openingRefreshesCatalogOnceWithoutSendingAndUsesOneStatusBanner() {
        start()
        compose.onNodeWithTag("calendar_sync_overview").assertExists()
        compose.onNodeWithTag(CalendarCatalogTags.REFRESH).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(0, sends); state.value = state.value.copy(busy = true) }
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(0, sends) }
    }
    @Test fun recordedFailuresRemainAttentionInTheRenderedBanner() {
        start()
        for (status in listOf("AUTH", "LOCAL_KEYS", "CAPACITY", "UNSUPPORTED", "ERROR")) {
            compose.runOnIdle { state.value = state.value.copy(selections = listOf(selection.copy(status = status))) }
            compose.onNodeWithTag("calendar_sync_status")
                .assertTextEquals(compose.activity.getString(R.string.calendar_dashboard_attention))
        }
        compose.runOnIdle { assertEquals(0, sends) }
    }
    @Test fun touchingTheNameOpensDetailsWithoutTogglingAnything() {
        start()
        compose.onNodeWithText(summary.title).performScrollTo().performClick()
        compose.onNodeWithTag("calendar_detail_sheet").assertExists()
        compose.onNodeWithTag("system-create-toggle").assertIsNotSelected()
        compose.runOnIdle { assertEquals(0, visibilityChanges); assertEquals(0, modes); assertEquals(0, sends) }
        compose.onNodeWithTag("calendar_detail_close").performClick()
        compose.onNodeWithTag("calendar_detail_sheet").assertDoesNotExist()
    }
    @Test fun signingOutClosesAnOpenDetailAndRemovesMetadata() {
        start()
        compose.onNodeWithText(summary.title).performScrollTo().performClick()
        compose.onNodeWithTag("calendar_detail_sheet").assertExists()
        compose.runOnIdle { state.value = state.value.copy(authenticated = false) }
        compose.onNodeWithTag("calendar_detail_sheet").assertDoesNotExist()
        compose.onNodeWithText(summary.title).assertDoesNotExist()
    }
}
