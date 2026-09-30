// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CapturedCreateDispatchIdentityTest {
    private val content = EventContent("Fixture 🦌", EventTime.AllDay(LocalDate.parse("2030-10-02"),
        LocalDate.parse("2030-10-03")), "Line one\nLine two", "Room", details = EventDetails())
    private val intent = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-capture"),
        LocalEventId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0),
        AdapterFormat("capture", 1), Instant.parse("2030-01-01T00:00:00Z")), content)
    private val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(intent,
        MutationId("11111111-2222-3333-4444-555555555555"), AdapterFormat("submission", 2)), "synthetic-protected-payload")
    private fun identity(content: EventContent = this.content) = CapturedCreateDispatchIdentity.of(submission.copy(
        reservation = submission.reservation.copy(intent = intent.copy(content = content))))

    @Test fun versionOneEncodingMatchesIndependentUtf8ByteLengthFixture() {
        // Generated independently with .NET SHA256 over the specified big-endian UTF8 fields.
        assertEquals("e108d2d3f66ca9510a2a6ba479bb2f72e96503a194e625f331d2bb18c083a798", identity().contentFingerprint)
        assertEquals(1, identity().fingerprintVersion)
    }
    @Test fun fieldBoundariesAndExactLineEndingsCannotAlias() {
        assertNotEquals(identity(content.copy(title = "a", description = "bc")).contentFingerprint,
            identity(content.copy(title = "ab", description = "c")).contentFingerprint)
        assertNotEquals(identity().contentFingerprint, identity(content.copy(description = "Line one\r\nLine two")).contentFingerprint)
        assertNotEquals(identity().contentFingerprint, identity(content.copy(location = "Room ")).contentFingerprint)
    }
    @Test fun temporalTypeAndExactZoneArePartOfTheFrozenContent() {
        val time = EventTime.Timed(Instant.parse("2030-10-02T00:00:00Z"), Instant.parse("2030-10-03T00:00:00Z"), ZoneId.of("UTC"))
        assertNotEquals(identity().contentFingerprint, identity(content.copy(time = time)).contentFingerprint)
        assertNotEquals(identity(content.copy(time = time)).contentFingerprint,
            identity(content.copy(time = time.copy(zone = ZoneOffset.UTC))).contentFingerprint)
    }
    @Test fun invalidUnicodeInEveryTextFieldIsRefusedWithoutReplacement() {
        for (invalid in listOf(content.copy(title = "\uD800"), content.copy(description = "\uD801"), content.copy(location = "\uDC00"))) {
            try { identity(invalid); fail("Malformed text must not share a replacement digest") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun identityRetainsSubmissionAndCapturedDimensionsWithoutLeakingDiagnostics() {
        val link = identity()
        assertEquals(CreateAttemptIdentity.of(CreateAttemptRecord(submission.reservation.attempt, intent.reservation.scope,
            submission.reservation.format, submission.protectedPayload)), link.attempt)
        assertEquals(intent.reservation.localId, link.localId); assertEquals(intent.reservation.id, link.captureId)
        assertEquals(intent.reservation.timestamp, link.timestamp); assertEquals(intent.reservation.format, link.captureFormat)
        assertFalse(link.toString().contains(content.title)); assertFalse(link.toString().contains(link.contentFingerprint))
        try { link.copy(fingerprintVersion = 2); fail("Implicit fingerprint migration accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
