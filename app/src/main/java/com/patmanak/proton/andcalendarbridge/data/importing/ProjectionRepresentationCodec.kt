// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.ProjectedEventReference
import com.patmanak.proton.andcalendarbridge.sync.ProjectionRepresentation
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Separate from canonical event encoding. Unknown formats are never rewritten or downgraded. */
internal object ProjectionRepresentationCodec {
    fun encode(reference: ProjectedEventReference?): String? = reference?.let {
        JSONObject().put("id", it.representation.id).put("version", it.representation.version)
            .put("dates", JSONArray(it.dates.map(Instant::toString))).toString()
    }

    fun decode(canonical: RemoteChange.Upsert, payload: String?): ProjectedEventReference? {
        if (payload == null) return null
        try {
            require(payload.toByteArray(Charsets.UTF_8).size in 1..4096)
            val json = JSONObject(payload)
            require(json.length() == 3 && json.get("id") is String && json.get("version") is Int && json.get("dates") is JSONArray)
            val kind = ProjectionRepresentation.entries.singleOrNull { it.id == json.get("id") && it.version == json.get("version") }
                ?: throw IllegalArgumentException("Unknown representation")
            val dates = json.getJSONArray("dates")
            require(dates.length() <= 32)
            val instants = (0 until dates.length()).map { index ->
                val text = dates.get(index) as? String ?: throw IllegalArgumentException("Invalid date type")
                Instant.parse(text).also { require(it.toString() == text) }
            }
            val restored = ProjectedEventReference.restore(canonical, kind, instants)
            require(encode(restored) == payload)
            return restored
        } catch (_: Exception) {
            throw IllegalStateException("Invalid stored projection representation")
        }
    }
}
