// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.network.data.ApiProvider
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class DeleteReadApiCode { SUCCESS, NOT_FOUND, OTHER, MISSING }
enum class DeleteReadDetails { MISSING, NULL, OBJECT, OTHER }
enum class DeleteReadResult { PRESENT, ABSENT, UNKNOWN, NO_CAPTURE }
enum class DeleteReadContextMismatch { NONE, CALENDAR, BOOTSTRAP, OTHER }

/** Closed transport/parser observations only. PRESENT does not establish verified content;
 * ABSENT does not retain evidence, authorize acknowledgement or override the production parser.
 * extraKeys identifies any root key outside the parser's absence grammar (Code and Error).
 */
data class DeleteReadDiagnostic(val httpCode: Int?, val apiCode: DeleteReadApiCode,
    val details: DeleteReadDetails, val extraKeys: Boolean, val result: DeleteReadResult,
    val contextMatched: Boolean, val beforeMatchesOriginal: Boolean, val afterMatchesBefore: Boolean,
    val contextMismatchComponent: DeleteReadContextMismatch, val detailsEmpty: Boolean = false, val unknownKeys: Boolean = false) {
    init { require(httpCode == null || httpCode in 100..599) }
}

/** Debug-only exact authenticated GET of an explicitly enrolled UNKNOWN deletion.
 * Holds the ordinary authentication/logout mutex. Reflection drift fails closed. The helper
 * has no journal, dispatch, result retention or acknowledgement capability. Raw protected
 * envelopes, routes, identifiers, response bodies and exceptions never leave this object.
 */
