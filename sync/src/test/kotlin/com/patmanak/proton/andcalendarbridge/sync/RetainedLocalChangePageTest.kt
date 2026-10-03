// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test

class RetainedLocalChangePageTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0)
    private fun ref(event: String, kind: MutationKind = MutationKind.UPDATE, state: LocalEditState = LocalEditState.PAUSED) =
        RetainedLocalChangeRef(EventId(event), MutationId("mutation-$event"), kind, state)

    @Test fun boundedPageRetainsMetadataAndContinuesAfterLastReturnedRow() {
        val rows = listOf(ref("a"), ref("b", MutationKind.DELETE, LocalEditState.CONFLICT), ref("c"))
        val page = RetainedLocalChangePage.fromOrderedMetadata(RetainedLocalChangeQuery(scope, 2), rows)
        assertEquals(rows.take(2), page.entries)
        assertEquals(RetainedLocalChangeCursor(scope, EventId("b")), page.next)
    }

    @Test fun exactOrEmptyPageHasNoSpeculativeContinuation() {
        for (rows in listOf(emptyList(), listOf(ref("a"), ref("b")))) {
            val page = RetainedLocalChangePage.fromOrderedMetadata(RetainedLocalChangeQuery(scope, 2), rows)
            assertEquals(rows, page.entries); assertNull(page.next)
        }
    }

    @Test fun limitsAndCursorOwnershipAreValidatedBeforeUse() {
        for (limit in listOf(-1, 0, 51, Int.MAX_VALUE)) rejected { RetainedLocalChangeQuery(scope, limit) }
        for (other in listOf(scope.copy(account = AccountId("foreign")), scope.copy(calendar = CalendarId("foreign")), scope.copy(generation = 1))) {
            rejected { RetainedLocalChangeQuery(scope, cursor = RetainedLocalChangeCursor(other, EventId("a"))) }
        }
        rejected { RetainedLocalChangeQuery(scope, cursor = RetainedLocalChangeCursor(scope, EventId("a"), 2)) }
        assertEquals(50, RetainedLocalChangeQuery(scope).limit)
        assertEquals(1, RetainedLocalChangeQuery(scope, 1).limit)
    }

    @Test fun identitiesHaveStrictUtf8ByteAndControlLimits() {
        for (value in listOf(" ", "nul\u0000", "line\n", "x".repeat(4097), "é".repeat(2049), "\uD800")) {
            rejected { RetainedLocalChangeRef(EventId(value), MutationId("synthetic"), MutationKind.UPDATE, LocalEditState.PAUSED) }
            rejected { RetainedLocalChangeRef(EventId("synthetic"), MutationId(value), MutationKind.DELETE, LocalEditState.CONFLICT) }
            rejected { RetainedLocalChangeCursor(scope, EventId(value)) }
        }
        assertEquals("é".repeat(2048), RetainedLocalChangeCursor(scope, EventId("é".repeat(2048))).afterEvent.value)
        rejected { RetainedLocalChangeQuery(scope.copy(account = AccountId("nul\u0000"))) }
        rejected { RetainedLocalChangeQuery(scope.copy(calendar = CalendarId("é".repeat(2049)))) }
    }

    @Test fun pageKeepsSuppliedSqlOrderInsteadOfJvmOrdering() {
        // SQLite BINARY places U+E000 before U+10000; UTF-16 String ordering does the reverse.
        val rows = listOf(ref("\uE000"), ref("\uD800\uDC00"))
        val page = RetainedLocalChangePage.fromOrderedMetadata(RetainedLocalChangeQuery(scope, 1), rows)
        assertEquals(rows.take(1), page.entries)
        assertEquals(rows.first().eventId, requireNotNull(page.next).afterEvent)
    }

    @Test fun invalidRowsCannotBeHiddenInTheLookahead() {
        rejected { ref("a", MutationKind.CREATE) }
        rejected { RetainedLocalChangePage.fromOrderedMetadata(RetainedLocalChangeQuery(scope, 1), listOf(ref("a"), ref("a"))) }
        rejected { RetainedLocalChangePage.fromOrderedMetadata(RetainedLocalChangeQuery(scope, 1), listOf(ref("a"), ref("b"), ref("c"))) }
        rejected { RetainedLocalChangePage.fromOrderedMetadata(RetainedLocalChangeQuery(scope, 1),
            listOf(ref("a"), ref("b").copy(mutationId = MutationId("mutation-a")))) }
    }

    @Test fun pageOwnsItsListAndAllDiagnosticsAreRedacted() {
        val rows = mutableListOf(ref("private-synthetic-a"), ref("private-synthetic-b"))
        val query = RetainedLocalChangeQuery(scope, 1)
        val page = RetainedLocalChangePage.fromOrderedMetadata(query, rows)
        rows.clear(); assertEquals(1, page.entries.size)
        try { (page.entries as MutableList).clear(); fail("Must remain immutable") } catch (_: UnsupportedOperationException) { }
        for (value in listOf(query, page, page.next, page.entries.first())) {
            assertTrue(value.toString().endsWith("(REDACTED)"))
            assertFalse(value.toString().contains("synthetic"))
        }
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("Must reject") } catch (failure: IllegalArgumentException) {
            assertFalse(failure.message.orEmpty().contains("synthetic")); assertNull(failure.cause)
        }
    }
}
