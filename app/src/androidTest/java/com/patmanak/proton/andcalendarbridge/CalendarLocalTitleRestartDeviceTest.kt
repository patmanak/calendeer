// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonLocalTitlePreparationProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Private on-device test manifest only; all identifiers stay out of instrumentation output. */
internal object LocalTitleRestartFixture {
    private const val PREFERENCES = "local-title-process-qualification-v1"
    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun databaseName(token: String): String {
        require(token.matches(Regex("[a-f0-9]{12}")))
        return "title-process-qualification-$token.db"
    }
    fun requireAbsent(context: Context, token: String) {
        check(preferences(context).all.isEmpty() && !context.getDatabasePath(databaseName(token)).exists()) {
            "Previous preparation must be verified or explicitly cleaned before another run"
        }
    }
    private fun digest(payload: String) = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun remember(context: Context, token: String, accountHash: String, record: LocalEditPreparation) {
        val prefs = preferences(context)
        check(prefs.all.isEmpty()) { "Never replace the first recovery manifest" }
        check(prefs.edit().putString("token", token).putString("accountHash", accountHash)
            .putString("account", record.intent.scope.account.value).putString("calendar", record.intent.scope.calendar.value)
            .putLong("generation", record.intent.scope.generation).putString("event", record.intent.base.eventId.value)
            .putString("mutation", record.intent.id.value).putString("timestamp", record.timestamp.toString())
            .putString("format", record.format.id).putInt("formatVersion", record.format.version)
            .putString("observationFormat", record.intent.format.id).putInt("observationVersion", record.intent.format.version)
            .putString("payloadDigest", digest(record.protectedPayload)).putInt("pid", android.os.Process.myPid()).commit())
    }
    suspend fun readRecord(context: Context, token: String, accountHash: String): LocalEditPreparation {
        val prefs = preferences(context)
        check(prefs.getString("token", null) == token && prefs.getString("accountHash", null) == accountHash) {
            "Exact previously qualified recovery manifest required"
        }
        check(context.getDatabasePath(databaseName(token)).exists()) { "Never reconstruct a missing journal" }
        val scope = CalendarScope(AccountId(requireNotNull(prefs.getString("account", null))),
            CalendarId(requireNotNull(prefs.getString("calendar", null))), prefs.getLong("generation", -1))
        val event = EventId(requireNotNull(prefs.getString("event", null)))
        return ImportStore(context, databaseName(token)).use { store ->
            val record = requireNotNull(store.localEditPreparation(scope, event)) { "Missing preparation must not be regenerated" }
            check(record.intent.scope == scope && record.intent.base.eventId == event &&
                record.intent.id.value == prefs.getString("mutation", null) &&
                record.timestamp.toString() == prefs.getString("timestamp", null) &&
                record.format == AdapterFormat(requireNotNull(prefs.getString("format", null)), prefs.getInt("formatVersion", -1)) &&
                record.intent.format == AdapterFormat(requireNotNull(prefs.getString("observationFormat", null)), prefs.getInt("observationVersion", -1)) &&
                digest(record.protectedPayload) == prefs.getString("payloadDigest", null)) { "Retained header or ciphertext changed" }
            check(store.localEdit(scope, event) == record.intent && record.intent.state == LocalEditState.PAUSED &&
                store.projectedBaseline(scope, event) == record.intent.base &&
                store.transaction(scope) { remote(event) } == record.intent.base && store.pending(scope).isEmpty()) {
                "Historical owner and imported bases must survive unchanged"
            }
            record
        }
    }
    fun differentProcess(context: Context) = preferences(context).getInt("pid", -1).let {
        it > 0 && it != android.os.Process.myPid()
    }
    fun cleanup(context: Context, token: String, accountHash: String) {
        val prefs = preferences(context)
        if (prefs.all.isEmpty()) {
            check(!context.getDatabasePath(databaseName(token)).exists()) { "Unmanifested journal needs explicit recovery" }
            return
        }
        check(prefs.getString("token", null) == token && prefs.getString("accountHash", null) == accountHash) {
            "Cleanup is restricted to the exact recovery fixture"
        }
        if (context.getDatabasePath(databaseName(token)).exists()) check(context.deleteDatabase(databaseName(token)))
        check(prefs.edit().clear().commit())
    }
}

