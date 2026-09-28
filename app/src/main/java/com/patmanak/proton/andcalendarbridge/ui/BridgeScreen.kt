package com.patmanak.proton.andcalendarbridge.ui

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.ui.locale.AppLanguage
import com.patmanak.proton.andcalendarbridge.ui.theme.ThemeMode

@Composable
fun BridgeScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    authenticationContent: @Composable () -> Unit,
    calendarContent: @Composable () -> Unit,
    signedIn: Boolean = false,
    settingsEnabled: Boolean = true,
    verificationRequired: Boolean = false,
) {
    var openPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable(signedIn) { mutableStateOf(false) }
    LaunchedEffect(verificationRequired) {
        if (verificationRequired) openPicker = null
    }
    BackHandler(settingsOpen && openPicker == null) { settingsOpen = false }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    }
                    TextButton(onClick = { settingsOpen = !settingsOpen }, enabled = settingsEnabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("brand_settings")) {
                        Text(stringResource(if (settingsOpen) R.string.brand_back else R.string.brand_settings))
                    }
                }
                if (!signedIn && !settingsOpen) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource(R.drawable.calendeer_mascot), contentDescription = null,
                            contentScale = ContentScale.Fit, modifier = Modifier.size(152.dp))
                        Text(stringResource(R.string.brand_welcome), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (verificationRequired || (!signedIn && !settingsOpen) || (signedIn && settingsOpen)) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        authenticationContent()
                    }
                }
                }
                if (signedIn && !settingsOpen && !verificationRequired) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Image(painterResource(R.drawable.calendeer_mascot), contentDescription = null,
                            contentScale = ContentScale.Fit, modifier = Modifier.size(64.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SectionTitle(stringResource(R.string.calendars_heading))
                            Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    )) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            calendarContent()
                        }
                    }
                }
                }
                if (settingsOpen && !verificationRequired) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(stringResource(R.string.preferences_heading))
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    )) {
                        PreferenceRow(stringResource(R.string.appearance), themeLabel(themeMode)) { openPicker = "theme" }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        PreferenceRow(stringResource(R.string.language), languageLabel(language)) { openPicker = "language" }
                    }
                    Text(stringResource(R.string.preferences_scope), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                }
                Text(stringResource(R.string.brand_independent), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.development_notice), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    when (if (verificationRequired) null else openPicker) {
        "theme" -> ChoiceDialog(
            stringResource(R.string.appearance), ThemeMode.entries, themeMode, { themeLabel(it) },
            onDismiss = { openPicker = null },
        ) { openPicker = null; onThemeChange(it) }
        "language" -> ChoiceDialog(
            stringResource(R.string.language), AppLanguage.entries, language, { languageLabel(it) },
            onDismiss = { openPicker = null },
        ) { openPicker = null; onLanguageChange(it) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() })
}

@Composable
private fun PreferenceRow(title: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = option == selected, role = Role.RadioButton,
                            onClick = { onSelect(option) },
                        ).heightIn(min = 52.dp).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(when (mode) {
    ThemeMode.SYSTEM -> R.string.follow_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
})

@Composable
private fun languageLabel(language: AppLanguage): String =
    if (language == AppLanguage.SYSTEM) stringResource(R.string.follow_system) else language.nativeLabel
