// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.RetainedLocalChangeListing
import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Strict single-owner in-memory boundary, deliberately independent of SQL/crypto implementation. */
internal class TextOperationsFixture {
    val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 2)
    val stamp = Instant.parse("2033-03-20T09:00:00Z")
    val base = EventContent("Original title", EventTime.Timed(stamp.plusSeconds(3600), stamp.plusSeconds(7200),
        ZoneId.of("Europe/Paris")), "Original\r\nnotes", "Original\r\nroom", details = EventDetails())
    val changed = base.copy(title = "Edited title", description = "Edited\r\nnotes", location = "Edited room")
    val intent = LocalEditIntent(MutationId("fixture-owner"), scope, AdapterFormat("fixture-observation", 2),
        RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), base), changed)
    val preparation = LocalTextEditPreparation(intent, stamp, AdapterFormat("fixture-capsule", 1), "sealed-capsule")
    val wireFormat = AdapterFormat("fixture-wire", 1)
    val reference = RetainedLocalChangeRef(intent.base.eventId, intent.id, MutationKind.UPDATE, LocalEditState.PAUSED)
    val query = TitleUpdateWorkQuery(scope)
    val calls = mutableListOf<String>()
    var allowed = true
    var sessionOpen = true
    var observedIntent = intent
    var retained: LocalTextEditPreparation? = null
    var saved: TextUpdateAttemptRecord? = null
    var submitted: TextUpdateAttemptRecord? = null
    var result: TextUpdateVerifiedResult? = null
    var completion: TextUpdateCompletion? = null
    var acceptProof = true
    var acceptAck = true
    var retainedLease: ((CalendarScope) -> Boolean)? = null
    var evidenceLease: ((CalendarScope) -> Boolean)? = null
    var capsuleHook: (suspend ((CalendarScope) -> Boolean) -> LocalTextEditPreparation)? = null
    var retainHook: ((LocalTextEditPreparation) -> LocalTextEditPreparation)? = null
    var wireHook: ((TextUpdateAttemptRecord) -> TextUpdateAttemptRecord)? = null
    var rememberHook: ((TextUpdateAttemptRecord) -> TextUpdateAttemptRecord)? = null
    var submitObservation: ((TextUpdateAttemptRecord) -> TextUpdateSubmissionObservation)? = null
    var inspectHook: (((CalendarScope) -> Boolean) -> TextUpdateSubmissionObservation)? = null
    var pageRows: List<TextUpdateWorkRef>? = null
    var cancelAt: String? = null
    var cancellationHits = 0
    var throwAfterCancellation = false
    private suspend fun cancelHere(stage: String): Boolean {
        if (cancelAt != stage) return false
        cancellationHits++
        currentCoroutineContext().cancel()
        if (throwAfterCancellation) throw IOException("Synthetic failure after cancellation")
        return true
    }
    private fun admitted(admit: (CalendarScope) -> Boolean) = admit(scope)
    fun verified(record: TextUpdateAttemptRecord): TextUpdateVerifiedResult = TextUpdateVerifiedResult.of(record,
        TextUpdateSubmissionObservation.Verified(record, intent.base.copy(revision = Revision("fixture-result"),
            content = changed.copy(description = "Edited\nnotes"))))
    fun seedUnknown() {
        retained = preparation
        saved = TextUpdateAttemptRecord(TextUpdateAttemptIdentity.requestId(preparation, wireFormat), preparation,
            wireFormat, "sealed-first-wire", TextUpdateAttemptState.OUTCOME_UNKNOWN)
    }

    val journal = object : TextUpdateRecoveryJournal {
        override val completionProtocol = 1
        override suspend fun remember(record: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "remember"; check(admitted(admit)); check(saved == null)
            saved = rememberHook?.invoke(record) ?: record.frozen(); return saved == record
        }
        override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
            check(scope == this@TextOperationsFixture.scope && admitted(admit))
            if (cancelHere("read")) return null
            return saved?.takeIf { it.id == id }?.frozen()
        }
        override suspend fun claim(expected: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "claim"; check(admitted(admit)); check(saved == expected && expected.state == TextUpdateAttemptState.PREPARED)
            saved = expected.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN); return true
        }
        override suspend fun retainEvidence(expectedUnknown: TextUpdateAttemptRecord, protectedEvidence: String,
            admit: (CalendarScope) -> Boolean): Boolean = error("Unexpected evidence operation")
        override suspend fun retainVerified(result: TextUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "proof"; check(admitted(admit)); check(result.record == saved!!.copy(protectedEvidence = null))
            if (acceptProof) this@TextOperationsFixture.result = result.snapshot()
            cancelHere("proof")
            return acceptProof
        }
        override suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateVerifiedResult? {
            check(scope == this@TextOperationsFixture.scope && admitted(admit)); check(id == saved!!.id); return result
        }
        override suspend fun retainCompletion(completion: TextUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean {
            check(admitted(admit)); check(completion.result == result); this@TextOperationsFixture.completion = completion; return true
        }
        override suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateCompletion? {
            check(scope == this@TextOperationsFixture.scope && admitted(admit)); check(id == saved!!.id); return completion
        }
        override suspend fun page(query: TextUpdateWorkQuery, admit: (CalendarScope) -> Boolean): TextUpdateWorkPage? {
            check(query.scope == scope && admitted(admit))
            if (cancelHere("page")) return null
            return TextUpdateWorkPage.fromOrderedMetadata(query, pageRows ?: listOfNotNull(saved?.let { TextUpdateWorkRef(it.id, it.eventId, it.state) }))
        }
    }
    private val owners = object : SystemTextLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): TextLocalOwnerSnapshot? {
            calls += "observe"; check(scope == this@TextOperationsFixture.scope && event == intent.base.eventId && owner == intent.id)
            check(admitted(admit))
            if (cancelHere("observe")) return null
            return TextLocalOwnerSnapshot(observedIntent, retained)
        }
        override suspend fun retain(preparation: LocalTextEditPreparation, admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? {
            calls += "retain-capsule"; check(admitted(admit))
            if (cancelHere("retain-capsule")) return null
            retained = retainHook?.invoke(preparation) ?: preparation; return retained
        }
        override suspend fun qualify(preparation: LocalTextEditPreparation, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "qualify"; check(preparation.intent == intent); return admitted(admit)
        }
        override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateRecoveryJournal,
            port: CalendarTextUpdateSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation =
            port.submit(record, journal, admit, { requested -> requested == scope && qualify(record.preparation, admit) }, admitEvidence)
        override suspend fun acknowledge(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "ack"; check(admitted(admit)); check(this@TextOperationsFixture.result == result)
            if (cancelHere("ack")) return true
            return acceptAck
        }
        override suspend fun complete(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "complete"; check(acceptAck && admitted(admit))
            val receipt = TextUpdateAcknowledgementReceipt.of(result, "00000000-0000-0000-0000-000000000099")
            return journal.retainCompletion(TextUpdateCompletion.of(result,
                TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.RESTORED)), admit)
        }
    }
    private val capsules = object : CalendarLocalTextPreparationPort {
        override val localTextFormat = preparation.format
        override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant): LocalTextEditPreparation =
            error("Unguarded preparation forbidden")
        override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant,
            admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? {
            calls += "capsule"; check(intent == this@TextOperationsFixture.intent && admitted(admit)); retainedLease = admit
            if (cancelHere("capsule")) return null
            return capsuleHook?.invoke(admit) ?: LocalTextEditPreparation(intent, timestamp, preparation.format, preparation.protectedPayload)
        }
    }
    private val submissions = object : CalendarTextUpdateSubmissionPort {
        override val textUpdateFormat = wireFormat
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalTextEditPreparation,
            admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
            calls += "wire"; check(admitted(admit)); check(id == TextUpdateAttemptIdentity.requestId(preparation, wireFormat))
            if (cancelHere("wire")) return null
            val candidate = TextUpdateAttemptRecord(id, preparation, wireFormat, "sealed-new-wire")
            return wireHook?.invoke(candidate) ?: candidate
        }
        override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
            calls += "submit"; retainedLease = admit; evidenceLease = admitEvidence
            check(admitted(admit) && record == saved && qualifySource(scope)); check(journal.claim(record, admit))
            submitted = record.frozen()
            val unknown = saved!!
            return submitObservation?.invoke(unknown) ?: TextUpdateSubmissionObservation.Verified(unknown, verified(unknown).event)
        }
        override suspend fun inspect(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
            calls += "inspect"; check(admitted(admit)); check(record == saved && record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN)
            retainedLease = admit
            return inspectHook?.invoke(admit) ?: TextUpdateSubmissionObservation.Verified(record, verified(record).event)
        }
    }
    val operations = CoordinatorSystemTextOperations(owners, journal, submissions,
        evidenceAdmission = { target ->
            var open = sessionOpen
            val lease: (CalendarScope) -> Boolean = { requested ->
                if (!sessionOpen) open = false
                open && requested == target
            }
            lease
        })
    suspend fun process(timestamp: Instant = stamp, candidate: MutationId = MutationId("random-traversal-candidate")): Boolean =
        operations.process(scope, reference, candidate, timestamp, { it == scope && allowed }) { calls += "wake" }
}

