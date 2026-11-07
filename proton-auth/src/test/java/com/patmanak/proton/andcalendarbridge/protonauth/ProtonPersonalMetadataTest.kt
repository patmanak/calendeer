// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class ProtonPersonalMetadataTest {
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
