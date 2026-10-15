// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStatus
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStore
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated preference/database fixtures; no Provider, sessions, event data or network. */
class TextModesStoreDeviceTest {
    private val scope = CalendarScope(AccountId("synthetic-text-mode"), CalendarId("calendar"), 0)
    private fun fixture(block: suspend (Context, String, SystemCreateWorkStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val db = "text-mode-$id.db"; val prefs = "text-mode-$id"
        val wrapped = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(if (name == "system-create-modes") prefs else name, mode)
        }
        val store = SystemCreateWorkStore(wrapped, db)
        try { block(wrapped, db, store) }
        finally { store.close(); base.deleteDatabase(db); base.deleteSharedPreferences(prefs) }
    }
    @Test fun textConsentDefaultsOffIncludingWhenTitleAndCreateAreEnabled() = fixture { _, _, store ->
        assertFalse(store.textsEnabled(scope)); assertFalse(SystemCreateUiState().textSupportAvailable)
        assertTrue(SystemCreateUiState().textsEnabled.isEmpty())
        store.setMode(scope, true); store.setTitlesMode(scope, true)
        assertFalse(store.textsEnabled(scope)); assertTrue(store.titlesEnabled(scope))
    }
    @Test fun textModeRequiresCreateAndCannotPreauthorizeLaterEnable() = fixture { _, _, store ->
        store.setTextsMode(scope, true); assertFalse(store.textsEnabled(scope))
        store.setMode(scope, true); assertFalse(store.textsEnabled(scope))
        store.setTextsMode(scope, true); assertTrue(store.textsEnabled(scope)); assertFalse(store.titlesEnabled(scope))
    }
    @Test fun textAndTitleModesAreIndependentBothWays() = fixture { _, _, store ->
        store.setMode(scope, true); store.setTextsMode(scope, true)
        assertTrue(store.textsEnabled(scope)); assertFalse(store.titlesEnabled(scope))
        store.setTitlesMode(scope, true); store.setTextsMode(scope, false)
        assertTrue(store.titlesEnabled(scope)); assertFalse(store.textsEnabled(scope))
        store.setTextsMode(scope, true); store.setTitlesMode(scope, false)
        assertTrue(store.textsEnabled(scope)); assertFalse(store.titlesEnabled(scope))
    }
    @Test fun createOffClearsBothConsentsWithoutRestoringThemOnReenable() = fixture { _, _, store ->
        store.setMode(scope, true); store.setTitlesMode(scope, true); store.setTextsMode(scope, true)
        assertTrue(store.textsEnabled(scope))
        store.setMode(scope, false); assertFalse(store.textsEnabled(scope)); assertFalse(store.titlesEnabled(scope))
        store.setMode(scope, true); assertFalse(store.textsEnabled(scope)); assertFalse(store.titlesEnabled(scope))
    }
    @Test fun exactFullScopePersistsAcrossReopenButNeverLeaksToOtherGenerationOrAccount() = fixture { context, db, store ->
        store.setMode(scope, true); store.setTextsMode(scope, true)
        for (other in listOf(scope.copy(account = AccountId("other")), scope.copy(calendar = CalendarId("other")), scope.copy(generation = 1))) {
            store.setMode(other, true); assertFalse(store.textsEnabled(other))
        }
        store.close(); SystemCreateWorkStore(context, db).use { reopened ->
            assertTrue(reopened.textsEnabled(scope)); reopened.setTextsMode(scope, false)
            assertTrue(reopened.modeEnabled(scope)); assertFalse(reopened.textsEnabled(scope))
        }
    }
    @Test fun disablingTextOrCreationKeepsPendingAndUnknownIdentitiesExactly() = fixture { _, _, store ->
        val first = store.reserve(scope, 1); val second = store.reserve(scope, 2)
        assertTrue(store.status(second, SystemCreateWorkStatus.UNKNOWN))
        val before = store.page(scope)
        store.setMode(scope, true); store.setTextsMode(scope, true); assertTrue(store.textsEnabled(scope))
        store.setTextsMode(scope, false); assertEquals(before, store.page(scope))
        store.setMode(scope, false); assertEquals(before, store.page(scope))
        assertEquals(first, store.reserve(scope, 1)); assertEquals(second.attempt, store.read(scope, 2)!!.attempt)
    }
    @Test fun framedPreferenceKeysAreOpaqueAndDoNotCollideOnSeparators() = fixture { context, _, store ->
        val one = CalendarScope(AccountId("fixture:a"), CalendarId("b"), 0)
        val two = CalendarScope(AccountId("fixture"), CalendarId("a:b"), 0)
        store.setMode(one, true); store.setMode(two, true); store.setTextsMode(one, true)
        assertTrue(store.textsEnabled(one)); assertFalse(store.textsEnabled(two))
        val keys = context.getSharedPreferences("system-create-modes", Context.MODE_PRIVATE).all.keys
        assertTrue(keys.any { it.matches(Regex("texts-v1-[0-9a-f]{64}")) })
        assertTrue(keys.all { it.matches(Regex("(?:texts-)?v1-[0-9a-f]{64}")) })
        assertFalse(keys.any { it.contains(one.account.value) || it.contains(two.calendar.value) })
    }
}
