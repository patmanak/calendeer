// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.ui.calendars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState
import com.patmanak.proton.andcalendarbridge.sync.catalog.*
import java.util.Date

internal enum class DashboardSyncStatus { WORKING, ATTENTION, CONNECTION, PAUSED, WAITING, READY }

/** Observed state only: a successful import alone does not prove an outgoing cycle completed. */
internal fun dashboardSyncStatus(catalog: CalendarCatalogState, imports: ImportUiState,
    creates: SystemCreateUiState, settings: CalendarSyncSettingsState): DashboardSyncStatus {
    if (imports.busy || creates.busy) return DashboardSyncStatus.WORKING
    if (!imports.hasPermission || imports.nativeRemovalPending || creates.failed || creates.legacyUnsupported.isNotEmpty() ||
        imports.selections.any { !it.available || it.deferredCount > 0 || it.historyGap || it.retainedLocalChangeCount > 0 || it.localConflictCount > 0 ||
            it.status !in setOf("IMPORTED", "PAUSED", "PENDING", "CREATE_PENDING", "SERIES_PENDING", "RUNNING", "NETWORK") })
        return DashboardSyncStatus.ATTENTION
    if (imports.selections.any { it.status == "NETWORK" } || (catalog.failure in setOf(CalendarCatalogFailure.UNREACHABLE, CalendarCatalogFailure.TIMED_OUT) &&
        catalog.status == CalendarCatalogStatus.ERROR)) return DashboardSyncStatus.CONNECTION
    if (catalog.status == CalendarCatalogStatus.ERROR) return DashboardSyncStatus.ATTENTION
    val active = imports.selections.filter { it.enabled && it.available }
    if (active.isEmpty() && imports.selections.isNotEmpty()) return DashboardSyncStatus.PAUSED
    if (active.isEmpty() || creates.pending.values.any { it > 0 } || imports.selections.any { it.pendingProjectionCount > 0 } ||
        active.any { it.status != "IMPORTED" } || settings.lastSuccessMillis == null)
        return DashboardSyncStatus.WAITING
    return DashboardSyncStatus.READY
}

@Composable
internal fun CalendarSyncOverview(catalog: CalendarCatalogState, imports: ImportUiState,
    creates: SystemCreateUiState, settings: CalendarSyncSettingsState, onSync: () -> Unit) {
    val status = dashboardSyncStatus(catalog, imports, creates, settings)
    val label = when (status) {
        DashboardSyncStatus.WORKING -> R.string.calendar_dashboard_working
        DashboardSyncStatus.ATTENTION -> R.string.calendar_dashboard_attention
        DashboardSyncStatus.CONNECTION -> R.string.sync_overview_connection
        DashboardSyncStatus.PAUSED -> R.string.import_status_paused
        DashboardSyncStatus.WAITING -> R.string.calendar_dashboard_waiting
        DashboardSyncStatus.READY -> R.string.sync_overview_ready
    }
    Surface(Modifier.fillMaxWidth().testTag("calendar_sync_overview"), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (status == DashboardSyncStatus.WORKING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Box(Modifier.size(10.dp).background(when (status) {
                    DashboardSyncStatus.READY -> Color(0xff2e7d32)
                    DashboardSyncStatus.ATTENTION -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                }, CircleShape))
                Text(stringResource(label), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("calendar_sync_status").semantics { heading(); liveRegion = LiveRegionMode.Polite })
            }
            val context = LocalContext.current
            settings.lastSuccessMillis?.let { timestamp ->
                val date = Date(timestamp)
                val formatted = android.text.format.DateFormat.getDateFormat(context).format(date) + " " +
                    android.text.format.DateFormat.getTimeFormat(context).format(date)
                Text(stringResource(R.string.sync_overview_last, formatted), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.sync_settings_interval_value,
                com.patmanak.proton.andcalendarbridge.ui.settings.intervalLabel(settings.intervalMinutes)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings.accountPresent && (!settings.automatic || !settings.masterAutomatic))
                Text(stringResource(R.string.sync_overview_push_off), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onSync, enabled = canRequestSystemSync(imports, creates),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CalendarImportTags.SYNC)) {
                Text(stringResource(R.string.calendar_dashboard_sync))
            }
            if (creates.failed) SystemSyncAttentionContent(imports, creates, showHeading = false)
        }
    }
}