object ProtonDeleteInspectionProbe {
    /** Local protected binding only. Codec opening is suspend; no network is reachable here. */
    suspend fun localEvidence(record: DeleteAttemptRecord): String = withContext(Dispatchers.IO) {
        try {
            val frozen = qualifiedRecord(record)
            val protector = protector()
            ProtonDeleteRequestCodec(protector).open(frozen)
            currentCoroutineContext().ensureActive()
            evidenceShape(frozen, protector).also { currentCoroutineContext().ensureActive() }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); "BINDING_INVALID" }
    }

    suspend fun inspect(context: Context, record: DeleteAttemptRecord,
        expectedIdentityHash: String): DeleteReadDiagnostic? = withContext(Dispatchers.IO) {
        try {
            if (!expectedIdentityHash.matches(Regex("[a-f0-9]{64}"))) return@withContext null
            val frozen = qualifiedRecord(record)
            val protector = protector()
            val request = ProtonDeleteRequestCodec(protector).open(frozen)
            if (evidenceShape(frozen, protector) == "BINDING_INVALID") return@withContext null
            currentCoroutineContext().ensureActive()
            val facade = ProtonAuthentication.deletions(context)
            val mutex = field(facade, "lock") as Mutex
            mutex.withLock {
                if (field(facade, "authenticated") != true) return@withLock null
                val graph = field(facade, "graph") as ProtonAuthenticationGraph
                val adapter = graph.deletions as? ProtonDeleteSubmissionAdapter ?: return@withLock null
                val sessions = field(adapter, "sessions") as CatalogSessionSource
                val session = sessions.current()?.takeIf { it.userId.id == frozen.scope.account.value }
                    ?: return@withLock null
                suspend fun checkpoint() {
                    currentCoroutineContext().ensureActive()
                    require(field(facade, "authenticated") == true && sessions.current() == session)
                    require(graph.matchesIdentityHash(frozen.scope.account, expectedIdentityHash))
                    require(sessions.current() == session)
                    currentCoroutineContext().ensureActive()
                }
                checkpoint()
                val qualification = field(adapter, "context") as TitleUpdateContextQualification
                val before = deleteSnapshot(qualification.qualify(session, frozen.scope, ::checkpoint))
                checkpoint()
                // Fresh membership/account qualification is still required. A mismatch with
                // historical context may be diagnosed by GET only; it grants no proof or ACK.
                val beforeMatchesOriginal = before == request.context
                val remote = field(adapter, "remote") as ProtonDeleteSubmissionRemote
                val provider = field(remote, "provider") as ApiProvider
                val url = "https://calendar-api.proton.me/".toHttpUrl().newBuilder()
                    .addPathSegment("calendar").addPathSegment("v1").addPathSegment(frozen.scope.calendar.value)
                    .addPathSegment("events").addPathSegment(frozen.eventId.value).build()
                val attempt = ProtonDeleteReadAttempt(url, session.sessionId.id)
                try {
                    // The same exact GET, tag, no-retry setting and interceptor witness as
                    // ProtonDeleteSubmissionRemote. Ignore Core exception codes and bodies.
                    try {
                        provider.get<ProtonDeleteSubmissionApi>(sessionId = session.sessionId)
                            .invoke(forceNoRetryOnConnectionErrors = true) {
                                val response = event(frozen.scope.calendar.value, frozen.eventId.value, attempt)
                                response.body()?.close(); response.errorBody()?.close()
                            }.valueOrThrow
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { currentCoroutineContext().ensureActive() }
                    checkpoint()
                    val captured = attempt.finish()
                    currentCoroutineContext().ensureActive()
                    val after = deleteSnapshot(qualification.qualify(session, frozen.scope, ::checkpoint))
                    checkpoint()
                    val afterMatchesBefore = after == before
                    val matched = beforeMatchesOriginal && afterMatchesBefore
                    val mismatch = mismatch(before, request.context).takeUnless { it == DeleteReadContextMismatch.NONE }
                        ?: mismatch(after, before)
                    val diagnostic = if (captured == null) noCapture(matched, beforeMatchesOriginal, afterMatchesBefore, mismatch)
                    else shape(captured, frozen, matched, beforeMatchesOriginal, afterMatchesBefore, mismatch)
                    currentCoroutineContext().ensureActive()
                    diagnostic
                } finally { attempt.close() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
    }

    private fun qualifiedRecord(record: DeleteAttemptRecord): DeleteAttemptRecord = record.frozen().also {
        require(it.state == DeleteAttemptState.OUTCOME_UNKNOWN && it.format == DELETE_SUBMISSION_FORMAT &&
            it.intent.format == EVENT_FORMAT && it.id == DeleteAttemptIdentity.requestId(it.intent, it.format))
    }
    private fun protector() = ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)

    private fun evidenceShape(record: DeleteAttemptRecord, protector: EventPayloadProtector): String {
        val sealed = record.protectedEvidence ?: return "NONE"
        val plain = protector.open(sealed)
        deleteBounded(plain); require(UniqueJsonKeys(plain).validate())
        val root = (Json.parseToJsonElement(plain) as JsonObject).also { deleteValidateJson(it) }
        val complete = (root["complete"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        require(complete != null)
        val body = root.string("body")
        require(complete || body.isEmpty())
        val identity = DeleteAttemptIdentity.of(record)
        val expected = buildJsonObject {
            put("kind", "delete-evidence"); put("version", 1); put("attempt", record.id.value)
            put("payload", identity.payloadFingerprint); put("intent", identity.intentFingerprint)
            put("complete", complete); put("body", body)
        }
        require(root == expected)
        if (!complete) return "NO_RESPONSE"
        return when (ProtonDeleteResponseParser.parse(body)) {
            ProtonDeleteResponse.ReportedSuccess -> "REPORTED_SUCCESS"
            is ProtonDeleteResponse.ReportedFailure -> "REPORTED_FAILURE"
            ProtonDeleteResponse.Unknown -> "UNCLASSIFIED"
        }
    }

    private fun shape(captured: ProtonDeleteCapturedRead, record: DeleteAttemptRecord,
        contextMatched: Boolean, beforeMatchesOriginal: Boolean, afterMatchesBefore: Boolean,
        contextMismatchComponent: DeleteReadContextMismatch): DeleteReadDiagnostic {
        val root = try {
            deleteBounded(captured.body); require(UniqueJsonKeys(captured.body).validate())
            (Json.parseToJsonElement(captured.body) as JsonObject).also { deleteValidateJson(it) }
        } catch (_: Exception) { null }
        val code = (root?.get("Code") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val apiCode = when (code) {
            1000L -> DeleteReadApiCode.SUCCESS
            2501L -> DeleteReadApiCode.NOT_FOUND
            null -> DeleteReadApiCode.MISSING
            else -> DeleteReadApiCode.OTHER
        }
        val details = when (root?.get("Details")) {
            null -> DeleteReadDetails.MISSING
            JsonNull -> DeleteReadDetails.NULL
            is JsonObject -> DeleteReadDetails.OBJECT
            else -> DeleteReadDetails.OTHER
        }
        val result = when (ProtonDeleteEventResponseParser.parse(captured.url, record.scope.calendar.value,
            record.eventId.value, captured.httpCode, captured.body)) {
            is ProtonDeleteEventRead.Present -> DeleteReadResult.PRESENT
            is ProtonDeleteEventRead.Absent -> DeleteReadResult.ABSENT
            ProtonDeleteEventRead.Unknown -> DeleteReadResult.UNKNOWN
        }
        return DeleteReadDiagnostic(captured.httpCode.takeIf { it in 100..599 }, apiCode, details,
            root?.keys?.any { it !in setOf("Code", "Error") } == true, result, contextMatched,
            beforeMatchesOriginal, afterMatchesBefore, contextMismatchComponent,
            (root?.get("Details") as? JsonObject)?.isEmpty() == true,
            root?.keys?.any { it !in setOf("Code", "Error", "Details") } == true)
    }
    private fun noCapture(contextMatched: Boolean, beforeMatchesOriginal: Boolean, afterMatchesBefore: Boolean,
        contextMismatchComponent: DeleteReadContextMismatch) = DeleteReadDiagnostic(null, DeleteReadApiCode.MISSING,
        DeleteReadDetails.MISSING, false, DeleteReadResult.NO_CAPTURE, contextMatched,
        beforeMatchesOriginal, afterMatchesBefore, contextMismatchComponent)
    private fun mismatch(actual: JsonObject, expected: JsonObject): DeleteReadContextMismatch {
        val changed = (actual.keys + expected.keys).filter { actual[it] != expected[it] }.toSet()
        return when (changed) {
            emptySet<String>() -> DeleteReadContextMismatch.NONE
            setOf("calendar") -> DeleteReadContextMismatch.CALENDAR
            setOf("bootstrap") -> DeleteReadContextMismatch.BOOTSTRAP
            else -> DeleteReadContextMismatch.OTHER
        }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
}
