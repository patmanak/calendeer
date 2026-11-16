// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit retained-series orchestration only; SQL/Provider and Proton are separate E3 evidence. */
class CoordinatorSystemSeriesOperationsTest {
    @Test fun explicitSeriesFreezeAndSnapshotPreserveTypedCapsuleAndRuleConsent() {
        val f = Fixture()
        assertTrue(SystemPersonalEditProfile.accepts(f.intent))
        assertEquals(f.intent, SystemPersonalEditProfile.freeze(f.intent))
        val snapshot = PersonalLocalOwnerSnapshot(f.intent, f.preparation)
        assertEquals(f.intent, snapshot.intent); assertTrue(snapshot.preparation!!.isSeries)
        assertNotSame(f.preparation, snapshot.preparation)
        val ruleOnly = f.intent.copy(content = f.intent.base.content.copy(recurrence = f.intent.content.recurrence))
        assertTrue(PersonalEditConsent.allowed(ruleOnly, { false }, { false }, { true }, { false }))
        assertFalse(PersonalEditConsent.allowed(ruleOnly, { true }, { true }, { false }, { true }))
        assertFalse(SystemPersonalEditProfile.accepts(f.intent.copy(state = LocalEditState.CONFLICT)))
        assertFalse(SystemPersonalEditProfile.requiresExtendedSnapshot(f.intent))
    }
    @Test fun explicitSeriesRouteUsesGuardedSeriesCapsuleAndDistinctWireIdentity() = runBlocking {
        val f = Fixture()
        assertFalse(f.process())
        assertEquals(1, f.calls.count { it == "series-capsule" })
        assertFalse("personal-capsule" in f.calls)
        assertEquals(1, f.calls.count { it == "series-submit" })
        val saved = requireNotNull(f.legacy.saved)
        assertTrue(saved.preparation.isSeries); assertEquals(f.seriesWire, saved.format)
        assertEquals(PersonalUpdateAttemptIdentity.requestId(f.preparation, f.seriesWire), saved.id)
        assertEquals(f.intent, saved.preparation.intent)
        assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, saved.state)
    }
    @Test fun missingSeriesCapabilityCannotFallBackToPersonalCapsuleOrWire() = runBlocking {
        for (variant in 0..1) {
            val f = Fixture()
            if (variant == 0) f.seriesCapsuleCapability = null else f.seriesWireCapability = null
            f.process()
            assertFalse("personal-capsule" in f.calls); assertFalse("series-capsule" in f.calls)
            assertFalse("wire" in f.calls); assertFalse("series-submit" in f.calls)
            assertNull(f.legacy.saved)
        }
    }
    @Test fun wrongCapsuleProfileOrFormatIsNeverRetainedOrSubmitted() = runBlocking {
        for (variant in 0..1) {
            val f = Fixture()
            f.fresh = if (variant == 0) f.legacy.preparation else PersonalEditPreparation.forSeries(f.intent,
                f.legacy.stamp, AdapterFormat("foreign-capsule", 3), "foreign")
            f.process()
            assertEquals(1, f.calls.count { it == "series-capsule" })
            assertFalse("retain" in f.calls); assertFalse("wire" in f.calls); assertFalse("series-submit" in f.calls)
        }
    }
    @Test fun retainedUnknownSeriesInspectionIgnoresSendingCapabilityAndNeverPreparesOrSubmits() = runBlocking {
        val f = Fixture()
        f.retained = f.preparation
        f.legacy.saved = PersonalUpdateAttemptRecord(PersonalUpdateAttemptIdentity.requestId(f.preparation, f.seriesWire),
            f.preparation, f.seriesWire, "existing-series-wire", PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        f.seriesCapsuleCapability = null; f.seriesWireCapability = null
        val progress = f.operations.inspect(TitleUpdateWorkQuery(f.legacy.scope), { true }) { fail("No proof expected") }
        assertEquals(1, progress.held)
        assertEquals(listOf("series-inspect"), f.calls)
        assertTrue(f.legacy.saved!!.preparation.isSeries)
    }
    @Test fun revokedSeriesPreparationLeasePreventsRetentionAndWire() = runBlocking {
        val f = Fixture()
        f.duringPreparation = { f.allowed = false }
        assertFalse(f.process())
        assertEquals(1, f.calls.count { it == "series-capsule" })
        assertFalse("retain" in f.calls); assertFalse("wire" in f.calls); assertNull(f.legacy.saved)
    }

    private class Fixture {
        val legacy = PersonalOperationsFixture()
        val seriesBase = legacy.intent.base.copy(content = legacy.base.copy(recurrence = EventRecurrence("synthetic-series", "FREQ=DAILY;COUNT=3")))
        val changed = legacy.changed.copy(recurrence = EventRecurrence("synthetic-series", "FREQ=DAILY;COUNT=4"))
        val intent = legacy.intent.copy(base = seriesBase, content = changed, seriesProfile = SeriesEditProfile.of(seriesBase.content, changed))
        val preparation = PersonalEditPreparation.forSeries(intent, legacy.stamp, AdapterFormat("series-capsule", 3), "sealed-series")
        val seriesWire = AdapterFormat("series-wire", 3)
        var seriesCapsuleCapability: AdapterFormat? = preparation.format
        var seriesWireCapability: AdapterFormat? = seriesWire
        var fresh = preparation
        var retained: PersonalEditPreparation? = null
        var allowed = true
        var duringPreparation: () -> Unit = {}
        val calls = mutableListOf<String>()
        private val owners = object : SystemPersonalLocalOwner {
            override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
                admit: (CalendarScope) -> Boolean): PersonalLocalOwnerSnapshot? {
                calls += "observe"; check(admit(scope)); return PersonalLocalOwnerSnapshot(intent, retained)
            }
            override suspend fun retain(preparation: PersonalEditPreparation, admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
                calls += "retain"; check(admit(legacy.scope)); retained = preparation.frozen(); return retained
            }
            override suspend fun qualify(preparation: PersonalEditPreparation, admit: (CalendarScope) -> Boolean): Boolean =
                preparation.isSeries && preparation.intent == intent && admit(legacy.scope)
            override suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateRecoveryJournal,
                port: CalendarPersonalUpdatePort, admit: (CalendarScope) -> Boolean,
                admitEvidence: (CalendarScope) -> Boolean) = port.submit(record, journal, admit, { it == legacy.scope && allowed }, admitEvidence)
            override suspend fun acknowledge(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
                admit: (CalendarScope) -> Boolean): Boolean = error("No verified result")
            override suspend fun complete(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
                admit: (CalendarScope) -> Boolean): Boolean = error("No completion")
        }
        private val capsules = object : CalendarPersonalPreparationPort {
            override val personalFormat = legacy.preparation.format
            override val seriesFormat get() = seriesCapsuleCapability
            override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant): PersonalEditPreparation = error("No unrestricted preparation")
            override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant, admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
                calls += "personal-capsule"; error("No series fallback")
            }
            override suspend fun prepareSeries(intent: LocalEditIntent, timestamp: Instant, admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
                calls += "series-capsule"; check(intent == this@Fixture.intent && admit(legacy.scope))
                duringPreparation(); return fresh.frozen()
            }
        }
        private val submissions = object : CalendarPersonalUpdatePort {
            override val personalUpdateFormat = legacy.wireFormat
            override val seriesUpdateFormat get() = seriesWireCapability
            override suspend fun prepareSubmission(id: MutationId, preparation: PersonalEditPreparation,
                admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord? {
                calls += "wire"; check(admit(legacy.scope) && preparation.isSeries)
                return PersonalUpdateAttemptRecord(id, preparation, seriesWire, "sealed-series-wire")
            }
            override suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
                admitEvidence: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
                calls += "series-submit"; check(record.preparation.isSeries && record.format == seriesWire)
                check(admit(record.scope) && qualifySource(record.scope) && journal.claim(record, admit))
                return PersonalUpdateSubmissionObservation.Unknown
            }
            override suspend fun inspect(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
                calls += "series-inspect"; check(admit(record.scope) && record.preparation.isSeries)
                return PersonalUpdateSubmissionObservation.Unknown
            }
        }
        val operations = CoordinatorSystemPersonalOperations(owners, legacy.journal, capsules, submissions,
            { error("No discovery") }, consentAdmission = { target -> { it.scope == target } })
        suspend fun process() = operations.process(legacy.scope, legacy.reference, MutationId("ignored"), legacy.stamp,
            { it == legacy.scope && allowed }) { fail("No verified proof expected") }
    }
}
