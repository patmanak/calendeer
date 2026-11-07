// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant

/** Prepare a frozen personal event v2 edit against the complete authenticated imported base.
 * Combined text/title/time and kind/zone transitions; unchanged personal reminders/color. LocalEditPreparationFailure is sanitized;
 * cancellation propagates. Success grants no upload, journal or native write authority.
 * Reads are successive observations; they do not supply an atomic server precondition.
 */
fun interface CalendarPersonalPreparationPort {
    val personalFormat: AdapterFormat? get() = null
    suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant): PersonalEditPreparation

    /** The original caller lease must stay open around every suspension and crypto stage.
     * Once denied, it never reopens during this call. Unsupported implementations deny
     * without calling the unrestricted overload. Caller owns generation, consent and retention.
     */
    suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant,
        admit: (CalendarScope) -> Boolean): PersonalEditPreparation? = null
}
