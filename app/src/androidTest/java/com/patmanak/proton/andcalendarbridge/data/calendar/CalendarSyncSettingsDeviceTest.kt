// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Disposable local scheduling metadata only; never changes the master switch or Proton. */
@RunWith(AndroidJUnit4::class)
class CalendarSyncSettingsDeviceTest {
    private suspend fun fixture(test: suspend (AndroidCalendarSyncSettings, Account, Context) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val account = Account("Calendeer scheduling fixture-$suffix", AndroidCalendarProjection.ACCOUNT_TYPE)
        val name = "sync-fixture-$suffix-"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(name + key, mode)
        }
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val accounts = AccountManager.get(context)
        assertTrue(accounts.addAccountExplicitly(account, null, null))
        try { test(AndroidCalendarSyncSettings(isolated, jobs), account, isolated) }
        finally {
            jobs.cancel()
            ContentResolver.setSyncAutomatically(account, SystemSyncWork.AUTHORITY, false)
            ContentResolver.cancelSync(account, SystemSyncWork.AUTHORITY)
            ContentResolver.getPeriodicSyncs(account, SystemSyncWork.AUTHORITY).forEach {
                ContentResolver.removePeriodicSync(account, SystemSyncWork.AUTHORITY, it.extras)
            }
            assertTrue(accounts.removeAccountExplicitly(account))
            context.deleteSharedPreferences(name + SystemSyncWork.PREFS)
        }
    }

    private suspend fun period(account: Account, seconds: Long) = withTimeout(10_000) {
        while (true) {
            val entries = ContentResolver.getPeriodicSyncs(account, SystemSyncWork.AUTHORITY)
            if (entries.size == 1 && entries.single().period == seconds && entries.single().extras.isEmpty) break
            delay(50)
        }
    }

    @Test fun oneHourDefaultAndAllIntervalsReplaceRatherThanAccumulate() = runBlocking { fixture { settings, account, _ ->
        settings.bind(account)
        period(account, 3600)
        assertEquals(60, settings.state.value.intervalMinutes)
        for (minutes in listOf(15, 30, 120, 360, 60)) {
            settings.setIntervalMinutes(minutes)
            period(account, minutes * 60L)
        }
        try { settings.setIntervalMinutes(16); fail("Unsupported frequency accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(60, settings.state.value.intervalMinutes)
    } }

    @Test fun laterReconfigurationPreservesDisabledAccountSwitch() = runBlocking { fixture { settings, account, _ ->
        settings.bind(account)
        ContentResolver.setSyncAutomatically(account, SystemSyncWork.AUTHORITY, false)
        withTimeout(10_000) {
            while (ContentResolver.getSyncAutomatically(account, SystemSyncWork.AUTHORITY)) delay(50)
        }
        settings.setIntervalMinutes(30)
        settings.bind(account)
        settings.refresh()
        assertFalse(settings.state.value.automatic)
        assertFalse(settings.automaticAllowed())
    } }

    @Test fun offerIsPersistedOnceAndSuccessTimeNeverCrossesAnUnboundAccount() = runBlocking { fixture { settings, account, isolated ->
        settings.bind(account)
        assertNull(settings.state.value.lastSuccessMillis)
        assertTrue(settings.markBatteryOfferSeen())
        assertFalse(settings.markBatteryOfferSeen())
        val again = AndroidCalendarSyncSettings(isolated, CoroutineScope(currentCoroutineContext()))
        assertFalse(again.markBatteryOfferSeen())
        SystemSyncWork.recordSuccess(isolated, account)
        settings.refresh()
        assertNotNull(settings.state.value.lastSuccessMillis)
        settings.bind(null)
        assertNull(settings.state.value.lastSuccessMillis)
        assertFalse(settings.state.value.accountPresent)
    } }

    @Test fun manualRequestReportsMissingAccountAndAcceptsAnExplicitRequestWithAutoOff() = runBlocking { fixture { settings, account, isolated ->
        assertEquals(ManualSyncRequestResult.ACCOUNT_NOT_READY, settings.requestManual())
        settings.bind(account)
        ContentResolver.setSyncAutomatically(account, SystemSyncWork.AUTHORITY, false)
        withTimeout(10_000) {
            while (ContentResolver.getSyncAutomatically(account, SystemSyncWork.AUTHORITY)) delay(50)
        }
        assertEquals(ManualSyncRequestResult.REQUESTED, settings.requestManual())
        val unavailable = object : ContextWrapper(isolated) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? {
                if (name == Context.ACCOUNT_SERVICE) throw SecurityException("Synthetic service failure")
                return super.getSystemService(name)
            }
        }
        assertEquals(ManualSyncRequestResult.UNAVAILABLE,
            AndroidCalendarSyncSettings(unavailable, CoroutineScope(currentCoroutineContext())).requestManual())
        settings.bind(null)
        assertEquals(ManualSyncRequestResult.ACCOUNT_NOT_READY, settings.requestManual())
    } }
}
