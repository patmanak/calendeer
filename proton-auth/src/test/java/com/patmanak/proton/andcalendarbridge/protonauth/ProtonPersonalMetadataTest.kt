// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class ProtonPersonalMetadataTest {
    @Test fun frozenHistoricalV2EnvelopeAndIdentityStayExactAndUnknownNeverSendsAgain() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed()
        val golden = Json.parseToJsonElement(javaClass.getResource("/personal-v2-before-metadata.json")!!.readText()).jsonObject
        assertEquals("9645669", golden.string("sourceCommit"))
        assertEquals(golden.string("preparation"), f.values.getValue(record.preparation.protectedPayload))
        assertEquals(golden.string("request"), f.values.getValue(record.protectedPayload))
        assertEquals(golden.string("requestId"), record.id.value)
        assertEquals(golden.string("preparationFingerprint"), PersonalUpdateAttemptIdentity.of(record).preparationFingerprint)
        assertEquals(golden.string("payloadFingerprint"), PersonalUpdateAttemptIdentity.of(record).payloadFingerprint)
        assertTrue(f.journal.claim(record) { true })
        assertTrue(f.inspect(record.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)) is PersonalUpdateSubmissionObservation.Verified)
        assertEquals(0, f.count("send"))
    }
    @Test fun changedDisplayRemindersPreserveEmailAndVerifyOnlyExactReadback() = runTest {
        val f = PersonalSubmissionFixture(replace = { it.copy(details = EventDetails(listOf(EventReminder(0), EventReminder(-10), EventReminder(10)))) })
        val expected = Json.parseToJsonElement("""[{"Type":0,"Trigger":"-PT1H"},{"Type":1,"Trigger":"PT0S"},{"Type":1,"Trigger":"PT10M"},{"Type":1,"Trigger":"-PT10M"}]""")
        f.readRow = JsonObject(f.readRow + ("Notifications" to expected))
        f.decoded = f.decoded!!.copy(revision = f.readRow.revision(f.bootstrap))
        val record = f.seed()
        val opened = ProtonPersonalUpdateRequestCodec(f.protector).open(ProtonProtectedPersonalUpdateRequest(record.id, record.preparation, record.format, record.protectedPayload))
        val event = (opened.request.array("Events").single() as JsonObject).obj("Event")
        assertEquals(expected, event["Notifications"]); assertEquals(f.originalRow["Color"], event["Color"])
        assertTrue(f.submit(record) is PersonalUpdateSubmissionObservation.Verified)
        assertEquals(1, f.count("send"))
        val unknown = f.journal.stored!!
        f.readRow = JsonObject(f.readRow + ("Notifications" to Json.parseToJsonElement("""[{"Type":1,"Trigger":"PT0S"},{"Type":1,"Trigger":"PT10M"}]""")))
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.inspect(unknown))
        assertEquals(1, f.count("send"))
    }
    @Test fun removingAllDisplayRemindersKeepsEmailAndColorEditsKeepRawNotifications() = runTest {
        for (color in listOf(0, 0x123456, null)) {
            val f = PersonalSubmissionFixture(replace = { it.copy(colorRgb = color) })
            val expectedColor = color?.let { JsonPrimitive(String.format(java.util.Locale.ROOT, "#%06x", it)) } ?: JsonNull
            f.readRow = JsonObject(f.readRow + ("Color" to expectedColor))
            f.decoded = f.decoded!!.copy(revision = f.readRow.revision(f.bootstrap))
            val record = f.seed()
            assertTrue(f.submit(record) is PersonalUpdateSubmissionObservation.Verified)
            f.readRow = JsonObject(f.readRow + ("Color" to JsonPrimitive("#654321")))
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.inspect(f.journal.stored!!))
            assertEquals(1, f.count("send"))
        }
        val f = PersonalSubmissionFixture(replace = { it.copy(details = EventDetails(), colorRgb = null) })
        f.readRow = JsonObject(f.readRow + mapOf("Color" to JsonNull,
            "Notifications" to Json.parseToJsonElement("""[{"Type":0,"Trigger":"-PT1H"}]""")))
        f.decoded = f.decoded!!.copy(revision = f.readRow.revision(f.bootstrap))
        assertTrue(f.submit(f.seed()) is PersonalUpdateSubmissionObservation.Verified)
    }
    @Test fun requestRetainsRawNotificationOrderEmailAndColorSpellingAndReadbackRequiresThem() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed()
        val request = ProtonPersonalUpdateRequestCodec(f.protector).open(ProtonProtectedPersonalUpdateRequest(record.id,record.preparation,record.format,record.protectedPayload))
        val event = (request.request.array("Events").single() as JsonObject).obj("Event")
        assertEquals(f.originalRow["Notifications"],event["Notifications"]); assertEquals(JsonPrimitive("#aBcD12"),event["Color"])
        assertTrue(f.submit(record) is PersonalUpdateSubmissionObservation.Verified)
        assertEquals(1,f.count("send")); assertTrue(f.count("context") >= 3)
        val unknown = requireNotNull(f.journal.stored)
        f.readRow = JsonObject(f.readRow + ("Color" to JsonPrimitive("#ABCD12")))
        assertEquals(PersonalUpdateSubmissionObservation.Unknown,f.inspect(unknown))
        assertEquals(1,f.count("send"))
    }
    @Test fun changedOrUnqualifiedNotificationAndUnknownParticipantsNeverVerify() = runTest {
        for (variant in 0..4) {
            val f = PersonalSubmissionFixture(); val record = f.seed()
            f.readRow = when (variant) {
                0 -> JsonObject(f.readRow + ("Notifications" to JsonArray(emptyList())))
                1 -> JsonObject(f.readRow + ("Notifications" to Json.parseToJsonElement("""[{"Type":2,"Trigger":"-PT5M"}]""")))
                2 -> JsonObject(f.readRow + ("Notifications" to Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT5M","Unknown":1}]""")))
                3 -> JsonObject(f.readRow + ("AttendeesInfo" to Json.parseToJsonElement("""{"Attendees":[],"MoreAttendees":1}""")))
                else -> JsonObject(f.readRow + ("Unknown" to JsonPrimitive(1)))
            }
            assertEquals(PersonalUpdateSubmissionObservation.Unknown,f.submit(record))
            assertEquals(0,f.count("verify"))
        }
    }
    @Test fun changedSecondMembershipContextCannotCompleteVerifiedResult() = runTest {
        val f = PersonalSubmissionFixture(); val record = f.seed()
        var contexts = 0
        f.hook = { if (it == "context" && ++contexts == 3) f.freshContext = JsonObject(f.context + ("member" to JsonPrimitive("other"))) }
        assertEquals(PersonalUpdateSubmissionObservation.Unknown,f.submit(record))
        assertEquals(1,f.count("verify")); assertEquals(1,f.count("send"))
        assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN,f.journal.stored!!.state)
    }
}
