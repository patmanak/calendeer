// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ProtonCreatedEventResponseTest {
    private val jsonType = "application/json".toMediaType()
    private fun parse(body: String) = body.toResponseBody(jsonType).use(::createdEventResponse)
    private fun refused(body: String) {
        try { parse(body); fail("Ambiguous or incomplete readback must be refused") }
        catch (_: Exception) { }
    }

    @Test fun uniqueNotificationFieldRemainsAnExactArrayForPolicyQualification() {
        val event = parse("""{"Code":1000,"Event":{"ID":"synthetic-event","Notifications":[]}}""").obj("Event")
        assertEquals(JsonArray(emptyList()), event.getValue("Notifications"))
        assertTrue(CreatedEventReadbackPolicy.KNOWN_EMPTY_V2.accepts(event))
    }

    @Test fun duplicatePersonalFieldCannotOverwriteANonEmptyOverrideWithAnEmptyOne() {
        refused("""{"Code":1000,"Event":{"Notifications":[{"Type":0,"Trigger":"-PT15M"}],"Notifications":[]}}""")
        refused("""{"Code":1000,"Event":{"Notifications":null,"Notifications":[]}}""")
    }

    @Test fun escapedEquivalentNestedAndRootNamesAreAlsoAmbiguous() {
        refused("""{"Code":1000,"Event":{"Notifications":[{}],"\u004eotifications":[]}}""")
        refused("""{"Code":1000,"Events":[{"ID":"foreign-event","\u0049D":"synthetic-event","Notifications":[]}]}""")
        refused("""{"Code":1000,"\u0043ode":1000,"Event":{"Notifications":[]}}""")
        refused("""{"Code":1000,"Event":{"Notifications":[]},"Event":{"Notifications":[]}}""")
    }

    @Test fun truncatedInvalidOrNonSuccessfulBodiesCannotBecomeReadbackEvidence() {
        for (body in listOf("", "[]", "null", """{"Code":1000,"Event":{"Notifications":[]}""",
            """{"Code":1000,"Events":[{"Notifications":[]}]} garbage""",
            """{"Code":"1000","Event":{"Notifications":[]}}""", """{"Code":1001,"Event":{"Notifications":[]}}""")) refused(body)
    }

    @Test fun duplicateKeysInsideUnknownFieldsAreStillRefusedBeforeJsonNormalization() {
        refused("""{"Code":1000,"Event":{"Notifications":[],"Other":{"x":0,"x":1}}}""")
    }

    @Test fun excessiveBytesAndInvalidUtf8CannotProduceEvidence() {
        refused("""{"Code":1000,"Other":"${"é".repeat(524_288)}"}""")
        try { byteArrayOf(0xc3.toByte(), 0x28).toResponseBody(jsonType).use(::createdEventResponse); fail("Invalid UTF-8 accepted") }
        catch (_: Exception) { }
    }

    @Test fun excessiveNestingIsRefusedBeforeJsonParsing() {
        refused("""{"Code":1000,"Other":${"[".repeat(65)}0${"]".repeat(65)}}""")
    }
}
