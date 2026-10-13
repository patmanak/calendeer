// SPDX-License-Identifier: GPL-3.0-only
// Same-calendar UPDATE input adaptation from Proton WebClients; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.serialization.json.*

internal val TITLE_UPDATE_SUBMISSION_FORMAT = AdapterFormat("proton-calendar-title-update-submission", 1)
internal class ProtonTitleUpdateSubmission(val fragments: ProtonTimestampedSharedTitle, val context: JsonObject,
    val request: JsonObject) { override fun toString() = "ProtonTitleUpdateSubmission(redacted)" }

internal fun titleUpdateOnlyBytes(payload: String): ByteArray = simpleEventUpdateOnlyBytes(payload)

/** Historical title entry point keeps its exact profile and response semantics. */
internal object ProtonTitleUpdateProfile {
    fun qualify(row: JsonObject) = ProtonSimpleEventUpdate.qualify(row)
    fun matchesReadback(original: JsonObject, fragments: ProtonTimestampedSharedTitle, actual: JsonObject): Boolean =
        ProtonSimpleEventUpdate.matchesReadback(original, fragments.clearSharedPart, fragments.encryptedSharedPart, actual)
}

internal class ProtonTitleUpdateSubmissionCodec(private val protector: EventPayloadProtector) {
    fun seal(id: MutationId, preparation: LocalEditPreparation, context: JsonObject): TitleUpdateAttemptRecord {
        val frozen = preparation.copy(intent = LocalEditPreparation.freezeIntent(preparation.intent))
        val fragments = ProtonTitlePreparationCodec(protector).openLocal(frozen)
        val request = request(fragments, context)
        val root = envelope(id, frozen, context, request)
        return TitleUpdateAttemptRecord(id, frozen, TITLE_UPDATE_SUBMISSION_FORMAT, protector.seal(root.toString()))
    }
    fun open(record: TitleUpdateAttemptRecord): ProtonTitleUpdateSubmission {
        val frozen = record.frozen()
        require(frozen.format == TITLE_UPDATE_SUBMISSION_FORMAT)
        val fragments = ProtonTitlePreparationCodec(protector).openLocal(frozen.preparation)
        val plain = protector.open(frozen.protectedPayload)
        require(plain.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES && UniqueJsonKeys(plain).validate())
        val root = Json.parseToJsonElement(plain) as JsonObject
        val context = root.obj("context")
        val request = request(fragments, context)
        require(root == envelope(frozen.id, frozen.preparation, context, request))
        return ProtonTitleUpdateSubmission(fragments, context, request)
    }
    private fun request(fragments: ProtonTimestampedSharedTitle, context: JsonObject): JsonObject =
        ProtonSimpleEventUpdate.request(fragments.originalRow, fragments.clearSharedPart,
            fragments.encryptedSharedPart, fragments.changed, context)
    private fun envelope(id: MutationId, preparation: LocalEditPreparation, context: JsonObject, request: JsonObject) = buildJsonObject {
        put("kind", "title-update-submission"); put("version", 1); put("attempt", id.value)
        put("preparation", hash(preparation.protectedPayload)); put("context", context); put("request", request)
    }
    companion object {
        val CONTEXT_KEYS = ProtonSimpleEventUpdate.CONTEXT_KEYS
        fun hash(value: String) = ProtonSimpleEventUpdate.hash(value)
        fun context(selection: ProtonCreateSelection, calendar: JsonObject, bootstrap: JsonObject) =
            ProtonSimpleEventUpdate.context(selection, calendar, bootstrap)
    }
}
