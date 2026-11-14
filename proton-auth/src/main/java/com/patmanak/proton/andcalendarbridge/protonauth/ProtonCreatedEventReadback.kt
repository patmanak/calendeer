// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Readback interpretation only. Neither profile grants dispatch or upgrades a stored preparation. */
internal enum class CreatedEventReadbackPolicy(val version: Int) {
    SHARED_ONLY_V1(1), KNOWN_EMPTY_V2(2), PERSONAL_EDIT_V2(3), PERSONAL_CREATE_V3(4), SERIES_CREATE_V4(5);

    /** Personal data is observed through the authenticated read, not signed by the shared parts. */
    fun accepts(row: JsonObject): Boolean = when (this) {
        SHARED_ONLY_V1 -> true
        PERSONAL_EDIT_V2 -> try { ProtonPersonalEventWire.qualify(row); true } catch (_: Exception) { false }
        PERSONAL_CREATE_V3, SERIES_CREATE_V4 -> try {
            val notifications = row.array("Notifications")
            ProtonPersonalMetadata.valid(notifications, row["Color"]) && notifications.size <= 5 &&
                notifications.all { (it as JsonObject).number("Type") == 1L } &&
                notifications.map { ProtonEventDetails.durationSeconds((it as JsonObject).string("Trigger")) }.distinct().size == notifications.size
        } catch (_: Exception) { false }
        KNOWN_EMPTY_V2 -> (row["Notifications"] as? JsonArray)?.isEmpty() == true
    }

    fun acceptsExpected(content: EventContent): Boolean {
        val time = content.time
        if (this == SERIES_CREATE_V4) return try {
            val recurrence = requireNotNull(content.recurrence)
            require(recurrence.isMaster && recurrence.additionalDates.isEmpty() && recurrence.excludedDates.isEmpty() && !recurrence.cancelled)
            require(CreateSeriesRule.parse(requireNotNull(recurrence.rule)).supports(time))
            ProtonCreateSubmissionCodec.freezePersonal(content.copy(recurrence = null)); true
        } catch (_: Exception) { false }
        if (this == PERSONAL_CREATE_V3) return try { ProtonCreateSubmissionCodec.freezePersonal(content); true } catch (_: Exception) { false }
        if (this == PERSONAL_EDIT_V2) return content.details != null && content.details!!.organizer == null && content.details!!.attendees.isEmpty() && content.recurrence == null && (content.colorRgb == null || content.colorRgb!! in 0..0xffffff)
        return this == SHARED_ONLY_V1 || (content.details == EventDetails() && content.recurrence == null && content.colorRgb == null &&
            (time !is EventTime.Timed || (time.start.nano == 0 && time.end.nano == 0)))
    }
}

internal fun interface CreateEventVerifier {
    suspend fun verify(session: CatalogSession, calendar: String, bootstrap: JsonObject, row: JsonObject,
        uid: String, policy: CreatedEventReadbackPolicy): RemoteChange.Upsert?
}

internal interface CreateReadbackRemote {
    suspend fun byUid(session: CatalogSession, calendar: String, uid: String, page: Int): List<JsonObject>
    suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject
}

/** Shared read-only path. No journal, preparation opening, admission, send or retry capability. */
internal class ProtonCreatedEventReadback(private val sessions: CatalogSessionSource,
    private val remote: CreateReadbackRemote, private val events: EventImportRemote,
    private val verifier: CreateEventVerifier) {
    suspend fun inspect(session: CatalogSession, scope: CalendarScope, uid: String, expected: EventContent,
        policy: CreatedEventReadbackPolicy, candidateId: String? = null): RemoteChange.Upsert? {
        return try {
            if (uid.isBlank() || !policy.acceptsExpected(expected)) return null
            checkSession(session, scope)
            val ids = mutableSetOf<String>()
            var finished = false
            for (page in 0 until 100) {
                val rows = remote.byUid(session, scope.calendar.value, uid, page)
                checkSession(session, scope)
                if (rows.size > 100) return null
                for (row in rows) {
                    if (row.string("CalendarID") != scope.calendar.value || row.string("UID") != uid || row.string("ID").isBlank() ||
                        !ids.add(row.string("ID"))) return null
                }
                if (ids.size > 1) return null
                if (rows.size < 100) { finished = true; break }
            }
            if (!finished || ids.size != 1 || (candidateId != null && candidateId != ids.single())) return null
            val row = remote.event(session, scope.calendar.value, ids.single())
            checkSession(session, scope)
            if (row.string("ID") != ids.single() || row.string("CalendarID") != scope.calendar.value || row.string("UID") != uid ||
                !policy.accepts(row)) return null
            val bootstrap = events.bootstrap(session, scope.calendar.value)
            checkSession(session, scope)
            val actual = verifier.verify(session, scope.calendar.value, bootstrap, row, uid, policy) ?: return null
            checkSession(session, scope)
            if (actual.eventId.value != ids.single() || normalized(actual.content, policy) != normalized(expected, policy)) null else actual
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
    }

    private suspend fun checkSession(expected: CatalogSession, scope: CalendarScope) {
        if (expected.userId.id != scope.account.value || sessions.current() != expected)
            createUnavailable(CreatePreparationFailure.AUTHENTICATION)
    }

    private fun normalized(content: EventContent, policy: CreatedEventReadbackPolicy) = content.copy(title = content.title.replace("\r\n", "\n"),
        description = content.description.replace("\r\n", "\n"), location = content.location.replace("\r\n", "\n"),
        details = if (policy == CreatedEventReadbackPolicy.SHARED_ONLY_V1) null else content.details,
        recurrence = if (policy == CreatedEventReadbackPolicy.SERIES_CREATE_V4) content.recurrence?.let {
            it.copy(rule = CreateSeriesRule.parse(requireNotNull(it.rule)).rule)
        } else content.recurrence,
        time = when (val time = content.time) {
            is EventTime.Timed -> if (time.zone.normalized() == java.time.ZoneOffset.UTC) time.copy(zone = java.time.ZoneOffset.UTC) else time
            is EventTime.AllDay -> time
        })
}
