// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Contako. Copyright (C) 2026 Patmanak. See app/PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.ui.locale

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppLanguage(val tag: String?, val nativeLabel: String) {
    SYSTEM(null, ""),
    ENGLISH("en", "English"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    ITALIAN("it", "Italiano"),
    DUTCH("nl", "Nederlands"),
    POLISH("pl", "Polski"),
    PORTUGUESE("pt", "Português"),
}

interface LanguagePreferenceBoundary {
    val language: StateFlow<AppLanguage>
    fun setLanguage(language: AppLanguage)
}

/** Uses Android's app-language setting on 33+, a private preference on 31–32. */
class AndroidLanguagePreferenceStore(context: Context) : LanguagePreferenceBoundary,
    SharedPreferences.OnSharedPreferenceChangeListener {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableLanguage: MutableStateFlow<AppLanguage>
    override val language: StateFlow<AppLanguage> get() = mutableLanguage

    init {
        // Preserve a choice made before an OS upgrade without overwriting an existing
        // Android app-language choice. Afterwards LocaleManager is authoritative.
        if (Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean(KEY_NATIVE_MIGRATED, false)) {
            val manager = this.context.getSystemService(LocaleManager::class.java)
            val stored = storedLanguage(preferences)
            if (manager.applicationLocales.isEmpty && stored.tag != null) {
                manager.applicationLocales = LocaleList.forLanguageTags(stored.tag)
            }
            preferences.edit().putBoolean(KEY_NATIVE_MIGRATED, true).apply()
        }
        mutableLanguage = MutableStateFlow(readLanguage())
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun setLanguage(language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        }
        preferences.edit().putString(KEY_LANGUAGE, language.name).apply()
        mutableLanguage.value = language
    }

    /** Called on activity entry, including return from Android's app-language settings. */
    fun refresh() { mutableLanguage.value = readLanguage() }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == KEY_LANGUAGE) refresh()
    }

    private fun readLanguage(): AppLanguage {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return AppLanguage.SYSTEM
            return AppLanguage.entries.firstOrNull { it.tag == locales[0]?.language } ?: AppLanguage.SYSTEM
        }
        return storedLanguage(preferences)
    }

    companion object {
        private const val PREFERENCES = "appearance"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_NATIVE_MIGRATED = "native_locale_migrated"

        private fun storedLanguage(preferences: SharedPreferences): AppLanguage =
            preferences.getString(KEY_LANGUAGE, null)
                ?.let { stored -> AppLanguage.entries.firstOrNull { it.name == stored } }
                ?: AppLanguage.SYSTEM

        fun localizedContext(base: Context): Context {
            if (Build.VERSION.SDK_INT >= 33) return base
            val stored = storedLanguage(base.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))
            val tag = stored.tag ?: return base
            // Copy the configuration: mutating base.resources.configuration affects other users.
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocales(LocaleList.forLanguageTags(tag))
            return base.createConfigurationContext(configuration)
        }
    }
}
