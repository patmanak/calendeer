// SPDX-License-Identifier: GPL-3.0-only
// Calendar sync DELETE operations follow the pinned Proton WebClients payload; see RECURRENCE_WRITES.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.Collections
import kotlinx.serialization.json.*

internal data class ProtonSeriesRemovalKey(val id: MutationId, val scope: CalendarScope, val masterId: EventId,
    val revision: Revision, val uid: String) {
    init {
        for (value in listOf(id.value, scope.account.value, scope.calendar.value, masterId.value, revision.value, uid)) {
            require(value.isNotBlank())
            deleteBounded(value, 4096)
        }
    }
    override fun toString() = "ProtonSeriesRemovalKey(redacted)"
}

/** Encrypted preparation for qualification. Carries no send, claim or acknowledgement capability. */
internal data class ProtonSealedSeriesRemoval(val key: ProtonSeriesRemovalKey, val protectedPayload: String) {
    init { require(protectedPayload.isNotBlank()); deleteBounded(protectedPayload) }
    override fun toString() = "ProtonSealedSeriesRemoval(redacted)"
}

internal class ProtonEntireSeriesBatch(members: List<Pair<EventId, Revision>>, context: JsonObject) {
    val members: List<Pair<EventId, Revision>> = Collections.unmodifiableList(members.toList())
    val context = deleteSnapshot(context)
    val request get() = deleteRequest(members.map { it.first }, context)
    fun reportedOutcomes(body: String): Map<EventId, ProtonDeleteResponse>? {
        val indexed = ProtonDeleteResponseParser.parseBatch(body, members.size) ?: return null
        return members.indices.associate { members[it].first to indexed[it] }
    }
    override fun toString() = "ProtonEntireSeriesBatch(redacted)"
}

internal class ProtonEntireSeriesRemovalCodec(private val protector: EventPayloadProtector) {
    fun seal(key: ProtonSeriesRemovalKey, plan: SeriesRemovalPlan, context: JsonObject): ProtonSealedSeriesRemoval {
        require(plan.updatedMaster == null && plan.deletions == plan.family)
        val batch = batch(key, plan.deletions, context)
        val plain = envelope(key, batch).toString().also { deleteBounded(it) }
        return ProtonSealedSeriesRemoval(key, protector.seal(plain))
    }

    fun open(sealed: ProtonSealedSeriesRemoval): ProtonEntireSeriesBatch {
        val plain = protector.open(sealed.protectedPayload)
        deleteBounded(plain)
        require(UniqueJsonKeys(plain).validate())
        val root = (Json.parseToJsonElement(plain) as JsonObject).also { deleteValidateJson(it) }
        val revisions = root.array("members")
        require(revisions.size in 1..367)
        val members = revisions.map {
            val row = it as JsonObject
            EventId(row.string("id")) to Revision(row.string("revision"))
        }
        val batch = batch(sealed.key, members, root.obj("context"))
        require(root == envelope(sealed.key, batch))
        return batch
    }

    private fun batch(key: ProtonSeriesRemovalKey, members: List<Pair<EventId, Revision>>, context: JsonObject): ProtonEntireSeriesBatch {
        require(members.size in 1..367 && members.last() == (key.masterId to key.revision))
        require(members.map { it.first }.distinct().size == members.size)
        for ((id, revision) in members) {
            deleteBounded(id.value, 4096)
            deleteBounded(revision.value, 4096)
        }
        validateDeleteContext(context)
        require(context.string("member").isNotBlank())
        return ProtonEntireSeriesBatch(members, context)
    }

    private fun envelope(key: ProtonSeriesRemovalKey, batch: ProtonEntireSeriesBatch) = buildJsonObject {
        put("kind", "entire-series-delete"); put("version", 1); put("requestId", key.id.value)
        put("account", key.scope.account.value); put("calendar", key.scope.calendar.value); put("generation", key.scope.generation)
        put("master", key.masterId.value); put("revision", key.revision.value); put("uid", key.uid)
        putJsonArray("members") {
            for ((id, revision) in batch.members) add(buildJsonObject { put("id", id.value); put("revision", revision.value) })
        }
        put("context", batch.context); put("request", batch.request)
    }
}
