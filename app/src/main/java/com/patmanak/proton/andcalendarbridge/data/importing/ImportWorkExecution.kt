package com.patmanak.proton.andcalendarbridge.data.importing

import kotlinx.coroutines.CancellationException

enum class ImportRunResult { COMPLETE, CONTINUE, RETRY }

/** The durable queue owns work; success is returned only after its continuation is scheduled. */
internal suspend fun executeImportWork(
    ready: suspend () -> Boolean?,
    run: suspend () -> ImportRunResult,
    continueWork: () -> Unit,
): ImportRunResult {
    return try {
        when (ready()) {
            null -> ImportRunResult.RETRY
            false -> ImportRunResult.COMPLETE
            true -> when (val result = run()) {
                ImportRunResult.CONTINUE -> { continueWork(); ImportRunResult.COMPLETE }
                else -> result
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (retryableImportFailure(failure)) ImportRunResult.RETRY else throw failure
    }
}
