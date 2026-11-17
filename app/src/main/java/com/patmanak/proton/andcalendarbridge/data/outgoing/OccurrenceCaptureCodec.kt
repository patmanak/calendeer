// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** Local snapshot schema 4; request capsule v4 and historical snapshot schemas remain unchanged. */
internal object OccurrenceCaptureCodec {
    private val nativeProfile = AdapterFormat("calendeer-native-occurrence", 1)
    fun encode(intent: LocalCreateIntent): String {
        val candidate = requireNotNull(intent.occurrence)
        require(candidate.profile == nativeProfile)
        val original = candidate.original
        return JSONObject().put("schema", 4).put("profile", 1)
            .put("body", JSONObject(LocalCreateContentCodec.encodeContent(LocalCreateIntent(intent.reservation, intent.content))))
            .put("master", JSONObject(ImportPayloadCodec.encodeChange(candidate.master)))
            .put("kind", if (original is RecurrencePoint.AllDay) "DATE" else "TIMED")
            .put("original", when (original) { is RecurrencePoint.AllDay -> original.date.toString(); is RecurrencePoint.Timed -> original.instant.toString() }).toString()
    }
    fun decode(json: JSONObject, reservation: LocalCreateReservation): LocalCreateIntent {
        require(json.keys().asSequence().toSet() == setOf("schema", "profile", "body", "master", "kind", "original"))
        require(json.get("schema") == 4 && json.get("profile") == 1)
        val body = json.getJSONObject("body")
        require(body.get("schema") in listOf(1, 2))
        val flat = LocalCreateContentCodec.decodeIntent(body.toString(), reservation)
        require(same(body, JSONObject(LocalCreateContentCodec.encodeContent(flat))))
        val masterJson = json.getJSONObject("master")
        val master = ImportPayloadCodec.decodeChange(masterJson.toString()) as? RemoteChange.Upsert
            ?: throw IllegalArgumentException("Occurrence master must be present")
        require(same(masterJson, JSONObject(ImportPayloadCodec.encodeChange(master))))
        val original = when (json.getString("kind")) {
            "DATE" -> RecurrencePoint.AllDay(LocalDate.parse(json.getString("original")))
            "TIMED" -> RecurrencePoint.Timed(Instant.parse(json.getString("original")))
            else -> throw IllegalArgumentException("Unknown original slot kind")
        }
        require(json.getString("original") == when (original) { is RecurrencePoint.AllDay -> original.date.toString(); is RecurrencePoint.Timed -> original.instant.toString() })
        return flat.copy(occurrence = OccurrenceEditCandidate(master, original, flat.content).also { require(it.profile == nativeProfile) })
    }
    /** Private local uniqueness index, not a request/content fingerprint or remote identifier. */
    fun slotKey(intent: LocalCreateIntent): String {
        val candidate = requireNotNull(intent.occurrence)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        listOf("calendeer-occurrence-slot-v1", candidate.master.eventId.value, candidate.master.content.recurrence!!.uid,
            when (val slot = candidate.original) { is RecurrencePoint.AllDay -> "DATE:${slot.date}"; is RecurrencePoint.Timed -> "TIMED:${slot.instant}" }
        ).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
            a.keys().asSequence().all { same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        else -> a == b
    }
}
