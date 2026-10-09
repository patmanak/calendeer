// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TitleUpdatePreparationReservationTest {
    private val scope = CalendarScope(AccountId("reservation-account"), CalendarId("reservation-calendar"), 2)
    private val content = EventContent("Synthetic original", EventTime.Timed(
        Instant.parse("2026-10-09T10:00:00Z"), Instant.parse("2026-10-09T11:00:00Z"), ZoneId.of("Europe/Paris")),
        details = EventDetails())
    private val intent = LocalEditIntent(MutationId("reservation-intent"), scope, AdapterFormat("fixture-import", 1),
        RemoteChange.Upsert(EventId("reservation-event"), Revision("original"), content), content.copy(title = "Synthetic changed"))
    private val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-09T12:00:00Z"),
        AdapterFormat("fixture-preparation", 1), "opaque-capsule")
    private val record = TitleUpdateAttemptRecord(MutationId("reservation-attempt"), preparation,
        AdapterFormat("fixture-wire", 1), "opaque-first-wire")

    @Test fun reservationBindsEveryPreparationAndIdentityField() {
        val reservation = TitleUpdatePreparationReservation.of(record)
        assertTrue(reservation.matches(record))
        val substitutions = listOf(
            record.copy(id = MutationId("different-attempt")),
            record.copy(format = record.format.copy(version = 2)),
            record.copy(preparation = preparation.copy(timestamp = preparation.timestamp.plusSeconds(1))),
            record.copy(preparation = preparation.copy(format = preparation.format.copy(version = 2))),
            record.copy(preparation = preparation.copy(protectedPayload = "different-capsule")),
            record.copy(preparation = preparation.copy(intent = intent.copy(id = MutationId("different-intent")))),
            record.copy(preparation = preparation.copy(intent = intent.copy(scope = scope.copy(generation = 3)))),
            record.copy(preparation = preparation.copy(intent = intent.copy(base = intent.base.copy(revision = Revision("different-base"))))),
            record.copy(preparation = preparation.copy(intent = intent.copy(content = intent.content.copy(title = "Different edit"))))
        )
        substitutions.forEach { assertFalse(reservation.matches(it)) }
        assertEquals(scope, reservation.scope); assertEquals(intent.base.eventId, reservation.eventId)
    }

    @Test fun structuralReferenceDoesNotPretendToAuthorizeDispatchOrFirstWire() {
        val reservation = TitleUpdatePreparationReservation.of(record)
        assertTrue(reservation.matches(record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "evidence")))
        assertTrue(reservation.matches(record.copy(protectedPayload = "different-wire")))
        // Wire immutability and UNKNOWN refusal belong to the durable journal, not this reference.
        assertEquals(reservation, reservation.frozen())
    }

    @Test fun unknownDetailsAndColorAreNotReservableTitleWork() {
        for (baseContent in listOf(content.copy(details = null), content.copy(colorRgb = 0x123456))) {
            val other = preparation.copy(intent = intent.copy(base = intent.base.copy(content = baseContent),
                content = baseContent.copy(title = intent.content.title)))
            rejects { TitleUpdatePreparationReservation(record.id, other, record.format) }
        }
    }

    @Test fun identitiesUseStrictUtf8BoundsAndDiagnosticsStayRedacted() {
        rejects { TitleUpdatePreparationReservation(MutationId("x".repeat(LocalEditPreparation.MAX_CONTEXT_BYTES + 1)), preparation, record.format) }
        rejects { TitleUpdatePreparationReservation(MutationId("\uD800"), preparation, record.format) }
        rejects { TitleUpdatePreparationReservation(record.id, preparation, AdapterFormat("é".repeat(2049), 1)) }
        val text = TitleUpdatePreparationReservation.of(record).toString()
        for (private in listOf(scope.account.value, intent.content.title, preparation.protectedPayload, record.id.value))
            assertFalse(text.contains(private))
    }

    @Test fun unsupportedPreparationJournalDeniesWithoutCallingLegacyWork() = runTest {
        val journal = object : TitleUpdatePreparationJournal {
            override suspend fun remember(record: TitleUpdateAttemptRecord) = error("Legacy retention forbidden")
            override suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? = error("No attempt read")
            override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean = error("No dispatch")
            override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String) = error("No evidence")
        }
        assertNull(journal.reservePreparation(TitleUpdatePreparationReservation.of(record)) { true })
        assertNull(journal.readPreparationReservation(scope, intent.base.eventId))
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid reservation must be rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: java.nio.charset.CharacterCodingException) { }
    }
}
