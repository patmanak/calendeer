// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import me.proton.core.network.domain.ApiException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*

internal fun interface TitleUpdateContextQualification {
    suspend fun qualify(session: CatalogSession, scope: CalendarScope): JsonObject
}
internal interface TitleUpdateSubmissionRemote {
    suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String
    suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject
}

/** Internal controlled-writer experiment. A fresh read and a single PUT are not server CAS. */
internal class ProtonTitleUpdateSubmissionAdapter(private val preparations: CalendarLocalEditPreparationPort,
    private val protector: EventPayloadProtector, private val sessions: CatalogSessionSource,
    private val context: TitleUpdateContextQualification, private val remote: TitleUpdateSubmissionRemote,
    private val events: EventImportRemote, private val verifier: CreateEventVerifier) : CalendarTitleUpdateSubmissionPort {
    override val titleUpdateFormat = TITLE_UPDATE_SUBMISSION_FORMAT
    private val codec = ProtonTitleUpdateSubmissionCodec(protector)

    override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = try {
        val frozen = preparation.copy(intent = LocalEditPreparation.freezeIntent(preparation.intent))
        // Open the historical fragments before any remote read; this is not submission admission.
        ProtonTitlePreparationCodec(protector).openLocal(frozen)
        val session = session(frozen.intent.scope)
        val qualified = context.qualify(session, frozen.intent.scope)
        checkSession(session)
        codec.seal(id, frozen, qualified)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: LocalEditPreparationException) { throw failure }
      catch (failure: CreatePreparationException) { localEditUnavailable(LocalEditPreparationFailure.valueOf(failure.reason.name)) }
      catch (failure: ProtonTitlePreparationException) {
          localEditUnavailable(if (failure.reason == ProtonTitlePreparationFailure.STORAGE)
              LocalEditPreparationFailure.STORAGE else LocalEditPreparationFailure.UNSUPPORTED)
      } catch (_: GateCProtectedStorageUnavailable) { localEditUnavailable(LocalEditPreparationFailure.STORAGE) }
        catch (failure: Exception) {
            val imported = when (failure) {
                is RemoteImportException -> failure
                is ApiException, is java.io.IOException -> failure.importFailure()
                else -> null
            }
            localEditUnavailable(when (imported?.reason) {
                RemoteImportFailure.AUTHENTICATION -> LocalEditPreparationFailure.AUTHENTICATION
                RemoteImportFailure.NETWORK -> LocalEditPreparationFailure.NETWORK
                RemoteImportFailure.RETRY -> LocalEditPreparationFailure.RETRY
                RemoteImportFailure.LOCAL_KEYS -> LocalEditPreparationFailure.LOCAL_KEYS
                RemoteImportFailure.NOT_FOUND -> LocalEditPreparationFailure.NOT_FOUND
                else -> LocalEditPreparationFailure.UNSUPPORTED
            })
        }

    override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation {
        val frozen = record.frozen()
        return guarded {
            val stored = journal.read(frozen.scope, frozen.id)?.frozen() ?: return@guarded TitleUpdateSubmissionObservation.Unknown
            // A stale PREPARED caller may inspect a committed UNKNOWN, never the reverse.
            if (frozen.state == TitleUpdateAttemptState.PREPARED && frozen.protectedEvidence != null ||
                frozen.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN && stored.state == TitleUpdateAttemptState.PREPARED ||
                frozen.protectedEvidence != null && frozen.protectedEvidence != stored.protectedEvidence)
                return@guarded TitleUpdateSubmissionObservation.Unknown
            if (stored.copy(state = frozen.state, protectedEvidence = frozen.protectedEvidence) != frozen)
                return@guarded TitleUpdateSubmissionObservation.Unknown
            if (stored.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN) return@guarded inspect(stored)
            if (stored.protectedEvidence != null) return@guarded TitleUpdateSubmissionObservation.Unknown
            val submission = codec.open(stored)
            val session = session(stored.scope)
            val fresh = preparations.prepareLocalTitle(stored.preparation.intent, stored.preparation.timestamp)
            if (fresh.intent != stored.preparation.intent || fresh.timestamp != stored.preparation.timestamp ||
                fresh.format != stored.preparation.format) return@guarded TitleUpdateSubmissionObservation.Unknown
            val freshFragments = ProtonTitlePreparationCodec(protector).openLocal(fresh)
            if (freshFragments.originalRow != submission.fragments.originalRow || !freshFragments.changed)
                return@guarded TitleUpdateSubmissionObservation.Unknown
            checkSession(session)
            val currentContext = context.qualify(session, stored.scope)
            if (currentContext != submission.context) return@guarded TitleUpdateSubmissionObservation.Unknown
            checkSession(session)
            // The journal is the final durable admission boundary, immediately before one send.
            val admitted = TitleUpdateAttemptAdmission(journal).dispatch(stored) { unknown ->
                checkSession(session)
                var response: String? = null
                try { response = remote.send(session, stored.scope.calendar.value, submission.request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* UNKNOWN is inspection-only, including no response. */ }
                val evidence = buildJsonObject {
                    put("kind", "title-update-evidence"); put("version", 1)
                    put("payload", TitleUpdateAttemptIdentity.of(unknown).payloadFingerprint)
                    put("attempt", unknown.id.value); put("complete", response != null); put("body", response ?: "")
                }
                // Preserve the first received body before any interpretation or authenticated GET.
                journal.retainEvidence(unknown, protector.seal(evidence.toString()))
            }
            val latest = journal.read(stored.scope, stored.id) ?: return@guarded TitleUpdateSubmissionObservation.Unknown
            if (!admitted && latest.state != TitleUpdateAttemptState.OUTCOME_UNKNOWN) return@guarded TitleUpdateSubmissionObservation.Unknown
            inspect(latest)
        }
    }

    override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation {
        val frozen = record.frozen()
        return guarded {
            if (frozen.state != TitleUpdateAttemptState.OUTCOME_UNKNOWN) return@guarded TitleUpdateSubmissionObservation.Unknown
            val submission = codec.open(frozen)
            frozen.protectedEvidence?.let { sealed ->
                val text = protector.open(sealed)
                require(text.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES && UniqueJsonKeys(text).validate())
                val evidence = Json.parseToJsonElement(text) as JsonObject
                require(evidence.keys == setOf("kind", "version", "payload", "attempt", "complete", "body") &&
                    evidence.string("kind") == "title-update-evidence" && evidence.number("version") == 1L &&
                    evidence.string("attempt") == frozen.id.value && evidence.string("payload") == TitleUpdateAttemptIdentity.of(frozen).payloadFingerprint)
                require((evidence["complete"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null)
                evidence.string("body") // A response is evidence, never acknowledgement or a retry grant.
            }
            val session = session(frozen.scope)
            val currentContext = context.qualify(session, frozen.scope)
            checkSession(session)
            if (currentContext != submission.context) return@guarded TitleUpdateSubmissionObservation.Unknown
            val row = remote.event(session, frozen.scope.calendar.value, frozen.preparation.intent.base.eventId.value)
            checkSession(session)
            if (!ProtonTitleUpdateProfile.matchesReadback(submission.fragments.originalRow, submission.fragments, row))
                return@guarded TitleUpdateSubmissionObservation.Unknown
            val bootstrap = events.bootstrap(session, frozen.scope.calendar.value)
            checkSession(session)
            if (ProtonTitleUpdateSubmissionCodec.hash(bootstrap.toString()) != submission.context.string("bootstrap"))
                return@guarded TitleUpdateSubmissionObservation.Unknown
            val actual = verifier.verify(session, frozen.scope.calendar.value, bootstrap, row,
                submission.fragments.originalRow.string("UID"), CreatedEventReadbackPolicy.KNOWN_EMPTY_V2)
                ?: return@guarded TitleUpdateSubmissionObservation.Unknown
            checkSession(session)
            if (actual.eventId != frozen.preparation.intent.base.eventId || actual.content != frozen.preparation.intent.content)
                return@guarded TitleUpdateSubmissionObservation.Unknown
            TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(frozen), actual)
        }
    }
    private suspend fun session(scope: CalendarScope) = sessions.current()?.takeIf { it.userId.id == scope.account.value }
        ?: localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
    private suspend fun checkSession(expected: CatalogSession) {
        if (sessions.current() != expected) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
    }
    private suspend fun guarded(block: suspend () -> TitleUpdateSubmissionObservation) = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { TitleUpdateSubmissionObservation.Unknown }
}

internal interface ProtonTitleUpdateSubmissionApi : BaseRetrofitApi {
    @Streaming @PUT("calendar/v1/{calendar}/events/sync")
    suspend fun update(@Path("calendar") calendar: String, @Body body: RequestBody, @Tag attempt: ProtonTitleUpdateDispatchAttempt): retrofit2.Response<ResponseBody>
    @Streaming @GET("calendar/v1/{calendar}/events/{event}")
    suspend fun event(@Path("calendar") calendar: String, @Path("event") event: String): ResponseBody
}
internal class ProtonTitleUpdateSubmissionRemote(private val provider: ApiProvider) : TitleUpdateSubmissionRemote {
    override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String {
        val url = "https://calendar-api.proton.me/".toHttpUrl().newBuilder().addPathSegment("calendar").addPathSegment("v1")
            .addPathSegment(calendar).addPathSegment("events").addPathSegment("sync").build()
        val attempt = ProtonTitleUpdateDispatchAttempt(url, request.toString())
        return provider.get<ProtonTitleUpdateSubmissionApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            val response = update(calendar, attempt.requestBody, attempt)
            (response.body() ?: response.errorBody())?.use(CalendarCatalogBodyReader::read) ?: ""
        }.valueOrThrow
    }
    override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject =
        provider.get<ProtonTitleUpdateSubmissionApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            event(calendar, id).use { createdEventResponse(it).obj("Event") }
        }.valueOrThrow
}
