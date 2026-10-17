// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*

/** Ordinary exact tombstones only. No UPDATE or transferred-CREATE consent is borrowed. */
interface SystemDeleteLocalOwner {
    suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
        admit: (CalendarScope) -> Boolean): LocalDeleteIntent?
    suspend fun reserve(record: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun submit(record: DeleteAttemptRecord, journal: DeleteRecoveryJournal,
        port: CalendarDeleteSubmissionPort, admit: (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation
    suspend fun conflict(intent: LocalDeleteIntent, observed: RemoteChange.Upsert,
        admit: (CalendarScope) -> Boolean): Boolean
    suspend fun acknowledge(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
        admit: (CalendarScope) -> Boolean): Boolean
    suspend fun complete(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
        admit: (CalendarScope) -> Boolean): Boolean
}
