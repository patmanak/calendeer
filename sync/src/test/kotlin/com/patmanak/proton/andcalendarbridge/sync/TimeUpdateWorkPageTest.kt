// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test

class TimeUpdateWorkPageTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2)
    private fun ref(event: String, state: TimeUpdateAttemptState = TimeUpdateAttemptState.PREPARED) =
        TimeUpdateWorkRef(MutationId("attempt-$event"), EventId(event), state)

    @Test fun lookaheadContinuesAfterLastVisibleMetadataAndPreservesStates() {
        val rows = listOf(ref("a"), ref("b", TimeUpdateAttemptState.OUTCOME_UNKNOWN), ref("c"))
        val page = TimeUpdateWorkPage.fromOrderedMetadata(TimeUpdateWorkQuery(scope, 2), rows)
        assertEquals(rows.take(2), page.entries)
        assertEquals(TimeUpdateWorkCursor(scope, EventId("b")), page.next)
    }
    @Test fun exactAndEmptyPagesHaveNoSpeculativeContinuation() {
        for (rows in listOf(emptyList(), listOf(ref("a"), ref("b")))) {
            val page = TimeUpdateWorkPage.fromOrderedMetadata(TimeUpdateWorkQuery(scope, 2), rows)
            assertEquals(rows, page.entries); assertNull(page.next)
        }
    }
    @Test fun queryRejectsInvalidBoundsAndForeignOrFutureCursor() {
        for (limit in listOf(-1, 0, 51, Int.MAX_VALUE)) rejected { TimeUpdateWorkQuery(scope, limit) }
        for (other in listOf(scope.copy(account = AccountId("foreign")), scope.copy(calendar = CalendarId("foreign")),
            scope.copy(generation = 3))) rejected { TimeUpdateWorkQuery(scope, cursor = TimeUpdateWorkCursor(other, EventId("a"))) }
        rejected { TimeUpdateWorkQuery(scope, cursor = TimeUpdateWorkCursor(scope, EventId("a"), 2)) }
        assertEquals(50, TimeUpdateWorkQuery(scope).limit)
        assertEquals(1, TimeUpdateWorkQuery(scope, 1).limit)
    }
    @Test fun identitiesRequireValidUtf8WithinByteBudgetAndNoControls() {
        for (value in listOf(" ", "nul\u0000", "line\n", "x".repeat(4097), "é".repeat(2049), "\uD800")) {
            rejected { TimeUpdateWorkRef(MutationId(value), EventId("event"), TimeUpdateAttemptState.PREPARED) }
            rejected { TimeUpdateWorkRef(MutationId("attempt"), EventId(value), TimeUpdateAttemptState.OUTCOME_UNKNOWN) }
            rejected { TimeUpdateWorkCursor(scope, EventId(value)) }
        }
        assertEquals("é".repeat(2048), TimeUpdateWorkCursor(scope, EventId("é".repeat(2048))).afterEvent.value)
        rejected { TimeUpdateWorkQuery(scope.copy(account = AccountId("nul\u0000"))) }
        rejected { TimeUpdateWorkQuery(scope.copy(calendar = CalendarId("é".repeat(2049)))) }
    }
    @Test fun suppliedBinaryOrderIsPreservedInsteadOfJvmOrdering() {
        val rows = listOf(ref("\uE000"), ref("\uD800\uDC00"))
        val page = TimeUpdateWorkPage.fromOrderedMetadata(TimeUpdateWorkQuery(scope, 1), rows)
        assertEquals(rows.take(1), page.entries); assertEquals(rows.first().eventId, page.next!!.afterEvent)
    }
    @Test fun oversizedOrDuplicateLookaheadCannotBeHidden() {
        val query = TimeUpdateWorkQuery(scope, 1)
        rejected { TimeUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("a"))) }
        rejected { TimeUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("b"), ref("c"))) }
        rejected { TimeUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("b").copy(id = MutationId("attempt-a")))) }
    }
    @Test fun pageOwnsImmutableListAndDiagnosticsAreRedacted() {
        val rows = mutableListOf(ref("private-synthetic-a"), ref("private-synthetic-b"))
        val query = TimeUpdateWorkQuery(scope, 1)
        val page = TimeUpdateWorkPage.fromOrderedMetadata(query, rows)
        rows.clear(); assertEquals(1, page.entries.size)
        try { (page.entries as MutableList).clear(); fail("Must remain immutable") } catch (_: UnsupportedOperationException) { }
        for (value in listOf(query, page, page.next, page.entries.first())) {
            assertTrue(value.toString().endsWith("(REDACTED)")); assertFalse(value.toString().contains("synthetic"))
        }
    }
    @Test fun oversizedMetadataIsRejectedBeforeIterationOrCopying() {
        val inaccessible = object : AbstractList<TimeUpdateWorkRef>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): TimeUpdateWorkRef = error("Must reject before reading rows")
        }
        rejected { TimeUpdateWorkPage.fromOrderedMetadata(TimeUpdateWorkQuery(scope, 1), inaccessible) }
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Must reject") } catch (failure: IllegalArgumentException) {
            assertFalse(failure.message.orEmpty().contains("synthetic")); assertNull(failure.cause)
        }
    }
}
