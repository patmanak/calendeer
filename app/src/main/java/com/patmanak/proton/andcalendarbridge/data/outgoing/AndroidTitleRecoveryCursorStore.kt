// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.TitleUpdateWorkCursor
import com.patmanak.proton.andcalendarbridge.sync.TitleUpdateWorkQuery
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Backup-excluded local index position. It cannot qualify an attempt or release its hold. */
class AndroidTitleRecoveryCursorStore(
    context: Context,
    name: String = "calendar-title-recovery-cursors",
) : TitleRecoveryCursorStore {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override suspend fun exhausted(scope: CalendarScope): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val key = key(scope)
        try {
            val done = preferences.getBoolean("$key-done", false)
            check(!done || !preferences.contains(key))
            currentCoroutineContext().ensureActive()
            done
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { throw IllegalStateException("Invalid title recovery traversal") }
    }

    override suspend fun markExhausted(scope: CalendarScope) = withContext(Dispatchers.IO) {
        TitleUpdateWorkQuery(scope)
        currentCoroutineContext().ensureActive()
        val key = key(scope)
        check(preferences.edit().remove(key).putBoolean("$key-done", true).commit()) {
            "Title recovery traversal was not persisted"
        }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun read(scope: CalendarScope): TitleUpdateWorkCursor? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val key = key(scope)
        try {
            val encoded = preferences.getString(key, null) ?: return@withContext null
            check(encoded.length <= 32_768)
            val value = JSONObject(encoded)
            check(value.length() == 3 && value.get("protocol") == 1 && value.getString("scope") == key)
            val cursor = TitleUpdateWorkCursor(scope, EventId(value.getString("event")))
            TitleUpdateWorkQuery(scope, cursor = cursor)
            currentCoroutineContext().ensureActive()
            cursor
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { throw IllegalStateException("Invalid title recovery cursor") }
    }

    override suspend fun write(scope: CalendarScope, cursor: TitleUpdateWorkCursor?) = withContext(Dispatchers.IO) {
        TitleUpdateWorkQuery(scope, cursor = cursor)
        currentCoroutineContext().ensureActive()
        val key = key(scope)
        val editor = preferences.edit().remove("$key-done")
        if (cursor == null) editor.remove(key) else editor.putString(key, JSONObject().apply {
            put("protocol", cursor.protocol); put("scope", key); put("event", cursor.afterEvent.value)
        }.toString())
        check(editor.commit()) { "Title recovery cursor was not persisted" }
        currentCoroutineContext().ensureActive()
    }

    private fun key(scope: CalendarScope): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("calendar-title-recovery-v1", scope.account.value, scope.calendar.value,
            scope.generation.toString()).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return "v1-" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
