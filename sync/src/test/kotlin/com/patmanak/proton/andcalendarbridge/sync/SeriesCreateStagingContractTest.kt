// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SeriesCreateStagingContractTest {
    private val content = EventContent("Synthetic series", EventTime.AllDay(LocalDate.parse("2030-03-29"),
        LocalDate.parse("2030-03-30")), details = EventDetails())
    private val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId(UUID(0, 1).toString()),
        CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0), AdapterFormat("capture", 1),
        Instant.parse("2030-01-01T00:00:00Z")), content, CreateSeriesRule(5))
    private val seriesFormat = AdapterFormat("synthetic-preparation", 4)
    @Test fun explicitSeriesReservationAndPreparationRetainTheWholeRule() {
        val reservation = LocalCreateSubmissionReservation(intent, MutationId(UUID(0, 2).toString()), seriesFormat)
        assertEquals(intent.series, reservation.draft.series)
        assertEquals(content, reservation.draft.content); assertNull(reservation.draft.content.recurrence)
        assertEquals(intent, LocalCreatePreparation(intent, seriesFormat, "synthetic-opaque-payload").intent)
    }
    @Test fun seriesPreparationRequiresADistinctExplicitCapability() {
        val closed = CalendarCreatePreparationPort { error("Selection must be local") }
        assertNull(closed.capturedPreparationFormat(content, intent.series))
        val port = object : CalendarCreatePreparationPort {
            override val localCreateFormat = seriesFormat.copy(version = 2)
            override val personalLocalCreateFormat = seriesFormat.copy(version = 3)
            override val seriesLocalCreateFormat = seriesFormat
            override suspend fun prepareCreate(draft: CalendarCreateDraft): PreparedCalendarCreate = error("Selection must be local")
        }
        assertEquals(seriesFormat, port.capturedPreparationFormat(content, intent.series))
        assertEquals(port.localCreateFormat, port.capturedPreparationFormat(content, null))
        val alias = object : CalendarCreatePreparationPort {
            override val localCreateFormat = seriesFormat
            override val seriesLocalCreateFormat = seriesFormat
            override suspend fun prepareCreate(draft: CalendarCreateDraft): PreparedCalendarCreate = error("Selection must be local")
        }
        assertNull(alias.capturedPreparationFormat(content, intent.series))
    }
    @Test fun seriesSubmissionRequiresADistinctExplicitCapabilityAndKeepsSimpleSelection() {
        fun port(declared: AdapterFormat?) = object : CalendarCreateSubmissionPort {
            override val knownEmptySubmissionFormat = seriesFormat.copy(version = 2)
            override val seriesSubmissionFormat = declared
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate = error("No IO")
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = error("No IO")
            override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = error("No IO")
        }
        assertNull(port(null).capturedSubmissionFormat(content, intent.series))
        assertNull(port(seriesFormat.copy(version = 2)).capturedSubmissionFormat(content, intent.series))
        val open = port(seriesFormat)
        assertEquals(seriesFormat, open.capturedSubmissionFormat(content, intent.series))
        assertEquals(seriesFormat.copy(version = 2), open.capturedSubmissionFormat(content, null))
    }
}
