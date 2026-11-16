// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming

internal val CREATE_PREPARATION_FORMAT = AdapterFormat("proton-calendar-create-preparation", 1)
/** v2 carries an explicit empty personal override; historical v1 remains incompatible and paused. */
internal val LOCAL_CREATE_PREPARATION_FORMAT = AdapterFormat("proton-calendar-local-create-preparation", 2)
internal val PERSONAL_LOCAL_CREATE_PREPARATION_FORMAT = AdapterFormat("proton-calendar-local-create-preparation", 3)
internal val SERIES_LOCAL_CREATE_PREPARATION_FORMAT = AdapterFormat("proton-calendar-local-create-preparation", 4)
internal fun interface CreateContextPreparation {
    fun prepare(scope: CalendarScope, metadata: JsonObject, bootstrap: JsonObject, addresses: List<me.proton.core.user.domain.entity.UserAddress>,
        uid: String, draft: CalendarCreateDraft): ProtonPreparedCreateEvent
}

/** Authenticated context reads followed by local crypto and Keystore sealing. Never dispatches. */
internal class ProtonCreatePreparationAdapter(private val sessions: CatalogSessionSource,
    private val context: EventContextRefresher, private val metadata: CreateCalendarMetadataRemote, private val events: EventImportRemote,
    private val addresses: LocalEventAddresses, private val prepare: CreateContextPreparation,
    private val protector: EventPayloadProtector, private val submission: Boolean = false) : CalendarCreatePreparationPort {
    override val localCreateFormat: AdapterFormat? get() = if (submission) null else LOCAL_CREATE_PREPARATION_FORMAT
    override val personalLocalCreateFormat: AdapterFormat? get() = if (submission) null else PERSONAL_LOCAL_CREATE_PREPARATION_FORMAT
    override val seriesLocalCreateFormat: AdapterFormat? get() = if (submission) null else SERIES_LOCAL_CREATE_PREPARATION_FORMAT
    override suspend fun prepareCreate(draft: CalendarCreateDraft): PreparedCalendarCreate = try {
        val frozen = try { draft.frozen() } catch (_: Exception) { createUnavailable(CreatePreparationFailure.UNSUPPORTED) }
        prepareFrozen(frozen)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: CreatePreparationException) { throw failure }
      catch (_: Exception) { createUnavailable(CreatePreparationFailure.CONTEXT) }

    private suspend fun prepareFrozen(draft: CalendarCreateDraft): PreparedCalendarCreate = try {
        if (!ProtonEventColourPolicy.accepts(draft.content.colorRgb))
            createUnavailable(CreatePreparationFailure.UNSUPPORTED)
        val series = draft.series
        val personal = series != null || ProtonCreateSubmissionCodec.personal(draft.content)
        val sharedDraft = if (personal) {
            try {
                val full = ProtonCreateSubmissionCodec.freezePersonal(draft.content)
                val shared = draft.copy(content = ProtonCreateSubmissionCodec.sharedContent(full))
                if (series != null) ProtonSimpleEventSerializer.serializeSeries(correlationUid(draft), draft.timestamp, shared.content, series)
                else ProtonSimpleEventSerializer.serialize(correlationUid(draft), draft.timestamp, shared.content)
                shared
            } catch (_: Exception) { createUnavailable(CreatePreparationFailure.UNSUPPORTED) }
        } else {
            if (draft.content.colorRgb != null) createUnavailable(CreatePreparationFailure.UNSUPPORTED)
            draft
        }
        val initial = localStorage { sessions.current() }?.takeIf { it.userId.id == draft.scope.account.value }
            ?: createUnavailable(CreatePreparationFailure.AUTHENTICATION)
        readContext { context.refresh(initial) }
        checkSession(initial)
        val calendar = readContext { metadata.calendar(initial, draft.scope.calendar.value) }
        checkSession(initial)
        val bootstrap = readContext { events.bootstrap(initial, draft.scope.calendar.value) }
        checkSession(initial)
        val local = localStorage { addresses.get(initial.userId) }
        checkSession(initial)
        val uid = correlationUid(draft)
        val prepared = prepare.prepare(draft.scope, calendar, bootstrap, local, uid, sharedDraft)
        checkSession(initial)
        val selection = if (submission) ProtonCreateContextPolicy.select(draft.scope, calendar, bootstrap, local) else null
        val knownEmpty = !personal && draft.content.details == EventDetails()
        // Notifications are a personal JSON sibling, never part of shared signed/encrypted text.
        // Refuse an unexpected profile from the preparer rather than silently dropping its data.
        val event = if (personal) ProtonCreateSubmissionCodec.personalEvent(prepared.event, draft.content) else if (knownEmpty) {
            val existing = prepared.event["Notifications"]
            if (existing != null && (existing !is JsonArray || existing.isNotEmpty()))
                createUnavailable(CreatePreparationFailure.CONTEXT)
            JsonObject(prepared.event + ("Notifications" to JsonArray(emptyList())))
        } else prepared.event
        val format = if (submission) {
            if (series != null) SERIES_CREATE_SUBMISSION_FORMAT else if (personal) PERSONAL_CREATE_SUBMISSION_FORMAT else if (knownEmpty) KNOWN_EMPTY_SUBMISSION_FORMAT else CREATE_SUBMISSION_FORMAT
        } else if (series != null) SERIES_LOCAL_CREATE_PREPARATION_FORMAT else if (personal) PERSONAL_LOCAL_CREATE_PREPARATION_FORMAT else if (knownEmpty) LOCAL_CREATE_PREPARATION_FORMAT else CREATE_PREPARATION_FORMAT
        val envelope = buildJsonObject {
            put("version", format.version); put("account", draft.scope.account.value)
            put("calendar", draft.scope.calendar.value); put("generation", draft.scope.generation)
            put("correlation", draft.correlation.value); put("uid", uid); put("timestamp", draft.timestamp.toString())
            if (knownEmpty) {
                if (!submission) put("knownEmptyDetails", true)
                put("notificationPolicy", "EXPLICIT_EMPTY")
            }
            if (personal) put("notificationPolicy", "EXPLICIT_PERSONAL")
            if (series != null && selection == null) put("seriesRule", series.rule)
            if (selection == null) put("event", event) else {
                put("context", ProtonCreateSubmissionCodec.context(selection))
                put("request", ProtonCreateSubmissionCodec.request(selection.member.string("ID"), event))
                put("expected", ProtonCreateSubmissionCodec.expected(draft))
            }
        }
        val receipt = PreparedCalendarCreate(draft.scope, format, localStorage { protector.seal(envelope.toString()) })
        checkSession(initial)
        receipt
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: CreatePreparationException) { throw failure }
      catch (_: GateCProtectedStorageUnavailable) { createUnavailable(CreatePreparationFailure.STORAGE) }
      catch (failure: RemoteImportException) {
          createUnavailable(when (failure.reason) {
              RemoteImportFailure.AUTHENTICATION -> CreatePreparationFailure.AUTHENTICATION
              RemoteImportFailure.NETWORK -> CreatePreparationFailure.NETWORK
              RemoteImportFailure.RETRY -> CreatePreparationFailure.RETRY
              RemoteImportFailure.LOCAL_KEYS -> CreatePreparationFailure.LOCAL_KEYS
              else -> CreatePreparationFailure.CONTEXT
          })
      } catch (_: Exception) { createUnavailable(CreatePreparationFailure.CONTEXT) }

    private suspend fun checkSession(expected: CatalogSession) {
        if (localStorage { sessions.current() } != expected) createUnavailable(CreatePreparationFailure.AUTHENTICATION)
    }

    private suspend fun <T> readContext(action: suspend () -> T): T = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CreatePreparationException) { throw failure }
        catch (failure: RemoteImportException) { throw failure }
        catch (failure: Exception) { throw failure.importFailure() }

    private suspend fun <T> localStorage(action: suspend () -> T): T = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CreatePreparationException) { throw failure }
        catch (_: Exception) { createUnavailable(CreatePreparationFailure.STORAGE) }

    /** Correlation only: deterministic scoped UID is not evidence of server uniqueness/idempotence. */
    internal fun correlationUid(draft: CalendarCreateDraft): String = createCorrelationUid(draft)
}

internal fun createCorrelationUid(draft: CalendarCreateDraft): String {
        val encoded = listOf(draft.scope.account.value, draft.scope.calendar.value, draft.scope.generation.toString(), draft.correlation.value)
        if (encoded.any { it.length > 4096 }) createUnavailable(CreatePreparationFailure.UNSUPPORTED)
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + "@calendeer.invalid"
}

internal fun interface CreateCalendarMetadataRemote {
    suspend fun calendar(session: CatalogSession, calendar: String): JsonObject
}
internal interface CreateCalendarMetadataApi : BaseRetrofitApi {
    @Streaming @GET("calendar/v1/{calendarId}")
    suspend fun calendar(@Path("calendarId") calendar: String): ResponseBody
}
internal class ProtonCreateCalendarMetadataRemote(private val provider: ApiProvider) : CreateCalendarMetadataRemote {
    override suspend fun calendar(session: CatalogSession, calendar: String): JsonObject =
        provider.get<CreateCalendarMetadataApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            calendar(calendar).use { eventResponse(it).obj("Calendar") }
        }.valueOrThrow
}
