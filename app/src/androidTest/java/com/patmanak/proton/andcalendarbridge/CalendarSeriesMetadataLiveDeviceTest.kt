// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonTemporalMetadataProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSeriesDiscoveryProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateTransportProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.SeriesMetadataShape
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit retained-session observations; fixture creation requires a separate mutation opt-in. */
@RunWith(AndroidJUnit4::class)
class CalendarSeriesMetadataLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cachedTestMailEntitlementIsObservedWithoutMutation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("seriesEntitlement") == "true")
        val identityHash = args.getString("createAccountHash").orEmpty()
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated && app.imports.state.value.selections.any { it.available } }
        val scope = app.imports.state.value.selections.first { it.available }.scope
        val result = ProtonTemporalMetadataProbe.cachedPaidMail(app, scope, identityHash) {
            app.imports.state.value.authenticated && app.imports.state.value.selections.any { it.available && it.scope == scope }
        }
        report(Bundle().apply { putString("series_entitlement", result) })
        assertTrue("Exact cached test entitlement required", result != "UNAVAILABLE")
    }

    /** Separate explicit mutation opt-in. Exact drafts and UNKNOWN work survive process restart. */
    @Test fun seedTwoRetainedSyntheticSeriesAndInspectTheirSignedMetadata() = runBlocking { seed(false) }
    @Test fun diagnoseOneDistinctSyntheticTransportAdmissionWithoutResendingUnknownWork() = runBlocking { seed(true) }
    @Test fun inspectDistinctUncolouredSeriesWithoutReplayingEarlierUnknownWork() = runBlocking { seed(true, colourless = true) }

    private suspend fun seed(transport: Boolean, colourless: Boolean = false) = withTimeout(240_000) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString(if (transport) "seriesTransportSeed" else "seriesMetadataSeed") == "true")
        val identityHash = args.getString("createAccountHash").orEmpty()
        assertTrue("Exact authorized account required", identityHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated && app.imports.state.value.selections.any { it.available } }
        val prefs = app.getSharedPreferences("series-metadata-qa-v1", Context.MODE_PRIVATE)
        val available = app.imports.state.value.selections.filter { it.available }.map { it.scope }
        val scope = if (prefs.contains("token")) CalendarScope(AccountId(prefs.getString("account", null)!!),
            CalendarId(prefs.getString("calendar", null)!!), prefs.getLong("generation", -1)) else available.first()
        fun lease() = app.imports.state.value.authenticated && app.imports.state.value.selections.any { it.scope == scope && it.available }
        assertTrue("Existing scoped fixture calendar required", lease())
        assertTrue("Authorize exact test account", ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, identityHash))
        if (!prefs.contains("token")) assertTrue("Freeze fixture identity before preparation", prefs.edit()
            .putString("token", UUID.randomUUID().toString().replace("-", "").take(12))
            .putString("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .putString("account", scope.account.value).putString("calendar", scope.calendar.value).putLong("generation", scope.generation).commit())
        val token = prefs.getString("token", null)!!
        val stamp = Instant.parse(prefs.getString("timestamp", null))
        val port = ProtonAuthentication.createSubmissions(app)
        assertTrue("Series CREATE profile must be available", port.seriesSubmissionFormat == AdapterFormat("proton-calendar-create-submission", 4))
        var unresolved = false
        for (allDay in if (transport && !colourless) listOf(false) else listOf(false, true)) {
            assertTrue("Current account and calendar required", lease())
            assertTrue("Authorize exact test account", ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, identityHash))
            val kind = if (colourless) "transport-uncoloured-${if (allDay) "all-day" else "timed"}"
                else if (transport) "transport" else if (allDay) "all-day" else "timed"
            val time = if (allDay) EventTime.AllDay(LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-24"))
                else EventTime.Timed(Instant.parse("2026-11-23T10:00:00Z"), Instant.parse("2026-11-23T11:00:00Z"), ZoneOffset.UTC)
            val id = MutationId("series-metadata-$kind-$token")
            val draft = CalendarCreateDraft(scope, id, stamp, EventContent("Calendeer QA Series NET $kind $token", time,
                details = EventDetails(), colorRgb = if (colourless) null else 0x7868e6), CreateSeriesRule(3))
            val observation = CreateAttemptStore(app, "series-metadata-qa-v1.db").use { journal ->
                journal.admitDispatch = { it == scope && lease() }
                val existing = journal.read(scope, id)
                val record = existing ?: run {
                    assertTrue("A requested fixture must never be regenerated", !prefs.getBoolean("requested-$kind", false))
                    val receipt = port.prepareSubmission(draft)
                    assertTrue("Exact series format required", receipt.format == port.seriesSubmissionFormat)
                    CreateAttemptRecord(id, scope, receipt.format, receipt.protectedPayload).also { journal.remember(it) }
                }
                val result = if (record.state == CreateAttemptState.OUTCOME_UNKNOWN) port.inspectCaptured(record, draft)
                else {
                    assertTrue("A submitted fixture cannot be sent twice", !prefs.getBoolean("requested-$kind", false))
                    assertTrue("Freeze the one send request", prefs.edit().putBoolean("requested-$kind", true).commit())
                    if (transport) ProtonCreateTransportProbe.submit(app, record, draft, journal, identityHash, ::lease) { category ->
                        assertTrue("Retain closed transport category", prefs.edit().putString("transport-category-$kind", category).commit())
                    } else port.submitCaptured(record, draft, journal)
                }
                val retained = requireNotNull(journal.read(scope, id))
                report(Bundle().apply {
                    putBoolean("series_seed_unknown", retained.state == CreateAttemptState.OUTCOME_UNKNOWN)
                    putString("series_seed_response", ProtonCreateSubmissionProbe.responseSummary(retained))
                    putString("series_seed_permit", ProtonCreateSubmissionProbe.retainedPermitSummary(retained))
                    if (transport) putString("series_transport", prefs.getString("transport-category-$kind",
                        if (kind == "transport") prefs.getString("transport-category", "NOT_ENTERED") else "NOT_ENTERED"))
                })
                result
            }
            report(Bundle().apply { putBoolean("series_seed_verified", observation is CreateSubmissionObservation.Verified) })
            val actual = (observation as? CreateSubmissionObservation.Verified)?.event ?: run {
                unresolved = true
                val retained = CreateAttemptStore(app, "series-metadata-qa-v1.db").use { requireNotNull(it.read(scope, id)) }
                val inspected = ProtonTemporalMetadataProbe.retainedSeries(app, retained, draft, identityHash, ::lease)
                report(Bundle().apply { putString("series_seed_read", inspected.first) })
                inspected.second
            }
            if (actual == null) { if (transport) break else continue }
            val shape = ProtonTemporalMetadataProbe.inspectSeries(app, scope, actual, identityHash, ::lease)
            assertTrue("Read-only current series observation required", shape != null)
            assertAndReport(shape!!, if (allDay) 2 else 1)
        }
        assertTrue("Uncertain work remains retained without resend", !unresolved)
    }

    @Test fun knownSyntheticSeriesMetadataAndCompleteFamilyAreReadWithoutMutation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("seriesMetadataQualification") == "true")
        val identityHash = args.getString("createAccountHash").orEmpty()
        assertTrue("Exact authorized account required", identityHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated &&
            app.imports.state.value.selections.isNotEmpty() && !app.imports.state.value.busy }
        val candidates = mutableListOf<Pair<CalendarScope, RemoteChange.Upsert>>()
        val selected = app.imports.state.value.selections.filter { it.available }.map { it.scope }
        assertTrue("Available test calendar required", selected.isNotEmpty())
        fun lease(scope: CalendarScope) = app.imports.state.value.authenticated &&
            app.imports.state.value.selections.any { it.scope == scope && it.available }
        for (scope in selected) {
            if (candidates.size == 5) break
            assertTrue("Authorize exact account before any record read",
                ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, identityHash))
            assertTrue("Selected account lease required", lease(scope))
            ImportStore(app).use { store ->
                store.readableDatabase.rawQuery("SELECT payload FROM records WHERE scope=? ORDER BY event",
                    arrayOf(ImportPayloadCodec.key(scope))).use { cursor ->
                    while (candidates.size < 5 && cursor.moveToNext()) {
                        val candidate = try { ImportPayloadCodec.decodeChange(cursor.getString(0)) as? RemoteChange.Upsert }
                            catch (_: Exception) { null } ?: continue
                        if (!candidate.content.title.startsWith("BridgeQA-") && !candidate.content.title.startsWith("Calendeer QA")) continue
                        val frozen = try { candidate.copy(content = SeriesEditProfile.freezeMaster(candidate.content)) }
                            catch (_: Exception) { null } ?: continue
                        candidates += scope to frozen
                    }
                }
            }
            assertTrue("Selected account lease required", lease(scope))
        }
        if (candidates.isEmpty()) for (scope in selected.take(1)) {
            val discovered = ProtonSeriesDiscoveryProbe.find(app, scope, identityHash) { lease(scope) }
            candidates += discovered.map { scope to it }
        }
        report(Bundle().apply { putInt("series_fixture_count", candidates.size) })
        assertTrue("Existing supported synthetic series required; no fixture was created", candidates.isNotEmpty())
        var observed = 0
        for ((scope, base) in candidates) {
            assertTrue("Selected account lease required", lease(scope))
            assertTrue("Authorize exact account before authenticated reads",
                ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, identityHash))
            assertTrue("Selected account lease required", lease(scope))
            val result = ProtonTemporalMetadataProbe.inspectSeries(app, scope, base, identityHash) { lease(scope) }
            assertTrue("Selected account lease required", lease(scope))
            assertTrue("Authenticated synthetic series observation required", result != null)
            val shape = result ?: return@runBlocking
            observed++
            assertAndReport(shape, observed)
        }
        report(Bundle().apply { putInt("series_observed_count", observed); putBoolean("series_read_only_probe_complete", true) })
    }

    private fun assertAndReport(shape: SeriesMetadataShape, observed: Int) {
        report(Bundle().apply {
                putInt("series_observed_count", observed)
                putBoolean("series_timed", shape.timed)
                putLong("series_full_day", shape.temporal.fullDay)
                putBoolean("series_start_delta_bounded", shape.temporal.startDeltaSeconds != null)
                putBoolean("series_end_delta_bounded", shape.temporal.endDeltaSeconds != null)
                shape.temporal.startDeltaSeconds?.let { putLong("series_start_delta_seconds", it) }
                shape.temporal.endDeltaSeconds?.let { putLong("series_end_delta_seconds", it) }
                putString("series_start_zone_shape", shape.temporal.startZone.name)
                putString("series_end_zone_shape", shape.temporal.endZone.name)
                putBoolean("series_rule_string", shape.rawRuleString)
                putBoolean("series_rule_matches_signed", shape.ruleMatchesSigned)
                putBoolean("series_recurrence_id_null", shape.recurrenceIdNull)
                putBoolean("series_exdates_empty", shape.exdatesEmpty)
                putBoolean("series_api_zone_spelling_matches", shape.apiZoneSpellingMatches)
                putBoolean("series_complete_family_qualified", shape.completeFamilyQualified)
                putBoolean("series_wire_profile_qualified", shape.wireProfileQualified)
            })
            assertTrue("Rule metadata must be a string matching signed content", shape.rawRuleString && shape.ruleMatchesSigned)
            assertTrue("Master metadata must have no detached slot or exclusions", shape.recurrenceIdNull && shape.exdatesEmpty)
            assertTrue("Complete current family qualification required", shape.completeFamilyQualified)
            assertTrue("Current raw series wire profile required", shape.wireProfileQualified)
            assertTrue("Conservative API temporal bounds required",
                shape.temporal.startDeltaSeconds == 0L && shape.temporal.endDeltaSeconds == 0L)
            assertTrue("Expected API event type required", shape.temporal.fullDay == if (shape.timed) 0L else 1L)
            assertTrue("Conservative API timezone spelling required",
                shape.apiZoneSpellingMatches)
    }

    private fun report(values: Bundle) = InstrumentationRegistry.getInstrumentation().sendStatus(0, values)
}
