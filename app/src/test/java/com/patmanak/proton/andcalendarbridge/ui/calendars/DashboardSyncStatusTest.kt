// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.ui.calendars

import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.*
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardSyncStatusTest {
    private val summary = CalendarSummary(CalendarId("fixture-calendar"), "Fixture")
    private val selection = ImportStore.Selection(CalendarScope(AccountId("fixture-account"), summary.id, 0),
        summary, true, "IMPORTED", completedAt = 1)
    private val catalog = CalendarCatalogState(CalendarCatalogStatus.READY)
    private val imports = ImportUiState(listOf(selection), hasPermission = true, authenticated = true)
    private val creates = SystemCreateUiState()
    private val settings = CalendarSyncSettingsState(lastSuccessMillis = 2)
    @Test fun importedRowsAloneDoNotClaimACompleteCycle() {
        assertEquals(DashboardSyncStatus.WAITING, dashboardSyncStatus(catalog, imports, creates, settings.copy(lastSuccessMillis = null)))
        assertEquals(DashboardSyncStatus.READY, dashboardSyncStatus(catalog, imports, creates, settings))
    }
    @Test fun retainedWorkInPausedCalendarsStillRequiresAttention() {
        val paused = selection.copy(scope = selection.scope.copy(calendar = CalendarId("paused")), enabled = false, retainedLocalChangeCount = 1)
        assertEquals(DashboardSyncStatus.ATTENTION, dashboardSyncStatus(catalog, imports.copy(selections = listOf(selection, paused)), creates, settings))
        assertEquals(DashboardSyncStatus.WAITING, dashboardSyncStatus(catalog, imports.copy(selections = listOf(selection,
            paused.copy(retainedLocalChangeCount = 0, pendingProjectionCount = 1))), creates, settings))
    }
    @Test fun pendingOutgoingAndProjectionAreNeverReady() {
        assertEquals(DashboardSyncStatus.WAITING, dashboardSyncStatus(catalog, imports, creates.copy(pending = mapOf(selection.scope to 1)), settings))
        assertEquals(DashboardSyncStatus.WAITING, dashboardSyncStatus(catalog, imports.copy(selections = listOf(selection.copy(pendingProjectionCount = 1))), creates, settings))
    }
    @Test fun permissionFailureAndUnsupportedWorkRemainVisible() {
        assertEquals(DashboardSyncStatus.ATTENTION, dashboardSyncStatus(catalog, imports.copy(hasPermission = false), creates, settings))
        assertEquals(DashboardSyncStatus.ATTENTION, dashboardSyncStatus(catalog, imports, creates.copy(failed = true), settings))
        assertEquals(DashboardSyncStatus.ATTENTION, dashboardSyncStatus(catalog, imports, creates.copy(legacyUnsupported = setOf(selection.scope)), settings))
    }
    @Test fun recordedImportFailuresAreNotReportedAsSimpleWaiting() {
        for (status in listOf("AUTH", "LOCAL_KEYS", "CAPACITY", "UNSUPPORTED", "ERROR", "PARTIAL", "INCOMPLETE", "HISTORY_GAP", "FUTURE_FAILURE")) {
            assertEquals(status, DashboardSyncStatus.ATTENTION, dashboardSyncStatus(catalog,
                imports.copy(selections = listOf(selection.copy(status = status))), creates, settings))
        }
    }
    @Test fun connectionFailureDoesNotAssertThatTheDeviceIsOffline() {
        assertEquals(DashboardSyncStatus.CONNECTION, dashboardSyncStatus(catalog.copy(status = CalendarCatalogStatus.ERROR,
            failure = CalendarCatalogFailure.UNREACHABLE), imports, creates, settings))
        assertEquals(DashboardSyncStatus.ATTENTION, dashboardSyncStatus(catalog.copy(status = CalendarCatalogStatus.ERROR,
            failure = CalendarCatalogFailure.FORBIDDEN), imports, creates, settings))
    }
    @Test fun busyAndPausedStatesAndAutomaticSwitchesKeepTheirMeaning() {
        assertEquals(DashboardSyncStatus.WORKING, dashboardSyncStatus(catalog, imports.copy(busy = true), creates, settings))
        assertEquals(DashboardSyncStatus.PAUSED, dashboardSyncStatus(catalog, imports.copy(selections = listOf(selection.copy(enabled = false))), creates, settings))
        assertEquals(DashboardSyncStatus.READY, dashboardSyncStatus(catalog, imports, creates,
            settings.copy(accountPresent = true, automatic = false, masterAutomatic = false)))
    }
}
