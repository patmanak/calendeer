// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal fun interface OccurrenceCreateCryptography {
    suspend fun prepare(session: CatalogSession, intent: LocalCreateIntent, id: MutationId,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject): CreateAttemptRecord
}

/** Inactive extension of CREATE. The caller's journal owns final native/SQL admission.
 * UNKNOWN submission is inert. Its separate inspector has no journal or transport permit. */
internal class ProtonOccurrenceCreateAdapter(private val sessions: CatalogSessionSource,
    private val context: EventContextRefresher, private val metadata: CreateCalendarMetadataRemote,
    private val events: EventImportRemote, private val remote: CreateSubmissionRemote,
    private val crypto: OccurrenceCreateCryptography, private val verifier: SeriesFamilyEventVerifier,
    private val protector: EventPayloadProtector, private val childVerifier: OccurrenceChildVerifier) {
    private val codec = ProtonOccurrenceSubmissionCodec(protector)
    private val family = ProtonCreatedEventReadback(sessions, remote, events, CreateEventVerifier { _, _, _, _, _, _ -> null }, verifier)

    suspend fun prepare(draft: CalendarCreateDraft): PreparedCalendarCreate = guarded {
        val frozen = draft.frozen()
        val intent = requireNotNull(frozen.occurrence)
        val candidate = requireNotNull(intent.occurrence)
        val master = candidate.master
        if (!ProtonEventColourPolicy.accepts(intent.content.colorRgb)) createUnavailable(CreatePreparationFailure.UNSUPPORTED)
        ProtonSharedTextPatcher.isPersonalSingleOccurrence(candidate)
        val session = session(frozen.scope)
        context.refresh(session); checkSession(session)
        family.requireSeries(session, frozen.scope, master) { checkSession(session) }
        val calendar = metadata.calendar(session, frozen.scope.calendar.value); checkSession(session)
        val row = remote.event(session, frozen.scope.calendar.value, master.eventId.value); checkSession(session)
        val bootstrap = events.bootstrap(session, frozen.scope.calendar.value); checkSession(session)
        val record = crypto.prepare(session, intent, frozen.correlation, calendar, bootstrap, row)
        checkSession(session)
        require(record.id == frozen.correlation && record.scope == frozen.scope && record.format == OCCURRENCE_CREATE_SUBMISSION_FORMAT &&
            record.state == CreateAttemptState.PREPARED && record.protectedEvidence == null)
        codec.open(record, intent)
        family.requireSeries(session, frozen.scope, master) { checkSession(session) }
        checkSession(session)
        PreparedCalendarCreate(record.scope, record.format, record.protectedPayload)
    }

    suspend fun submit(record: CreateAttemptRecord, draft: CalendarCreateDraft,
        journal: CreateAttemptJournal): CreateSubmissionObservation = guarded {
        val frozen = draft.frozen()
        val intent = requireNotNull(frozen.occurrence)
        require(record.id == frozen.correlation && record.scope == frozen.scope && record.format == OCCURRENCE_CREATE_SUBMISSION_FORMAT)
        val stored = requireNotNull(journal.read(record.scope, record.id))
        require(stored.copy(state = record.state, protectedEvidence = record.protectedEvidence) == record)
        val original = codec.open(stored, intent)
        if (stored.state == CreateAttemptState.OUTCOME_UNKNOWN) return@guarded CreateSubmissionObservation.Unknown
        require(stored.protectedEvidence == null)
        val session = session(record.scope)
        val receipt = prepare(frozen)
        val fresh = codec.open(CreateAttemptRecord(record.id, record.scope, receipt.format, receipt.protectedPayload), intent)
        require(original.context == fresh.context && original.source == fresh.source && original.notificationDefaults == fresh.notificationDefaults)
        checkSession(session)
        var response: String? = null
        val allowsTransport = currentCreateTransportGuard(journal, record.scope)
        val admitted = CreateAttemptAdmission(journal).dispatch(stored) {
            checkSession(session)
            if (!allowsTransport()) throw ProtonSingleDispatchDenied()
            response = remote.sendOccurrence(session, record.scope.calendar.value, original.request, allowsTransport)
        }
        if (!admitted) return@guarded CreateSubmissionObservation.Unknown
        response?.let { body ->
            val evidence = protector.seal(buildJsonObject {
                put("version", 1); put("account", record.scope.account.value); put("calendar", record.scope.calendar.value)
                put("generation", record.scope.generation); put("correlation", record.id.value); put("body", body)
            }.toString())
            journal.retainEvidence(stored.copy(state = CreateAttemptState.OUTCOME_UNKNOWN), evidence)
        }
        checkSession(session)
        CreateSubmissionObservation.Unknown
    }

    suspend fun inspect(record: CreateAttemptRecord, draft: CalendarCreateDraft): CreateSubmissionObservation {
        require(record.state == CreateAttemptState.OUTCOME_UNKNOWN && record.id == draft.correlation && record.scope == draft.scope)
        val retained = codec.open(record, requireNotNull(draft.occurrence))
        val candidate = requireNotNull(retained.intent.occurrence)
        val master = candidate.master
        val uid = requireNotNull(master.content.recurrence).uid
        val calendar = record.scope.calendar.value
        val hint = record.protectedEvidence?.let { sealed ->
            val plain = protector.open(sealed)
            require(plain.length <= 2_097_152 && UniqueJsonKeys(plain).validate())
            val evidence = Json.parseToJsonElement(plain).jsonObject
            require(evidence.number("version") == 1L && evidence.string("account") == record.scope.account.value &&
                evidence.string("calendar") == calendar && evidence.number("generation") == record.scope.generation &&
                evidence.string("correlation") == record.id.value)
            when (val response = ProtonCreateResponseParser.parse(evidence.string("body"), calendar, uid)) {
                is ProtonCreateResponse.Candidate -> response.event.string("ID")
                is ProtonCreateResponse.ReportedFailure -> return CreateSubmissionObservation.Unknown
                ProtonCreateResponse.Unknown -> null
            }
        }
        val session = session(record.scope)
        suspend fun members() = family.uidFamily(session, record.scope, uid, 2) { checkSession(session) }
        val ids = members() ?: return CreateSubmissionObservation.Unknown
        val masterId = master.eventId.value
        if (ids.size != 2 || masterId !in ids) return CreateSubmissionObservation.Unknown
        val childId = (ids - masterId).single()
        if (hint != null && hint != childId) return CreateSubmissionObservation.Unknown
        val source = remote.event(session, calendar, masterId)
        checkSession(session)
        if (source != retained.source) return CreateSubmissionObservation.Unknown
        val child = remote.event(session, calendar, childId)
        checkSession(session)
        require(child.string("ID") == childId && child.string("CalendarID") == calendar && child.string("UID") == uid)
        qualifyOccurrenceChild(child, retained)
        val bootstrap = events.bootstrap(session, calendar)
        checkSession(session)
        if (verifier.verify(session, calendar, bootstrap, source, uid) != master) return CreateSubmissionObservation.Unknown
        checkSession(session)
        val actual = childVerifier.verify(session, calendar, bootstrap, child, uid) ?: return CreateSubmissionObservation.Unknown
        checkSession(session)
        if (actual.eventId.value != childId || normalizedCreateReadback(actual.content) != normalizedCreateReadback(candidate.content) || members() != ids)
            return CreateSubmissionObservation.Unknown
        return CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), actual.copy(content = candidate.content))
    }

    private suspend fun session(scope: CalendarScope) = sessions.current()?.takeIf { it.userId.id == scope.account.value }
        ?: createUnavailable(CreatePreparationFailure.AUTHENTICATION)
    private suspend fun checkSession(expected: CatalogSession) {
        currentCoroutineContext().ensureActive()
        if (sessions.current() != expected) createUnavailable(CreatePreparationFailure.AUTHENTICATION)
    }
    private suspend fun <T> guarded(block: suspend () -> T): T = try { currentCoroutineContext().ensureActive(); block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            when (failure) {
            is CreatePreparationException -> throw failure
            is ProtonPersonalPatchException -> createUnavailable(CreatePreparationFailure.UNSUPPORTED)
            is LocalEditPreparationException ->
            createUnavailable(if (failure.reason == LocalEditPreparationFailure.UNSUPPORTED) CreatePreparationFailure.UNSUPPORTED else CreatePreparationFailure.CONTEXT)
            is RemoteImportException ->
            createUnavailable(when (failure.reason) {
                RemoteImportFailure.AUTHENTICATION -> CreatePreparationFailure.AUTHENTICATION
                RemoteImportFailure.NETWORK -> CreatePreparationFailure.NETWORK
                RemoteImportFailure.RETRY -> CreatePreparationFailure.RETRY
                RemoteImportFailure.LOCAL_KEYS -> CreatePreparationFailure.LOCAL_KEYS
                RemoteImportFailure.UNSUPPORTED -> CreatePreparationFailure.UNSUPPORTED
                else -> CreatePreparationFailure.CONTEXT
            })
            else -> createUnavailable(CreatePreparationFailure.CONTEXT)
            }
        }
}
