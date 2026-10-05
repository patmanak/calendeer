package com.patmanak.proton.andcalendarbridge.ui.calendars

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.data.importing.ImportUiState
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState

/** Bridge configuration only; editing stays in the user's calendar application. */
@Composable
fun SystemCreatePanel(imports: ImportUiState, state: SystemCreateUiState,
    onMode: (ImportStore.Selection, Boolean) -> Unit, modifier: Modifier = Modifier) {
    if (!imports.authenticated || imports.selections.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("system-create-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.system_create_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.system_create_limits), style = MaterialTheme.typography.bodySmall)
        imports.selections.forEach { selection ->
            val enabled = selection.scope in state.enabled
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selection.summary.title, Modifier.weight(1f))
                Switch(enabled, { onMode(selection, it) }, enabled = enabled ||
                    (imports.hasPermission && selection.enabled && selection.available),
                    modifier = Modifier.testTag("system-create-toggle"))
            }
            val pending = state.pending[selection.scope] ?: 0
            if (pending > 0) Text(stringResource(R.string.system_create_pending, pending),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("system-create-pending"))
        }
        if (state.failed) Text(stringResource(R.string.system_create_error), color = MaterialTheme.colorScheme.error)
    }
}
