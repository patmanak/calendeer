// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Sealed codecs with controlled transport/crypto/family observations; no service claim. */
class ProtonSeriesUpdateSubmissionTest {
    @Test fun capabilityRequiresEveryExplicitSeriesDependency() {
        for (missing in 0..3) {
            val f = Fixture(preparationEnabled = missing != 0, typedRemote = missing != 1,
                signedVerifier = missing != 2, familyVerifier = missing != 3)
            assertNull(f.adapter.seriesUpdateFormat)
            assertEquals(PERSONAL_UPDATE_REQUEST_FORMAT, f.adapter.personalUpdateFormat)
        }
        assertEquals(PERSONAL_SERIES_UPDATE_REQUEST_FORMAT, Fixture().adapter.seriesUpdateFormat)
    }
    @Test fun preparesDistinctFrozenRequestOnlyAfterFamilyQualification() = runTest {
        val f = Fixture()
        val record = f.adapter.prepareSubmission(MutationId("series-wire"), f.prepared) { f.allowed }
        assertNotNull(record); assertTrue(record!!.preparation.isSeries)
        assertEquals(PERSONAL_SERIES_UPDATE_REQUEST_FORMAT, record.format)
        assertTrue(f.count("family") > 0)
        assertEquals(f.intent.base, f.familyBases.single())
        assertEquals(0, f.count("sendSeries")); assertEquals(0, f.count("prepareSeries"))
        val blocked = Fixture(); blocked.familyAllowed = false
        try { blocked.adapter.prepareSubmission(MutationId("series-wire"), blocked.prepared) { true }; fail("Missing family accepted") }
        catch (_: LocalEditPreparationException) { }
    }
    @Test fun freshSeriesPreparationAndTypedSendReachExactSignedFamilyReadback() = runTest {
        val f = Fixture(); val record = f.seed()
        val result = f.submit(record)
        assertTrue(result is PersonalUpdateSubmissionObservation.Verified)
        assertEquals(1, f.count("prepareSeries")); assertEquals(1, f.count("sendSeries"))
        assertEquals(0, f.count("preparePersonal")); assertEquals(0, f.count("sendPersonal")); assertEquals(0, f.count("verifyPersonal"))
        assertEquals(1, f.count("verifySeries")); assertTrue(f.count("family") > 0)
        assertEquals(f.decoded, f.familyBases.last())
        assertEquals(f.intent.content, (result as PersonalUpdateSubmissionObservation.Verified).event.content)
        assertTrue(f.retained!!.preparation.isSeries)
        val evidence = Json.parseToJsonElement(f.p.values.getValue(f.retained!!.protectedEvidence!!)) as JsonObject
        assertEquals(JsonPrimitive("personal-series-v3-evidence"), evidence["kind"])
        assertEquals(JsonPrimitive(3), evidence["version"])
    }
    @Test fun unknownNeverPreparesClaimsOrSendsEvenWhenReentered() = runTest {
        val f = Fixture(); val unknown = f.seed().copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        f.retained = unknown
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(unknown))
        assertEquals(0, f.count("prepareSeries")); assertEquals(0, f.count("claim")); assertEquals(0, f.count("sendSeries"))
        assertTrue(f.inspect(unknown) is PersonalUpdateSubmissionObservation.Verified)
        assertEquals(0, f.count("prepareSeries")); assertEquals(0, f.count("claim")); assertEquals(0, f.count("sendSeries"))
    }
    @Test fun historicalUnknownInspectionDoesNotDependOnActivePreparationOrTypedSender() = runTest {
        val f = Fixture(preparationEnabled = false, typedRemote = false)
        assertNull(f.adapter.seriesUpdateFormat)
        val unknown = f.seed().copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN); f.retained = unknown
        assertTrue(f.inspect(unknown) is PersonalUpdateSubmissionObservation.Verified)
        assertEquals(0, f.count("prepareSeries")); assertEquals(0, f.count("sendSeries")); assertEquals(0, f.count("sendPersonal"))
    }
    @Test fun unavailableFreshFamilyOrCapabilityNeverClaims() = runTest {
        for (variant in 0..2) {
            val f = Fixture(preparationEnabled = variant != 0, typedRemote = variant != 1)
            if (variant == 2) f.preparationFamilyAllowed = false
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(f.seed()))
            assertEquals(0, f.count("claim")); assertEquals(0, f.count("sendSeries")); assertEquals(0, f.count("sendPersonal"))
        }
    }
    @Test fun postSendFamilyFailureOrWrongSignedObservationRetainsUnknown() = runTest {
        for (variant in 0..4) {
            val f = Fixture()
            when (variant) {
                0 -> f.familyAllowed = false
                1 -> f.decoded = null
                2 -> f.decoded = f.decoded!!.copy(revision = f.intent.base.revision)
                3 -> f.decoded = f.decoded!!.copy(content = f.intent.content.copy(recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY;COUNT=9")))
                4 -> f.row = JsonObject(f.row + ("RRule" to JsonPrimitive("FREQ=DAILY;COUNT=9")))
            }
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(f.seed()))
            assertEquals(1, f.count("sendSeries")); assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, f.retained!!.state)
            assertNotNull(f.retained!!.protectedEvidence)
        }
    }
    @Test fun lostResponseMayOnlyBeInspectedAndEvidenceCannotCrossProfiles() = runTest {
        val f = Fixture(); f.loseResponse = true; f.decoded = null
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.submit(f.seed()))
        val unknown = f.retained!!
        assertEquals(1, f.count("sendSeries"))
        f.decoded = f.intent.base.copy(revision = f.row.revision(f.p.bootstrap), content = f.intent.content)
        assertTrue(f.inspect(unknown) is PersonalUpdateSubmissionObservation.Verified)
        assertEquals(1, f.count("sendSeries")); assertEquals(1, f.count("prepareSeries"))
        val evidence = unknown.protectedEvidence!!
        f.p.values[evidence] = f.p.values.getValue(evidence).replace("personal-series-v3-evidence", "personal-edit-v2-evidence")
        assertEquals(PersonalUpdateSubmissionObservation.Unknown, f.inspect(unknown))
    }
    @Test fun leaseRevocationDuringSignedOrFamilyReadbackNeverVerifies() = runTest {
        for (stage in listOf("verifySeries", "family")) {
            val f = Fixture(); f.hook = { if (it == stage) f.allowed = false }
            val result = f.submit(f.seed())
            assertEquals(PersonalUpdateSubmissionObservation.Unknown, result)
            assertEquals(1, f.count("sendSeries")); assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, f.retained!!.state)
        }
    }

    private class Fixture(preparationEnabled: Boolean = true, typedRemote: Boolean = true,
        signedVerifier: Boolean = true, familyVerifier: Boolean = true) {
        val p = PersonalSubmissionFixture()
        val original = JsonObject(p.originalRow + ("RRule" to JsonPrimitive("FREQ=DAILY;COUNT=5")))
        val base = p.intent.base.copy(revision = original.revision(p.bootstrap),
            content = p.intent.base.content.copy(recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY;COUNT=5")))
        val intent = p.intent.copy(base = base, content = p.intent.content.copy(recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY;COUNT=8")),
            seriesProfile = SeriesEditProfile(CreateSeriesRule(5), CreateSeriesRule(8)))
        val prepared = ProtonPersonalPreparationCodec(p.protector).sealSeries(intent, "synthetic-uid", p.timestamp,
            ProtonTimestampedSharedPersonal(original, p.fragments.clearSharedPart, p.fragments.encryptedSharedPart, true))
        var row = JsonObject(p.readRow + ("RRule" to JsonPrimitive("FREQ=DAILY;COUNT=8")))
        var decoded: RemoteChange.Upsert? = intent.base.copy(revision = row.revision(p.bootstrap), content = intent.content)
        var retained: PersonalUpdateAttemptRecord? = null
        var allowed = true; var familyAllowed = true; var preparationFamilyAllowed = true; var loseResponse = false
        val steps = mutableListOf<String>()
        val familyBases = mutableListOf<RemoteChange.Upsert>()
        var hook: suspend (String) -> Unit = {}
        suspend fun step(name: String) { steps += name; hook(name) }
        fun count(name: String) = steps.count { it == name }
        val journal = object : PersonalUpdateAttemptJournal {
            override suspend fun remember(record: PersonalUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean = error("Unused")
            override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord? {
                step("read"); return retained?.takeIf { it.scope == scope && it.id == id && admit(scope) }
            }
            override suspend fun claim(expected: PersonalUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
                step("claim")
                if (!admit(expected.scope) || retained != expected) return false
                retained = expected.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN); return true
            }
            override suspend fun retainEvidence(expectedUnknown: PersonalUpdateAttemptRecord, protectedEvidence: String,
                admit: (CalendarScope) -> Boolean): Boolean {
                if (!admit(expectedUnknown.scope) || retained != expectedUnknown) return false
                retained = expectedUnknown.copy(protectedEvidence = protectedEvidence); return true
            }
        }
        val preparations = object : CalendarPersonalPreparationPort {
            override val personalFormat = PERSONAL_PREPARATION_FORMAT
            override val seriesFormat = PERSONAL_SERIES_PREPARATION_FORMAT.takeIf { preparationEnabled }
            override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant): PersonalEditPreparation = error("No unrestricted path")
            override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant, admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
                step("preparePersonal"); error("Series took personal path")
            }
            override suspend fun prepareSeries(intent: LocalEditIntent, timestamp: Instant, admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
                step("prepareSeries"); check(preparationFamilyAllowed); return prepared.takeIf { admit(p.scope) }
            }
        }
        val typed = object : SeriesUpdateSubmissionRemote {
            override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String {
                step("sendPersonal"); error("Wrong transport")
            }
            override suspend fun sendSeries(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String {
                step("sendSeries"); check(retained!!.state == PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
                if (loseResponse) throw java.io.IOException("synthetic")
                return "{\"Code\":1001}"
            }
            override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject { step("get"); return row }
        }
        val remote: TitleUpdateSubmissionRemote = if (typedRemote) typed else object : TitleUpdateSubmissionRemote {
            override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String = typed.send(session, calendar, request, allowsTransport)
            override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject = typed.event(session, calendar, id)
        }
        val adapter = ProtonPersonalUpdateSubmissionAdapter(preparations, p.protector, { p.initial },
            { _, _, checkpoint -> checkpoint(); step("context"); checkpoint(); p.context }, remote,
            object : EventImportRemote {
                override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No window")
                override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { step("bootstrap"); return p.bootstrap }
            }, CreateEventVerifier { _, _, _, _, _, _ -> step("verifyPersonal"); null },
            if (signedVerifier) SeriesFamilyEventVerifier { _, _, _, _, _ -> step("verifySeries"); decoded } else null,
            if (familyVerifier) PersonalSeriesQualification { _, _, observedBase, checkpoint ->
                checkpoint(); familyBases += observedBase; step("family"); check(familyAllowed); checkpoint()
            } else null)
        suspend fun seed(): PersonalUpdateAttemptRecord {
            val sealed = ProtonPersonalUpdateRequestCodec(p.protector).seal(MutationId("series-wire"), prepared, p.context)
            return PersonalUpdateAttemptRecord(sealed.id, sealed.preparation, sealed.format, sealed.protectedPayload).also { retained = it }
        }
        suspend fun submit(record: PersonalUpdateAttemptRecord) = adapter.submit(record, journal, { allowed }, { allowed }, { true })
        suspend fun inspect(record: PersonalUpdateAttemptRecord) = adapter.inspect(record, journal) { allowed }
    }
}
