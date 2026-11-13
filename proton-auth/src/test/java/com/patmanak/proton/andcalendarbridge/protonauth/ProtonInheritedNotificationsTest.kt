// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import com.patmanak.proton.andcalendarbridge.sync.*
import org.junit.Assert.*
import org.junit.Test

class ProtonInheritedNotificationsTest {
    private val explicit = Json.parseToJsonElement("""[{"Type":0,"Trigger":"-PT1H"},{"Type":1,"Trigger":"-PT10M"}]""")
    @Test fun nullIsQualifiedButMissingNotificationsRemainUnsupported() {
        assertTrue(ProtonPersonalMetadata.valid(JsonNull, JsonNull))
        assertFalse(ProtonPersonalMetadata.valid(null, JsonNull))
    }

    @Test fun unchangedRemindersRetainNullThroughOfflineRequestAndExactReadback() = runTest {
        val f = PersonalSubmissionFixture()
        val original = JsonObject(f.originalRow + ("Notifications" to JsonNull))
        val fragments = ProtonTimestampedSharedPersonal(original, f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true)
        val preparation = f.prepare(fragments)
        val codec = ProtonPersonalUpdateRequestCodec(f.protector)
        val opened = codec.open(codec.seal(com.patmanak.proton.andcalendarbridge.sync.MutationId("null-fixture"), preparation, f.context))
        assertEquals(JsonNull, opened.fragments.originalRow["Notifications"])
        assertEquals(JsonNull, (opened.request.array("Events").single() as JsonObject).obj("Event")["Notifications"])
        val actual = JsonObject(f.readRow + ("Notifications" to JsonNull))
        assertTrue(ProtonPersonalEventUpdate.matchesReadback(original, fragments.clearSharedPart, fragments.encryptedSharedPart, actual, f.intent.base.content, f.intent.content))
        assertFalse(ProtonPersonalEventUpdate.matchesReadback(original, fragments.clearSharedPart, fragments.encryptedSharedPart, f.readRow, f.intent.base.content, f.intent.content))
    }

