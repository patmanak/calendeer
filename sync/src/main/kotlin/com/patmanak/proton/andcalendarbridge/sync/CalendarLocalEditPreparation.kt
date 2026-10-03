// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant

enum class LocalEditPreparationFailure { AUTHENTICATION, PERMISSION, CONTEXT, CONFLICT, NOT_FOUND, LOCAL_KEYS, UNSUPPORTED, STORAGE, NETWORK, RETRY }
class LocalEditPreparationException(val reason: LocalEditPreparationFailure) :
    IllegalStateException("Calendar local edit preparation: ${reason.name}")

/** Fresh authenticated reads followed by a protected, title-only preparation.
 * Freeze the paused intent before suspension, verify the observed base and current local session,
 * and refuse changed/unsupported context. Cancellation propagates; failures expose no private data.
 * Reads are successive observations, not a server snapshot or concurrent-write precondition.
 * No remote mutation, account/key setup, journal commit, dispatch admission or writable capability.
 * The caller still owns scope generation, calendar mode, intent lifetime and retention admission.
 */
fun interface CalendarLocalEditPreparationPort {
    /** Local declaration only. Null refuses preparation; a change requires explicit reconciliation. */
    val localTitleFormat: AdapterFormat? get() = null
    suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant): LocalEditPreparation
}