internal class TimeOperationsFixture(allDay: Boolean = false) {
    val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 2)
    val stamp = Instant.parse("2033-03-20T09:00:00Z")
    val base = EventContent("Original title", if (allDay)
        EventTime.AllDay(java.time.LocalDate.of(2033, 3, 20), java.time.LocalDate.of(2033, 3, 21)) else
        EventTime.Timed(stamp.plusSeconds(3600), stamp.plusSeconds(7200), ZoneId.of("Europe/Paris")), "Original\r\nnotes", "Original\r\nroom", details = EventDetails())
    val changed = base.copy(time = when (val old = base.time) {
        is EventTime.AllDay -> EventTime.AllDay(old.start.plusDays(1), old.endExclusive.plusDays(2))
        is EventTime.Timed -> EventTime.Timed(stamp.plusSeconds(10800), stamp.plusSeconds(18000), ZoneId.of("Europe/Paris"))
    })
    val intent = LocalEditIntent(MutationId("fixture-owner"), scope, AdapterFormat("fixture-observation", 2),
        RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), base), changed)
    val preparation = LocalTimeEditPreparation(intent, stamp, AdapterFormat("fixture-capsule", 1), "sealed-capsule")
    val wireFormat = AdapterFormat("fixture-wire", 1)
    val reference = RetainedLocalChangeRef(intent.base.eventId, intent.id, MutationKind.UPDATE, LocalEditState.PAUSED)
    val query = TitleUpdateWorkQuery(scope)
    val calls = mutableListOf<String>()
    var allowed = true
    var sessionOpen = true
    var observedIntent = intent
    var retained: LocalTimeEditPreparation? = null
    var saved: TimeUpdateAttemptRecord? = null
    var submitted: TimeUpdateAttemptRecord? = null
    var result: TimeUpdateVerifiedResult? = null
    var completion: TimeUpdateCompletion? = null
    var acceptProof = true
    var acceptAck = true
    var retainedLease: ((CalendarScope) -> Boolean)? = null
    var evidenceLease: ((CalendarScope) -> Boolean)? = null
    var capsuleHook: (suspend ((CalendarScope) -> Boolean) -> LocalTimeEditPreparation)? = null
    var retainHook: ((LocalTimeEditPreparation) -> LocalTimeEditPreparation)? = null
    var wireHook: ((TimeUpdateAttemptRecord) -> TimeUpdateAttemptRecord)? = null
    var rememberHook: ((TimeUpdateAttemptRecord) -> TimeUpdateAttemptRecord)? = null
    var submitObservation: ((TimeUpdateAttemptRecord) -> TimeUpdateSubmissionObservation)? = null
    var inspectHook: (((CalendarScope) -> Boolean) -> TimeUpdateSubmissionObservation)? = null
    var pageRows: List<TimeUpdateWorkRef>? = null
    var cancelAt: String? = null
    var cancellationHits = 0
    var throwAfterCancellation = false
    private suspend fun cancelHere(stage: String): Boolean {
        if (cancelAt != stage) return false
        cancellationHits++
        currentCoroutineContext().cancel()
        if (throwAfterCancellation) throw IOException("Synthetic failure after cancellation")
        return true
    }
    private fun admitted(admit: (CalendarScope) -> Boolean) = admit(scope)
    fun verified(record: TimeUpdateAttemptRecord): TimeUpdateVerifiedResult = TimeUpdateVerifiedResult.of(record,
        TimeUpdateSubmissionObservation.Verified(record, intent.base.copy(revision = Revision("fixture-result"),
            content = changed.copy(description = "Original\nnotes"))))
    fun seedUnknown() {
        retained = preparation
        saved = TimeUpdateAttemptRecord(TimeUpdateAttemptIdentity.requestId(preparation, wireFormat), preparation,
            wireFormat, "sealed-first-wire", TimeUpdateAttemptState.OUTCOME_UNKNOWN)
    }

    val journal = object : TimeUpdateRecoveryJournal {
        override val completionProtocol = 1
        override suspend fun remember(record: TimeUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "remember"; check(admitted(admit)); check(saved == null)
            saved = rememberHook?.invoke(record) ?: record.frozen(); return saved == record
        }
        override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateAttemptRecord? {
            check(scope == this@TimeOperationsFixture.scope && admitted(admit))
            if (cancelHere("read")) return null
            return saved?.takeIf { it.id == id }?.frozen()
        }
        override suspend fun claim(expected: TimeUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "claim"; check(admitted(admit)); check(saved == expected && expected.state == TimeUpdateAttemptState.PREPARED)
            saved = expected.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN); return true
        }
        override suspend fun retainEvidence(expectedUnknown: TimeUpdateAttemptRecord, protectedEvidence: String,
            admit: (CalendarScope) -> Boolean): Boolean = error("Unexpected evidence operation")
        override suspend fun retainVerified(result: TimeUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "proof"; check(admitted(admit)); check(result.record == saved!!.copy(protectedEvidence = null))
            if (acceptProof) this@TimeOperationsFixture.result = result.snapshot()
            cancelHere("proof")
            return acceptProof
        }
        override suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateVerifiedResult? {
            check(scope == this@TimeOperationsFixture.scope && admitted(admit)); check(id == saved!!.id); return result
        }
        override suspend fun retainCompletion(completion: TimeUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean {
            check(admitted(admit)); check(completion.result == result); this@TimeOperationsFixture.completion = completion; return true
        }
        override suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateCompletion? {
            check(scope == this@TimeOperationsFixture.scope && admitted(admit)); check(id == saved!!.id); return completion
        }
        override suspend fun page(query: TimeUpdateWorkQuery, admit: (CalendarScope) -> Boolean): TimeUpdateWorkPage? {
            check(query.scope == scope && admitted(admit))
            if (cancelHere("page")) return null
            return TimeUpdateWorkPage.fromOrderedMetadata(query, pageRows ?: listOfNotNull(saved?.let { TimeUpdateWorkRef(it.id, it.eventId, it.state) }))
        }
    }
    private val owners = object : SystemTimeLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): TimeLocalOwnerSnapshot? {
            calls += "observe"; check(scope == this@TimeOperationsFixture.scope && event == intent.base.eventId && owner == intent.id)
            check(admitted(admit))
            if (cancelHere("observe")) return null
            return if (SystemTimeEditProfile.accepts(observedIntent)) TimeLocalOwnerSnapshot(observedIntent, retained) else null
        }
        override suspend fun retain(preparation: LocalTimeEditPreparation, admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? {
            calls += "retain-capsule"; check(admitted(admit))
            if (cancelHere("retain-capsule")) return null
            retained = retainHook?.invoke(preparation) ?: preparation; return retained
        }
        override suspend fun qualify(preparation: LocalTimeEditPreparation, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "qualify"; check(preparation.intent == intent); return admitted(admit)
        }
        override suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateRecoveryJournal,
            port: CalendarTimeUpdateSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation =
            port.submit(record, journal, admit, { requested -> requested == scope && qualify(record.preparation, admit) }, admitEvidence)
        override suspend fun acknowledge(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "ack"; check(admitted(admit)); check(this@TimeOperationsFixture.result == result)
            if (cancelHere("ack")) return true
            return acceptAck
        }
        override suspend fun complete(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "complete"; check(acceptAck && admitted(admit))
            val receipt = TimeUpdateAcknowledgementReceipt.of(result, "00000000-0000-0000-0000-000000000099")
            return journal.retainCompletion(TimeUpdateCompletion.of(result,
                TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.RESTORED)), admit)
        }
    }
    private val capsules = object : CalendarLocalTimePreparationPort {
        override val localTimeFormat = preparation.format
        override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant): LocalTimeEditPreparation =
            error("Unguarded preparation forbidden")
        override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant,
            admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? {
            calls += "capsule"; check(intent == this@TimeOperationsFixture.intent && admitted(admit)); retainedLease = admit
            if (cancelHere("capsule")) return null
            return capsuleHook?.invoke(admit) ?: LocalTimeEditPreparation(intent, timestamp, preparation.format, preparation.protectedPayload)
        }
    }
    private val submissions = object : CalendarTimeUpdateSubmissionPort {
        override val timeUpdateFormat = wireFormat
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalTimeEditPreparation,
            admit: (CalendarScope) -> Boolean): TimeUpdateAttemptRecord? {
            calls += "wire"; check(admitted(admit)); check(id == TimeUpdateAttemptIdentity.requestId(preparation, wireFormat))
            if (cancelHere("wire")) return null
            val candidate = TimeUpdateAttemptRecord(id, preparation, wireFormat, "sealed-new-wire")
            return wireHook?.invoke(candidate) ?: candidate
        }
        override suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
            calls += "submit"; retainedLease = admit; evidenceLease = admitEvidence
            check(admitted(admit) && record == saved && qualifySource(scope)); check(journal.claim(record, admit))
            submitted = record.frozen()
            val unknown = saved!!
            return submitObservation?.invoke(unknown) ?: TimeUpdateSubmissionObservation.Verified(unknown, verified(unknown).event)
        }
        override suspend fun inspect(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
            calls += "inspect"; check(admitted(admit)); check(record == saved && record.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN)
            retainedLease = admit
            return inspectHook?.invoke(admit) ?: TimeUpdateSubmissionObservation.Verified(record, verified(record).event)
        }
    }
    val operations = CoordinatorSystemTimeOperations(owners, journal, submissions,
        evidenceAdmission = { target ->
            var open = sessionOpen
            val lease: (CalendarScope) -> Boolean = { requested ->
                if (!sessionOpen) open = false
                open && requested == target
            }
            lease
        })
    suspend fun process(timestamp: Instant = stamp, candidate: MutationId = MutationId("random-traversal-candidate")): Boolean =
        operations.process(scope, reference, candidate, timestamp, { it == scope && allowed }) { calls += "wake" }
}
