// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real sealed codecs and controlled local protector; no signature/service claim. */
class ProtonSeriesPersonalCapsuleTest {
    private class Fixture(inherited: Boolean = false, changeReminders: Boolean = false) {
        val personal = PersonalSubmissionFixture(inherited = inherited)
        val row = JsonObject(personal.originalRow + ("RRule" to JsonPrimitive("FREQ=DAILY;COUNT=5")))
        val base = personal.intent.base.copy(revision = row.revision(personal.bootstrap),
            content = personal.intent.base.content.copy(recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY;COUNT=5")))
        val intent = personal.intent.copy(base = base, content = personal.intent.content.copy(
            recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY;COUNT=8"),
            details = if (changeReminders) EventDetails(listOf(EventReminder(20))) else personal.intent.content.details),
            seriesProfile = SeriesEditProfile(CreateSeriesRule(5), CreateSeriesRule(8)))
        val fragments = ProtonTimestampedSharedPersonal(row, personal.fragments.clearSharedPart, personal.fragments.encryptedSharedPart,
            true, ProtonPersonalMetadataEdit.captureDefaults(row, personal.bootstrap, intent.base.content, intent.content))
        val codec = ProtonPersonalPreparationCodec(personal.protector)
        fun prepare() = codec.sealSeries(intent, "synthetic-uid", personal.timestamp, fragments)
    }
    private fun refused(block: () -> Unit) {
        try { block(); fail("Expected protected profile refusal") }
        catch (_: ProtonPersonalPreparationException) { } catch (_: IllegalArgumentException) { }
    }
    @Test fun seriesPreparationUsesItsOwnVersionAndPreservesExactIdentityAndFragments() {
        val f = Fixture(); val prepared = f.prepare()
        assertTrue(prepared.isSeries); assertEquals(3, prepared.format.version); assertEquals(f.intent, prepared.intent)
        val reopened = f.codec.openLocal(prepared)
        assertEquals(f.row, reopened.originalRow); assertEquals(f.fragments.clearSharedPart, reopened.clearSharedPart)
        assertEquals(f.fragments.encryptedSharedPart, reopened.encryptedSharedPart)
        refused { f.codec.sealLocal(f.intent, "synthetic-uid", f.personal.timestamp, f.fragments) }
        refused { PersonalEditPreparation(prepared.intent, prepared.timestamp, prepared.format, prepared.protectedPayload) }
    }
    @Test fun wrongVersionKindOrProfileCannotReopenOrUpgradeHistoricalPayloads() {
        val f = Fixture(); val prepared = f.prepare()
        val plain = f.personal.values.getValue(prepared.protectedPayload)
        val root = Json.parseToJsonElement(plain) as JsonObject
        val variants = listOf(JsonObject(root + ("version" to JsonPrimitive(2))),
            JsonObject(root + ("kind" to JsonPrimitive("offline-personal-edit-v2-preparation"))),
            JsonObject(root + ("format" to buildJsonObject { put("id", prepared.format.id); put("version", 2) })),
            JsonObject(root + ("localIntent" to JsonObject(root.obj("localIntent") - "seriesProfile"))),
            JsonObject(root + ("future" to JsonPrimitive(true))))
        for (tampered in variants) {
            f.personal.values[prepared.protectedPayload] = tampered.toString()
            refused { f.codec.openLocal(prepared) }
        }
        f.personal.values[prepared.protectedPayload] = plain
        val old = f.personal.preparation
        refused { f.codec.openLocal(PersonalEditPreparation.forSeries(f.intent, old.timestamp, old.format, old.protectedPayload)) }
        assertEquals(f.row, f.codec.openLocal(prepared).originalRow)
    }
    @Test fun rawRuleOrUidCannotDisagreeWithTheSignedIntentBinding() {
        val f = Fixture()
        for (row in listOf(JsonObject(f.row + ("RRule" to JsonPrimitive("FREQ=DAILY;COUNT=6"))),
            JsonObject(f.row + ("UID" to JsonPrimitive("foreign"))))) {
            val parts = ProtonTimestampedSharedPersonal(row, f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true)
            refused { f.codec.sealSeries(f.intent, "synthetic-uid", f.personal.timestamp, parts) }
        }
        refused { f.codec.sealSeries(f.intent, "foreign", f.personal.timestamp, f.fragments) }
    }
    @Test fun inheritedAlertsStayNullOrFreezeDefaultEmailsOnlyWhenThePhoneRemindersChange() {
        for (changed in listOf(false, true)) {
            val f = Fixture(inherited = true, changeReminders = changed)
            val opened = f.codec.openLocal(f.prepare())
            assertEquals(JsonNull, opened.originalRow["Notifications"])
            assertEquals(changed, opened.defaults != null)
        }
    }
    @Test fun seriesRequestCapsuleBindsThePreparationProfileAndFrozenBreakingBit() = runTest {
        val f = Fixture(); val prepared = f.prepare()
        val id = PersonalUpdateAttemptIdentity.requestId(prepared, PERSONAL_SERIES_UPDATE_REQUEST_FORMAT)
        val codec = ProtonPersonalUpdateRequestCodec(f.personal.protector)
        val record = codec.seal(id, prepared, f.personal.context)
        assertEquals(3, record.format.version); assertTrue(record.preparation.isSeries)
        val opened = codec.open(record)
        assertEquals(JsonPrimitive(1), (opened.request.array("Events").single() as JsonObject).obj("Event")["IsBreakingChange"])
        val root = Json.parseToJsonElement(f.personal.values.getValue(record.protectedPayload)) as JsonObject
        val operation = opened.request.array("Events").single() as JsonObject
        val changedBit = JsonObject(opened.request + ("Events" to JsonArray(listOf(JsonObject(operation +
            ("Event" to JsonObject(operation.obj("Event") + ("IsBreakingChange" to JsonPrimitive(0)))))))))
        for (tampered in listOf(JsonObject(root + ("version" to JsonPrimitive(2))),
            JsonObject(root + ("kind" to JsonPrimitive("personal-edit-v2-request"))),
            JsonObject(root + ("request" to changedBit)))) {
            f.personal.values[record.protectedPayload] = tampered.toString()
            try { codec.open(record); fail("Cannot reinterpret or change a retained series request") } catch (_: ProtonPersonalUpdateRequestException) { }
        }
        f.personal.values[record.protectedPayload] = root.toString()
        assertEquals(opened.request, codec.open(record).request)
    }
}
