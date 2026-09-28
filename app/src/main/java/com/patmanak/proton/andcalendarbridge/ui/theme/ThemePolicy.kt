// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Contako. Copyright (C) 2026 Patmanak. See app/PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.ui.theme

enum class ThemeMode { SYSTEM, LIGHT, DARK }

fun resolveDarkTheme(themeMode: ThemeMode, systemDarkTheme: Boolean): Boolean =
    themeMode == ThemeMode.DARK || themeMode == ThemeMode.SYSTEM && systemDarkTheme
