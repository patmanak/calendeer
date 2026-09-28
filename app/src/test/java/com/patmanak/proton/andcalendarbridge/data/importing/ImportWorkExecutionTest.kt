package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportWorkExecutionTest {
    @Test fun interruptedProjectionRemainsPendingAndNextRunDrainsIt() = runBlocking {
        var pending = true
        var providerAvailable = false
        var attempts = 0
        suspend fun attempt() = executeImportWork({ true }, {
            attempts++
            if (!providerAvailable) throw CalendarProjectionException(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            pending = false
            ImportRunResult.COMPLETE
        }, { error("No continuation needed") })
        assertEquals(ImportRunResult.RETRY, attempt())
        assertTrue(pending)
        providerAvailable = true
        assertEquals(ImportRunResult.COMPLETE, attempt())
        assertFalse(pending)
        assertEquals(2, attempts)
    }

    @Test fun transientFailureDuringReadinessAlsoRetriesWithoutStartingImport() = runBlocking {
        assertEquals(ImportRunResult.RETRY, executeImportWork(
            { throw CalendarProjectionException(CalendarProjectionFailure.PROVIDER_UNAVAILABLE) },
            { error("Import must not start") }, { error("No continuation") }))
    }

    @Test fun unresolvedAuthenticationRetriesAndSignedOutStopsWithoutImport() = runBlocking {
        for ((ready, expected) in listOf(null to ImportRunResult.RETRY, false to ImportRunResult.COMPLETE)) {
            assertEquals(expected, executeImportWork({ ready }, { error("No import") }, { error("No continuation") }))
        }
    }

    @Test fun boundedBatchSchedulesExactlyOneContinuationBeforeSuccess() = runBlocking {
        var continuations = 0
        assertEquals(ImportRunResult.COMPLETE, executeImportWork({ true }, { ImportRunResult.CONTINUE }, { continuations++ }))
        assertEquals(1, continuations)
    }

    @Test fun retryDoesNotAlsoAppendAContinuation() = runBlocking {
        assertEquals(ImportRunResult.RETRY, executeImportWork({ true }, { ImportRunResult.RETRY }, { error("No continuation") }))
    }

    @Test fun cancellationAtEveryBoundaryPropagatesUnchanged() = runBlocking {
        for (stage in 0..2) {
            val cancelled = CancellationException("Fixture cancellation")
            try {
                executeImportWork({ if (stage == 0) throw cancelled else true },
                    { if (stage == 1) throw cancelled else ImportRunResult.CONTINUE }, { throw cancelled })
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        }
    }

    @Test fun permissionsAndIdentityFailuresAreNeverRetriedByTheBoundary() = runBlocking {
        for (category in listOf(CalendarProjectionFailure.PERMISSION_REQUIRED,
            CalendarProjectionFailure.IDENTITY_CONFLICT, CalendarProjectionFailure.ACCOUNT_UNAVAILABLE)) {
            val blocked = CalendarProjectionException(category)
            try {
                executeImportWork({ true }, { throw blocked }, { error("No continuation") })
                fail("Expected failure")
            } catch (actual: CalendarProjectionException) { assertSame(blocked, actual) }
        }
    }

    @Test fun programmingErrorsAreNotConvertedIntoEndlessRetries() = runBlocking {
        val failure = IllegalStateException("Fixture invariant")
        try {
            executeImportWork({ true }, { throw failure }, { error("No continuation") })
            fail("Expected failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }
}
