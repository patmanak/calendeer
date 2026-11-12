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
import android.annotation.SuppressLint
import androidx.work.WorkManager
import androidx.work.NetworkType
import androidx.work.impl.model.WorkSpec
import java.util.concurrent.TimeUnit

/** Disposable local scheduling metadata only; never changes the master switch or Proton. */
@RunWith(AndroidJUnit4::class)
@SuppressLint("RestrictedApi") // Inspect only the exact fixture's persisted WorkSpec (WM 2.8 has no public interval getter).
class CalendarSyncSettingsDeviceTest {
    private fun workName(account: Account) = "sync-fixture-periodic-${account.name}"
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
        try { test(AndroidCalendarSyncSettings(isolated, jobs, workName(account)), account, isolated) }
        finally {
            jobs.cancel()
            WorkManager.getInstance(context).cancelUniqueWork(workName(account)).result.get(10, TimeUnit.SECONDS)
            ContentResolver.setSyncAutomatically(account, SystemSyncWork.AUTHORITY, false)
            ContentResolver.cancelSync(account, SystemSyncWork.AUTHORITY)
            ContentResolver.getPeriodicSyncs(account, SystemSyncWork.AUTHORITY).forEach {
                ContentResolver.removePeriodicSync(account, SystemSyncWork.AUTHORITY, it.extras)
            }
            assertTrue(accounts.removeAccountExplicitly(account))
            context.deleteSharedPreferences(name + SystemSyncWork.PREFS)
        }
    }

    private suspend fun period(account: Account, seconds: Long): UUID = withTimeout(10_000) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = WorkManager.getInstance(context)
        while (true) {
            val entries = manager.getWorkInfosForUniqueWork(workName(account)).get(10, TimeUnit.SECONDS)
                .filter { !it.state.isFinished }
            if (entries.size == 1) {
                // Room is runtime-only transitively; do not add a shipped dependency for this assertion.
                val database = manager.javaClass.getMethod("getWorkDatabase").invoke(manager)
                val dao = database.javaClass.getMethod("workSpecDao").invoke(database)
                val spec = dao.javaClass.getMethod("getWorkSpec", String::class.java)
                    .invoke(dao, entries.single().id.toString()) as WorkSpec?
                if (spec != null && spec.intervalDuration == seconds * 1000) {
                    assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
                    assertTrue(spec.input.getBoolean(SystemSyncWork.PULL, false))
                    assertEquals(account.name, spec.input.getString(SystemSyncWork.ACCOUNT))
                    assertTrue(ContentResolver.getPeriodicSyncs(account, SystemSyncWork.AUTHORITY).isEmpty())
                    return@withTimeout entries.single().id
                }
            }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    @Test fun oneHourDefaultAndAllIntervalsReplaceRatherThanAccumulate() = runBlocking { fixture { settings, account, _ ->
        settings.bind(account)
        val id = period(account, 3600)
        assertEquals(60, settings.state.value.intervalMinutes)
        for (minutes in listOf(15, 30, 120, 360, 60)) {
            settings.setIntervalMinutes(minutes)
            assertEquals("Updating the interval must not replace a running executor", id, period(account, minutes * 60L))
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
        period(account, 1800) // Push OFF must not disable periodic pull.
    } }

    @Test fun nativePeriodicSchedulesAreRemovedIncludingAfterEnablingPush() = runBlocking { fixture { settings, account, _ ->
        settings.bind(account)
        ContentResolver.setSyncAutomatically(account, SystemSyncWork.AUTHORITY, false)
        ContentResolver.setSyncAutomatically(account, SystemSyncWork.AUTHORITY, true)
        val extras = android.os.Bundle().apply { putBoolean("fixture_period", true) }
        ContentResolver.addPeriodicSync(account, SystemSyncWork.AUTHORITY, extras, 3600)
        withTimeout(10_000) {
            while (ContentResolver.getPeriodicSyncs(account, SystemSyncWork.AUTHORITY).isEmpty()) delay(50)
        }
        settings.removeAndroidPeriodics(account)
        withTimeout(10_000) {
            while (ContentResolver.getPeriodicSyncs(account, SystemSyncWork.AUTHORITY).isNotEmpty()) delay(50)
        }
        period(account, 3600)
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
