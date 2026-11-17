// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant

/** Immutable local-to-dispatch link. A private equality fingerprint, not encryption or admission. */
data class CapturedCreateDispatchIdentity(
    val attempt: CreateAttemptIdentity,
    val localId: LocalEventId,
    val captureId: MutationId,
    val captureFormat: AdapterFormat,
    val timestamp: Instant,
    val contentFingerprint: String,
    val fingerprintVersion: Int = 1,
) {
    init {
        require(timestamp.nano == 0 && fingerprintVersion in 1..3 && contentFingerprint.matches(Regex("[a-f0-9]{64}")))
        require(attempt.id != captureId && attempt.id.value != localId.value)
    }
    override fun toString() = "CapturedCreateDispatchIdentity(REDACTED)"
    fun matchesOccurrence(intent: LocalCreateIntent): Boolean = try {
        val captured = intent.reservation
        fingerprintVersion == 3 && captured.scope == attempt.scope && captured.localId == localId &&
            captured.id == captureId && captured.format == captureFormat && captured.timestamp == timestamp &&
            contentFingerprint == occurrenceFingerprint(intent)
    } catch (_: IllegalArgumentException) { false }

    /** Equality qualification only; it does not authenticate a remote observation. */
    fun matchesContent(content: EventContent): Boolean = try {
        require(fingerprintVersion != 3) { "Occurrence matching requires its complete captured context" }
        val series = if (fingerprintVersion == 2) capturedCreateSeries(content) else null
        val original = LocalCreateIntent(LocalCreateReservation(captureId, localId, attempt.scope, captureFormat, timestamp),
            if (series == null) content else content.copy(recurrence = null), series)
        contentFingerprint == Companion.contentFingerprint(original)
    } catch (_: IllegalArgumentException) { false }

    companion object {
        fun ofOccurrence(record: CreateAttemptRecord, intent: LocalCreateIntent): CapturedCreateDispatchIdentity {
            val captured = intent.reservation
            require(record.scope == captured.scope)
            return CapturedCreateDispatchIdentity(CreateAttemptIdentity.of(record), captured.localId, captured.id,
                captured.format, captured.timestamp, occurrenceFingerprint(intent), 3)
        }
        fun of(submission: LocalCreateSubmission): CapturedCreateDispatchIdentity {
            val reserved = submission.reservation
            val captured = reserved.intent.reservation
            val record = CreateAttemptRecord(reserved.attempt, captured.scope, reserved.format, submission.protectedPayload)
            return of(record, reserved.intent)
        }

        /** A retained link only. Does not prepare, reserve or admit a submission. */
        fun of(record: CreateAttemptRecord, intent: LocalCreateIntent): CapturedCreateDispatchIdentity {
            val captured = intent.reservation
            require(record.scope == captured.scope)
            return CapturedCreateDispatchIdentity(CreateAttemptIdentity.of(record), captured.localId, captured.id,
                captured.format, captured.timestamp, contentFingerprint(intent), if (intent.series == null) 1 else 2)
        }

        private fun occurrenceFingerprint(intent: LocalCreateIntent): String {
            val candidate = requireNotNull(intent.occurrence)
            val master = candidate.master
            val series = capturedCreateSeries(master.content)
            val parent = LocalCreateIntent(intent.reservation, master.content.copy(recurrence = null), series)
            val child = LocalCreateIntent(intent.reservation, intent.content)
            val fields = listOf("calendeer-captured-occurrence-content-v3", master.eventId.value, master.revision.value,
                requireNotNull(master.content.recurrence).uid, contentFingerprint(parent),
                when (val original = candidate.original) {
                    is RecurrencePoint.AllDay -> "DATE:" + original.date
                    is RecurrencePoint.Timed -> "TIMED:" + original.instant
                }, contentFingerprint(child))
            val digest = MessageDigest.getInstance("SHA-256")
            fields.forEach { value ->
                val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
                val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun contentFingerprint(intent: LocalCreateIntent): String {
            require(intent.occurrence == null) { "Occurrence requires a distinct fingerprint version" }
            val digest = MessageDigest.getInstance("SHA-256")
            fun field(value: String) {
                val bytes = try {
                    val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
                    ByteArray(encoded.remaining()).also { encoded.get(it) }
                } catch (_: CharacterCodingException) { throw IllegalArgumentException("Invalid captured text") }
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
            val content = intent.content
            val series = intent.series
            val knownEmpty = series == null && content.details == EventDetails() && content.colorRgb == null
            field(if (series != null) "calendeer-captured-create-series-content-v2"
                else if (knownEmpty) "calendeer-captured-create-content-v1" else "calendeer-captured-create-personal-content-v1")
            field(content.title); field(content.description); field(content.location)
            when (val time = content.time) {
                is EventTime.AllDay -> { field("ALL_DAY"); field(time.start.toString()); field(time.endExclusive.toString()) }
                is EventTime.Timed -> { field("TIMED"); field(time.start.toString()); field(time.end.toString()); field(time.zone.id) }
            }
            if (knownEmpty) {
                // Historical bytes are immutable, including these fixed tags.
                field("KNOWN_EMPTY_DETAILS"); field("NO_RECURRENCE"); field("NO_COLOR")
            } else {
                val details = requireNotNull(content.details)
                field("KNOWN_PERSONAL_DETAILS"); field(details.reminders.size.toString())
                details.reminders.forEach { field(it.minutesBefore.toString()) }
                field("NO_ORGANIZER"); field("NO_ATTENDEES")
                if (series == null) field("NO_RECURRENCE") else { field("DAILY_SERIES"); field(series.rule) }
                if (content.colorRgb == null) field("NO_COLOR")
                else { field("RGB_COLOR"); field(content.colorRgb.toString()) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

/** Strict canonical master profile; UID authenticity belongs to the adapter's verified readback. */
internal fun capturedCreateSeries(content: EventContent): CreateSeriesRule {
    val recurrence = requireNotNull(content.recurrence)
    require(recurrence.isMaster && recurrence.additionalDates.isEmpty() && recurrence.excludedDates.isEmpty() && !recurrence.cancelled)
    return CreateSeriesRule.parse(requireNotNull(recurrence.rule)).also {
        require(it.rule == recurrence.rule && it.supports(content.time))
    }
}
