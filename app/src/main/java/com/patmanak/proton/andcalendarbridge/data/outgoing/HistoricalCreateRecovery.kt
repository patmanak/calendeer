package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*

enum class HistoricalCreateIssueReason { SEARCHING, MISSING_HISTORY, INCOMPLETE_PROOF }

/** Local search position only; not a request, send permit or proof of absent remote content. */
data class HistoricalCreateRecovery(val work: SystemCreateWork? = null, val after: String? = null,
    val reason: HistoricalCreateIssueReason = HistoricalCreateIssueReason.MISSING_HISTORY) {
    override fun toString() = "HistoricalCreateRecovery(REDACTED)"
}

/** One indexed page per observation. Only the caller's complete historical proof can restore work. */
internal suspend fun recoverHistoricalCreatePage(scope: CalendarScope, row: Long, marker: String?,
    candidates: List<LocalEventId>, limit: Int, key: (LocalEventId) -> String?,
    qualify: suspend (LocalEventId) -> SystemCreateWork?): HistoricalCreateRecovery {
    require(row > 0 && limit in 1..50 && candidates.size <= limit)
    if (marker.isNullOrBlank()) return HistoricalCreateRecovery(reason = HistoricalCreateIssueReason.INCOMPLETE_PROOF)
    val matches = candidates.filter { key(it) == marker }
    if (matches.size > 1) return HistoricalCreateRecovery(reason = HistoricalCreateIssueReason.INCOMPLETE_PROOF)
    if (matches.size == 1) {
        val id = matches.single()
        val work = qualify(id)
        return if (work != null && work.scope == scope && work.row == row && work.localId == id &&
            work.status == SystemCreateWorkStatus.COMPLETE) HistoricalCreateRecovery(work)
        else HistoricalCreateRecovery(reason = HistoricalCreateIssueReason.INCOMPLETE_PROOF)
    }
    return if (candidates.size == limit) HistoricalCreateRecovery(after = candidates.last().value,
        reason = HistoricalCreateIssueReason.SEARCHING) else HistoricalCreateRecovery()
}
