// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult

/** Explicit new synthetic admission only. Existing UNKNOWN work is never dispatched here. */
object ProtonCreateTransportProbe {
    suspend fun submit(context: Context, record: CreateAttemptRecord, draft: CalendarCreateDraft,
        journal: CreateAttemptJournal, identityHash: String, admit: () -> Boolean,
        retainCategory: (String) -> Unit): CreateSubmissionObservation = withContext(Dispatchers.IO) {
        require(record.state == CreateAttemptState.PREPARED &&
            record.format in setOf(SERIES_CREATE_SUBMISSION_FORMAT, PERSONAL_CREATE_SUBMISSION_FORMAT))
        val frozen = draft.frozen()
        require(frozen.scope == record.scope && frozen.correlation == record.id &&
            frozen.content.title.startsWith("Calendeer QA Series NET transport"))
        require(identityHash.matches(Regex("[a-f0-9]{64}")))
        val facade = ProtonAuthentication.createSubmissions(context)
        val lock = field(facade, "lock") as Mutex
        lock.withLock {
            val graph = field(facade, "graph") as ProtonAuthenticationGraph
            val adapter = requireNotNull(graph.submissions) as ProtonCreateSubmissionAdapter
            val sessions = field(adapter, "sessions") as CatalogSessionSource
            val session = requireNotNull(sessions.current()).also { require(it.userId.id == record.scope.account.value) }
            var active = true
            suspend fun check() {
                currentCoroutineContext().ensureActive()
                if (!admit() || field(facade, "authenticated") != true || sessions.current() != session) active = false
                require(active)
                if (!graph.matchesIdentityHash(record.scope.account, identityHash)) active = false
                if (!admit() || field(facade, "authenticated") != true || sessions.current() != session) active = false
                require(active)
            }
            check()
            val remote = field(adapter, "remote") as CreateSubmissionRemote
            val observed = object : CreateSubmissionRemote by remote {
                override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String {
                    check()
                    return try {
                        remote.send(session, calendar, request).also { retainCategory("COMPLETE_RESPONSE") }
                    } catch (cancelled: CancellationException) { retainCategory("CANCELLED"); throw cancelled }
                      catch (failure: Exception) { retainCategory(category(failure)); throw failure }
                }
            }
            val readback = field(adapter, "readback") as ProtonCreatedEventReadback
            val diagnostic = ProtonCreateSubmissionAdapter(field(adapter, "preparations") as CalendarCreatePreparationPort,
                field(adapter, "protector") as EventPayloadProtector, sessions, observed,
                field(adapter, "events") as EventImportRemote, field(readback, "verifier") as CreateEventVerifier)
            try { diagnostic.submitCaptured(record, frozen, journal).also { check() } }
            finally { active = false }
        }
    }

    private fun category(failure: Exception): String = when (failure) {
        is ProtonSingleDispatchDenied -> "LOCAL_PERMIT_DENIED"
        is ApiException -> when (val error = failure.error) {
            is ApiResult.Error.Http -> {
                // Only fixed vocabulary leaves memory. Never retain the message/details themselves.
                val inMemory = error.proton.toString().take(4096)
                val hints = listOf("color", "subscription", "icalendar", "signature", "rrule", "permission",
                    "member", "timezone", "uid", "format", "not allowed").filter { inMemory.contains(it, ignoreCase = true) }
                    .map { it.replace(' ', '_').uppercase(java.util.Locale.ROOT) }.joinToString("_").ifEmpty { "NONE" }
                "HTTP_${error.httpCode.takeIf { it in 100..599 } ?: 0}_API_${error.proton?.code?.takeIf { it in 0..999_999 } ?: 0}_HINT_$hints"
            }
            is ApiResult.Error.Parse -> "API_PARSE"
            is ApiResult.Error.Connection -> "API_CONNECTION"
        }
        is java.io.IOException -> "IO"
        else -> "OTHER"
    }
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(value)
    }
}