/** Host stops the app and disconnects networking before VERIFY. No Activity or Proton port is opened. */
@RunWith(AndroidJUnit4::class)
class CalendarLocalTitleRestartDeviceTest {
    @Test fun historicalTitleSurvivesOfflineProcessRestartWithoutPreparation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarLocalTitleRestart") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val accountHash = args.getString("createAccountHash").orEmpty()
        assertTrue("Explicit qualified fixture identity required", token.matches(Regex("[a-f0-9]{12}")) &&
            accountHash.matches(Regex("[a-f0-9]{64}")))
        val phase = args.getString("titleRestartPhase")
        assertTrue("Explicit offline or cleanup phase required", phase in setOf("VERIFY", "CLEANUP"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (phase == "CLEANUP") {
            LocalTitleRestartFixture.cleanup(context, token, accountHash)
            return@runBlocking
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertTrue("Networking must be absent throughout recovery", connectivity.activeNetwork == null)
        assertTrue("Preparation process must have terminated", LocalTitleRestartFixture.differentProcess(context))
        val wrongHash = (if (accountHash.first() == 'a') "b" else "a") + accountHash.drop(1)
        var refused = false
        try { LocalTitleRestartFixture.readRecord(context, token, wrongHash) }
        catch (_: IllegalStateException) { refused = true }
        assertTrue("A different account witness must not read this fixture", refused)
        val record = LocalTitleRestartFixture.readRecord(context, token, accountHash)
        assertTrue("Fresh offline Keystore opening must accept the exact historical record",
            ProtonLocalTitlePreparationProbe.openHistorical(record))
        assertTrue("Offline opening must refuse a substituted edit timestamp",
            !ProtonLocalTitlePreparationProbe.openHistorical(record.copy(timestamp = record.timestamp.plusSeconds(60))))
        val damaged = record.protectedPayload.toCharArray().apply {
            val index = size / 2
            this[index] = if (this[index] == 'A') 'B' else 'A'
        }
        assertTrue("Offline opening must refuse damaged ciphertext",
            !ProtonLocalTitlePreparationProbe.openHistorical(record.copy(protectedPayload = String(damaged))))
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val schedules = AtomicInteger()
        val remote = object : ReadOnlyCalendarRemote {
            override val format = record.intent.format
            override suspend fun currentAccount() = record.intent.scope.account
            override suspend fun fetch(request: PageRequest): RemotePage = error("Offline recovery must not download")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("Offline recovery must not upload")
        }
        val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { false }
            override fun hasPermissions() = true
            override fun accountFor(accountId: AccountId) = Account("synthetic-title-restart", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("No Provider writes")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No Provider writes")
        }
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        val preparer = object : CalendarLocalEditPreparationPort {
            override val localTitleFormat = record.format
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant): LocalEditPreparation =
                error("Historical records must never be regenerated")
        }
        try {
            ImportStore(context, LocalTitleRestartFixture.databaseName(token)).use { store ->
                val owner = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
                try {
                    owner.authenticationChanged(true)
                    withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                    assertTrue("Replay after restart must remain historical", owner.prepareLocalTitle(record.intent.scope,
                        record.intent.base.eventId, record.intent.id, record.timestamp.plusSeconds(60), preparer) == LocalTitlePreparationResult.HISTORICAL)
                } finally { owner.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin() }
            }
        } finally { jobs.coroutineContext[Job]!!.cancelAndJoin() }
        assertTrue("Offline replay must preserve the complete retained record",
            LocalTitleRestartFixture.readRecord(context, token, accountHash) == record)
        assertTrue("Network must remain absent", connectivity.activeNetwork == null)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("offline_title_process_restart", true)
            putBoolean("historical_title_replay_without_preparer", true)
            putBoolean("outgoing_update_enabled", false)
        })
    }
}
