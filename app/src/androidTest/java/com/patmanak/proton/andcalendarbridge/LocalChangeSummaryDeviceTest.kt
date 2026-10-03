// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Metadata counts from real isolated SQL; no Provider or service access. */
@RunWith(AndroidJUnit4::class)
class LocalChangeSummaryDeviceTest {
    private val format = AdapterFormat("controlled-import", 1)
    private val target = CalendarScope(AccountId("synthetic-account"), CalendarId("fixture"), 0)
    private fun base(event: String) = RemoteChange.Upsert(EventId(event), Revision("base"), EventContent("Private fixture title",
        EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails()))
    private suspend fun ImportStore.seed(scope: CalendarScope = target, event: String = "update"): RemoteChange.Upsert {
        if (selections(scope.account).none { it.scope == scope }) {
            remember(scope.account, selections(scope.account).map { it.summary } + CalendarSummary(scope.calendar, "Fixture"))
            setEnabled(scope, true)
        }
        val value = base(event)
        transaction(scope) { putRemote(value); putProjection(ProjectionIntent(scope, value)); checkpoint = PageCheckpoint(scope, format) }
        assertEquals(1, acknowledge(ProjectionIntent(scope, value)))
        return value
    }
    private suspend fun ImportStore.edit(scope: CalendarScope = target, event: String = "update") {
        val value = seed(scope, event)
        captureLocalEdit(LocalEditIntent(MutationId(event), scope, format, value, value.content.copy(title = "Captured fixture")))
    }
    private suspend fun ImportStore.summary(scope: CalendarScope = target) = selections(scope.account).single { it.scope == scope }
    private fun fixture(block: suspend ImportStore.(String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-summary-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try { store.block(name) } finally { store.close(); context.deleteDatabase(name) }
    }
    @Test fun capturedUpdateAppearsWithoutBeingClassifiedAsPendingAndroidProjection() = fixture {
        edit()
        val value = summary()
        assertEquals(1, value.retainedLocalChangeCount); assertEquals(0, value.localConflictCount)
        assertEquals(0, value.pendingProjectionCount)
    }
    @Test fun updateAndDeleteConflictsStayCountedWhenRemoteReturnsToTheCapturedBase() = fixture {
        edit()
        val deletion = seed(event = "delete")
        captureLocalDelete(LocalDeleteIntent(MutationId("delete"), target, format, deletion, deletion.content))
        assertEquals(2, summary().retainedLocalChangeCount); assertEquals(0, summary().localConflictCount)
        for (event in listOf("update", "delete")) {
            val value = base(event)
            transaction(target) { putRemote(value.copy(revision = Revision("changed"))) }
            transaction(target) { putRemote(value) }
        }
        val value = summary()
        assertEquals(2, value.retainedLocalChangeCount); assertEquals(2, value.localConflictCount)
        assertEquals(0, value.pendingProjectionCount)
    }
    @Test fun countsAreScopedByAccountCalendarAndGenerationAndReopenWithoutMutation() = fixture { name ->
        edit()
        edit(target.copy(calendar = CalendarId("other-calendar")), "other")
        edit(target.copy(account = AccountId("other-account")), "foreign")
        val owner = localEdit(target, EventId("update")); val imported = transaction(target) { remote(EventId("update")) }
        // A journal entry from another generation must never contribute to the current selection.
        val originalKey = org.json.JSONArray().put(target.account.value).put(target.calendar.value).put(0).toString()
        val generationKey = org.json.JSONArray().put(target.account.value).put(target.calendar.value).put(1).toString()
        writableDatabase.execSQL("INSERT INTO calendars (scope,account,calendar,generation,title) VALUES (?,?,?,?,?)",
            arrayOf(generationKey, target.account.value, target.calendar.value, 1, "Other generation"))
        writableDatabase.execSQL("INSERT INTO local_edits SELECT ?, mutation, event, adapter, version, base, local, state, kind FROM local_edits WHERE scope=?",
            arrayOf(generationKey, originalKey))
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(1, reopened.summary().retainedLocalChangeCount)
            assertEquals(1, reopened.summary(target.copy(calendar = CalendarId("other-calendar"))).retainedLocalChangeCount)
            assertEquals(1, reopened.summary(target.copy(account = AccountId("other-account"))).retainedLocalChangeCount)
            assertEquals(1, reopened.summary(target.copy(generation = 1)).retainedLocalChangeCount)
        }
        assertTrue(owner == localEdit(target, EventId("update")))
        assertTrue(imported == transaction(target) { remote(EventId("update")) })
        assertTrue(base("update") == projectedBaseline(target, EventId("update")))
        assertTrue(pending(target).isEmpty())
    }
    @Test fun pausedUnavailableCalendarsKeepCountsAndCalendarsWithoutCaptureHaveZero() = fixture {
        seed(); assertEquals(0, summary().retainedLocalChangeCount); assertEquals(0, summary().localConflictCount)
        edit(); setEnabled(target, false); remember(target.account, emptyList())
        val value = summary()
        assertFalse(value.available); assertFalse(value.enabled)
        assertEquals(1, value.retainedLocalChangeCount); assertEquals(0, value.localConflictCount)
    }
    @Test fun summaryDoesNotDecodeCapturedContentsAndCannotConstructContradictoryCounts() = fixture {
        edit()
        writableDatabase.execSQL("UPDATE local_edits SET base='unsupported-opaque-content', local='unsupported-opaque-content'")
        assertEquals(1, summary().retainedLocalChangeCount)
        for (counts in listOf(-1 to 0, 0 to 1, 1 to -1)) {
            try { summary().copy(retainedLocalChangeCount = counts.first, localConflictCount = counts.second); fail("Invalid counts accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
