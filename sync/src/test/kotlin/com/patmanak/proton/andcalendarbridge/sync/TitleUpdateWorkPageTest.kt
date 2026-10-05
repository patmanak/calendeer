// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test

class TitleUpdateWorkPageTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2)
    private fun ref(event: String, state: TitleUpdateAttemptState = TitleUpdateAttemptState.PREPARED) =
        TitleUpdateWorkRef(MutationId("attempt-$event"), EventId(event), state)

    @Test fun lookaheadContinuesAfterLastVisibleMetadataAndPreservesStates() {
        val rows = listOf(ref("a"), ref("b", TitleUpdateAttemptState.OUTCOME_UNKNOWN), ref("c"))
        val page = TitleUpdateWorkPage.fromOrderedMetadata(TitleUpdateWorkQuery(scope, 2), rows)
        assertEquals(rows.take(2), page.entries)
        assertEquals(TitleUpdateWorkCursor(scope, EventId("b")), page.next)
    }
    @Test fun exactAndEmptyPagesHaveNoSpeculativeContinuation() {
        for (rows in listOf(emptyList(), listOf(ref("a"), ref("b")))) {
            val page = TitleUpdateWorkPage.fromOrderedMetadata(TitleUpdateWorkQuery(scope, 2), rows)
            assertEquals(rows, page.entries); assertNull(page.next)
        }
    }
    @Test fun queryRejectsInvalidBoundsAndForeignOrFutureCursor() {
        for (limit in listOf(-1, 0, 51, Int.MAX_VALUE)) rejected { TitleUpdateWorkQuery(scope, limit) }
        for (other in listOf(scope.copy(account = AccountId("foreign")), scope.copy(calendar = CalendarId("foreign")),
            scope.copy(generation = 3))) rejected { TitleUpdateWorkQuery(scope, cursor = TitleUpdateWorkCursor(other, EventId("a"))) }
        rejected { TitleUpdateWorkQuery(scope, cursor = TitleUpdateWorkCursor(scope, EventId("a"), 2)) }
        assertEquals(50, TitleUpdateWorkQuery(scope).limit)
        assertEquals(1, TitleUpdateWorkQuery(scope, 1).limit)
    }
    @Test fun identitiesRequireValidUtf8WithinByteBudgetAndNoControls() {
        for (value in listOf(" ", "nul\u0000", "line\n", "x".repeat(4097), "é".repeat(2049), "\uD800")) {
            rejected { TitleUpdateWorkRef(MutationId(value), EventId("event"), TitleUpdateAttemptState.PREPARED) }
            rejected { TitleUpdateWorkRef(MutationId("attempt"), EventId(value), TitleUpdateAttemptState.OUTCOME_UNKNOWN) }
            rejected { TitleUpdateWorkCursor(scope, EventId(value)) }
        }
        assertEquals("é".repeat(2048), TitleUpdateWorkCursor(scope, EventId("é".repeat(2048))).afterEvent.value)
        rejected { TitleUpdateWorkQuery(scope.copy(account = AccountId("nul\u0000"))) }
        rejected { TitleUpdateWorkQuery(scope.copy(calendar = CalendarId("é".repeat(2049)))) }
    }
    @Test fun suppliedBinaryOrderIsPreservedInsteadOfJvmOrdering() {
        val rows = listOf(ref("\uE000"), ref("\uD800\uDC00"))
        val page = TitleUpdateWorkPage.fromOrderedMetadata(TitleUpdateWorkQuery(scope, 1), rows)
        assertEquals(rows.take(1), page.entries); assertEquals(rows.first().eventId, page.next!!.afterEvent)
    }
    @Test fun oversizedOrDuplicateLookaheadCannotBeHidden() {
        val query = TitleUpdateWorkQuery(scope, 1)
        rejected { TitleUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("a"))) }
        rejected { TitleUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("b"), ref("c"))) }
        rejected { TitleUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("b").copy(id = MutationId("attempt-a")))) }
    }
    @Test fun pageOwnsImmutableListAndDiagnosticsAreRedacted() {
        val rows = mutableListOf(ref("private-synthetic-a"), ref("private-synthetic-b"))
        val query = TitleUpdateWorkQuery(scope, 1)
        val page = TitleUpdateWorkPage.fromOrderedMetadata(query, rows)
        rows.clear(); assertEquals(1, page.entries.size)
        try { (page.entries as MutableList).clear(); fail("Must remain immutable") } catch (_: UnsupportedOperationException) { }
        for (value in listOf(query, page, page.next, page.entries.first())) {
            assertTrue(value.toString().endsWith("(REDACTED)")); assertFalse(value.toString().contains("synthetic"))
        }
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Must reject") } catch (failure: IllegalArgumentException) {
            assertFalse(failure.message.orEmpty().contains("synthetic")); assertNull(failure.cause)
        }
    }
}