    @Test fun changedRemindersFreezeDefaultsAndUnknownInspectsWithoutResending() = runTest {
        val f = PersonalSubmissionFixture(replace = { it.copy(details = EventDetails(listOf(EventReminder(10)))) }, inherited = true)
        f.readRow = JsonObject(f.readRow + ("Notifications" to explicit))
        f.decoded = f.decoded!!.copy(revision = f.readRow.revision(f.bootstrap))
        val record = f.seed()
        val codec = ProtonPersonalUpdateRequestCodec(f.protector)
        val opened = codec.open(ProtonProtectedPersonalUpdateRequest(record.id, record.preparation, record.format, record.protectedPayload))
        assertEquals(JsonNull, opened.fragments.originalRow["Notifications"])
        assertEquals(f.fragments.defaults, opened.fragments.defaults)
        assertEquals(explicit, (opened.request.array("Events").single() as JsonObject).obj("Event")["Notifications"])
        assertTrue(f.submit(record) is PersonalUpdateSubmissionObservation.Verified)
        val unknown = requireNotNull(f.journal.stored)
        assertTrue(f.inspect(unknown) is PersonalUpdateSubmissionObservation.Verified)
        for (wrong in listOf(JsonNull, JsonArray(emptyList()), Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT10M"}]"""))) {
            f.readRow = JsonObject(f.readRow + ("Notifications" to wrong))
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.inspect(unknown))
        }
        assertEquals(1, f.count("send"))
    }

    @Test fun initialKindSelectsDefaultsEvenWhenEditChangesKindAndRemovingAlertsPreservesEmail() {
        val f = PersonalSubmissionFixture(inherited = true)
        val timed = f.intent.base.content
        val day = timed.copy(time = EventTime.AllDay(java.time.LocalDate.of(2026,10,24), java.time.LocalDate.of(2026,10,25)))
        for ((before, after, email) in listOf(
            Triple(timed, day.copy(details = EventDetails()), "-PT1H"),
            Triple(day, timed.copy(details = EventDetails()), "-P1D"))) {
            val original = JsonObject(f.originalRow + ("FullDay" to JsonPrimitive(if (before.time is EventTime.AllDay) 1 else 0)))
            val defaults = requireNotNull(ProtonPersonalMetadataEdit.captureDefaults(original, f.bootstrap, before, after))
            val result = ProtonPersonalMetadataEdit.apply(original, before, after, defaults)
            assertEquals(Json.parseToJsonElement("""[{"Type":0,"Trigger":"$email"}]"""), result["Notifications"])
            assertEquals(JsonNull, original["Notifications"])
        }
    }

    @Test fun missingWrongCalendarWrongKindAndUnqualifiedDefaultsAreRejected() {
        val f = PersonalSubmissionFixture(replace = { it.copy(details = EventDetails()) }, inherited = true)
        val defaults = requireNotNull(f.fragments.defaults)
        val variants = listOf<JsonObject?>(null, JsonObject(defaults + ("CalendarID" to JsonPrimitive("other"))),
            JsonObject(defaults + ("FullDay" to JsonPrimitive(1))),
            JsonObject(defaults + ("Notifications" to JsonArray(List(101) { buildJsonObject { put("Type", 0); put("Trigger", "-PT1H") } }))),
            JsonObject(defaults + ("Notifications" to Json.parseToJsonElement("""[{"Type":2,"Trigger":"-PT5M"}]"""))),
            JsonObject(defaults + ("Notifications" to Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT1M"}]"""))))
        for (value in variants) {
            try {
                f.prepare(ProtonTimestampedSharedPersonal(f.originalRow, f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true, value))
                fail("Expected unsupported defaults")
            } catch (expected: ProtonPersonalPreparationException) { assertEquals(ProtonPersonalPreparationFailure.UNSUPPORTED, expected.reason) }
        }
    }

    @Test fun defaultsAreOwnedAndCapsuleRejectsMissingExtraOrModifiedSnapshot() {
        val f = PersonalSubmissionFixture(replace = { it.copy(details = EventDetails()) }, inherited = true)
        val mutable = requireNotNull(f.fragments.defaults).toMutableMap()
        val owned = ProtonTimestampedSharedPersonal(f.originalRow, f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true, JsonObject(mutable))
        mutable["CalendarID"] = JsonPrimitive("other")
        assertEquals(f.fragments.defaults, owned.defaults)
        val preparation = f.prepare(owned)
        val plain = Json.parseToJsonElement(f.values.getValue(preparation.protectedPayload)).jsonObject
        assertEquals(f.fragments.defaults, plain["notificationDefaults"])
        val codec = ProtonPersonalPreparationCodec(f.protector)
        val roundtrip = codec.openLocal(preparation)
        assertEquals(JsonNull, roundtrip.originalRow["Notifications"])
        assertEquals(owned.defaults, roundtrip.defaults)
        for (root in listOf(JsonObject(plain - "notificationDefaults"),
            JsonObject(plain + ("unknown" to JsonNull)),
            JsonObject(plain + ("notificationDefaults" to JsonObject(requireNotNull(owned.defaults) + ("FullDay" to JsonPrimitive(1))))))) {
            f.values[preparation.protectedPayload] = root.toString()
            try { codec.openLocal(preparation); fail("Expected unsupported capsule") }
            catch (expected: ProtonPersonalPreparationException) { assertEquals(ProtonPersonalPreparationFailure.UNSUPPORTED, expected.reason) }
        }
    }

    @Test fun nullKindTransitionsWithUnchangedRemindersRejectDifferentDestinationDisplayDefaults() {
        val f = PersonalSubmissionFixture(inherited = true)
        val timed = f.intent.base.content
        val day = timed.copy(time = EventTime.AllDay(java.time.LocalDate.of(2026,10,24), java.time.LocalDate.of(2026,10,25)))
        for ((before, after) in listOf(timed to day, day to timed)) {
            val initialDay = before.time is EventTime.AllDay
            val original = JsonObject(f.originalRow + ("FullDay" to JsonPrimitive(if (initialDay) 1 else 0)))
            val settings = f.bootstrap.obj("CalendarSettings")
            val bootstrap = JsonObject(f.bootstrap + ("CalendarSettings" to JsonObject(settings +
                ((if (initialDay) "DefaultPartDayNotifications" else "DefaultFullDayNotifications") to
                    Json.parseToJsonElement("""[{"Type":0,"Trigger":"-PT1H"},{"Type":1,"Trigger":"-PT15M"}]""")))))
            try {
                ProtonPersonalMetadataEdit.captureDefaults(original, bootstrap, before, after)
                fail("Expected unsupported inherited destination reminders")
            } catch (expected: RemoteImportException) { assertEquals(RemoteImportFailure.UNSUPPORTED, expected.reason) }
        }
    }

    @Test fun nullKindTransitionsWithEqualDisplayDefaultsAllowDifferentEmailsAndKeepNull() {
        val f = PersonalSubmissionFixture(inherited = true)
        val timed = f.intent.base.content
        val day = timed.copy(time = EventTime.AllDay(java.time.LocalDate.of(2026,10,24), java.time.LocalDate.of(2026,10,25)))
        for ((before, after) in listOf(timed to day, day to timed)) {
            val original = JsonObject(f.originalRow + ("FullDay" to JsonPrimitive(if (before.time is EventTime.AllDay) 1 else 0)))
            // The fixture has the same five-minute display defaults but distinct timed/day email offsets.
            assertNull(ProtonPersonalMetadataEdit.captureDefaults(original, f.bootstrap, before, after))
            assertEquals(JsonNull, ProtonPersonalMetadataEdit.apply(original, before, after)["Notifications"])
        }
    }
}
