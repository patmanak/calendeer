package com.patmanak.proton.andcalendarbridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF6D42D8), onPrimary = Color.White,
    primaryContainer = Color(0xFFECE5FF), onPrimaryContainer = Color(0xFF31185F),
    secondary = Color(0xFF665580), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADDFA), onSecondaryContainer = Color(0xFF281638),
    background = Color(0xFFF6F4FC), onBackground = Color(0xFF242035),
    surface = Color.White, onSurface = Color(0xFF242035),
    surfaceContainer = Color(0xFFF0EBF8), surfaceContainerLow = Color(0xFFF8F5FD),
    onSurfaceVariant = Color(0xFF696177), outline = Color(0xFF82788F), outlineVariant = Color(0xFFE3DDEF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB69AFF), onPrimary = Color(0xFF22133F),
    primaryContainer = Color(0xFF32244F), onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFD0BCE7), onSecondary = Color(0xFF362744),
    secondaryContainer = Color(0xFF4D3D5C), onSecondaryContainer = Color(0xFFEADDFA),
    background = Color(0xFF15121F), onBackground = Color(0xFFF4F0FC),
    surface = Color(0xFF211C30), onSurface = Color(0xFFF4F0FC),
    surfaceContainer = Color(0xFF2B243B), surfaceContainerLow = Color(0xFF1C1728),
    onSurfaceVariant = Color(0xFFBAB1CA), outline = Color(0xFF958AA6), outlineVariant = Color(0xFF3B334C),
)

@Composable
fun BridgeTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
