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
    private val titlePort: CalendarTitleUpdateSubmissionPort,
) : SystemTitleUpdateOperations {
    override suspend fun discover(query: RetainedLocalChangeQuery): RetainedLocalChangeListing {
        currentCoroutineContext().ensureActive()
        return RetainedLocalChangeListing.Available(RetainedLocalChangePage.fromOrderedMetadata(query, emptyList()))
    }

    override suspend fun process(scope: CalendarScope, reference: RetainedLocalChangeRef, attempt: MutationId,
        timestamp: Instant, admission: (CalendarScope) -> Boolean, proofRetained: () -> Unit): Boolean {
        currentCoroutineContext().ensureActive()
        // Retired writers never prepare, claim or resend. Historical inspection/ACK stays below.
        return false
    }

    override suspend fun inspect(query: TitleUpdateWorkQuery, admission: (CalendarScope) -> Boolean,
        proofRetained: () -> Unit) = imports.inspectSystemTitles(query, journal, titlePort, admission, proofRetained)
}
