// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.key.domain.useKeys
import me.proton.core.key.domain.verifyData

/** Opt-in qualification only; neither account names nor raw response content are returned. */
object ProtonCreateSubmissionProbe {
    /** Local protected-envelope qualification only, never dispatch or a service claim. */
    fun knownEmptyPreparationSummary(record: CreateAttemptRecord): String = try {
        val decoded = ProtonCreateSubmissionCodec.open(record, ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
        if (record.format == KNOWN_EMPTY_SUBMISSION_FORMAT && decoded.policy == CreatedEventReadbackPolicy.KNOWN_EMPTY_V2 &&
            decoded.draft.content.details == EventDetails()) "SUBMISSION_V2_EXPLICIT_EMPTY" else "INCOMPATIBLE"
    } catch (_: Exception) { "INCOMPATIBLE" }

    suspend fun matchesAccount(context: Context, account: AccountId, expectedIdentityHash: String): Boolean =
        ProtonAuthentication.matchesIdentityHash(context, account, expectedIdentityHash)

    /** Debug-only diagnosis of retained UNKNOWN work; closed shapes, no admission/send capability.
     * Reflection keeps this temporary diagnostic out of the release authentication interface.
     * Field drift fails closed. Reads hold the same logout mutex as normal submission inspection.
     */
    suspend fun knownEmptyReadShape(context: Context, record: CreateAttemptRecord): String = withContext(Dispatchers.IO) {
        try {
            if (record.state != CreateAttemptState.OUTCOME_UNKNOWN || record.format != KNOWN_EMPTY_SUBMISSION_FORMAT)
                return@withContext "UNSUPPORTED_STATE"
            val port = ProtonAuthentication.createSubmissions(context)
            val mutex = field(port, "lock") as Mutex
            mutex.withLock {
                if (field(port, "authenticated") != true) return@withLock "AUTHENTICATION_UNAVAILABLE"
                val graph = field(port, "graph") as ProtonAuthenticationGraph
                val adapter = graph.submissions as? ProtonCreateSubmissionAdapter ?: return@withLock "UNAVAILABLE"
                val sessions = field(adapter, "sessions") as CatalogSessionSource
                val session = sessions.current()?.takeIf { it.userId.id == record.scope.account.value }
                    ?: return@withLock "AUTHENTICATION_UNAVAILABLE"
                val submission = ProtonCreateSubmissionCodec.open(record,
                    ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
                val remote = field(adapter, "remote") as CreateReadbackRemote
                val rows = remote.byUid(session, record.scope.calendar.value, submission.uid, 0)
                if (sessions.current() != session) return@withLock "SESSION_CHANGED"
                if (rows.isEmpty()) return@withLock "UID_ABSENT"
                if (rows.size != 1) return@withLock "UID_AMBIGUOUS"
                val found = rows.single()
                if (found.string("CalendarID") != record.scope.calendar.value || found.string("UID") != submission.uid)
                    return@withLock "UID_FOREIGN"
                val row = remote.event(session, record.scope.calendar.value, found.string("ID"))
                if (sessions.current() != session) return@withLock "SESSION_CHANGED"
                if (row.string("ID") != found.string("ID") || row.string("CalendarID") != record.scope.calendar.value ||
                    row.string("UID") != submission.uid) return@withLock "EVENT_FOREIGN"
                val shape = when (val notifications = row["Notifications"]) {
                    null -> "RAW_ABSENT"
                    JsonNull -> "RAW_NULL"
                    is JsonArray -> if (notifications.isEmpty()) "RAW_EMPTY" else "RAW_NONEMPTY"
                    else -> "RAW_WRONG_TYPE"
                }
                if (shape != "RAW_EMPTY") return@withLock shape
                if (row.array("SharedEvents").map { (it as JsonObject).number("Type") }.sorted() != listOf(2L, 3L))
                    return@withLock "RAW_EMPTY_SHARED_SHAPE"
                if (row.array("CalendarEvents").isNotEmpty() || row.array("AttendeesEvents").isNotEmpty())
                    return@withLock "RAW_EMPTY_ADDITIONAL_PARTS"
                val events = field(adapter, "events") as EventImportRemote
                val bootstrap = events.bootstrap(session, record.scope.calendar.value)
                if (sessions.current() != session) return@withLock "SESSION_CHANGED"
                val decoder = field(graph.events, "decoder") as ProtonEventDecryptor
                val addresses = (field(decoder, "localAddresses") as LocalEventAddresses).get(session.userId)
                if (sessions.current() != session) return@withLock "SESSION_CHANGED"
                val crypto = field(decoder, "crypto") as CryptoContext
                val selected = addresses.singleOrNull { it.addressId.id == submission.context.string("address") }
                    ?: return@withLock "SIGNING_ADDRESS_UNAVAILABLE"
                val selectedKeyMatches = selected.keys.any {
                    java.security.MessageDigest.getInstance("SHA-256").digest(it.privateKey.key.toByteArray(Charsets.UTF_8))
                        .joinToString("") { byte -> "%02x".format(byte) } == submission.context.string("signingKey")
                }
                val texts = ProtonSimpleEventSerializer.serialize(submission.uid, submission.draft.timestamp, submission.draft.content)
                val originals = submission.request.array("Events").single().jsonObject.obj("Event").array("SharedEventContent")
                    .map { it as JsonObject }.associateBy { it.number("Type") }
                val partFlags = row.array("SharedEvents").map { it as JsonObject }.sortedBy { it.number("Type") }.joinToString("_") { part ->
                    val original = originals.getValue(part.number("Type"))
                    val matches = addresses.filter { it.email.equals(part.string("Author"), ignoreCase = true) }
                    val text = if (part.number("Type") == 2L) texts.clearShared else texts.encryptedShared
                    val signature = original.string("Signature")
                    val checks = selected.useKeys(crypto) {
                        publicKeyRing.keys.any { it.verifyData(crypto, text.toByteArray(Charsets.UTF_8), signature) } to
                            publicKeyRing.keys.any { crypto.pgpCrypto.verifyText(text, signature, it.key) }
                    }
                    "PART${part.number("Type")}_DATA${part.string("Data") == original.string("Data")}" +
                        "_SIG${part.optionalString("Signature") == original.optionalString("Signature")}_AUTHOR${matches.isNotEmpty()}" +
                        "_ONE${matches.size == 1}_SELECTED${matches.firstOrNull()?.addressId == selected.addressId}" +
                        "_KEY${selectedKeyMatches}_ORIGINALDATA${checks.first}_ORIGINALTEXT${checks.second}"
                }
                val decoded = decoder.decodePage(session.userId, record.scope.calendar.value, bootstrap, listOf(row), submission.uid)
                if (sessions.current() != session) return@withLock "SESSION_CHANGED"
                val actual = decoded.changes.singleOrNull()?.content
                    ?: return@withLock if (decoded.deferred.singleOrNull()?.reason == DeferredEventReason.UNVERIFIED_CONTENT)
                        "RAW_EMPTY_SIGNATURE_UNRESOLVED_$partFlags" else "RAW_EMPTY_CONTENT_UNSUPPORTED"
                fun normalized(value: EventContent) = value.copy(title = value.title.replace("\r\n", "\n"),
                    description = value.description.replace("\r\n", "\n"), location = value.location.replace("\r\n", "\n"),
                    time = when (val time = value.time) {
                        is EventTime.Timed -> if (time.zone.normalized() == java.time.ZoneOffset.UTC) time.copy(zone = java.time.ZoneOffset.UTC) else time
                        is EventTime.AllDay -> time
                    })
                val observed = normalized(actual); val expected = normalized(submission.draft.content)
                val differences = buildList {
                    if (observed.title != expected.title) add("TITLE")
                    if (observed.description != expected.description) add("DESCRIPTION")
                    if (observed.location != expected.location) add("LOCATION")
                    if (observed.time != expected.time) add("TIME")
                    if (observed.details != expected.details) add("DETAILS")
                    if (observed.colorRgb != expected.colorRgb) add("COLOR")
                    if (observed.recurrence != expected.recurrence) add("RECURRENCE")
                }
                if (differences.isEmpty()) "RAW_EMPTY_CONTENT_MATCH" else "RAW_EMPTY_CONTENT_DRIFT_" + differences.joinToString("_")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { "UNAVAILABLE" }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(owner)
    }

    fun responseSummary(record: CreateAttemptRecord): String {
      return try {
        val sealed = record.protectedEvidence ?: return "EVIDENCE_ABSENT"
        val protector = ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)
        val evidence = Json.parseToJsonElement(protector.open(sealed)).jsonObject
        if (evidence["complete"]?.jsonPrimitive?.booleanOrNull != true) "TRANSPORT_INCOMPLETE"
        else {
            val body = Json.parseToJsonElement(evidence.string("body")).jsonObject
            val topCode = body["Code"]?.jsonPrimitive?.intOrNull ?: -1
            val items = body["Responses"] as? JsonArray
            val itemCode = (items?.singleOrNull() as? JsonObject)?.get("Response")?.jsonObject
                ?.get("Code")?.jsonPrimitive?.intOrNull ?: -1
            "TOP_CODE=$topCode ITEM_CODE=$itemCode"
        }
      } catch (_: Exception) { "EVIDENCE_UNREADABLE" }
    }
}
