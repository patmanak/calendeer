// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Structure and isolation only; synthetic fragments are not cryptographic evidence. */
class ProtonTimestampedSharedTextTest {
    @Test fun ReplacementFragmentsDoNotOverwriteOriginalSignaturesPacketsOrOpaqueContext() {
        val original = Json.parseToJsonElement("""{"SharedKeyPacket":"original-packet","SharedEvents":[
            {"Type":2,"Data":"old-clear","Signature":"old-clear-signature"},
            {"Type":3,"Data":"old-secret","Signature":"old-secret-signature"}],"Opaque":[1,null]}""") as JsonObject
        val clear = buildJsonObject { put("Type", 2); put("Data", "new-clear"); put("Signature", "new-clear-signature") }
        val secret = buildJsonObject { put("Type", 3); put("Data", "new-secret"); put("Signature", "new-secret-signature") }
        val actual = ProtonTimestampedSharedText(original, clear, secret, true)
        assertTrue(actual.changed)
        assertEquals(original, actual.originalRow)
        assertEquals(clear, actual.clearSharedPart)
        assertEquals(secret, actual.encryptedSharedPart)
        assertEquals("original-packet", actual.originalRow.string("SharedKeyPacket"))
        assertEquals("old-secret-signature", (actual.originalRow.array("SharedEvents")[1] as JsonObject).string("Signature"))
    }

    @Test fun MutableInputsAndNestedGetterCopiesCannotAlterHistoricalFragments() {
        val nested = mutableListOf<JsonElement>(JsonPrimitive("opaque"), JsonNull)
        val backing = mutableMapOf<String, JsonElement>("Opaque" to JsonArray(nested))
        val input = JsonObject(backing)
        val expected = Json.parseToJsonElement(input.toString())
        val actual = ProtonTimestampedSharedText(input, input, input, false)
        backing.clear(); nested.clear()
        assertFalse(actual.changed)
        assertEquals(expected, actual.originalRow)
        assertEquals(expected, actual.clearSharedPart)
        assertEquals(expected, actual.encryptedSharedPart)
        assertNotSame(actual.originalRow, actual.originalRow)
        assertNotSame(actual.clearSharedPart.array("Opaque"), actual.clearSharedPart.array("Opaque"))
        assertNotSame(actual.clearSharedPart.array("Opaque"), actual.encryptedSharedPart.array("Opaque"))
    }

    @Test fun DiagnosticsRedactAllThreeFragments() {
        val row = buildJsonObject { put("ID", "fixture-id") }
        val clear = buildJsonObject { put("Data", "fixture-cleartext") }
        val secret = buildJsonObject { put("Data", "fixture-ciphertext"); put("Signature", "fixture-signature") }
        assertEquals("ProtonTimestampedSharedText(redacted)", ProtonTimestampedSharedText(row, clear, secret, true).toString())
    }
}
