// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant

/** Prepare a frozen time-only edit against the complete authenticated imported base.
 * Same-kind/same-zone simple events only. LocalEditPreparationFailure is sanitized;
 * cancellation propagates. Success grants no upload, journal or native write authority.
 * Reads are successive observations; they do not supply an atomic server precondition.
 */
fun interface CalendarLocalTimePreparationPort {
    val localTimeFormat: AdapterFormat? get() = null
    suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant): LocalTimeEditPreparation

    /** The original caller lease must stay open around every suspension and crypto stage.
     * Once denied, it never reopens during this call. Unsupported implementations deny
     * without calling the unrestricted overload. Caller owns generation, consent and retention.
     */
    suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant,
        admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? = null
}
