// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.charset.CharacterCodingException
import org.junit.Assert.assertThrows
import org.junit.Test

/** Lookup input only; no database, account or network access. */
class AttemptJournalReferenceTest {
    private val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0)
    private val id = MutationId("fixture-request")
    private fun references(value: String) = listOf(
        scope.copy(account = AccountId(value)) to id,
        scope.copy(calendar = CalendarId(value)) to id,
        scope to MutationId(value),
    )

    @Test fun exactByteBudgetAcceptsAsciiAccentsAndSupplementaryUnicodeInEveryField() {
        for (value in listOf("x".repeat(4096), "é".repeat(2048), "\uD83E\uDD8C".repeat(1024))) {
            for ((target, request) in references(value)) requireAttemptJournalReference(target, request, 4096)
        }
    }

    @Test fun characterCountCannotHideAnOversizedUtf8Reference() {
        for (value in listOf("x".repeat(4097), "é".repeat(2049), "\uD83E\uDD8C".repeat(1025))) {
            for ((target, request) in references(value)) {
                assertThrows(IllegalArgumentException::class.java) { requireAttemptJournalReference(target, request, 4096) }
            }
        }
    }

    @Test fun unpairedSurrogatesAreRejectedRatherThanReplacedInLookupIdentity() {
        for (value in listOf("\uD800", "\uDC00", "prefix-\uD800-suffix")) {
            for ((target, request) in references(value)) {
                assertThrows(CharacterCodingException::class.java) { requireAttemptJournalReference(target, request, 4096) }
            }
        }
    }
}
