package com.patmanak.proton.andcalendarbridge.ui.calendars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.importing.ImportUiState
import com.patmanak.proton.andcalendarbridge.data.importing.CalendarSyncSettingsState
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogState
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogStatus
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.util.Date

/** Configuration and metadata only. Opening a card never changes synchronization or write consent. */
@Composable
fun CalendarDashboard(
    catalog: CalendarCatalogState,
    imports: ImportUiState,
    creates: SystemCreateUiState,
    onRefresh: () -> Unit,
    onSetEnabled: (ImportStore.Selection, Boolean) -> Unit,
    onSync: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onMode: (ImportStore.Selection, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onOpenConflicts: (ImportStore.Selection) -> Unit = {},
    syncSettings: CalendarSyncSettingsState = CalendarSyncSettingsState(),
) {
    if (!imports.authenticated) return
    LaunchedEffect(catalog.account) {
        if (catalog.canRefresh && !catalog.isRetryBlocked && catalog.status !in setOf(CalendarCatalogStatus.LOADING, CalendarCatalogStatus.SIGNED_OUT)) onRefresh()
    }
    Column(modifier.fillMaxWidth().testTag(CalendarDashboardTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.calendars_heading), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        CalendarSyncOverview(catalog, imports, creates, syncSettings, onSync)
        if (!imports.hasPermission) PermissionCard(onRequestPermission, onOpenSettings)
        CatalogNotice(catalog, imports.selections.isNotEmpty())
        imports.selections.forEach { selection ->
            key(selection.scope) {
                CalendarCard(selection, imports, creates, onSetEnabled, onMode, onOpenConflicts)
            }
        }
        // A newly discovered row needs a persisted scope before its controls can be offered.
        catalog.calendars.filter { summary -> imports.selections.none { it.summary.id == summary.id } }
            .forEach { summary -> key(summary.id) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CalendarColor(summary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(summary.title.ifBlank { stringResource(R.string.catalog_untitled) },
                                style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.calendar_dashboard_configuring),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.import_permission_help), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.import_privacy), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CalendarImportTags.PERMISSION)) {
                Text(stringResource(R.string.import_allow))
            }
            TextButton(onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CalendarImportTags.SETTINGS)) {
                Text(stringResource(R.string.import_settings))
            }
        }
    }
}

@Composable
private fun CatalogNotice(state: CalendarCatalogState, hasSelections: Boolean) {
    when (state.status) {
        CalendarCatalogStatus.SIGNED_OUT -> Text(stringResource(R.string.catalog_signed_out),
            modifier = Modifier.testTag(CalendarCatalogTags.SIGNED_OUT))
        CalendarCatalogStatus.LOADING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(if (state.calendars.isEmpty() && !hasSelections) R.string.catalog_loading else R.string.catalog_refreshing),
                modifier = Modifier.testTag(CalendarCatalogTags.LOADING).semantics { liveRegion = LiveRegionMode.Polite })
        }
        CalendarCatalogStatus.READY -> if (state.calendars.isEmpty() && !hasSelections) {
            Text(stringResource(R.string.catalog_empty), modifier = Modifier.testTag(CalendarCatalogTags.EMPTY))
        }
        CalendarCatalogStatus.ERROR -> {
            Text(stringResource(failureResource(state.failure)), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(CalendarCatalogTags.ERROR).semantics { liveRegion = LiveRegionMode.Polite })
            if (state.calendars.isNotEmpty() || hasSelections) Text(stringResource(R.string.catalog_stale),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(CalendarCatalogTags.STALE))
        }
    }
    if (state.isRetryBlocked) Text(stringResource(R.string.catalog_retry_wait),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun CalendarCard(
    selection: ImportStore.Selection,
    imports: ImportUiState,
    creates: SystemCreateUiState,
    onSetEnabled: (ImportStore.Selection, Boolean) -> Unit,
    onMode: (ImportStore.Selection, Boolean) -> Unit,
    onOpenConflicts: (ImportStore.Selection) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val createEnabled = selection.scope in creates.enabled
    val outgoing = creates.pending[selection.scope] ?: 0
    val hasIssues = selection.deferredCount > 0 || selection.historyGap || selection.retainedLocalChangeCount > 0
    val status = when {
        !selection.available -> R.string.calendar_dashboard_unavailable
        !selection.enabled -> R.string.import_status_paused
        selection.status == "RUNNING" -> R.string.calendar_dashboard_working
        hasIssues -> R.string.calendar_dashboard_attention
        outgoing > 0 || selection.pendingProjectionCount > 0 -> R.string.calendar_dashboard_waiting
        selection.status == "IMPORTED" -> R.string.calendar_dashboard_ready
        selection.status in setOf("PENDING", "CREATE_PENDING", "SERIES_PENDING") -> R.string.calendar_dashboard_waiting
        else -> R.string.calendar_dashboard_attention
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag(CalendarDashboardTags.CARD)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(selection.enabled, enabled = !imports.busy && selection.available,
                    role = Role.Switch, onValueChange = { onSetEnabled(selection, it) })
                .testTag(CalendarImportTags.TOGGLE), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CalendarColor(selection.summary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selection.summary.title.ifBlank { stringResource(R.string.catalog_untitled) },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(status), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(CalendarImportTags.STATUS).semantics { liveRegion = LiveRegionMode.Polite })
                }
                Switch(selection.enabled, onCheckedChange = null, enabled = !imports.busy && selection.available)
            }
            Text(stringResource(if (createEnabled) R.string.calendar_mode_editable else R.string.calendar_dashboard_read_only),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            // Unresolved work stays visible even when options are closed or the calendar is paused.
            if (selection.scope in creates.legacyUnsupported) Text(stringResource(R.string.calendar_legacy_unsupported),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("legacy-update-unsupported").semantics { liveRegion = LiveRegionMode.Polite })
            if (selection.deferredCount > 0) PendingText(
                stringResource(R.string.calendar_dashboard_held, selection.deferredCount), CalendarImportTags.DEFERRED)
            if (selection.historyGap) PendingText(stringResource(R.string.calendar_dashboard_history), CalendarImportTags.HISTORY_GAP)
            if (selection.pendingProjectionCount > 0) PendingText(
                stringResource(R.string.calendar_dashboard_pending, selection.pendingProjectionCount), CalendarImportTags.PROJECTION_PENDING)
            if (selection.retainedLocalChangeCount > 0) Text(
                stringResource(R.string.calendar_dashboard_local, selection.retainedLocalChangeCount, selection.localConflictCount),
                style = MaterialTheme.typography.bodySmall,
                color = if (selection.localConflictCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(CalendarImportTags.LOCAL_CHANGES).semantics { liveRegion = LiveRegionMode.Polite })
            if (outgoing > 0) PendingText(stringResource(R.string.calendar_dashboard_outgoing, outgoing), "system-create-pending")
            TextButton(onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CalendarDashboardTags.OPTIONS)) {
                Text(stringResource(if (expanded) R.string.calendar_dashboard_close_options else R.string.calendar_dashboard_options))
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.calendar_dashboard_editing), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.system_write_concurrency_warning),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("system-write-concurrency-warning"))
                val canEnable = imports.hasPermission && selection.enabled && selection.available
                ModeToggle(stringResource(R.string.calendar_mode_editable), createEnabled, createEnabled || canEnable,
                    "system-create-toggle") { onMode(selection, it) }
                Text(stringResource(R.string.calendar_mode_description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selection.scope in creates.modeReview) Text(stringResource(R.string.calendar_mode_migration),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CalendarDetails(selection)
                TextButton(onClick = { onOpenConflicts(selection) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ConflictArchiveTags.OPEN)) {
                    Text(stringResource(R.string.conflict_archive_open))
                }
            }
        }
    }
}

