// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.LocalEditPreparation
import com.patmanak.proton.andcalendarbridge.sync.TitleUpdateAttemptRecord
import com.patmanak.proton.andcalendarbridge.sync.TitleUpdateAttemptIdentity
import com.patmanak.proton.andcalendarbridge.sync.TitleUpdateAttemptState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext

/** Protected-fixture shapes and opt-in authenticated GET diagnostics. No private values or mutation. */
object ProtonTitleUpdateShapeProbe {
    /** UNKNOWN-only GET diagnosis under the normal owner/logout lock. No send or journal capability. */
    suspend fun readbackDiagnostics(context: Context, record: TitleUpdateAttemptRecord): Map<String, String> {
        val frozen = record.frozen()
        return withContext(Dispatchers.IO) {
            val result = linkedMapOf<String, String>()
            fun stage(value: String) { result["STAGE"] = value }
            fun check(name: String, predicate: () -> Boolean) {
                result[name] = try { predicate().toString() } catch (_: Exception) { "false" }
            }
            try {
                stage("STATE")
                if (frozen.state != TitleUpdateAttemptState.OUTCOME_UNKNOWN) return@withContext result
                val port = ProtonAuthentication.titleUpdates(context)
                val mutex = field(port, "lock") as Mutex
                mutex.withLock {
                    stage("AUTHENTICATION")
                    if (field(port, "authenticated") != true) return@withLock
                    val graph = field(port, "graph") as ProtonAuthenticationGraph
                    val adapter = graph.titleUpdates as? ProtonTitleUpdateSubmissionAdapter ?: return@withLock
                    val sessions = field(adapter, "sessions") as CatalogSessionSource
                    val session = sessions.current()?.takeIf { it.userId.id == frozen.scope.account.value } ?: return@withLock
                    suspend fun sameSession(): Boolean {
                        if (sessions.current() == session) return true
                        result.clear(); stage("SESSION_CHANGED"); return false
                    }
                    stage("OPEN")
                    val submission = ProtonTitleUpdateSubmissionCodec(field(adapter, "protector") as EventPayloadProtector).open(frozen)
                    val original = submission.fragments.originalRow
                    stage("CONTEXT")
                    val fresh = (field(adapter, "context") as TitleUpdateContextQualification).qualify(session, frozen.scope)
                    if (!sameSession()) return@withLock
                    check("CONTEXT_SAME") { fresh == submission.context }
                    stage("EVENT_GET")
                    val row = (field(adapter, "remote") as TitleUpdateSubmissionRemote).event(session,
                        frozen.scope.calendar.value, frozen.preparation.intent.base.eventId.value)
                    if (!sameSession()) return@withLock
                    val own = row.string("ID") == frozen.preparation.intent.base.eventId.value &&
                        row.string("CalendarID") == frozen.scope.calendar.value && row.string("UID") == original.string("UID")
                    result["EVENT_BINDING"] = own.toString()
                    if (!own) return@withLock
                    stage("ROW_COMPARE")
                    check("PROFILE") { ProtonTitleUpdateProfile.qualify(row); true }
                    check("READBACK_MATCH") { ProtonTitleUpdateProfile.matchesReadback(original, submission.fragments, row) }
                    val expectedParts = listOf(submission.fragments.clearSharedPart, submission.fragments.encryptedSharedPart).associateBy { it.number("Type") }
                    val actualParts = row.array("SharedEvents").map { it as JsonObject }.associateBy { it.number("Type") }
                    check("SHARED_PARTS_EXACT") { actualParts == expectedParts }
                    for (type in listOf(2L, 3L)) for (name in listOf("Data", "Signature", "Author")) {
                        check("PART_${type}_${name}_SAME") { actualParts[type]?.get(name) == expectedParts[type]?.get(name) }
                    }
                    val changedKeys = (original.keys + row.keys).filter { original[it] != row[it] }.toSet()
                    val safeFields = setOf("ID", "CalendarID", "SharedEventID", "CalendarKeyPacket", "CreateTime", "ModifyTime", "Permissions",
                        "AddressKeyPacket", "AddressID", "SharedKeyPacket", "SharedEvents", "CalendarEvents", "AttendeesEvents", "Attendees",
                        "AttendeesInfo", "IsProtonProtonInvite", "Notifications", "Color", "StartTime", "StartTimezone", "EndTime", "EndTimezone",
                        "FullDay", "UID", "RecurrenceID", "Exdates", "RRule", "IsOrganizer", "IsPersonalSingleEdit", "VideoConferencingData",
                        "LastEditTime", "NotificationOccurrences")
                    for (name in safeFields) {
                        result["DIFF_$name"] = (name in changedKeys).toString()
                        if (name in changedKeys) result["READ_TYPE_$name"] = shapeType(row[name])
                    }
                    check("UNKNOWN_FIELDS_DIFFER") { changedKeys.any { it !in safeFields } }
                    check("UNTOUCHED_METADATA_EXACT") { JsonObject(original - setOf("ModifyTime", "SharedEvents")) == JsonObject(row - setOf("ModifyTime", "SharedEvents")) }
                    check("LAST_EDIT_EQUAL_ORIGINAL") { row.number("LastEditTime") == original.number("LastEditTime") }
                    check("LAST_EDIT_EQUAL_MODIFY") { row.number("LastEditTime") == row.number("ModifyTime") }
                    check("LAST_EDIT_EQUAL_PREPARATION_EPOCH") { row.number("LastEditTime") == frozen.preparation.timestamp.epochSecond }
                    check("LAST_EDIT_GE_ORIGINAL") { row.number("LastEditTime") >= original.number("LastEditTime") }
                    stage("BOOTSTRAP")
                    val bootstrap = (field(adapter, "events") as EventImportRemote).bootstrap(session, frozen.scope.calendar.value)
                    if (!sameSession()) return@withLock
                    check("BOOTSTRAP_HASH_SAME") { ProtonTitleUpdateSubmissionCodec.hash(bootstrap.toString()) == submission.context.string("bootstrap") }
                    stage("VERIFY")
                    val decoded = (field(adapter, "verifier") as CreateEventVerifier).verify(session,
                        frozen.scope.calendar.value, bootstrap, row, original.string("UID"), CreatedEventReadbackPolicy.KNOWN_EMPTY_V2)
                    if (!sameSession()) return@withLock
                    check("DECODED") { decoded != null }
                    check("DECODED_EVENT_EXPECTED") { decoded?.eventId == frozen.preparation.intent.base.eventId }
                    check("DECODED_CONTENT_EXPECTED") { decoded?.content == frozen.preparation.intent.content }
                    check("DECODED_REVISION_CHANGED") { decoded != null && decoded.revision != frozen.preparation.intent.base.revision }
                    stage("COMPLETE")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { result["STOPPED"] = "true" }
            result.toMap()
        }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let { it.isAccessible = true; it.get(owner) }
    private fun shapeType(value: JsonElement?): String = when (value) {
        null -> "absent"
        JsonNull -> "null"
        is JsonObject -> "object"
        is JsonArray -> if (value.isEmpty()) "array-empty" else "array"
        is JsonPrimitive -> if (value.isString) "string" else if (value.booleanOrNull != null) "boolean" else "number"
    }

    /** Historical local fixture only; true does not qualify fresh remote context or dispatch. */
    fun profileDiagnostics(preparation: LocalEditPreparation): Map<String, String> = buildMap {
        fun check(name: String, predicate: () -> Boolean) {
            put(name, try { predicate().toString() } catch (_: Exception) { "false" })
        }
        val fragments = try {
            ProtonTitlePreparationCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)).openLocal(preparation)
        } catch (_: Exception) { put("LOCAL_OPEN", "false"); return@buildMap }
        put("LOCAL_OPEN", "true")
        val row = fragments.originalRow
        check("PROFILE_ALL") { ProtonTitleUpdateProfile.qualify(row); true }
        check("ROW_FIELDS_KNOWN") { row.keys.all { it in setOf("ID", "CalendarID", "SharedEventID", "CalendarKeyPacket", "CreateTime", "ModifyTime", "Permissions",
            "AddressKeyPacket", "AddressID", "SharedKeyPacket", "SharedEvents", "CalendarEvents", "AttendeesEvents", "Attendees",
            "AttendeesInfo", "IsProtonProtonInvite", "Notifications", "Color", "StartTime", "StartTimezone", "EndTime", "EndTimezone",
            "FullDay", "UID", "RecurrenceID", "Exdates", "RRule", "IsOrganizer", "IsPersonalSingleEdit", "VideoConferencingData",
            "LastEditTime", "NotificationOccurrences") } }
        check("CHANGED") { fragments.changed }
        check("PERMISSIONS_PERSONAL") { row.number("Permissions") in setOf(1L, 3L) }
        // Pinned upstream ATTENDEE_PERMISSIONS: SEE=1, INVITE=2, SEE_AND_INVITE=3.
        check("PERMISSIONS_EQUAL_SEE_1") { row.number("Permissions") == 1L }
        check("PERMISSIONS_EQUAL_INVITE_2") { row.number("Permissions") == 2L }
        check("PERMISSIONS_EQUAL_SEE_AND_INVITE_3") { row.number("Permissions") == 3L }
        check("IS_ORGANIZER") { row.number("IsOrganizer") == 1L }
        check("PERSONAL_SINGLE_EDIT_FALSE") { row["IsPersonalSingleEdit"] == JsonPrimitive(false) }
        listOf("ID", "CalendarID", "UID", "SharedKeyPacket").forEach { name -> check("NONBLANK_$name") { row.string(name).isNotBlank() } }
        listOf("Notifications", "CalendarEvents", "AttendeesEvents", "Exdates").forEach { name -> check("EMPTY_$name") { row.array(name).isEmpty() } }
        listOf("Color", "VideoConferencingData", "RRule", "RecurrenceID", "AddressKeyPacket", "CalendarKeyPacket").forEach { name ->
            check("NULL_$name") { row[name] == null || row[name] == JsonNull }
        }
        check("NO_PROTON_INVITE") { row["IsProtonProtonInvite"] == null || row["IsProtonProtonInvite"] == JsonNull || row.number("IsProtonProtonInvite") == 0L }
        check("ATTENDEES_EMPTY") { row["Attendees"] == null || row["Attendees"] == JsonNull || row.array("Attendees").isEmpty() }
        check("ATTENDEES_EMPTY_PROOF") { row["Attendees"] is JsonArray || row["AttendeesInfo"] is JsonObject }
        check("ATTENDEES_INFO_SHAPE") { row["AttendeesInfo"] == null || row["AttendeesInfo"] == JsonNull ||
            row.obj("AttendeesInfo").keys in setOf(setOf("Attendees", "MoreAttendees"), setOf("Attendees", "MoreAttendees", "Code")) }
        check("ATTENDEES_INFO_EMPTY") { row["AttendeesInfo"] == null || row["AttendeesInfo"] == JsonNull || row.obj("AttendeesInfo").array("Attendees").isEmpty() }
        check("ATTENDEES_INFO_NO_MORE") { row["AttendeesInfo"] == null || row["AttendeesInfo"] == JsonNull || row.obj("AttendeesInfo").number("MoreAttendees") == 0L }
        check("ATTENDEES_INFO_CODE_SUCCESS") { row["AttendeesInfo"] == null || row["AttendeesInfo"] == JsonNull ||
            "Code" !in row.obj("AttendeesInfo") || row.obj("AttendeesInfo").number("Code") == 1000L }
        check("LAST_EDIT_NONNEGATIVE") { "LastEditTime" !in row || row.number("LastEditTime") >= 0 }
        check("NOTIFICATION_OCCURRENCES_EMPTY") { "NotificationOccurrences" !in row || row.array("NotificationOccurrences").isEmpty() }
        check("CREATE_MODIFY_VALID") { row.number("CreateTime") >= 0 && row.number("ModifyTime") >= row.number("CreateTime") }
        check("FULL_DAY_VALID") { row.number("FullDay") in 0L..1L }
        check("END_AFTER_START") { row.number("EndTime") >= row.number("StartTime") }
        check("TIMEZONE_STRINGS") { row.string("StartTimezone"); row.string("EndTimezone"); true }
        check("ORIGINAL_PART_TYPES") { row.array("SharedEvents").map { (it as JsonObject).number("Type") }.sorted() == listOf(2L, 3L) }
        check("ORIGINAL_PART_SHAPES") { row.array("SharedEvents").all { (it as JsonObject).keys == setOf("Type", "Data", "Signature", "Author") &&
            listOf("Data", "Signature", "Author").all { name -> it.string(name).isNotBlank() } } }
        val prepared = listOf(fragments.clearSharedPart, fragments.encryptedSharedPart)
        check("PREPARED_PART_SHAPES") { prepared.all { it.keys == setOf("Type", "Data", "Signature", "Author") } }
        check("PREPARED_PART_AUTHORS_SAME") { prepared[0].string("Author").equals(prepared[1].string("Author"), ignoreCase = true) }
        check("PREPARED_PART_AUTHORS_ORIGINAL") { prepared.all { part -> row.array("SharedEvents").map { it as JsonObject }
            .single { it.number("Type") == part.number("Type") }.string("Author").equals(part.string("Author"), ignoreCase = true) } }
        // Context isn't retained in LocalEditPreparation. Only its fresh selector can qualify it.
    }

    /** API codes are not HTTP status. No response text, identifiers or arbitrary primitive values escape. */
    fun responseSummary(record: TitleUpdateAttemptRecord): Map<String, String> = try {
        val protector = ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)
        val sealed = record.protectedEvidence
        if (sealed == null) mapOf("RESULT" to "NO_EVIDENCE") else {
            val plain = protector.open(sealed)
            require(plain.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES && UniqueJsonKeys(plain).validate())
            val evidence = Json.parseToJsonElement(plain) as JsonObject
            require(evidence.string("kind") == "title-update-evidence" && evidence.number("version") == 1L &&
                evidence.string("attempt") == record.id.value &&
                evidence.string("payload") == TitleUpdateAttemptIdentity.of(record).payloadFingerprint)
            if (evidence["complete"] != JsonPrimitive(true)) mapOf("RESULT" to "NO_RESPONSE") else {
                val body = evidence.string("body")
                require(UniqueJsonKeys(body).validate())
                val response = Json.parseToJsonElement(body) as JsonObject
                buildMap {
                    put("RESULT", "RESPONSE")
                    fun code(key: String, value: JsonElement?) {
                        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                        if (number != null && number in 0..999999) put(key, number.toString())
                    }
                    code("API_CODE", response["Code"])
                    (response["Responses"] as? JsonArray)?.let { entries ->
                        put("RESPONSE_COUNT", entries.size.toString())
                        entries.take(4).forEachIndexed { index, entry ->
                            code("RESPONSE_${index}_CODE", (entry as? JsonObject)?.get("Response")?.let { (it as? JsonObject)?.get("Code") }
                                ?: (entry as? JsonObject)?.get("Code"))
                        }
                    }
                }
            }
        }
    } catch (_: Exception) { mapOf("RESULT" to "UNAVAILABLE") }

    fun rowShape(preparation: LocalEditPreparation): Map<String, String> = try {
        val row = ProtonTitlePreparationCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
            .openLocal(preparation).originalRow
        buildMap {
            fun shape(prefix: String, value: JsonObject) {
                value.forEach { (key, item) ->
                    val safe = key.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) } ?: "UNRECOGNIZED"
                    put("$prefix$safe", when (item) {
                        JsonNull -> "null"
                        is JsonObject -> if (item.isEmpty()) "object-empty" else "object"
                        is JsonArray -> if (item.isEmpty()) "array-empty" else "array"
                        is JsonPrimitive -> if (item.isString) "string" else if (item.booleanOrNull != null) "boolean" else "number"
                    })
                }
            }
            shape("row.", row)
            row.array("SharedEvents").forEach { value ->
                val part = value as JsonObject
                val type = part.number("Type").takeIf { it == 2L || it == 3L } ?: return@forEach
                shape("part$type.", part)
            }
            (row["AttendeesInfo"] as? JsonObject)?.let { shape("attendeesInfo.", it) }
        }
    } catch (_: Exception) { mapOf("RESULT" to "UNAVAILABLE") }
}
