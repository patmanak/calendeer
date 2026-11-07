package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoricalCreateRecoveryTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0)
    private val local = LocalEventId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val retained = SystemCreateWork(scope, 42, local,
        MutationId("11111111-1111-1111-1111-111111111111"),
        MutationId("22222222-2222-2222-2222-222222222222"), Instant.EPOCH, SystemCreateWorkStatus.COMPLETE)

    @Test fun missingWorkRestoresOnlyExactProvenHistoricalOwner() = runBlocking {
        var reads = 0
        val result = recoverHistoricalCreatePage(scope, 42, "marked", listOf(local), 25,
            { "marked" }, { reads++; retained })
        assertEquals(retained, result.work)
        assertEquals(1, reads)
    }

    @Test fun unrelatedOrAbsentMarkersNeverReadSubmissionOrAllocateIdentity() = runBlocking {
        for (marker in listOf(null, "", "unrelated")) {
            val result = recoverHistoricalCreatePage(scope, 42, marker, listOf(local), 25,
                { "marked" }, { error("Unrelated markers must not qualify work") })
            assertNull(result.work)
        }
    }

    @Test fun partialCorruptOrUnqualifiedProofIsActionableWithoutAReplacementRequest() = runBlocking {
        val result = recoverHistoricalCreatePage(scope, 42, "marked", listOf(local), 25,
            { "marked" }, { null })
        assertNull(result.work)
        assertEquals(HistoricalCreateIssueReason.INCOMPLETE_PROOF, result.reason)
        assertNull(result.after)
    }

    @Test fun wrongScopeRowLocalIdentityOrNonCompleteOwnerCannotBeRestored() = runBlocking {
        for (wrong in listOf(retained.copy(scope = scope.copy(generation = 1)), retained.copy(row = 43),
            retained.copy(localId = LocalEventId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")), retained.copy(status = SystemCreateWorkStatus.UNKNOWN))) {
            assertNull(recoverHistoricalCreatePage(scope, 42, "marked", listOf(local), 25,
                { "marked" }, { wrong }).work)
        }
    }

    @Test fun boundedTraversalRetainsPositionAndRestartsAfterTerminalPage() = runBlocking {
        val first = recoverHistoricalCreatePage(scope, 42, "marked", listOf(LocalEventId("cccccccc-cccc-cccc-cccc-cccccccccccc")), 1,
            { "other" }, { error("No matching identity") })
        assertEquals("cccccccc-cccc-cccc-cccc-cccccccccccc", first.after)
        assertEquals(HistoricalCreateIssueReason.SEARCHING, first.reason)
        val terminal = recoverHistoricalCreatePage(scope, 42, "marked", emptyList(), 1,
            { "other" }, { error("No matching identity") })
        assertNull(terminal.after)
        assertEquals(HistoricalCreateIssueReason.MISSING_HISTORY, terminal.reason)
    }

    @Test fun ambiguousKeysRefuseAllCandidates() = runBlocking {
        val result = recoverHistoricalCreatePage(scope, 42, "marked", listOf(local, LocalEventId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")), 25,
            { "marked" }, { error("Ambiguous marker") })
        assertNull(result.work)
        assertEquals(HistoricalCreateIssueReason.INCOMPLETE_PROOF, result.reason)
    }
}
