// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationDestination
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Opt-in observation of the EXISTING production six-hour periodic work, without a scheduling
 * command, worker invocation, network operation (except exact-account identity admission),
 * clock/radio/power/permission/mode change, login or reset. Normal application/session startup
 * can still trigger production work; this is not an exclusive worker-causality witness.
 *
 * Arguments: calendarNaturalPeriodic=true, naturalPeriodicPhase=SEED|VERIFY,
 * naturalPeriodicToken=<12 lowercase hex>, naturalPeriodicAccountHash=<authorized SHA-256>.
 * SEED requires an already completed period and freezes an immutable private per-token witness.
 * VERIFY with no completed-period advance reports NOT_YET and is JUnit-skipped, never verified.
 * Only endpoint state is observed: transient intervening changes cannot be excluded.
 * No claim about sync contents, success of import, battery behavior, OEM, reboot or deadlines.
 *
 * SQL is intentionally pinned to the bundled WorkManager 2.8.1 schema 16 / Room identity.
 * It opens the existing no-backup database READONLY and uses SQLite's normal WAL read snapshot;
 * no database copy, WorkManager internal API, transaction write, schema migration or cleanup.
 */
@RunWith(AndroidJUnit4::class)
class NaturalPeriodicObservationDeviceTest {
    @Test fun observesAnExistingNaturalPeriodicCompletion() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarNaturalPeriodic") == "true")
        val phase = args.getString("naturalPeriodicPhase").orEmpty()
        val token = args.getString("naturalPeriodicToken").orEmpty()
        val accountHash = args.getString("naturalPeriodicAccountHash").orEmpty()
        assertTrue("Explicit periodic observation enrollment required", phase in setOf("SEED", "VERIFY") &&
            token.matches(Regex("[a-f0-9]{12}")) && accountHash.matches(Regex("[a-f0-9]{64}")))

