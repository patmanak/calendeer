package com.patmanak.proton.andcalendarbridge.sync.catalog

import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId

/** Discovery metadata only: presence in this list does not establish event-key or sync readiness. */
data class CalendarSummary(val id: CalendarId, val title: String, val colorRgb: Int? = null) {
    init { require(colorRgb == null || colorRgb in 0..0xFFFFFF) }
    override fun toString(): String = "CalendarSummary(REDACTED)"
}

enum class CalendarCatalogFailure {
    AUTHENTICATION_REQUIRED, UNREACHABLE, TIMED_OUT, RATE_LIMITED, FORBIDDEN,
    UNSUPPORTED_RESPONSE, TRY_AGAIN,
}

sealed interface CalendarCatalogResult {
    data class Success(val account: AccountId, val calendars: List<CalendarSummary>) : CalendarCatalogResult {
        override fun toString(): String = "CalendarCatalogResult.Success(count=${calendars.size})"
    }
    data class Failure(val category: CalendarCatalogFailure, val retryAfterMillis: Long? = null) : CalendarCatalogResult {
        init { require(retryAfterMillis == null || retryAfterMillis >= 0) }
    }
}

/**
 * Loads the complete discovery catalog of the currently authenticated identity. A transport,
 * authorization, parsing or traversal failure MUST be Failure, never an empty successful list.
 * Implementations own server pagination/bounds and preserve opaque identifiers. If a complete
 * traversal cannot be established within those bounds, return UNSUPPORTED_RESPONSE, not partial
 * success. No event download, account setup or remote mutation is permitted here.
 *
 * Bind every request and result to the active session and serialize against sign-out/account
 * replacement. Propagate CancellationException. No tokens, raw bodies or server exception text
 * cross this boundary. Success asserts metadata discovery only, not Calendar bootstrap/readiness.
 */
fun interface CalendarCatalogPort {
    suspend fun load(): CalendarCatalogResult
}