@Composable
private fun CalendarColor(summary: CalendarSummary) {
    val color = summary.colorRgb?.let { Color(0xFF000000 or it.toLong()) } ?: MaterialTheme.colorScheme.outline
    Box(Modifier.size(14.dp).background(color, CircleShape))
}

@Composable
private fun PendingText(text: String, tag: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun CalendarDetails(selection: ImportStore.Selection) {
    Text(stringResource(R.string.calendar_dashboard_details), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() })
    Text(stringResource(statusResource(if (!selection.available) "UNAVAILABLE" else if (selection.enabled) selection.status else "PAUSED")),
        style = MaterialTheme.typography.bodySmall)
    selection.completedAt?.let { timestamp ->
        val context = LocalContext.current
        val locale = LocalConfiguration.current.locales.toLanguageTags()
        val formatted = remember(timestamp, locale) {
            android.text.format.DateFormat.getMediumDateFormat(context).format(Date(timestamp)) + " " +
                android.text.format.DateFormat.getTimeFormat(context).format(Date(timestamp))
        }
        Text(stringResource(R.string.import_last_completed, formatted), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(CalendarImportTags.COMPLETED))
    }
    selection.coverage?.let { coverage ->
        Text(stringResource(R.string.import_coverage_progress, coverage.receivedWindows, coverage.windows.size, coverage.appliedWindows),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(CalendarImportTags.COVERAGE))
        Text(stringResource(R.string.import_coverage_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (selection.deferredCount > 0) Text(stringResource(R.string.import_deferred_categories,
        selection.deferredCount - selection.unverifiedCount, selection.unverifiedCount), style = MaterialTheme.typography.bodySmall)
    if (selection.historyGap) Text(stringResource(R.string.import_history_gap_help), style = MaterialTheme.typography.bodySmall)
    if (selection.retainedLocalChangeCount > 0) Text(stringResource(R.string.calendar_mode_description),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(CalendarImportTags.LOCAL_CHANGES_HELP))
}

@Composable
private fun SyncHelp() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag(CalendarDashboardTags.HELP)) { Text(stringResource(R.string.calendar_dashboard_usage)) }
        if (expanded) {
            for (resource in listOf(R.string.calendar_mode_description, R.string.import_privacy, R.string.import_window,
                R.string.import_field_limits, R.string.import_recurrence_limits, R.string.import_pause_help)) {
                Text(stringResource(resource), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal object CalendarDashboardTags {
    const val PANEL = "calendar_dashboard"
    const val CARD = "calendar_card"
    const val OPTIONS = "calendar_options"
    const val HELP = "calendar_sync_help"
    const val OUTGOING_ERROR = "calendar_outgoing_error"
}

@Composable
internal fun ModeToggle(label: String, checked: Boolean, enabled: Boolean, tag: String,
    onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
