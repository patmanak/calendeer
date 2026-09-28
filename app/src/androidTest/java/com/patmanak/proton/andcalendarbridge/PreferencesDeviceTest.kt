package com.patmanak.proton.andcalendarbridge

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.ui.locale.AndroidLanguagePreferenceStore
import com.patmanak.proton.andcalendarbridge.ui.locale.AppLanguage
import com.patmanak.proton.andcalendarbridge.ui.theme.AndroidThemePreferenceStore
import com.patmanak.proton.andcalendarbridge.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only changes this application's appearance preferences, restoring them after each case. */
@RunWith(AndroidJUnit4::class)
class PreferencesDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun themeModesSurviveStoreRecreation() {
        val store = AndroidThemePreferenceStore(context)
        val original = store.mode.value
        try {
            for (mode in ThemeMode.entries) {
                store.setMode(mode)
                assertEquals(mode, AndroidThemePreferenceStore(context).mode.value)
            }
        } finally {
            store.setMode(original)
        }
    }

    @Test fun languageChoiceAndSystemResetUseThePlatformBoundary() {
        val store = AndroidLanguagePreferenceStore(context)
        val original = store.language.value
        val originalLocales = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
        } else null
        try {
            for (language in AppLanguage.entries) {
                store.setLanguage(language)
                store.refresh()
                assertEquals(language, AndroidLanguagePreferenceStore(context).language.value)
                if (Build.VERSION.SDK_INT >= 33) {
                    val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
                    if (language == AppLanguage.SYSTEM) assertTrue(locales.isEmpty)
                    else assertEquals(language.tag, locales[0].language)
                } else if (language.tag != null) {
                    val localized = AndroidLanguagePreferenceStore.localizedContext(context)
                    assertEquals(language.tag, localized.resources.configuration.locales[0].language)
                }
            }
        } finally {
            store.setLanguage(original)
            if (Build.VERSION.SDK_INT >= 33 && originalLocales != null) {
                context.getSystemService(LocaleManager::class.java).applicationLocales = originalLocales
                store.refresh()
            }
        }
    }

    @Test fun renamedApplicationLaunchesAndRecreates() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("com.patmanak.proton.andcalendarbridge", activity.packageName)
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity -> assertTrue(activity.window.decorView.isAttachedToWindow) }
        }
    }
}
