// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.protonauth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Authorized retained CREATE fixture, read-only service preparation. Shape diagnostics contain
 * schema names/types only. No UPDATE, native mutation or account attributes are touched.
 */
class CalendarTitleUpdateShapeLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun inspectRetainedFixtureWireShapeAndKeepTitlePreparation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarTitleUpdateShape") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val hash = args.getString("createAccountHash").orEmpty()
        assertTrue(token.matches(Regex("[a-f0-9]{12}")) && hash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty() }
        val original = app.getSharedPreferences("system-create-editor-auto-v1", Context.MODE_PRIVATE)
        assertEquals("Only inspect the enrolled synthetic fixture", token, original.getString("token", null))
        val scope = CalendarScope(AccountId(checkNotNull(original.getString("account", null))),
            CalendarId(checkNotNull(original.getString("calendar", null))), original.getLong("generation", -1))
        assertTrue(ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, hash))
        assertTrue(app.imports.state.value.selections.any { it.scope == scope && it.enabled && it.available })
        val work = SystemCreateWorkStore(app).use { checkNotNull(it.read(scope, original.getLong("event_row", -1))) }
        assertEquals(SystemCreateWorkStatus.COMPLETE, work.status)
        val submission = LocalCreateStore(app).use { checkNotNull(it.readSubmission(scope, work.localId)) }
        val attempt = CreateAttemptStore(app).use { checkNotNull(it.read(scope, work.attempt)) }
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, attempt.state)
        val captured = submission.reservation.intent
        val draft = submission.reservation.draft
        val observed = ProtonAuthentication.createSubmissions(app).inspectCaptured(attempt, draft)
        assertTrue("Require full authenticated read of the retained original", observed is CreateSubmissionObservation.Verified)
        val base = (observed as CreateSubmissionObservation.Verified).event
        assertEquals("Calendeer QA — création système $token", base.content.title)
        assertEquals(captured.content, base.content)
        val local = app.getSharedPreferences("title-update-live-qualification-v1", Context.MODE_PRIVATE)
        assertFalse("Never replace a frozen live title operation", local.contains("intent"))
        val id = UUID.randomUUID().toString()
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        assertTrue(local.edit().putString("intent", id).putString("attempt", UUID.randomUUID().toString())
            .putString("token", token).putString("account", scope.account.value).putString("calendar", scope.calendar.value)
            .putLong("generation", scope.generation).putString("event", base.eventId.value).putString("timestamp", timestamp.toString()).commit())
        val intent = LocalEditIntent(MutationId(id), scope, ProtonAuthentication.events(app).format, base,
            base.content.copy(title = "Calendeer QA — titre UPDATE $token"))
        val preparation = ProtonAuthentication.localEditPreparations(app).prepareLocalTitle(intent, timestamp)
        ImportStore(app, "title-update-live-preparation-v1.db").use { store ->
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic title qualification")))
            store.setEnabled(scope, true)
            store.transaction(scope) {
                putRemote(base); putProjection(ProjectionIntent(scope, base)); checkpoint = PageCheckpoint(scope, intent.format)
            }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
            assertEquals(intent, store.captureLocalEdit(intent))
            assertTrue(store.retainLocalEditPreparation(preparation))
            assertEquals(preparation, store.localEditPreparation(scope, base.eventId))
        }
        assertTrue(local.edit().putBoolean("preparation_retained", true).commit())
        val shape = ProtonTitleUpdateShapeProbe.rowShape(preparation)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            shape.toSortedMap().forEach { (key, type) -> putString("title_update_shape_$key", type) }
            putBoolean("title_update_preparation_retained_without_upload", true)
        })
        assertEquals(attempt, CreateAttemptStore(app).use { it.read(scope, work.attempt) })
    }
}
