// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SeriesPersonalPreparationTest {
    private val scope = CalendarScope(AccountId("series-profile-fixture"), CalendarId("calendar"), 0)
    private val time = Instant.parse("2030-04-01T10:00:00Z")
    private val format = AdapterFormat("fixture-series-preparation", 3)
    private val request = AdapterFormat("fixture-series-request", 3)
    private val rule = CreateSeriesRule(5)
    private val base = RemoteChange.Upsert(EventId("master"), Revision("base"), EventContent("Series fixture",
        EventTime.Timed(time, time.plusSeconds(3600), ZoneId.of("UTC")), details = EventDetails(listOf(EventReminder(10))),
        colorRgb = 0x123456, recurrence = EventRecurrence("fixture@calendeer.invalid", rule.rule)))
    private fun intent(count: Int = 8, uid: String = base.content.recurrence!!.uid): LocalEditIntent {
        val before = base.copy(content = base.content.copy(recurrence = EventRecurrence(uid, rule.rule)))
        return LocalEditIntent(MutationId("local-series-edit"), scope, AdapterFormat("fixture-import", 1), before,
            before.content.copy(title = "Edited series", recurrence = EventRecurrence(uid, CreateSeriesRule(count).rule)),
            seriesProfile = SeriesEditProfile(rule, CreateSeriesRule(count)))
    }
    private fun preparation(intent: LocalEditIntent = intent()) = PersonalEditPreparation.forSeries(intent, time, format, "sealed-series-fixture")
    private fun refused(block: () -> Unit) {
        try { block(); fail("Expected profile refusal") } catch (_: IllegalArgumentException) { }
    }
    @Test fun explicitSeriesFactoryRetainsTheProfileWhileHistoricalConstructorStaysClosed() {
        val input = intent()
        refused { PersonalEditPreparation(input, time, format, "sealed-fixture") }
        refused { PersonalEditCandidate(input) }
        val prepared = preparation(input)
        assertEquals(input, prepared.intent)
        assertEquals(setOf("TITLE", "RULE"), prepared.changedFields.map { it.name }.toSet())
        assertEquals(prepared.intent, prepared.frozen().intent)
        assertEquals(prepared.protectedPayload, prepared.frozen().protectedPayload)
    }
    @Test fun simpleIntentCannotUseTheExplicitSeriesFactory() {
        val simple = base.copy(content = base.content.copy(recurrence = null))
        val input = LocalEditIntent(MutationId("simple"), scope, AdapterFormat("fixture", 1), simple, simple.content.copy(title = "Edited"))
        refused { preparation(input) }
        assertEquals(input, PersonalEditPreparation(input, time, format, "sealed-simple").frozen().intent)
    }
    @Test fun copiesOwnTheirListsAndCannotBeChangedByTheirCaller() {
        val reminders = mutableListOf(EventReminder(10))
        val input = intent().let { it.copy(base = it.base.copy(content = it.base.content.copy(details = EventDetails(reminders))),
            content = it.content.copy(details = EventDetails(reminders))) }
        val prepared = preparation(input)
        reminders.clear()
        assertEquals(listOf(EventReminder(10)), prepared.intent.base.content.details!!.reminders)
        assertEquals(listOf(EventReminder(10)), prepared.intent.content.details!!.reminders)
        assertEquals(prepared.intent, prepared.frozen().intent)
    }
    @Test fun seriesRequestIdentitiesBindRulesUidsAndTheOriginalCapsuleWithoutChangingSimpleNames() {
        val p = preparation()
        val id = PersonalUpdateAttemptIdentity.requestId(p, request)
        assertTrue(id.value.startsWith("personal-series-v3-"))
        assertNotEquals(id, PersonalUpdateAttemptIdentity.requestId(preparation(intent(9)), request))
        assertNotEquals(id, PersonalUpdateAttemptIdentity.requestId(preparation(intent(uid = "other@calendeer.invalid")), request))
        val record = PersonalUpdateAttemptRecord(id, p, request, "sealed-request")
        assertTrue(PersonalUpdateAttemptIdentity.of(record).matchesPreparation(p))
        assertEquals(record, record.frozen())
        assertEquals(record.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN), record.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN).frozen())
        val simpleBase = base.copy(content = base.content.copy(recurrence = null))
        val simple = PersonalEditPreparation(LocalEditIntent(MutationId("simple"), scope, format, simpleBase,
            simpleBase.content.copy(title = "Edited")), time, format, "sealed-simple")
        assertTrue(PersonalUpdateAttemptIdentity.requestId(simple, request).value.startsWith("personal-edit-v2-"))
    }
    @Test fun verifiedSeriesReadbackRequiresTheExactRuleUidAndPersonalContent() {
        val p = preparation()
        val record = PersonalUpdateAttemptRecord(MutationId("request"), p, request, "sealed-request", PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        val actual = p.intent.base.copy(revision = Revision("next"), content = p.intent.content)
        val observed = PersonalUpdateSubmissionObservation.Verified(record, actual)
        assertEquals(actual, PersonalUpdateVerifiedResult.of(record, observed).snapshot().event)
        for (content in listOf(actual.content.copy(recurrence = actual.content.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=9")),
            actual.content.copy(recurrence = actual.content.recurrence!!.copy(uid = "other@calendeer.invalid")),
            actual.content.copy(recurrence = null), actual.content.copy(colorRgb = 0x654321),
            actual.content.copy(details = EventDetails()))) {
            refused { PersonalUpdateSubmissionObservation.Verified(record, actual.copy(content = content)) }
        }
    }
}
