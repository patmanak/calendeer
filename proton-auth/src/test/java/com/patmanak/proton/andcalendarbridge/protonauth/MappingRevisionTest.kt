package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MappingRevisionTest {
    private val row = obj("""{"ID":"event","CalendarID":"calendar","ModifyTime":12,"Notifications":null}""")
    private fun context(trigger: String) = obj("""{"CalendarSettings":{"CalendarID":"calendar","DefaultPartDayNotifications":[{"Type":1,"Trigger":"$trigger"}],"DefaultFullDayNotifications":[]}}""")

    @Test fun inheritedSettingChangesProduceDistinctContentRevisionsAtTheSameRemoteTimestamp() {
        assertNotEquals(row.revision(context("-PT15M")), row.revision(context("-PT30M")))
        assertEquals(row.revision(context("-PT15M")), row.revision(context("-PT15M")))
    }

    @Test fun unavailableAndExplicitlyEmptyDefaultsAreDifferentContexts() {
        val empty = obj("""{"CalendarSettings":{"CalendarID":"calendar","DefaultPartDayNotifications":[],"DefaultFullDayNotifications":[]}}""")
        assertNotEquals(row.revision(), row.revision(empty))
    }

    @Test fun keyBootstrapRefreshDoesNotInventAContentChange() {
        val before = context("-PT15M")
        val after = JsonObject(before + mapOf("Keys" to JsonArray(emptyList()), "Passphrase" to JsonObject(emptyMap())))
        assertEquals(row.revision(before), row.revision(after))
    }

    @Test fun clearParticipationChangesAreNotHiddenByAnUnchangedModifyTime() {
        val before = JsonObject(row + ("Attendees" to Json.parseToJsonElement("""[{"Token":"fixture","Status":0}]""")))
        val after = JsonObject(row + ("Attendees" to Json.parseToJsonElement("""[{"Token":"fixture","Status":3}]""")))
        assertNotEquals(before.revision(context("-PT15M")), after.revision(context("-PT15M")))
    }

    @Test fun newMappingNeverReusesTheLegacyContentRevision() {
        assertNotEquals(row.legacyRevision(), row.revision())
        assertNotEquals(row.legacyRevision(), row.revision(context("-PT15M")))
    }

    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
}
