// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A qualification port for one frozen occurrence and one attempt. Never advertised by the system worker. */
internal class SerializedOccurrenceQualification(private val dispatcher: CoroutineDispatcher, private val lock: Mutex,
    private val authenticated: () -> Boolean, private val runtime: () -> ProtonAuthenticationGraph?) {
    fun forAttempt(intent: LocalCreateIntent, attempt: MutationId, accountHash: String): CalendarCreateSubmissionPort {
        require(accountHash.matches(Regex("[a-f0-9]{64}")))
        val frozen = LocalCreateSubmissionReservation.forOccurrence(intent, attempt, OCCURRENCE_CREATE_SUBMISSION_FORMAT).draft.frozen()
        suspend fun <T> call(operation: suspend (CalendarCreateSubmissionPort) -> T): T = withContext(dispatcher) {
            lock.withLock {
                val graph = requireNotNull(runtime())
                check(authenticated() && graph.matchesIdentityHash(frozen.scope.account, accountHash))
                operation(requireNotNull(graph.occurrenceQualification).invoke())
            }
        }
        fun matches(record: CreateAttemptRecord, expected: CalendarCreateDraft) = record.scope == frozen.scope && record.id == attempt &&
            record.format == OCCURRENCE_CREATE_SUBMISSION_FORMAT && expected == frozen
        return object : CalendarCreateSubmissionPort {
            override val occurrenceSubmissionFormat = OCCURRENCE_CREATE_SUBMISSION_FORMAT
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate {
                require(draft == frozen)
                return call { it.prepareSubmission(frozen) }
            }
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal) = CreateSubmissionObservation.Unknown
            override suspend fun inspect(record: CreateAttemptRecord) = CreateSubmissionObservation.Unknown
            override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft, journal: CreateAttemptJournal) =
                if (matches(record, expected)) call { it.submitCaptured(record, frozen, journal) } else CreateSubmissionObservation.Unknown
            override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft) =
                if (matches(record, expected)) call { it.inspectCaptured(record, frozen) } else CreateSubmissionObservation.Unknown
            override suspend fun readCapturedExpectation(record: CreateAttemptRecord, expected: CalendarCreateDraft) =
                if (matches(record, expected)) call { it.readCapturedExpectation(record, frozen) } else null
        }
    }
}
