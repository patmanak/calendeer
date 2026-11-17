package com.patmanak.proton.andcalendarbridge.data.importing

import kotlinx.coroutines.CancellationException

enum class ImportRunResult { COMPLETE, CONTINUE, RETRY }

/** A drained work queue alone says nothing about the outcome of its network synchronization. */
internal data class CalendarPassResult(val work: ImportRunResult, val networkSynchronized: Boolean = false,
    val outgoingFailed: Boolean = false)

/** The durable queue owns work; success is returned only after its continuation is scheduled. */
internal suspend fun executeImportWork(
    ready: suspend () -> Boolean?,
    run: suspend () -> CalendarPassResult,
    continueWork: (outgoingFailed: Boolean) -> Unit,
    recordNetworkSuccess: suspend () -> Unit = {},
    previousOutgoingFailure: Boolean = false,
): ImportRunResult {
    return try {
        when (ready()) {
            null -> ImportRunResult.RETRY
            false -> ImportRunResult.COMPLETE
            true -> {
                val result = run()
                val outgoingFailed = previousOutgoingFailure || result.outgoingFailed
                when (result.work) {
                    ImportRunResult.CONTINUE -> { continueWork(outgoingFailed); ImportRunResult.COMPLETE }
                    ImportRunResult.COMPLETE -> {
                        if (result.networkSynchronized && !outgoingFailed) recordNetworkSuccess()
                        ImportRunResult.COMPLETE
                    }
                    ImportRunResult.RETRY -> ImportRunResult.RETRY
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (retryableImportFailure(failure)) ImportRunResult.RETRY else throw failure
    }
}
