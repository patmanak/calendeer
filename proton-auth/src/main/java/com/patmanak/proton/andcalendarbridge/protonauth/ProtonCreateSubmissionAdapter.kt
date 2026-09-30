// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.http.*

internal interface CreateSubmissionRemote : CreateReadbackRemote {
    suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String
}

internal class ProtonCreateSubmissionAdapter(private val preparations: CalendarCreatePreparationPort,
    private val protector: EventPayloadProtector, private val sessions: CatalogSessionSource,
    private val remote: CreateSubmissionRemote, private val events: EventImportRemote,
    verifier: CreateEventVerifier) : CalendarCreateSubmissionPort {
    override val knownEmptySubmissionFormat = KNOWN_EMPTY_SUBMISSION_FORMAT
    private val readback = ProtonCreatedEventReadback(sessions, remote, events, verifier)
    override suspend fun prepareSubmission(draft: CalendarCreateDraft) = preparations.prepareCreate(draft)

    override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = guarded {
        val stored = journal.read(record.scope, record.id) ?: createUnavailable(CreatePreparationFailure.CONTEXT)
        if (stored.format != record.format || stored.protectedPayload != record.protectedPayload)
            createUnavailable(CreatePreparationFailure.CONTEXT)
        if (stored.state == CreateAttemptState.OUTCOME_UNKNOWN) return@guarded inspect(stored)
        val submission = ProtonCreateSubmissionCodec.open(stored, protector)
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
        val admitted = CreateAttemptAdmission(journal).dispatch(stored) { unknown ->
            checkSession(session)
            try { body = remote.send(session, record.scope.calendar.value, submission.request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Every transport failure remains unknown, never resendable. */ }
            val evidence = buildJsonObject {
                put("version", 1); put("account", record.scope.account.value); put("calendar", record.scope.calendar.value)
                put("generation", record.scope.generation); put("correlation", record.id.value)
                put("complete", body != null); put("body", body ?: "")
            }
            journal.retainEvidence(unknown, protector.seal(evidence.toString()))
        }
        if (!admitted) return@guarded inspect(journal.read(record.scope, record.id) ?: return@guarded CreateSubmissionObservation.Unknown)
        checkSession(session)
        inspect(journal.read(record.scope, record.id) ?: return@guarded CreateSubmissionObservation.Unknown)
    }

    override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = guarded {
        if (record.state != CreateAttemptState.OUTCOME_UNKNOWN) return@guarded CreateSubmissionObservation.Unknown
        val submission = ProtonCreateSubmissionCodec.open(record, protector)
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
        val actual = readback.inspect(session, record.scope, submission.uid, submission.draft.content,
            submission.policy, candidateId) ?: return@guarded CreateSubmissionObservation.Unknown
        CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record), actual)
    }
    private suspend fun session(scope: CalendarScope) = sessions.current()?.takeIf { it.userId.id == scope.account.value }
        ?: createUnavailable(CreatePreparationFailure.AUTHENTICATION)
    private suspend fun checkSession(expected: CatalogSession) {
        if (sessions.current() != expected) createUnavailable(CreatePreparationFailure.AUTHENTICATION)
    }
    private suspend fun guarded(block: suspend () -> CreateSubmissionObservation): CreateSubmissionObservation = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CreateSubmissionObservation.Unknown }
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
    override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String {
        val url = "https://calendar-api.proton.me/".toHttpUrl().newBuilder()
            .addPathSegment("calendar").addPathSegment("v1").addPathSegment(calendar).addPathSegment("events").addPathSegment("sync").build()
        val attempt = ProtonSingleDispatchAttempt(url, request.toString()) // Outside every Core callback.
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
