// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test

class PersonalUpdateWorkPageTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2)
    private fun ref(event: String, state: PersonalUpdateAttemptState = PersonalUpdateAttemptState.PREPARED) =
        PersonalUpdateWorkRef(MutationId("attempt-$event"), EventId(event), state)

    @Test fun lookaheadContinuesAfterLastVisibleMetadataAndPreservesStates() {
        val rows = listOf(ref("a"), ref("b", PersonalUpdateAttemptState.OUTCOME_UNKNOWN), ref("c"))
        val page = PersonalUpdateWorkPage.fromOrderedMetadata(PersonalUpdateWorkQuery(scope, 2), rows)
        assertEquals(rows.take(2), page.entries)
        assertEquals(PersonalUpdateWorkCursor(scope, EventId("b")), page.next)
    }
    @Test fun exactAndEmptyPagesHaveNoSpeculativeContinuation() {
        for (rows in listOf(emptyList(), listOf(ref("a"), ref("b")))) {
            val page = PersonalUpdateWorkPage.fromOrderedMetadata(PersonalUpdateWorkQuery(scope, 2), rows)
            assertEquals(rows, page.entries); assertNull(page.next)
        }
    }
    @Test fun queryRejectsInvalidBoundsAndForeignOrFutureCursor() {
        for (limit in listOf(-1, 0, 51, Int.MAX_VALUE)) rejected { PersonalUpdateWorkQuery(scope, limit) }
        for (other in listOf(scope.copy(account = AccountId("foreign")), scope.copy(calendar = CalendarId("foreign")),
            scope.copy(generation = 3))) rejected { PersonalUpdateWorkQuery(scope, cursor = PersonalUpdateWorkCursor(other, EventId("a"))) }
        rejected { PersonalUpdateWorkQuery(scope, cursor = PersonalUpdateWorkCursor(scope, EventId("a"), 2)) }
        assertEquals(50, PersonalUpdateWorkQuery(scope).limit)
        assertEquals(1, PersonalUpdateWorkQuery(scope, 1).limit)
    }
    @Test fun identitiesRequireValidUtf8WithinByteBudgetAndNoControls() {
        for (value in listOf(" ", "nul\u0000", "line\n", "x".repeat(4097), "é".repeat(2049), "\uD800")) {
            rejected { PersonalUpdateWorkRef(MutationId(value), EventId("event"), PersonalUpdateAttemptState.PREPARED) }
            rejected { PersonalUpdateWorkRef(MutationId("attempt"), EventId(value), PersonalUpdateAttemptState.OUTCOME_UNKNOWN) }
            rejected { PersonalUpdateWorkCursor(scope, EventId(value)) }
        }
        assertEquals("é".repeat(2048), PersonalUpdateWorkCursor(scope, EventId("é".repeat(2048))).afterEvent.value)
        rejected { PersonalUpdateWorkQuery(scope.copy(account = AccountId("nul\u0000"))) }
        rejected { PersonalUpdateWorkQuery(scope.copy(calendar = CalendarId("é".repeat(2049)))) }
    }
    @Test fun suppliedBinaryOrderIsPreservedInsteadOfJvmOrdering() {
        val rows = listOf(ref("\uE000"), ref("\uD800\uDC00"))
        val page = PersonalUpdateWorkPage.fromOrderedMetadata(PersonalUpdateWorkQuery(scope, 1), rows)
        assertEquals(rows.take(1), page.entries); assertEquals(rows.first().eventId, page.next!!.afterEvent)
    }
    @Test fun oversizedOrDuplicateLookaheadCannotBeHidden() {
        val query = PersonalUpdateWorkQuery(scope, 1)
        rejected { PersonalUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("a"))) }
        rejected { PersonalUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("b"), ref("c"))) }
        rejected { PersonalUpdateWorkPage.fromOrderedMetadata(query, listOf(ref("a"), ref("b").copy(id = MutationId("attempt-a")))) }
    }
    @Test fun pageOwnsImmutableListAndDiagnosticsAreRedacted() {
        val rows = mutableListOf(ref("private-synthetic-a"), ref("private-synthetic-b"))
        val query = PersonalUpdateWorkQuery(scope, 1)
        val page = PersonalUpdateWorkPage.fromOrderedMetadata(query, rows)
        rows.clear(); assertEquals(1, page.entries.size)
        try { (page.entries as MutableList).clear(); fail("Must remain immutable") } catch (_: UnsupportedOperationException) { }
        for (value in listOf(query, page, page.next, page.entries.first())) {
            assertTrue(value.toString().endsWith("(REDACTED)")); assertFalse(value.toString().contains("synthetic"))
        }
    }
    @Test fun oversizedMetadataIsRejectedBeforeIterationOrCopying() {
        val inaccessible = object : AbstractList<PersonalUpdateWorkRef>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): PersonalUpdateWorkRef = error("Must reject before reading rows")
        }
        rejected { PersonalUpdateWorkPage.fromOrderedMetadata(PersonalUpdateWorkQuery(scope, 1), inaccessible) }
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Must reject") } catch (failure: IllegalArgumentException) {
            assertFalse(failure.message.orEmpty().contains("synthetic")); assertNull(failure.cause)
        }
    }
}
