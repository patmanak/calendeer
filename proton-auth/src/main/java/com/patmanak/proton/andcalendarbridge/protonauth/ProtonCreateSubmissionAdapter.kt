// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.http.*

internal interface CreateSubmissionRemote : CreateReadbackRemote {
    suspend fun send(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String
    suspend fun sendOccurrence(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String = throw ProtonSingleDispatchDenied()
}

internal class ProtonCreateSubmissionAdapter(private val preparations: CalendarCreatePreparationPort,
    private val protector: EventPayloadProtector, private val sessions: CatalogSessionSource,
    private val remote: CreateSubmissionRemote, private val events: EventImportRemote,
    verifier: CreateEventVerifier, private val occurrences: ProtonOccurrenceCreateAdapter? = null) : CalendarCreateSubmissionPort {
    override val knownEmptySubmissionFormat = KNOWN_EMPTY_SUBMISSION_FORMAT
    override val personalSubmissionFormat = PERSONAL_CREATE_SUBMISSION_FORMAT
    override val seriesSubmissionFormat = SERIES_CREATE_SUBMISSION_FORMAT
    override val occurrenceSubmissionFormat get() = occurrences?.let { OCCURRENCE_CREATE_SUBMISSION_FORMAT }
    private val readback = ProtonCreatedEventReadback(sessions, remote, events, verifier)
    override suspend fun prepareSubmission(draft: CalendarCreateDraft) = if (draft.occurrence == null) preparations.prepareCreate(draft)
        else occurrences?.prepare(draft) ?: createUnavailable(CreatePreparationFailure.UNSUPPORTED)
    override suspend fun readCapturedExpectation(record: CreateAttemptRecord, expected: CalendarCreateDraft): EventContent? = try {
        val frozen = expected.frozen()
        if (record.format == OCCURRENCE_CREATE_SUBMISSION_FORMAT) {
            require(record.id == frozen.correlation && record.scope == frozen.scope)
            ProtonOccurrenceSubmissionCodec(protector).open(record, requireNotNull(frozen.occurrence)).intent.occurrence!!.content
        } else {
        val submission = ProtonCreateSubmissionCodec.open(record, protector)
        qualifyCaptured(record, submission, frozen)
        frozen.canonicalContent(submission.uid)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation =
        submitWithExpectation(record, journal, null)
    override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft,
        journal: CreateAttemptJournal): CreateSubmissionObservation = submitWithExpectation(record, journal, expected)

    private suspend fun submitWithExpectation(record: CreateAttemptRecord, journal: CreateAttemptJournal,
        suppliedExpected: CalendarCreateDraft?): CreateSubmissionObservation = guarded {
        val expected = suppliedExpected?.frozen()
        val stored = journal.read(record.scope, record.id) ?: createUnavailable(CreatePreparationFailure.CONTEXT)
        if (stored.format != record.format || stored.protectedPayload != record.protectedPayload)
            createUnavailable(CreatePreparationFailure.CONTEXT)
        if (stored.format == OCCURRENCE_CREATE_SUBMISSION_FORMAT) {
            if (stored.state == CreateAttemptState.OUTCOME_UNKNOWN) return@guarded CreateSubmissionObservation.Unknown
            return@guarded occurrences?.submit(stored, requireNotNull(expected), journal) ?: CreateSubmissionObservation.Unknown
        }
        if (stored.state == CreateAttemptState.OUTCOME_UNKNOWN) return@guarded inspectWithExpectation(stored, expected)
        val submission = ProtonCreateSubmissionCodec.open(stored, protector)
        qualifyCaptured(stored, submission, expected)
        val session = session(record.scope)
        // Requalify keys, rights and scope using authenticated reads immediately before admission.
        val freshReceipt = preparations.prepareCreate(submission.draft)
        if (freshReceipt.scope != record.scope || freshReceipt.format != record.format)
            createUnavailable(CreatePreparationFailure.CONTEXT)
        val fresh = ProtonCreateSubmissionCodec.open(CreateAttemptRecord(record.id, record.scope, freshReceipt.format,
            freshReceipt.protectedPayload), protector)
        if (fresh.uid != submission.uid || fresh.context != submission.context || fresh.policy != submission.policy ||
            fresh.draft != submission.draft) createUnavailable(CreatePreparationFailure.CONTEXT)
        checkSession(session)
        var body: String? = null
        val allowsTransport = currentCreateTransportGuard(journal, record.scope)
        val admitted = CreateAttemptAdmission(journal).dispatch(stored) { unknown ->
            checkSession(session)
            if (!allowsTransport()) throw ProtonSingleDispatchDenied()
            try { body = remote.send(session, record.scope.calendar.value, submission.request, allowsTransport) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (denied: ProtonSingleDispatchDenied) { throw denied }
            catch (_: Exception) {
                if (!allowsTransport()) throw ProtonSingleDispatchDenied()
                // An admitted exchange with a lost response remains unknown.
            }
            val evidence = buildJsonObject {
                put("version", 1); put("account", record.scope.account.value); put("calendar", record.scope.calendar.value)
                put("generation", record.scope.generation); put("correlation", record.id.value)
                put("complete", body != null); put("body", body ?: "")
            }
            journal.retainEvidence(unknown, protector.seal(evidence.toString()))
        }
        if (!admitted) return@guarded inspectWithExpectation(journal.read(record.scope, record.id) ?: return@guarded CreateSubmissionObservation.Unknown, expected)
        checkSession(session)
        inspectWithExpectation(journal.read(record.scope, record.id) ?: return@guarded CreateSubmissionObservation.Unknown, expected)
    }

    override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = inspectWithExpectation(record, null)
    override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft): CreateSubmissionObservation =
        inspectWithExpectation(record, expected)

    private suspend fun inspectWithExpectation(record: CreateAttemptRecord, suppliedExpected: CalendarCreateDraft?): CreateSubmissionObservation = guarded {
        val expected = suppliedExpected?.frozen()
        if (record.state != CreateAttemptState.OUTCOME_UNKNOWN) return@guarded CreateSubmissionObservation.Unknown
        if (record.format == OCCURRENCE_CREATE_SUBMISSION_FORMAT) {
            return@guarded occurrences?.inspect(record, requireNotNull(expected)) ?: CreateSubmissionObservation.Unknown
        }
        val submission = ProtonCreateSubmissionCodec.open(record, protector)
        qualifyCaptured(record, submission, expected)
        val session = session(record.scope)
        var candidateId: String? = null
        record.protectedEvidence?.let { sealed ->
            val evidence = Json.parseToJsonElement(protector.open(sealed)) as JsonObject
            if (evidence.number("version") != 1L || evidence.string("account") != record.scope.account.value ||
                evidence.string("calendar") != record.scope.calendar.value || evidence.number("generation") != record.scope.generation ||
                evidence.string("correlation") != record.id.value) return@guarded CreateSubmissionObservation.Unknown
            val response = ProtonCreateResponseParser.parse(evidence.string("body"), record.scope.calendar.value, submission.uid)
            if (response is ProtonCreateResponse.Candidate) candidateId = response.event.string("ID")
        }
        val actual = readback.inspect(session, record.scope, submission.uid, submission.draft.canonicalContent(submission.uid),
            submission.policy, candidateId) ?: return@guarded CreateSubmissionObservation.Unknown
        // Readback already qualified the adapter's canonical text/UTC semantics and signatures.
        // Captured callers receive their exact domain expectation, with the observed ID/revision.
        val canonical = if (expected != null) actual.copy(content = expected.canonicalContent(submission.uid)) else actual
        CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), canonical)
    }
    private fun qualifyCaptured(record: CreateAttemptRecord, submission: ProtonCreateSubmission, expected: CalendarCreateDraft?) {
        if (expected == null) return
        val profile = when (record.format) {
            KNOWN_EMPTY_SUBMISSION_FORMAT -> submission.policy == CreatedEventReadbackPolicy.KNOWN_EMPTY_V2 &&
                expected.content.details == EventDetails() && expected.content.colorRgb == null
            PERSONAL_CREATE_SUBMISSION_FORMAT -> submission.policy == CreatedEventReadbackPolicy.PERSONAL_CREATE_V3 &&
                ProtonCreateSubmissionCodec.personal(expected.content) && submission.policy.acceptsExpected(expected.content)
            SERIES_CREATE_SUBMISSION_FORMAT -> submission.policy == CreatedEventReadbackPolicy.SERIES_CREATE_V4 &&
                expected.series != null && submission.policy.acceptsExpected(expected.canonicalContent(submission.uid))
            else -> false
        }
        if (!profile || submission.draft != expected) createUnavailable(CreatePreparationFailure.CONTEXT)
    }
    private suspend fun session(scope: CalendarScope) = sessions.current()?.takeIf { it.userId.id == scope.account.value }
        ?: createUnavailable(CreatePreparationFailure.AUTHENTICATION)
    private suspend fun checkSession(expected: CatalogSession) {
        if (sessions.current() != expected) createUnavailable(CreatePreparationFailure.AUTHENTICATION)
    }
    private suspend fun guarded(block: suspend () -> CreateSubmissionObservation): CreateSubmissionObservation = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); CreateSubmissionObservation.Unknown }
}

