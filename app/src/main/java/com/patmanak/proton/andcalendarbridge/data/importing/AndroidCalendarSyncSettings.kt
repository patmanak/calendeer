// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import android.accounts.Account
import android.content.Context
import android.content.ContentResolver
import android.os.PowerManager
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.patmanak.proton.andcalendarbridge.BridgeApplication
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ManualSyncRequestResult { REQUESTED, ACCOUNT_NOT_READY, UNAVAILABLE }

data class CalendarSyncSettingsState(
    val accountPresent: Boolean = false,
    val automatic: Boolean = false,
    val masterAutomatic: Boolean = false,
    val intervalMinutes: Int = 60,
    val lastSuccessMillis: Long? = null,
    val batteryExempt: Boolean = false,
    val batteryOfferSeen: Boolean = false,
)

/** Android owns triggers; these preferences are presentation/scheduling metadata, never intent storage. */
class AndroidCalendarSyncSettings(context: Context, private val scope: CoroutineScope,
    private val periodicWorkName: String = SystemSyncWork.PERIODIC_WORK) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences(SystemSyncWork.PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(CalendarSyncSettingsState())
    val state: StateFlow<CalendarSyncSettingsState> = mutable.asStateFlow()
    private var configured: Pair<Account, Int>? = null
    private var observer: Any? = null
    @Volatile private var bound: Account? = prefs.getString("bound_account", null)?.let {
        Account(it, AndroidCalendarProjection.ACCOUNT_TYPE)
    }
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    fun start(app: BridgeApplication) {
        android.accounts.AccountManager.get(context).getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE)
            .forEach(::removeAndroidPeriodics)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        observer = ContentResolver.addStatusChangeListener(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS) {
            scope.launch {
                refresh()
                bound?.let(::removeAndroidPeriodics)
                if (bound != null && !automaticAllowed()) cancelAutomaticWork()
                else if (automaticAllowed()) {
                    val pending = app.providerWake.registration.ensureArmed()
                    if (pending.enabled && pending.pendingToken != null)
                        app.providerWake.enqueueLocal(pending.pendingToken, pending.fingerprint)
                }
            }
        }
        scope.launch {
            app.imports.state.collect { imports ->
                try {
                    if (app.authentication.state.value.destination ==
                        com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination.RESTORING) return@collect
                    val account = if (imports.authenticated && imports.hasPermission)
                        ProtonAuthentication.events(app).currentAccount()?.let { AndroidCalendarProjection(app).ensureAccount(it) }
                        else null
                    bind(account)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { bind(null) }
            }
        }
        refresh()
        if (bound != null && !automaticAllowed()) cancelAutomaticWork()
    }

    @Synchronized internal fun bind(account: Account?) {
        if (bound != account) {
            check(prefs.edit().putString("bound_account", account?.name).commit())
            if (account == null) {
                cancelAutomaticWork()
                WorkManager.getInstance(context).cancelUniqueWork(periodicWorkName)
            }
        }
        bound = account
        if (account != null && SystemSyncWork.installed(context, account)) {
            val minutes = interval()
            if (configured != (account to minutes)) {
                configurePeriodic(account, minutes)
                configured = account to minutes
            }
            removeAndroidPeriodics(account)
        } else configured = null
        refresh()
        if (account != null && !automaticAllowed()) cancelAutomaticWork()
    }

    private fun configurePeriodic(account: Account, minutes: Int) {
        val initialized = "initialized:${account.name}"
        if (!prefs.getBoolean(initialized, false)) {
            // Enable this newly introduced channel once; never override a later user choice.
            ContentResolver.setIsSyncable(account, SystemSyncWork.AUTHORITY, 1)
            ContentResolver.setSyncAutomatically(account, SystemSyncWork.AUTHORITY, true)
            check(prefs.edit().putBoolean(initialized, true).commit())
        }
        removeAndroidPeriodics(account)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(periodicWorkName,
            ExistingPeriodicWorkPolicy.UPDATE, SystemSyncWork.periodicRequest(account, minutes))
    }

    internal fun removeAndroidPeriodics(account: Account) {
        for (periodic in ContentResolver.getPeriodicSyncs(account, SystemSyncWork.AUTHORITY))
            ContentResolver.removePeriodicSync(account, SystemSyncWork.AUTHORITY, periodic.extras)
    }

    fun setIntervalMinutes(minutes: Int) {
        require(minutes in INTERVALS)
        check(prefs.edit().putInt("interval_minutes", minutes).commit())
        configured = null
        bind(bound)
    }

    suspend fun requestManual(): ManualSyncRequestResult = try { withContext(Dispatchers.IO) {
        val account = bound
        if (account == null || !SystemSyncWork.installed(context, account)) ManualSyncRequestResult.ACCOUNT_NOT_READY
        else {
            WorkManager.getInstance(context).enqueue(SystemSyncWork.request(account, manual = true, upload = false))
                .awaitPersisted()
            ManualSyncRequestResult.REQUESTED
        }
    } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ManualSyncRequestResult.UNAVAILABLE }

    fun markBatteryOfferSeen(): Boolean {
        if (prefs.getBoolean("battery_offer_seen", false)) return false
        return prefs.edit().putBoolean("battery_offer_seen", true).commit().also { refresh() }
    }

    fun refresh() {
        val account = bound?.takeIf { SystemSyncWork.installed(context, it) }
        mutable.value = CalendarSyncSettingsState(account != null,
            account?.let { ContentResolver.getSyncAutomatically(it, SystemSyncWork.AUTHORITY) } ?: false,
            ContentResolver.getMasterSyncAutomatically(), interval(),
            account?.let { prefs.getLong("success:${it.name}", 0).takeIf { value -> value > 0 } },
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            prefs.getBoolean("battery_offer_seen", false))
    }

    fun automaticAllowed(): Boolean = bound?.takeIf { SystemSyncWork.installed(context, it) }
        ?.let(SystemSyncWork::automaticAllowed) == true

    private fun cancelAutomaticWork() {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(SystemSyncWork.AUTOMATIC_TAG)
    }

    private fun interval() = prefs.getInt("interval_minutes", 60).takeIf { it in INTERVALS } ?: 60

    companion object { val INTERVALS = listOf(15, 30, 60, 120, 360) }
}
