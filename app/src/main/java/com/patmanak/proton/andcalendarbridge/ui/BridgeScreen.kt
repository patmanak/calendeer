// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patmanak.proton.andcalendarbridge.R
import com.patmanak.proton.andcalendarbridge.ui.locale.AppLanguage
import com.patmanak.proton.andcalendarbridge.ui.theme.ThemeMode

/** Navigation only. Opening a destination never changes calendar consent or schedules work. */
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
    synchronizationContent: @Composable () -> Unit = {},
    snackbarContent: @Composable () -> Unit = {},
) {
    var openPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable(signedIn) { mutableStateOf(false) }
    val canConfigure = settingsEnabled && !verificationRequired
    LaunchedEffect(canConfigure) { if (!canConfigure) openPicker = null }
    BackHandler(settingsOpen && openPicker == null && canConfigure) { settingsOpen = false }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = snackbarContent,
        bottomBar = {
            if (signedIn) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(selected = !settingsOpen, enabled = canConfigure,
                    onClick = { settingsOpen = false }, modifier = Modifier.testTag("brand_calendars"),
                    icon = { Icon(painterResource(R.drawable.ui_calendar), contentDescription = null) },
                    label = { Text(stringResource(R.string.calendars_heading)) })
                NavigationBarItem(selected = settingsOpen, enabled = canConfigure,
                    onClick = { settingsOpen = true }, modifier = Modifier.testTag("brand_settings"),
                    icon = { Icon(painterResource(R.drawable.ui_settings), contentDescription = null) },
                    label = { Text(stringResource(R.string.brand_settings)) })
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(R.drawable.calendeer_mascot), contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!signedIn) TextButton(onClick = { settingsOpen = !settingsOpen }, enabled = canConfigure,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("brand_settings")) {
                    Text(stringResource(if (settingsOpen) R.string.brand_back else R.string.brand_settings))
                }
            }
            // A full-height scrolling body keeps controls reachable with the IME and large text.
            Column(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                when {
                    verificationRequired || !settingsEnabled -> ContentCard { authenticationContent() }
                    settingsOpen -> {
                        PageHeading(stringResource(R.string.brand_settings), stringResource(R.string.ui_settings_intro))
                        if (signedIn) {
                            SectionTitle(stringResource(R.string.ui_account))
                            ContentCard { authenticationContent() }
                        }
                        if (signedIn) synchronizationContent()
                        SectionTitle(stringResource(R.string.ui_personalization))
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface)) {
                            PreferenceRow(stringResource(R.string.appearance), themeLabel(themeMode), canConfigure,
                                "preference_theme") { openPicker = "theme" }
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            PreferenceRow(stringResource(R.string.language), languageLabel(language), canConfigure,
                                "preference_language") { openPicker = "language" }
                        }
                        SectionTitle(stringResource(R.string.ui_about))
                        ContentCard {
                            val context = LocalContext.current
                            val version = remember(context) {
                                @Suppress("DEPRECATION")
                                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                            }
                            Text(stringResource(R.string.ui_version, version), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.ui_about_bridge), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.brand_independent), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    signedIn -> calendarContent()
                    else -> {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Image(painterResource(R.drawable.calendeer_mascot), contentDescription = null,
                                modifier = Modifier.size(112.dp), contentScale = ContentScale.Fit)
                            Text(stringResource(R.string.brand_welcome), style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                            Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ContentCard { authenticationContent() }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    when (if (canConfigure) openPicker else null) {
        "theme" -> ChoiceDialog(stringResource(R.string.appearance), ThemeMode.entries, themeMode, { themeLabel(it) },
            onDismiss = { openPicker = null }) { openPicker = null; onThemeChange(it) }
        "language" -> ChoiceDialog(stringResource(R.string.language), AppLanguage.entries, language, { languageLabel(it) },
            onDismiss = { openPicker = null }) { openPicker = null; onLanguageChange(it) }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() })
}

@Composable
private fun ContentCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun PreferenceRow(title: String, value: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 80.dp).testTag(tag).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Icon(painterResource(R.drawable.ui_chevron), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
