// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test

class TextUpdateWorkPageTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2)
    private fun ref(event: String, state: TextUpdateAttemptState = TextUpdateAttemptState.PREPARED) =
        TextUpdateWorkRef(MutationId("attempt-$event"), EventId(event), state)

    @Test fun lookaheadContinuesAfterLastVisibleMetadataAndPreservesStates() {
        val rows = listOf(ref("a"), ref("b", TextUpdateAttemptState.OUTCOME_UNKNOWN), ref("c"))
        val page = TextUpdateWorkPage.fromOrderedMetadata(TextUpdateWorkQuery(scope, 2), rows)
        assertEquals(rows.take(2), page.entries)
        assertEquals(TextUpdateWorkCursor(scope, EventId("b")), page.next)
    }
    @Test fun exactAndEmptyPagesHaveNoSpeculativeContinuation() {
        for (rows in listOf(emptyList(), listOf(ref("a"), ref("b")))) {
            val page = TextUpdateWorkPage.fromOrderedMetadata(TextUpdateWorkQuery(scope, 2), rows)
            assertEquals(rows, page.entries); assertNull(page.next)
        }
    }
    @Test fun queryRejectsInvalidBoundsAndForeignOrFutureCursor() {
        for (limit in listOf(-1, 0, 51, Int.MAX_VALUE)) rejected { TextUpdateWorkQuery(scope, limit) }
        for (other in listOf(scope.copy(account = AccountId("foreign")), scope.copy(calendar = CalendarId("foreign")),
            scope.copy(generation = 3))) rejected { TextUpdateWorkQuery(scope, cursor = TextUpdateWorkCursor(other, EventId("a"))) }
        rejected { TextUpdateWorkQuery(scope, cursor = TextUpdateWorkCursor(scope, EventId("a"), 2)) }
        assertEquals(50, TextUpdateWorkQuery(scope).limit)
        assertEquals(1, TextUpdateWorkQuery(scope, 1).limit)
    }
    @Test fun identitiesRequireValidUtf8WithinByteBudgetAndNoControls() {
        for (value in listOf(" ", "nul\u0000", "line\n", "x".repeat(4097), "é".repeat(2049), "\uD800")) {
            rejected { TextUpdateWorkRef(MutationId(value), EventId("event"), TextUpdateAttemptState.PREPARED) }
            rejected { TextUpdateWorkRef(MutationId("attempt"), EventId(value), TextUpdateAttemptState.OUTCOME_UNKNOWN) }
            rejected { TextUpdateWorkCursor(scope, EventId(value)) }
        }
        assertEquals("é".repeat(2048), TextUpdateWorkCursor(scope, EventId("é".repeat(2048))).afterEvent.value)
        rejected { TextUpdateWorkQuery(scope.copy(account = AccountId("nul\u0000"))) }
        rejected { TextUpdateWorkQuery(scope.copy(calendar = CalendarId("é".repeat(2049)))) }
    }
    @Test fun suppliedBinaryOrderIsPreservedInsteadOfJvmOrdering() {
        val rows = listOf(ref("\uE000"), ref("\uD800\uDC00"))
        val page = TextUpdateWorkPage.fromOrderedMetadata(TextUpdateWorkQuery(scope, 1), rows)
        assertEquals(rows.take(1), page.entries); assertEquals(rows.first().eventId, page.next!!.afterEvent)
    }
    @Test fun oversizedOrDuplicateLookaheadCannotBeHidden() {
        val query = TextUpdateWorkQuery(scope, 1)
        rejected { TextUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("a"))) }
        rejected { TextUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("b"), ref("c"))) }
        rejected { TextUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("b").copy(id = MutationId("attempt-a")))) }
    }
    @Test fun pageOwnsImmutableListAndDiagnosticsAreRedacted() {
        val rows = mutableListOf(ref("private-synthetic-a"), ref("private-synthetic-b"))
        val query = TextUpdateWorkQuery(scope, 1)
        val page = TextUpdateWorkPage.fromOrderedMetadata(query, rows)
        rows.clear(); assertEquals(1, page.entries.size)
        try { (page.entries as MutableList).clear(); fail("Must remain immutable") } catch (_: UnsupportedOperationException) { }
        for (value in listOf(query, page, page.next, page.entries.first())) {
            assertTrue(value.toString().endsWith("(REDACTED)")); assertFalse(value.toString().contains("synthetic"))
        }
    }
    @Test fun oversizedMetadataIsRejectedBeforeIterationOrCopying() {
        val inaccessible = object : AbstractList<TextUpdateWorkRef>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): TextUpdateWorkRef = error("Must reject before reading rows")
        }
        rejected { TextUpdateWorkPage.fromOrderedMetadata(TextUpdateWorkQuery(scope, 1), inaccessible) }
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Must reject") } catch (failure: IllegalArgumentException) {
            assertFalse(failure.message.orEmpty().contains("synthetic")); assertNull(failure.cause)
        }
    }
}
