// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Local history needed for offline recovery of one bounded work page. This boundary exposes
 * neither remote inspection nor preparation, claim or submission. Read each complete attempt
 * and its retained proof before using metadata discovered by [TitleUpdateWorkJournal.page].
 * PREPARED work and attempts without a durable verified result remain held. Cancellation must
 * propagate without being reported as completed, held or failed work.
 */
interface TitleUpdateOfflineRecoveryJournal : TitleUpdateWorkJournal, TitleUpdateCompletionJournal {
    suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord?
}

/** Same durable journal for page discovery, full attempts and guarded inspection results.
 * Implementing this interface grants no preparation, claim or submission admission.
 */
interface TitleUpdateRecoveryJournal : TitleUpdateOfflineRecoveryJournal, TitleUpdateAttemptJournal

/** Outcome for one bounded current page, never a snapshot or global completion proof.
 * A null continuation does not resolve held/failed attempts. Retry blocked work with a new
 * first-page query; concurrent insertions behind a cursor also require a fresh traversal.
 * Inactive recovery cannot hand out a continuation under the expired access observation.
 */
data class TitleUpdateOfflineRecoveryProgress(
    val completed: Int = 0,
    val held: Int = 0,
    val failed: Int = 0,
    val next: TitleUpdateWorkCursor? = null,
    val inactive: Boolean = false,
) {
    init {
        require(completed >= 0 && held >= 0 && failed >= 0 &&
            completed.toLong() + held.toLong() + failed.toLong() <= TitleUpdateWorkQuery.MAX_PAGE_SIZE) {
            "Invalid offline title recovery counts"
        }
        require(!inactive || next == null) { "Inactive offline title recovery cannot continue" }
    }
    override fun toString() = "TitleUpdateOfflineRecoveryProgress(REDACTED)"
}

/** Shared page accounting for offline completion and optional guarded inspection. */
typealias TitleUpdateRecoveryProgress = TitleUpdateOfflineRecoveryProgress
