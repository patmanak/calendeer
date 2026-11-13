package com.patmanak.proton.andcalendarbridge.ui.calendars

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.data.importing.ImportUiState
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState

internal fun canRequestSystemSync(imports: ImportUiState, state: SystemCreateUiState) =
    imports.authenticated && imports.hasPermission && !imports.busy && !state.busy &&
        imports.selections.any { it.enabled && it.available }

/** Explains the aggregate attention flag without inventing a failure cause or changing consent. */
@Composable
internal fun SystemSyncAttentionCard(imports: ImportUiState, state: SystemCreateUiState,
    modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    if (!imports.authenticated || !state.failed) return
    var expanded by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth().testTag(CalendarDashboardTags.OUTGOING_ERROR),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sync_attention_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Text(stringResource(R.string.system_create_error), modifier = Modifier.testTag(SystemSyncAttentionTags.PRESERVED))
            if (onRetry != null) Button(onClick = onRetry, enabled = canRequestSystemSync(imports, state),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(SystemSyncAttentionTags.RETRY)) {
                Text(stringResource(R.string.sync_attention_retry))
            }
            val toggleLabel = stringResource(if (expanded) R.string.sync_attention_hide else R.string.sync_attention_why)
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(SystemSyncAttentionTags.WHY)) { Text(toggleLabel) }
            if (expanded) Column(Modifier.testTag(SystemSyncAttentionTags.DETAILS), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.sync_attention_explanation), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.calendar_mode_description),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.import_field_limits),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal object SystemSyncAttentionTags {
    const val RETRY = "system_sync_attention_retry"
    const val WHY = "system_sync_attention_why"
    const val DETAILS = "system_sync_attention_details"
    const val PRESERVED = "system_sync_attention_preserved"
}
