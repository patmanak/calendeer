package com.patmanak.proton.andcalendarbridge.ui.calendars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.importing.ImportUiState
import java.util.Date

/** Selection and status only. No event content, editing controls or remote mutation is presented. */
@Composable
fun CalendarImportPanel(
    state: ImportUiState,
    onSetEnabled: (ImportStore.Selection, Boolean) -> Unit,
    onSync: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.authenticated) return
    Column(modifier.fillMaxWidth().testTag(CalendarImportTags.PANEL), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.import_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.import_read_only))
        Text(stringResource(R.string.import_privacy), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.hasPermission) {
            Text(stringResource(R.string.import_permission_help))
            Button(onClick = onRequestPermission,
                modifier = Modifier.heightIn(min = 48.dp).testTag(CalendarImportTags.PERMISSION)) {
                Text(stringResource(R.string.import_allow))
            }
            TextButton(onClick = onOpenSettings,
                modifier = Modifier.heightIn(min = 48.dp).testTag(CalendarImportTags.SETTINGS)) {
                Text(stringResource(R.string.import_settings))
            }
        }
        if (state.selections.isEmpty()) Text(stringResource(R.string.import_no_calendars))
        state.selections.forEach { selection ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(selection.enabled, enabled = !state.busy && selection.available, role = Role.Switch,
                        onValueChange = { onSetEnabled(selection, it) })
                    .testTag(CalendarImportTags.TOGGLE).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(selection.summary.title.ifBlank { stringResource(R.string.catalog_untitled) },
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = selection.enabled, onCheckedChange = null, enabled = !state.busy && selection.available)
                }
                Text(stringResource(statusResource(if (!selection.available) "UNAVAILABLE" else if (selection.enabled) selection.status else "PAUSED")),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(CalendarImportTags.STATUS).semantics { liveRegion = LiveRegionMode.Polite })
                if (selection.deferredCount > 0) {
                    Text(stringResource(R.string.import_deferred_count, selection.deferredCount),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(CalendarImportTags.DEFERRED))
                    Text(stringResource(R.string.import_deferred_categories,
                        selection.deferredCount - selection.unverifiedCount, selection.unverifiedCount),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (selection.historyGap) {
                    Text(stringResource(R.string.import_history_gap_help),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(CalendarImportTags.HISTORY_GAP))
                }
                selection.coverage?.let { coverage ->
                    Text(stringResource(R.string.import_coverage_progress,
                        coverage.receivedWindows, coverage.windows.size, coverage.appliedWindows),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(CalendarImportTags.COVERAGE))
                    Text(stringResource(R.string.import_coverage_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selection.pendingProjectionCount > 0) {
                    Text(stringResource(R.string.import_projection_pending, selection.pendingProjectionCount),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(CalendarImportTags.PROJECTION_PENDING))
                }
                if (selection.retainedLocalChangeCount > 0) {
                    Text(stringResource(R.string.import_local_changes, selection.retainedLocalChangeCount, selection.localConflictCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selection.localConflictCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(CalendarImportTags.LOCAL_CHANGES).semantics { liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(R.string.import_local_changes_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(CalendarImportTags.LOCAL_CHANGES_HELP))
                }
                selection.completedAt?.let { timestamp ->
                    val context = LocalContext.current
                    val locale = LocalConfiguration.current.locales.toLanguageTags()
                    val completed = remember(timestamp, locale) {
                        android.text.format.DateFormat.getMediumDateFormat(context).format(Date(timestamp)) + " " +
                            android.text.format.DateFormat.getTimeFormat(context).format(Date(timestamp))
                    }
                    Text(stringResource(R.string.import_last_completed, completed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(CalendarImportTags.COMPLETED))
                }
            }
        }
        Button(onClick = onSync,
            enabled = state.hasPermission && !state.busy && state.selections.any { it.enabled },
            modifier = Modifier.heightIn(min = 48.dp).testTag(CalendarImportTags.SYNC)) {
            Text(stringResource(if (state.busy) R.string.import_running else R.string.import_sync))
        }
        Text(stringResource(R.string.import_window), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.import_field_limits), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.import_recurrence_limits), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.import_pause_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun statusResource(status: String): Int = when (status) {
    "PAUSED" -> R.string.import_status_paused
    "PENDING" -> R.string.import_status_pending
    "CREATE_PENDING" -> R.string.import_status_create_pending
    "RUNNING" -> R.string.import_status_running
    "IMPORTED" -> R.string.import_status_imported
    "PARTIAL" -> R.string.import_status_partial
    "INCOMPLETE" -> R.string.import_status_incomplete
    "SERIES_PENDING" -> R.string.import_status_series_pending
    "HISTORY_GAP" -> R.string.import_status_history_gap
    "UNAVAILABLE" -> R.string.import_status_unavailable
    "CAPACITY" -> R.string.import_status_capacity
    "LOCAL_KEYS" -> R.string.import_status_local_keys
    "PERMISSION" -> R.string.import_status_permission
    "AUTH" -> R.string.import_status_auth
    "NETWORK" -> R.string.import_status_network
    "UNSUPPORTED" -> R.string.import_status_unsupported
    else -> R.string.import_status_error
}

internal object CalendarImportTags {
    const val PANEL = "import_panel"
    const val PERMISSION = "import_permission"
    const val SETTINGS = "import_settings"
    const val TOGGLE = "import_toggle"
    const val STATUS = "import_status"
    const val COMPLETED = "import_completed"
    const val SYNC = "import_sync"
    const val DEFERRED = "import_deferred"
    const val HISTORY_GAP = "import_history_gap"
    const val COVERAGE = "import_coverage"
    const val PROJECTION_PENDING = "import_projection_pending"
    const val LOCAL_CHANGES = "import_local_changes"
    const val LOCAL_CHANGES_HELP = "import_local_changes_help"
}
