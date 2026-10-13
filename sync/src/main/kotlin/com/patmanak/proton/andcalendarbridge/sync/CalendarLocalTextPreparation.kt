// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant

/** Fresh authenticated preparation of title, description and location changes.
 * Freeze the complete supported paused intent before suspension and verify the full observed
 * original, session and current rights. Uses [LocalEditPreparationFailure] for sanitized failures;
 * cancellation propagates. Success grants no upload, journal or account/key-setup authority.
 * Reads are successive observations, not an atomic snapshot or a concurrent-write precondition.
 * This separate capability never widens historical title formats or consent. The caller owns
 * scope generation, mode, intent lifetime, admission and durable retention.
 */
fun interface CalendarLocalTextPreparationPort {
    /** Local declaration only; null or a changed format requires refusal/reconciliation. */
    val localTextFormat: AdapterFormat? get() = null
    suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant): LocalTextEditPreparation

    /** Checks the caller's original access lease around controlled context and crypto stages.
     * A denied lease returns null, and cannot reopen within this call. Unsupported implementations
     * deny without calling the unrestricted overload. The lease expires when the operation ends.
     */
    suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant,
        admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? = null
}
