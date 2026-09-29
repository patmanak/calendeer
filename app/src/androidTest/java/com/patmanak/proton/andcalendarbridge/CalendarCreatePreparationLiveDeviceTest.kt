// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real retained-account context reads and local preparation. No outgoing event dispatch. */
@RunWith(AndroidJUnit4::class)
class CalendarCreatePreparationLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realCalendarContextPreparesSealedTimedAndAllDayDraftsWithoutUpload() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("calendarCreatePreparation") == "true")
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) {
            app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty()
        }
        val before = app.imports.state.value.selections
        val selected = before.filter { it.enabled && it.available }
        assertTrue("At least one retained selected calendar is required", selected.isNotEmpty())
        val port = ProtonAuthentication.createPreparations(app)
        val times = listOf<EventTime>(
            EventTime.Timed(Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-01T11:00:00Z"), ZoneId.of("UTC")),
            EventTime.AllDay(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-03")))
        var preparedCalendars = 0
        var permissionRefusals = 0
        for (selection in selected) {
            try {
                for ((index, time) in times.withIndex()) {
                    val draft = CalendarCreateDraft(selection.scope, MutationId("synthetic-preparation-$index"),
                        Instant.parse("2026-09-29T10:00:00Z"), EventContent("Synthetic preparation only", time))
                    val receipt = port.prepareCreate(draft)
                    assertTrue("Preparation must retain scope", receipt.scope == selection.scope)
                    assertTrue("Preparation requires a versioned format", receipt.format == AdapterFormat("proton-calendar-create-preparation", 1))
                    assertTrue("Protected preparation must be nonempty", receipt.protectedPayload.isNotEmpty())
                    assertTrue("Protected preparation must hide display text", !receipt.protectedPayload.contains(draft.content.title))
                    assertTrue("Diagnostics must hide display text", !receipt.toString().contains(draft.content.title))
                }
                preparedCalendars++
            } catch (failure: CreatePreparationException) {
                assertTrue("Only a verified permission refusal is allowed for another selected calendar", failure.reason == CreatePreparationFailure.PERMISSION)
                permissionRefusals++
            }
        }
        assertTrue("At least one real calendar must prepare successfully", preparedCalendars > 0)
        assertTrue("Session must remain authenticated", app.imports.state.value.authenticated)
        assertTrue("Selections must remain unchanged", app.imports.state.value.selections.map { it.scope to it.enabled } == before.map { it.scope to it.enabled })
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putInt("prepared_calendar_count", preparedCalendars)
            putInt("prepared_draft_count", preparedCalendars * times.size)
            putInt("permission_refusal_count", permissionRefusals)
            putBoolean("outgoing_dispatch_implemented", false)
        })
    }
}
