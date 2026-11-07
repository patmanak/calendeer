// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.CalendarContract
import android.provider.CalendarContract.*
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.importing.PersonalPreparationAdmission
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic, token-isolated release update/reboot retention. No authentication or service access. */
class ReleaseStateRetentionDeviceTest {
    @Test fun retainAcrossReleaseUpdateAndReboot() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("providerFixture") == "true" && args.getString("releaseStateFixture") == "true")
        val token = args.getString("releaseStateToken").orEmpty()
        require(token.matches(Regex("[a-f0-9]{12}"))) { "Invalid release fixture token" }
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), token)
        when (args.getString("phase")) {
            "seed" -> fixture.seed()
            "verify" -> fixture.verify()
            "cleanup" -> { fixture.verify(); fixture.cleanup() }
            else -> error("Invalid release fixture phase")
        }
    }
    private class Fixture(val context: Context, token: String) {
        private val prefix = "release-state-$token"
        private val prefs = context.getSharedPreferences(prefix, Context.MODE_PRIVATE)
        private val importName = "$prefix-import.db"
        private val journalName = "$prefix-journal.db"
        private val alias = "$prefix-aes"
        private val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private val scope = CalendarScope(AccountId(prefix), CalendarId(prefix), 0)
        private val projection = AndroidCalendarProjection(context)
        private val account get() = projection.accountFor(scope.account)
        private val format = AdapterFormat("release-state-fixture", 1)
        private val base = RemoteChange.Upsert(EventId(prefix), Revision("synthetic-base"), EventContent("Synthetic release fixture",
            EventTime.Timed(Instant.parse("2030-05-01T09:00:00Z"), Instant.parse("2030-05-01T10:00:00Z"), ZoneId.of("UTC")),
            "Synthetic details", "Synthetic place", 0x123456, details = EventDetails(reminders = listOf(EventReminder(10)))))
        private val intent = LocalEditIntent(MutationId("$prefix-local"), scope, format, base, base.content.copy(
            title = "Synthetic release edit", description = "Synthetic revised details",
            time = (base.content.time as EventTime.Timed).copy(end = Instant.parse("2030-05-01T11:00:00Z"))))
        private val preparation = PersonalEditPreparation(intent, Instant.parse("2030-04-01T12:00:00Z"), format, "$prefix-protected-preparation")
        private val prepared = PersonalUpdateAttemptRecord(MutationId("$prefix-request"), preparation, format, "$prefix-protected-request")
        private val unknown = prepared.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        private fun requireFixture(value: Boolean) { check(value) { "Release fixture state mismatch" } }
        private fun sync(uri: Uri) = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build()
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun cursorHash(cursor: Cursor): String = cursor.use { rows ->
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(bytes: ByteArray) { digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
            val columns = rows.columnNames.sorted()
            columns.forEach { add(it.toByteArray(Charsets.UTF_8)) }
            while (rows.moveToNext()) for (column in columns) {
                val index = rows.getColumnIndexOrThrow(column); add(rows.getType(index).toString().toByteArray())
                add(if (rows.isNull(index)) byteArrayOf() else if (rows.getType(index) == Cursor.FIELD_TYPE_BLOB) rows.getBlob(index) else rows.getString(index).toByteArray(Charsets.UTF_8))
            }
            hash(digest.digest())
        }
        private fun nativeHash(uri: Uri, selection: String, args: Array<String>, order: String = "_id") =
            cursorHash(checkNotNull(context.contentResolver.query(sync(uri), null, selection, args, order)))
        private fun sqlHash(db: SQLiteDatabase): String {
            val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata' ORDER BY name", null).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
            return hash(names.joinToString("\n") { "$it:${cursorHash(db.rawQuery("SELECT * FROM \"$it\" ORDER BY rowid", null))}" }.toByteArray())
        }
        private fun apkHash(): String = java.io.File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            hash(digest.digest())
        }
        private fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        private fun status(values: Bundle) = InstrumentationRegistry.getInstrumentation().sendStatus(0, values)
        suspend fun seed() {
            if (prefs.contains("started")) { requireFixture(prefs.getBoolean("complete", false)); verify(); return }
            requireFixture(prefs.all.isEmpty() && !context.getDatabasePath(importName).exists() && !context.getDatabasePath(journalName).exists() &&
                !keys.containsAlias(alias) && AccountManager.get(context).accounts.none { it == account })
            requireFixture(prefs.edit().putBoolean("started", true).commit()) // A partial seed may never be replaced.
            val calendar = projection.ensureCalendarState(scope, CalendarSummary(scope.calendar, "Synthetic release retention"), true).id
            projection.apply(ProjectionIntent(scope, base))
            val row = checkNotNull(context.contentResolver.query(sync(Events.CONTENT_URI), arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)).use {
                requireFixture(it.count == 1 && it.moveToFirst()); it.getLong(0)
            }
            requireFixture(context.contentResolver.update(sync(Events.CONTENT_URI), ContentValues().apply {
                put(Events.TITLE, intent.content.title); put(Events.DESCRIPTION, intent.content.description)
                put(Events.DTEND, (intent.content.time as EventTime.Timed).end.toEpochMilli()); put(Events.DIRTY, 1)
            }, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())) == 1)
            requireFixture(projection.captureOwnedPersonalEdit(scope, base)?.content == intent.content)
            ImportStore(context, importName).use { store ->
                store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic release retention"))); store.setEnabled(scope, true)
                store.transaction(scope) { checkpoint = PageCheckpoint(scope, format); putRemote(base); putProjection(ProjectionIntent(scope, base)) }
                requireFixture(store.acknowledge(ProjectionIntent(scope, base)) == 1)
                requireFixture(store.captureLocalEdit(intent) == intent)
                requireFixture(store.retainCurrentPersonalPreparation(preparation) == PersonalPreparationAdmission.STORED)
            }
            TitleUpdateAttemptStore(context, journalName).use { store ->
                requireFixture(store.personalJournal.remember(prepared) { it == scope }); requireFixture(store.personalJournal.claim(prepared) { it == scope })
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            val key = generator.generateKey(); requireFixture(key.encoded == null)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val challenge = "$prefix-keystore-challenge".toByteArray()
            val encrypted = cipher.doFinal(challenge)
            val edit = prefs.edit().putLong("calendar", calendar).putLong("event", row).putInt("pid", Process.myPid()).putInt("boot", bootCount())
                .putString("apk", apkHash()).putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("challenge", Base64.encodeToString(encrypted, Base64.NO_WRAP)).putString("plaintext", hash(challenge))
                .putString("calendarHash", nativeHash(Calendars.CONTENT_URI, "${Calendars._ID}=?", arrayOf(calendar.toString())))
                .putString("eventHash", nativeHash(Events.CONTENT_URI, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())))
                .putString("reminderHash", nativeHash(Reminders.CONTENT_URI, "${Reminders.EVENT_ID}=?", arrayOf(row.toString())))
                .putString("attendeeHash", nativeHash(Attendees.CONTENT_URI, "${Attendees.EVENT_ID}=?", arrayOf(row.toString())))
                .putString("extendedHash", nativeHash(ExtendedProperties.CONTENT_URI, "${ExtendedProperties.EVENT_ID}=?", arrayOf(row.toString())))
            ImportStore(context, importName).use { edit.putString("importHash", sqlHash(it.readableDatabase)) }
            TitleUpdateAttemptStore(context, journalName).use { edit.putString("journalHash", sqlHash(it.readableDatabase)) }
            requireFixture(edit.putBoolean("complete", true).commit())
            status(Bundle().apply { putBoolean("release_state_seeded", true) })
        }
        suspend fun verify() {
            requireFixture(prefs.getBoolean("complete", false) && context.getDatabasePath(importName).exists() && context.getDatabasePath(journalName).exists())
            val calendar = prefs.getLong("calendar", -1); val row = prefs.getLong("event", -1); requireFixture(calendar > 0 && row > 0)
            requireFixture(AccountManager.get(context).accounts.count { it == account } == 1)
            checkNotNull(context.contentResolver.query(sync(Calendars.CONTENT_URI), arrayOf(Calendars._ID),
                "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(account.name, account.type), null)).use {
                requireFixture(it.count == 1 && it.moveToFirst() && it.getLong(0) == calendar)
            }
            checkNotNull(context.contentResolver.query(sync(Events.CONTENT_URI), arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)).use { requireFixture(it.count == 1 && it.moveToFirst() && it.getLong(0) == row) }
            requireFixture(nativeHash(Calendars.CONTENT_URI, "${Calendars._ID}=?", arrayOf(calendar.toString())) == prefs.getString("calendarHash", null))
            requireFixture(nativeHash(Events.CONTENT_URI, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())) == prefs.getString("eventHash", null))
            requireFixture(nativeHash(Reminders.CONTENT_URI, "${Reminders.EVENT_ID}=?", arrayOf(row.toString())) == prefs.getString("reminderHash", null))
            requireFixture(nativeHash(Attendees.CONTENT_URI, "${Attendees.EVENT_ID}=?", arrayOf(row.toString())) == prefs.getString("attendeeHash", null))
            requireFixture(nativeHash(ExtendedProperties.CONTENT_URI, "${ExtendedProperties.EVENT_ID}=?", arrayOf(row.toString())) == prefs.getString("extendedHash", null))
            for ((name, version) in listOf(importName to 23, journalName to 10)) SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { requireFixture(it.version == version) }
            ImportStore(context, importName).use { store ->
                requireFixture(store.selections(scope.account).singleOrNull()?.let { it.scope == scope && it.enabled && it.available } == true)
                requireFixture(store.transaction(scope) { checkpoint } == PageCheckpoint(scope, format))
                requireFixture(store.localEdit(scope, base.eventId) == intent && store.projectedBaseline(scope, base.eventId) == base)
                val retained = store.personalEditPreparation(scope, base.eventId)
                requireFixture(retained != null && retained.intent == intent && retained.timestamp == preparation.timestamp && retained.format == preparation.format && retained.protectedPayload == preparation.protectedPayload)
                requireFixture(store.personalUpdateAcknowledgement(scope, base.eventId) == null && sqlHash(store.readableDatabase) == prefs.getString("importHash", null))
            }
            TitleUpdateAttemptStore(context, journalName).use { store ->
                requireFixture(store.personalJournal.read(scope, prepared.id) { it == scope } == unknown)
                requireFixture(store.personalJournal.readVerified(scope, prepared.id) { it == scope } == null && store.personalJournal.readCompletion(scope, prepared.id) { it == scope } == null)
                requireFixture(sqlHash(store.readableDatabase) == prefs.getString("journalHash", null))
            }
            val key = keys.getKey(alias, null) as? SecretKey; requireFixture(key != null && key.encoded == null)
            val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(128, Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP))) }
            requireFixture(hash(decrypt.doFinal(Base64.decode(prefs.getString("challenge", null), Base64.NO_WRAP))) == prefs.getString("plaintext", null))
            status(Bundle().apply {
                putBoolean("release_state_verified", true); putBoolean("release_state_keystore_verified", true)
                putBoolean("release_state_apk_changed", apkHash() != prefs.getString("apk", null))
                putBoolean("release_state_reboot_observed", prefs.getInt("boot", -1) >= 0 && bootCount() > prefs.getInt("boot", -1))
                putBoolean("release_state_process_changed", Process.myPid() != prefs.getInt("pid", -1))
            })
        }
        suspend fun cleanup() {
            projection.removeOwnedCalendar(scope)
            requireFixture(AccountManager.get(context).removeAccountExplicitly(account))
            requireFixture(context.deleteDatabase(importName) && context.deleteDatabase(journalName))
            keys.deleteEntry(alias); requireFixture(prefs.edit().clear().commit())
            status(Bundle().apply { putBoolean("release_state_cleaned", true) })
        }
    }
}