internal suspend fun currentCreateTransportGuard(journal: CreateAttemptJournal, scope: CalendarScope): () -> Boolean {
    val operation = currentCoroutineContext()
    return { operation.isActive && journal.allowsTransport(scope) }
}

internal interface ProtonCreateSubmissionApi : BaseRetrofitApi {
    @Streaming @PUT("calendar/v1/{calendar}/events/sync")
    suspend fun create(@Path("calendar") calendar: String, @Body body: RequestBody, @Tag attempt: ProtonSingleDispatchAttempt): ResponseBody
    @Streaming @GET("calendar/v1/{calendar}/events")
    suspend fun byUid(@Path("calendar") calendar: String, @Query("UID") uid: String, @Query("Page") page: Int, @Query("PageSize") size: Int): ResponseBody
    @Streaming @GET("calendar/v1/{calendar}/events/{event}")
    suspend fun event(@Path("calendar") calendar: String, @Path("event") id: String): ResponseBody
}
internal class ProtonCreateSubmissionRemote(private val provider: ApiProvider) : CreateSubmissionRemote {
    override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean) =
        send(session, calendar, request, false, allowsTransport)
    override suspend fun sendOccurrence(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean) =
        send(session, calendar, request, true, allowsTransport)
    private suspend fun send(session: CatalogSession, calendar: String, request: JsonObject, occurrence: Boolean, allowsTransport: () -> Boolean): String {
        val url = "https://calendar-api.proton.me/".toHttpUrl().newBuilder()
            .addPathSegment("calendar").addPathSegment("v1").addPathSegment(calendar).addPathSegment("events").addPathSegment("sync").build()
        val attempt = if (occurrence) ProtonSingleDispatchAttempt.occurrence(url, request.toString(), allowsTransport)
            else ProtonSingleDispatchAttempt(url, request.toString(), allowsTransport) // Outside every Core callback.
        return provider.get<ProtonCreateSubmissionApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            create(calendar, attempt.requestBody, attempt).use(CalendarCatalogBodyReader::read)
        }.valueOrThrow
    }
    override suspend fun byUid(session: CatalogSession, calendar: String, uid: String, page: Int): List<JsonObject> =
        provider.get<ProtonCreateSubmissionApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            byUid(calendar, uid, page, 100).use { createdEventResponse(it).array("Events").map { value -> value as JsonObject } }
        }.valueOrThrow
    override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject =
        provider.get<ProtonCreateSubmissionApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            event(calendar, id).use { createdEventResponse(it).obj("Event") }
        }.valueOrThrow
}
