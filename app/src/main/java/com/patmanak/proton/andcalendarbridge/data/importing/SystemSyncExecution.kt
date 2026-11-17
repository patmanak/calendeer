// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

internal enum class SystemSyncOutcome { SUCCESS, FINISHED_WITHOUT_SYNC, RETRY, AUTH, PERMISSION, STALE, DISABLED }

/** Only unsolicited Android push requires the native automatic-sync switches. */
internal fun requiresPushSettings(manual: Boolean, pull: Boolean): Boolean = !manual && !pull

/** A manual request owns its bypass. Automatic sends consult the same switches again at dispatch. */
internal suspend fun executeCalendarPass(manual: Boolean, automaticAllowed: () -> Boolean,
    incoming: suspend () -> ImportRunResult,
    outgoing: suspend (pushAllowed: () -> Boolean) -> ImportRunResult): ImportRunResult {
    fun pushAllowed() = manual || automaticAllowed()
    val sent = if (pushAllowed()) outgoing(::pushAllowed) else ImportRunResult.COMPLETE
    val received = incoming()
    return when {
        received == ImportRunResult.RETRY || sent == ImportRunResult.RETRY -> ImportRunResult.RETRY
        received == ImportRunResult.CONTINUE || sent == ImportRunResult.CONTINUE -> ImportRunResult.CONTINUE
        else -> ImportRunResult.COMPLETE
    }
}

/** A failed/interrupted wait is not authority to cancel the independent executor. */
internal fun relaySystemSync(enqueue: () -> Unit, await: () -> SystemSyncOutcome): SystemSyncOutcome = try {
    enqueue()
    await()
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    SystemSyncOutcome.RETRY
} catch (_: Exception) {
    SystemSyncOutcome.RETRY
}

/** A system run owns all its pages, rather than acknowledging a hidden successor. */
internal suspend fun executeSystemSync(
    admit: suspend () -> SystemSyncOutcome?,
    pass: suspend (first: Boolean) -> CalendarPassResult,
    maxPasses: Int = 16,
): SystemSyncOutcome {
    require(maxPasses > 0)
    var outgoingFailed = false
    repeat(maxPasses) { index ->
        admit()?.let { return it }
        val result = pass(index == 0)
        outgoingFailed = outgoingFailed || result.outgoingFailed
        when (result.work) {
            ImportRunResult.COMPLETE -> return admit() ?: if (result.networkSynchronized && !outgoingFailed)
                SystemSyncOutcome.SUCCESS else SystemSyncOutcome.FINISHED_WITHOUT_SYNC
            ImportRunResult.RETRY -> return SystemSyncOutcome.RETRY
            ImportRunResult.CONTINUE -> Unit
        }
    }
    return SystemSyncOutcome.RETRY
}
