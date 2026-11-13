// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.data.importing.AndroidCalendarSyncSettings
import com.patmanak.proton.andcalendarbridge.data.importing.CalendarSyncSettingsState
import java.util.Date

@Composable
fun CalendarSyncSettingsPanel(settings: AndroidCalendarSyncSettings, state: CalendarSyncSettingsState) {
    val context = LocalContext.current
    CalendarSyncSettingsContent(state, settings::setIntervalMinutes,
        { openSettings(context, Intent(Settings.ACTION_SYNC_SETTINGS)) },
        { openBatterySettings(context, state.batteryExempt) })
}

@Composable
internal fun CalendarSyncSettingsContent(state: CalendarSyncSettingsState, onInterval: (Int) -> Unit,
    onAndroidSettings: () -> Unit, onBatterySettings: () -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.sync_settings_title), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val lastSuccess = state.lastSuccessMillis?.let {
                val date = Date(it)
                DateFormat.getDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
            } ?: stringResource(R.string.sync_settings_never)
            SyncValue(stringResource(R.string.sync_settings_last_success), lastSuccess)
            Text(stringResource(R.string.sync_settings_interval), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(15, 30, 60, 120, 360).forEach { minutes ->
                    FilterChip(selected = state.intervalMinutes == minutes,
                        onClick = { if (state.intervalMinutes != minutes) onInterval(minutes) },
                        modifier = Modifier.testTag("sync_interval_$minutes"), label = { Text(intervalLabel(minutes)) })
                }
            }
            SyncValue(stringResource(R.string.sync_settings_account_auto), enabledLabel(state.automatic))
            SyncValue(stringResource(R.string.sync_settings_master_auto), enabledLabel(state.masterAutomatic))
            Text(stringResource(R.string.sync_settings_schedule_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AutomaticSyncNotice(state)
            OutlinedButton(onClick = onAndroidSettings,
                modifier = Modifier.fillMaxWidth().testTag("sync_android_settings")) {
                Text(stringResource(R.string.sync_settings_android))
            }
            HorizontalDivider()
            SyncValue(stringResource(R.string.sync_settings_battery_label), enabledLabel(state.batteryExempt))
            TextButton(onClick = onBatterySettings, modifier = Modifier.fillMaxWidth().testTag("sync_battery_adjust")) {
                Text(stringResource(R.string.ui_adjust))
            }
        }
    }
}

@Composable
fun AutomaticSyncNotice(state: CalendarSyncSettingsState) {
    if (state.accountPresent && (!state.automatic || !state.masterAutomatic)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
            Text(stringResource(R.string.sync_settings_paused), modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SyncValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun enabledLabel(enabled: Boolean): String =
    stringResource(if (enabled) R.string.sync_settings_enabled else R.string.sync_settings_disabled)

@Composable
internal fun intervalLabel(minutes: Int): String = stringResource(when (minutes) {
    15 -> R.string.sync_settings_15_minutes
    30 -> R.string.sync_settings_30_minutes
    120 -> R.string.sync_settings_2_hours
    360 -> R.string.sync_settings_6_hours
    else -> R.string.sync_settings_1_hour
})

/** The durable offer marker is committed before displaying this optional explanation. */
@Composable
fun CalendarSyncBatteryOffer(settings: AndroidCalendarSyncSettings, state: CalendarSyncSettingsState) {
    val context = LocalContext.current
    var offerOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.accountPresent, state.batteryExempt, state.batteryOfferSeen) {
        if (state.accountPresent && !state.batteryExempt && !state.batteryOfferSeen && settings.markBatteryOfferSeen()) {
            offerOpen = true
        }
    }
    if (offerOpen && !state.batteryExempt) AlertDialog(
        onDismissRequest = { offerOpen = false },
        title = { Text(stringResource(R.string.sync_settings_battery_title)) },
        text = { Text(stringResource(R.string.sync_settings_battery_explanation)) },
        confirmButton = {
            TextButton(onClick = {
                offerOpen = false
                openBatterySettings(context, false)
            }) { Text(stringResource(R.string.sync_settings_battery_continue)) }
        },
        dismissButton = {
            TextButton(onClick = { offerOpen = false }) { Text(stringResource(R.string.sync_settings_battery_decline)) }
        },
    )
}

private fun openBatterySettings(context: Context, exempt: Boolean) {
    openSettings(context, if (exempt) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

private fun openSettings(context: Context, intent: Intent, fallback: Intent? = null) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        if (fallback != null) openSettings(context, fallback)
    } catch (_: SecurityException) {
        if (fallback != null) openSettings(context, fallback)
    }
}