        // Suppress unexpected platform/SQL exception details as well as raw identities in assertions.
        val result = try {
            val app = ApplicationProvider.getApplicationContext<BridgeApplication>()
            val auth = withTimeout(60_000) { app.authentication.state.first {
                !it.isSubmitting && it.destination != AuthenticationDestination.RESTORING
            } }
            check(auth.destination == AuthenticationDestination.AUTHENTICATED)
            withTimeout(60_000) {
                while (!app.imports.state.value.authenticated || app.imports.state.value.selections.isEmpty()) delay(250)
            }
            val accounts = app.imports.state.value.selections.map { it.scope.account }.distinct()
            check(accounts.size == 1 && ProtonCreateSubmissionProbe.matchesAccount(app, accounts.single(), accountHash))
            awaitSettled(app)
            val signature = policySignature(app)
            val apk = packageSignature(app)
            val boot = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
            check(boot >= 0)
            val elapsed = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val row = observe(app)
            val prefs = app.getSharedPreferences("natural-periodic-observation-v1-$token", Context.MODE_PRIVATE)
            val idHash = hash("$token:${row.id}")
            if (phase == "SEED") {
                check(prefs.all.isEmpty()) // Never replace, rebase or clear a previous enrollment.
                check(row.periodCount >= 1 && row.nextTime > wall)
                check(signature == policySignature(app) && apk == packageSignature(app))
                check(prefs.edit().putInt("format", 1).putString("account_hash", accountHash)
                    .putString("work_hash", idHash).putInt("generation", row.generation)
                    .putLong("period_count", row.periodCount).putLong("next_time", row.nextTime)
                    .putLong("last_enqueue", row.lastEnqueue).putInt("boot", boot)
                    .putLong("elapsed", elapsed).putLong("wall", wall)
                    .putString("package_hash", apk).putString("policy_hash", signature).commit())
                Outcome.SEEDED
            } else {
                check(prefs.getInt("format", -1) == 1 && prefs.getString("account_hash", null) == accountHash)
                check(prefs.getString("work_hash", null) == idHash &&
                    prefs.getInt("generation", -1) == row.generation)
                check(prefs.getString("package_hash", null) == apk && prefs.getString("policy_hash", null) == signature)
                check(prefs.getInt("boot", -1) == boot && elapsed > prefs.getLong("elapsed", Long.MAX_VALUE))
                // Reject observable clock discontinuity; this does not prove the clock was never changed.
                val elapsedDelta = elapsed - prefs.getLong("elapsed", Long.MAX_VALUE)
                val wallDelta = wall - prefs.getLong("wall", Long.MAX_VALUE)
                check(wallDelta > 0 && kotlin.math.abs(wallDelta - elapsedDelta) <= 60_000)
                val before = prefs.getLong("period_count", -1)
                check(before >= 1 && row.periodCount >= before)
                check(signature == policySignature(app) && apk == packageSignature(app))
                if (row.periodCount == before) Outcome.NOT_YET else {
                    check(wall >= prefs.getLong("next_time", Long.MAX_VALUE) &&
                        row.lastEnqueue >= prefs.getLong("next_time", Long.MAX_VALUE) &&
                        row.lastEnqueue > prefs.getLong("last_enqueue", Long.MAX_VALUE))
                    // Ensure the same settled qualified row survives the verification reads.
                    val after = observe(app)
                    check(after.id == row.id && after.generation == row.generation &&
                        after.periodCount == row.periodCount && after.lastEnqueue == row.lastEnqueue)
                    check(signature == policySignature(app) && apk == packageSignature(app))
                    Outcome.PASS
                }
            }
        } catch (_: Exception) {
            report(Outcome.FAIL)
            throw AssertionError("Natural periodic observation failed closed")
        }
        report(result)
        assumeTrue("Natural periodic completion NOT_YET; retain original seed and verify later", result != Outcome.NOT_YET)
    }

    private enum class Outcome { SEEDED, NOT_YET, PASS, FAIL }

    private fun report(outcome: Outcome) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("natural_periodic_result", outcome.name)
            putBoolean("natural_periodic_verified", outcome == Outcome.PASS)
            putBoolean("sync_contents_proven", false)
            putBoolean("battery_behavior_proven", false)
            putBoolean("exclusive_worker_causality_proven", false)
            putBoolean("oem_qualified", false)
        })
    }

    // No generated toString exposing the in-memory UUID; only its token-bound hash is persisted.
    private class Observation(val id: String, val generation: Int, val periodCount: Long, val lastEnqueue: Long) {
        val nextTime: Long get() = Math.addExact(lastEnqueue, PERIOD)
    }

    /** Allow ordinary restoration work to settle without requesting, cancelling or forcing work.
     * Three consecutive public-state samples reduce startup races; observe() still rejects any
     * RUNNING SQL row. A bounded timeout fails closed, never yields verified evidence.
     */
    private suspend fun awaitSettled(app: BridgeApplication) = withTimeout(180_000) {
        check(File(app.noBackupFilesDir, "androidx.work.workdb").isFile)
        val manager = WorkManager.getInstance(app)
        var settledSamples = 0
        while (settledSamples < 3) {
            val local = manager.getWorkInfosForUniqueWork("calendar-import-local").get(15, TimeUnit.SECONDS)
            val network = manager.getWorkInfosForUniqueWork("calendar-import-network").get(15, TimeUnit.SECONDS)
            val periodic = manager.getWorkInfosForUniqueWork(WORK_NAME).get(15, TimeUnit.SECONDS)
            val state = app.imports.state.value
            val settled = state.authenticated && state.hasPermission && state.selections.isNotEmpty() &&
                !state.busy && !app.systemCreates.state.value.busy &&
                local.all { it.state.isFinished } && network.all { it.state.isFinished } &&
                periodic.size == 1 && periodic.single().state == WorkInfo.State.ENQUEUED
            settledSamples = if (settled) settledSamples + 1 else 0
            if (settledSamples < 3) delay(500)
        }
    }

    private fun observe(app: BridgeApplication): Observation {
        val file = File(app.noBackupFilesDir, "androidx.work.workdb")
        check(file.isFile)
        val manager = WorkManager.getInstance(app)
        val publicBefore = manager.getWorkInfosForUniqueWork(WORK_NAME).get(15, TimeUnit.SECONDS)
        check(publicBefore.size == 1 && publicBefore.single().state == WorkInfo.State.ENQUEUED)
        val row = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            // One SELECT statement spans schema identity, unique name, and all row metadata in a
            // single WAL snapshot. No BEGIN/PRAGMA mutator or checkpoint is issued.
            db.rawQuery("""
                SELECT w.id, w.generation, w.period_count, w.last_enqueue_time,
                    w.state, w.worker_class_name, w.interval_duration, w.flex_duration,
                    w.initial_delay, w.run_attempt_count, w.required_network_type,
                    w.requires_charging, w.requires_device_idle, w.requires_battery_not_low,
                    w.requires_storage_not_low,
                    (SELECT user_version FROM pragma_user_version),
                    (SELECT identity_hash FROM room_master_table WHERE id=42),
                    (SELECT COUNT(*) FROM WorkName WHERE name=?),
                    (SELECT COUNT(*) FROM WorkSpec WHERE state=1)
                FROM WorkName n JOIN WorkSpec w ON w.id=n.work_spec_id WHERE n.name=?
            """.trimIndent(), arrayOf(WORK_NAME, WORK_NAME)).use { cursor ->
                check(cursor.count == 1 && cursor.moveToFirst())
                check(cursor.getInt(4) == 0 && cursor.getString(5) == WORKER &&
                    cursor.getLong(6) == PERIOD && cursor.getLong(7) == PERIOD &&
                    cursor.getLong(8) == 0L && cursor.getInt(9) == 0 && cursor.getInt(10) == 1)
                check((11..14).all { cursor.getInt(it) == 0 })
                check(cursor.getInt(15) == 16 && cursor.getString(16) == ROOM_IDENTITY &&
                    cursor.getInt(17) == 1 && cursor.getInt(18) == 0)
                check(cursor.getInt(1) >= 0 && cursor.getLong(2) >= 0 && cursor.getLong(3) > 0)
                Observation(cursor.getString(0), cursor.getInt(1), cursor.getLong(2), cursor.getLong(3))
            }
        }
        val publicAfter = manager.getWorkInfosForUniqueWork(WORK_NAME).get(15, TimeUnit.SECONDS)
        check(publicAfter.size == 1)
        check(listOf(publicBefore.single(), publicAfter.single()).all {
            it.id.toString() == row.id && it.generation == row.generation &&
                it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0
        })
        return row
    }

    /** Includes every retained raw mode flag, scope generation and selection; never prints any. */
    private fun policySignature(app: BridgeApplication): String {
        val state = app.imports.state.value
        check(state.authenticated && state.hasPermission && !state.busy &&
            !app.systemCreates.state.value.busy && state.selections.isNotEmpty())
        check(state.selections.any { it.enabled && it.available })
        val scopes = state.selections.map {
            listOf(it.scope.account.value, it.scope.calendar.value, it.scope.generation.toString(),
                it.enabled.toString(), it.available.toString()).joinToString("") { value -> "${value.length}:$value" }
        }.sorted()
        val modes = app.getSharedPreferences("system-create-modes", Context.MODE_PRIVATE).all.toSortedMap().map { (key, value) ->
            check(value is Boolean)
            "${key.length}:$key:$value"
        }
        return hash((scopes + modes).joinToString("\n"))
    }

    /** Hashes actual installed source APK bytes (base plus splits), not BuildConfig/version labels. */
    private fun packageSignature(app: BridgeApplication): String {
        val info = app.applicationInfo
        val sources = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty().toList()
        check(sources.isNotEmpty() && sources.distinct().size == sources.size)
        val hashes = sources.map { path ->
            val digest = MessageDigest.getInstance("SHA-256")
            File(path).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    digest.update(buffer, 0, length)
                }
            }
            hex(digest.digest())
        }.sorted()
        return hash(app.packageName + "\n" + hashes.joinToString("\n"))
    }

    private fun hash(value: String) = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val WORK_NAME = "calendar-import-periodic"
        const val WORKER = "com.patmanak.proton.andcalendarbridge.data.importing.CalendarImportWorker"
        const val PERIOD = 6 * 60 * 60 * 1000L
        const val ROOM_IDENTITY = "5181942b9ebc31ce68dacb56c16fd79f"
    }
}
