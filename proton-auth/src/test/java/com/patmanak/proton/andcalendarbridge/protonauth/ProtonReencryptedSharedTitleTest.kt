// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Container isolation only; fabricated ciphertext is not cryptographic or service evidence. */
class ProtonReencryptedSharedTitleTest {
    @Test fun replacementStaysSeparateFromOriginalSignaturesAndOpaquePartitions() {
        val row = Json.parseToJsonElement("""{"SharedKeyPacket":"original-packet","SharedEvents":[
          {"Type":2,"Data":"clear","Signature":"clear-signature"},
          {"Type":3,"Data":"old","Signature":"old-signature","Author":"synthetic@example.invalid","Opaque":[1,null]}],
          "CalendarEvents":[{"Opaque":true}],"AttendeesEvents":[{"Unknown":"value"}]}""") as JsonObject
        val secret = row.array("SharedEvents")[1] as JsonObject
        val replacement = JsonObject(secret + mapOf("Data" to JsonPrimitive("new"), "Signature" to JsonPrimitive("new-signature")))
        val actual = ProtonReencryptedSharedTitle(row, replacement, changed = true)
        assertTrue(actual.changed)
        assertEquals(row, actual.originalRow)
        assertEquals(replacement, actual.encryptedSharedPart)
        assertEquals(secret - setOf("Data", "Signature"), actual.encryptedSharedPart - setOf("Data", "Signature"))
        assertEquals("old-signature", (actual.originalRow.array("SharedEvents")[1] as JsonObject).string("Signature"))
    }

    @Test fun constructorAndEveryAccessIsolateNestedMutableBackingCollections() {
        val nested = mutableListOf<JsonElement>(JsonPrimitive("opaque"))
        val map = mutableMapOf<String, JsonElement>("Opaque" to JsonArray(nested), "Signature" to JsonPrimitive("original"))
        val row = JsonObject(map)
        val replacement = JsonObject(map)
        val actual = ProtonReencryptedSharedTitle(row, replacement, changed = false)
        val expected = Json.parseToJsonElement(row.toString())
        nested.clear()
        map.clear()
        assertEquals(expected, actual.originalRow)
        assertEquals(expected, actual.encryptedSharedPart)
        assertFalse(actual.changed)
        assertNotSame(actual.originalRow, actual.originalRow)
        assertNotSame(actual.encryptedSharedPart.array("Opaque"), actual.encryptedSharedPart.array("Opaque"))
    }

    @Test fun diagnosticsContainNeitherDataNorSignaturesOrIdentity() {
        val row = buildJsonObject { put("ID", "private-id"); put("Data", "private-data"); put("Signature", "private-signature") }
        assertEquals("ProtonReencryptedSharedTitle(redacted)", ProtonReencryptedSharedTitle(row, row, true).toString())
    }
}
