// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Production composition; no mutable SQL/Provider guard is borrowed from the coordinator. */
class CoordinatorSystemTitleOperations(
    private val imports: ImportCoordinator,
    private val journal: TitleUpdateAttemptStore,
    private val capsulePort: CalendarLocalEditPreparationPort,
    private val titlePort: CalendarTitleUpdateSubmissionPort,
) : SystemTitleUpdateOperations {
    override suspend fun discover(query: RetainedLocalChangeQuery) = imports.retainedLocalChanges(query)

    override suspend fun process(scope: CalendarScope, reference: RetainedLocalChangeRef, attempt: MutationId,
        timestamp: Instant, admission: (CalendarScope) -> Boolean, proofRetained: () -> Unit): Boolean {
        if (!admission(scope)) return false
        val prepared = imports.prepareSystemTitle(scope, reference.eventId, reference.mutationId, attempt, timestamp,
            journal, capsulePort, titlePort, admission)
        currentCoroutineContext().ensureActive()
        if (prepared !is LocalTitleAttemptPreparation.Prepared) return prepared == LocalTitleAttemptPreparation.Unavailable
        if (!admission(scope)) return false
        val record = prepared.record
        val before = journal.readVerified(scope, record.id)
        currentCoroutineContext().ensureActive()
        if (!admission(scope) || before != null) return false
        val result = imports.submitSystemTitle(record, journal, titlePort, admission)
        currentCoroutineContext().ensureActive()
        // A committed historical proof survives revocation without authorizing another send.
        val retained = journal.readVerified(scope, record.id)
        currentCoroutineContext().ensureActive()
        if (retained != null && retained.record.copy(state = TitleUpdateAttemptState.PREPARED) == record)
            proofRetained()
        if (result != null) {
            val acknowledged = imports.acknowledgeLocalTitle(scope, reference.eventId, record.id, journal)
            currentCoroutineContext().ensureActive()
            if (acknowledged == TitleUpdateAcknowledgementResult.ACKNOWLEDGED)
                imports.completeLocalTitle(scope, reference.eventId, record.id, journal)
        }
        currentCoroutineContext().ensureActive()
        return false
    }

    override suspend fun inspect(query: TitleUpdateWorkQuery, admission: (CalendarScope) -> Boolean,
        proofRetained: () -> Unit) = imports.inspectSystemTitles(query, journal, titlePort, admission, proofRetained)
}
