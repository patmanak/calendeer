package com.patmanak.proton.andcalendarbridge.ui.calendars

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.data.importing.ImportUiState
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState

/** Bridge configuration only; editing stays in the user's calendar application. */
@Composable
fun SystemCreatePanel(imports: ImportUiState, state: SystemCreateUiState,
    onMode: (ImportStore.Selection, Boolean) -> Unit, modifier: Modifier = Modifier,
    onTitlesMode: (ImportStore.Selection, Boolean) -> Unit = { _, _ -> }) {
    if (!imports.authenticated || imports.selections.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("system-create-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.system_create_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.system_create_limits), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.system_title_limits), style = MaterialTheme.typography.bodySmall)
        imports.selections.forEach { selection ->
            val enabled = selection.scope in state.enabled
            val titlesEnabled = selection.scope in state.titlesEnabled
            val canEnable = imports.hasPermission && selection.enabled && selection.available
            Text(selection.summary.title, style = MaterialTheme.typography.titleSmall)
            ModeToggle(stringResource(R.string.system_create_mode), enabled, enabled || canEnable,
                "system-create-toggle") { onMode(selection, it) }
            ModeToggle(stringResource(R.string.system_title_mode), titlesEnabled,
                titlesEnabled || (canEnable && enabled), "system-title-toggle") { onTitlesMode(selection, it) }
            val pending = state.pending[selection.scope] ?: 0
            if (pending > 0) Text(stringResource(R.string.system_create_pending, pending),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("system-create-pending"))
        }
        if (state.failed) Text(stringResource(R.string.system_create_error), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ModeToggle(label: String, checked: Boolean, enabled: Boolean, tag: String,
    onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
