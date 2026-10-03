// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Artifact isolation only; fabricated signatures do not establish crypto or service behavior. */
class ProtonTimestampedSharedTitleTest {
    @Test fun renewedPartsRemainSeparateFromOriginalPartsAndKeyPacket() {
        val original = Json.parseToJsonElement("""{"SharedKeyPacket":"original-packet","SharedEvents":[
            {"Type":2,"Data":"old-clear","Signature":"old-clear-signature"},
            {"Type":3,"Data":"old-secret","Signature":"old-secret-signature"}],"Opaque":[1,null]}""") as JsonObject
        val clear = buildJsonObject { put("Type", 2); put("Data", "new-clear"); put("Signature", "new-clear-signature") }
        val secret = buildJsonObject { put("Type", 3); put("Data", "new-secret"); put("Signature", "new-secret-signature") }
        val actual = ProtonTimestampedSharedTitle(original, clear, secret, true)
        assertTrue(actual.changed)
        assertEquals(original, actual.originalRow)
        assertEquals(clear, actual.clearSharedPart)
        assertEquals(secret, actual.encryptedSharedPart)
        assertEquals("original-packet", actual.originalRow.string("SharedKeyPacket"))
        assertEquals("old-clear-signature", (actual.originalRow.array("SharedEvents")[0] as JsonObject).string("Signature"))
        assertEquals("old-secret-signature", (actual.originalRow.array("SharedEvents")[1] as JsonObject).string("Signature"))
    }

    @Test fun eachInputAndNestedGetterHasItsOwnFrozenCopy() {
        val nested = mutableListOf<JsonElement>(JsonPrimitive("opaque"))
        val backing = mutableMapOf<String, JsonElement>("Opaque" to JsonArray(nested))
        val shared = JsonObject(backing)
        val expected = Json.parseToJsonElement(shared.toString())
        val actual = ProtonTimestampedSharedTitle(shared, shared, shared, false)
        backing.clear(); nested.clear()
        assertFalse(actual.changed)
        assertEquals(expected, actual.originalRow)
        assertEquals(expected, actual.clearSharedPart)
        assertEquals(expected, actual.encryptedSharedPart)
        assertNotSame(actual.originalRow, actual.originalRow)
        assertNotSame(actual.clearSharedPart.array("Opaque"), actual.clearSharedPart.array("Opaque"))
        assertNotSame(actual.clearSharedPart.array("Opaque"), actual.encryptedSharedPart.array("Opaque"))
    }

    @Test fun diagnosticsRedactAllThreeFragments() {
        val original = buildJsonObject { put("ID", "synthetic-id") }
        val clear = buildJsonObject { put("Data", "synthetic-clear"); put("Signature", "synthetic-signature") }
        val secret = buildJsonObject { put("Data", "synthetic-ciphertext") }
        assertEquals("ProtonTimestampedSharedTitle(redacted)", ProtonTimestampedSharedTitle(original, clear, secret, true).toString())
    }
}
