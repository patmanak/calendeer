// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.MutationId
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*

/** Validate lookup identity before entering SQL; each journal supplies its own context budget. */
internal fun requireAttemptJournalReference(scope: CalendarScope, id: MutationId, maxContextBytes: Int) {
    for (value in listOf(scope.account.value, scope.calendar.value, id.value)) {
        require(value.isNotBlank() && value.length <= maxContextBytes)
        require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <=
            maxContextBytes)
    }
}

/** The helper owns the connection/mutex; callbacks cannot suspend or re-enter it. */
internal interface AttemptJournalDatabase {
    suspend fun <T> withDatabase(beforeOpen: () -> Unit, action: (SQLiteDatabase) -> T): T
}

/** Shared transaction mechanics only. Each typed journal retains its codecs, ownership checks,
 * immutable claim/evidence rules and diagnostic. Revocation is sticky within this invocation. */
internal suspend fun <T> attemptJournalTransaction(
    database: AttemptJournalDatabase,
    scope: CalendarScope,
    admit: (CalendarScope) -> Boolean,
    denied: T,
    invalidMessage: String,
    action: (SQLiteDatabase, () -> Unit) -> T,
): T = withContext(Dispatchers.IO) {
    val operation = currentCoroutineContext()
    var closed = false
    fun active() {
        operation.ensureActive()
        if (closed) throw JournalAccessDenied()
        val allowed = admit(scope)
        if (!allowed) closed = true
        operation.ensureActive()
        if (!allowed || closed) throw JournalAccessDenied()
    }
    try {
        database.withDatabase(::active) { db ->
            db.beginTransaction()
            val result = try {
                operation.ensureActive()
                val value = action(db) { operation.ensureActive() }
                active()
                db.setTransactionSuccessful()
                value
            } finally { db.endTransaction() }
            // Cancellation after commit cannot roll back an already durable UNKNOWN claim.
            operation.ensureActive()
            result
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: JournalAccessDenied) { operation.ensureActive(); denied }
      catch (_: Exception) { operation.ensureActive(); throw IllegalStateException(invalidMessage) }
      finally { closed = true }
}

private class JournalAccessDenied : RuntimeException()
