// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DeleteSubmissionTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 1)
    private val format = AdapterFormat("synthetic-delete", 1)
    private val content = EventContent("Title\r\nline", EventTime.Timed(Instant.parse("2030-01-01T12:00:00Z"),
        Instant.parse("2030-01-01T13:00:00Z"), ZoneId.of("Europe/Paris")), description = "Notes", details = EventDetails())
    private fun intent(value: EventContent = content) = LocalDeleteIntent(MutationId("local-delete"), scope, format,
        RemoteChange.Upsert(EventId("event"), Revision("base"), value), value)
    private fun record(value: LocalDeleteIntent = intent()) = DeleteAttemptRecord(MutationId("request"), value, format, "protected")

    @Test fun exactKnownEmptyUnchangedSnapshotIsRequired() {
        for (value in listOf(content.copy(details = null), content.copy(colorRgb = 1),
            content.copy(details = EventDetails(reminders = listOf(EventReminder(5)))))) refused { record(intent(value)) }
        refused { record(intent().copy(content = content.copy(title = "newer local edit"))) }
        refused { record(intent().copy(state = LocalEditState.CONFLICT)) }
        val frozen = record()
        assertNotSame(frozen.intent, intent()); assertEquals(intent(), frozen.intent)
        assertEquals(frozen, frozen.frozen()); assertEquals(frozen.hashCode(), frozen.frozen().hashCode())
    }
    @Test fun preparedCannotContainEvidenceAndUnknownNeverRevertsWithEvidence() {
        val first = record()
        refused { first.copy(protectedEvidence = "response") }
        val unknown = first.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "response")
        refused { unknown.copy(state = DeleteAttemptState.PREPARED) }
        assertNotEquals(first, unknown); assertNotEquals(first, first.copy(protectedPayload = "different"))
        assertFalse(DeleteAttemptJournal::class.java.methods.any { it.name.contains("reset", true) || it.name.contains("resend", true) })
    }
    @Test fun framedIdentityBindsRawTextScopeGenerationIntentBaseAndFormat() {
        val base = intent(); val record = record(base); val identity = DeleteAttemptIdentity.of(record)
        val request = DeleteAttemptIdentity.requestId(base, format)
        val edited = content.copy(title = content.title.replace("\r\n", "\n"))
        val variants = listOf(base.copy(id = MutationId("other-intent")), base.copy(scope = scope.copy(generation = 2)),
            base.copy(scope = scope.copy(calendar = CalendarId("other"))), base.copy(format = format.copy(version = 2)),
            base.copy(base = base.base.copy(revision = Revision("other-base"))), intent(edited),
            base.copy(base = base.base.copy(eventId = EventId("other-event"))))
        for (value in variants) {
            assertNotEquals(request, DeleteAttemptIdentity.requestId(value, format))
            assertNotEquals(identity, DeleteAttemptIdentity.of(record(value)))
        }
        assertNotEquals(request, DeleteAttemptIdentity.requestId(base, format.copy(version = 2)))
        assertNotEquals(identity, DeleteAttemptIdentity.of(record.copy(id = MutationId("other-request"))))
        assertNotEquals(identity, DeleteAttemptIdentity.of(record.copy(protectedPayload = "other")))
        assertEquals(identity, DeleteAttemptIdentity.of(record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "reply")))
    }
    @Test fun verifiedRequiresUnknownConflictRequiresSameTargetAndChangedBase() {
        val record = record()
        refused { DeleteSubmissionObservation.Verified(record) }
        assertEquals(record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN),
            DeleteSubmissionObservation.Verified(record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)).record)
        refused { DeleteSubmissionObservation.Conflict(record, record.intent.base) }
        refused { DeleteSubmissionObservation.Conflict(record, record.intent.base.copy(eventId = EventId("foreign"))) }
        assertEquals(record, DeleteSubmissionObservation.Conflict(record, record.intent.base.copy(revision = Revision("changed"))).record)
    }
    @Test fun strictUtf8BoundsAndRedactedDiagnostics() {
        for (bad in listOf("\uD800", "é".repeat(2049))) refused { record().copy(id = MutationId(bad)) }
        refused { record(intent(content.copy(description = "\uD800"))) }
        for (value in listOf(record(), DeleteAttemptIdentity.of(record()), DeleteSubmissionObservation.Conflict(record(),
            intent().base.copy(revision = Revision("changed"))))) assertFalse(value.toString().contains("synthetic"))
    }
    private fun refused(action: () -> Unit) {
        try { action(); fail("Invalid deletion work accepted") } catch (_: IllegalArgumentException) { }
    }
}
