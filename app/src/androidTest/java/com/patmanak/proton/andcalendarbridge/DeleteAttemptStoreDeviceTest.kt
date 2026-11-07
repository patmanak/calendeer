// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite fixtures only; no Provider, account, crypto or service access. */
@RunWith(AndroidJUnit4::class)
class DeleteAttemptStoreDeviceTest {
    private val scope = CalendarScope(AccountId("delete-fixture-account"), CalendarId("delete-fixture-calendar"), 3)
    private val content = EventContent("Synthetic deletion", EventTime.Timed(
        Instant.parse("2026-10-25T10:00:00Z"), Instant.parse("2026-10-25T11:00:00Z"), ZoneId.of("Europe/Paris")),
        "Synthetic notes\r\nSecond line", "Synthetic place", details = EventDetails())
    private val base = RemoteChange.Upsert(EventId("delete-fixture-event"), Revision("synthetic-base"), content)
    private val intent = LocalDeleteIntent(MutationId("delete-local-owner"), scope,
        AdapterFormat("synthetic-observation", 1), base, content)
    private val prepared = DeleteAttemptRecord(MutationId("delete-request"), intent,
        AdapterFormat("synthetic-delete-wire", 1), "opaque-synthetic-request")
    private val allow: (CalendarScope) -> Boolean = { it == scope }

    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "delete-journal-fixture-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun corrupt(block: suspend () -> Unit) {
        try { block(); fail("Corrupt retained history was accepted") }
        catch (failure: IllegalStateException) { assertNull(failure.cause) }
    }
    private fun title(event: EventId = base.eventId, id: MutationId = MutationId("title-request")): TitleUpdateAttemptRecord {
        val edit = LocalEditIntent(MutationId("title-local-owner"), scope, intent.format,
            base.copy(eventId = event), content.copy(title = "Synthetic replacement"))
        return TitleUpdateAttemptRecord(id, LocalEditPreparation(edit, Instant.parse("2026-10-25T12:00:00Z"),
            AdapterFormat("synthetic-title-preparation", 1), "opaque-title-preparation"),
            AdapterFormat("synthetic-title-wire", 1), "opaque-title-wire")
    }
    private fun text(): TextUpdateAttemptRecord {
        val edit = LocalEditIntent(MutationId("text-local-owner"), scope, intent.format, base,
            content.copy(description = "Synthetic replacement"))
        return TextUpdateAttemptRecord(MutationId("text-request"), LocalTextEditPreparation(edit,
            Instant.parse("2026-10-25T12:00:00Z"), AdapterFormat("synthetic-text-preparation", 1), "opaque-text-preparation"),
            AdapterFormat("synthetic-text-wire", 1), "opaque-text-wire")
    }
    private fun time(): TimeUpdateAttemptRecord {
        val changed = content.copy(time = EventTime.Timed(Instant.parse("2026-10-25T13:00:00Z"),
            Instant.parse("2026-10-25T14:00:00Z"), ZoneId.of("Europe/Paris")))
        val edit = LocalEditIntent(MutationId("time-local-owner"), scope, intent.format, base, changed)
        return TimeUpdateAttemptRecord(MutationId("time-request"), LocalTimeEditPreparation(edit,
            Instant.parse("2026-10-25T12:00:00Z"), AdapterFormat("synthetic-time-preparation", 1), "opaque-time-preparation"),
            AdapterFormat("synthetic-time-wire", 1), "opaque-time-wire")
    }

    @Test fun firstWireUnknownAndEvidenceSurviveReopenWithoutAnotherClaim() = fixture { context, name, store ->
        assertTrue(store.deleteJournal.remember(prepared, allow))
        assertTrue(store.deleteJournal.remember(prepared, allow))
        assertFalse(store.deleteJournal.remember(prepared.copy(protectedPayload = "substitution"), allow))
        assertFalse(store.deleteJournal.claim(prepared.copy(protectedPayload = "substitution"), allow))
        assertTrue(store.deleteJournal.claim(prepared, allow))
        val unknown = prepared.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
        TitleUpdateAttemptStore(context, name).use { observer -> assertEquals(unknown, observer.deleteJournal.read(scope, prepared.id, allow)) }
        assertTrue(store.deleteJournal.retainEvidence(unknown, "first-evidence", allow))
        assertTrue(store.deleteJournal.retainEvidence(unknown, "first-evidence", allow))
        assertFalse(store.deleteJournal.retainEvidence(unknown, "replacement-evidence", allow))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(unknown.copy(protectedEvidence = "first-evidence"), reopened.deleteJournal.read(scope, prepared.id, allow))
            assertFalse(reopened.deleteJournal.remember(prepared, allow))
            assertFalse(reopened.deleteJournal.claim(prepared, allow))
            assertFalse(reopened.deleteJournal.claim(unknown, allow))
        }
    }
    @Test fun deniedAndRevokedLeasesRollbackAndCannotReopen() = fixture { _, _, store ->
        assertFalse(store.deleteJournal.remember(prepared) { false })
        assertNull(store.deleteJournal.read(scope, prepared.id, allow))
        var calls = 0
        assertFalse(store.deleteJournal.remember(prepared) { ++calls == 1 })
        assertEquals(2, calls)
        assertNull(store.deleteJournal.read(scope, prepared.id, allow))
        assertTrue(store.deleteJournal.remember(prepared, allow))
        calls = 0
        assertFalse(store.deleteJournal.claim(prepared) { ++calls == 1 })
        assertEquals(prepared, store.deleteJournal.read(scope, prepared.id, allow))
        assertNull(store.deleteJournal.read(scope, prepared.id) { false })
        assertTrue(store.deleteJournal.claim(prepared, allow))
        val unknown = prepared.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
        calls = 0
        assertFalse(store.deleteJournal.retainEvidence(unknown, "revoked-evidence") { ++calls == 1 })
        assertEquals(unknown, store.deleteJournal.read(scope, prepared.id, allow))
    }
    @Test fun cancellationInsideLeaseRollsBackAndUnknownSurvivesCancelledLaterCalls() = fixture { _, _, store ->
        assertTrue(store.deleteJournal.remember(prepared, allow))
        val first = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launch {
            val operation = currentCoroutineContext()
            var calls = 0
            try {
                store.deleteJournal.claim(prepared) {
                    if (++calls == 2) operation.cancel()
                    true
                }
                fail("Cancelled operation returned normally")
            } catch (_: CancellationException) { }
        }
        first.join()
        assertEquals(prepared, store.deleteJournal.read(scope, prepared.id, allow))
        assertTrue(store.deleteJournal.claim(prepared, allow))
        val next = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launch {
            currentCoroutineContext().cancel()
            try { store.deleteJournal.claim(prepared, allow); fail("Cancelled claim returned") }
            catch (_: CancellationException) { }
        }
        next.join()
        assertEquals(prepared.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN), store.deleteJournal.read(scope, prepared.id, allow))
    }
    @Test fun deleteOwnershipBlocksAllUpdateFamiliesAndFutureGenerations() = fixture { _, _, store ->
        assertTrue(store.deleteJournal.remember(prepared, allow))
        assertFalse(store.remember(title(), allow))
        assertFalse(store.textJournal.remember(text(), allow))
        assertFalse(store.timeJournal.remember(time(), allow))
        val future = prepared.copy(id = MutationId("future-request"), intent = intent.copy(scope = scope.copy(generation = 4)))
        assertFalse(store.deleteJournal.remember(future) { true })
        val otherEvent = base.copy(eventId = EventId("another-event"))
        val reusedId = prepared.copy(intent = intent.copy(base = otherEvent, content = otherEvent.content))
        assertFalse(store.deleteJournal.remember(reusedId, allow))
        assertFalse(store.remember(title(EventId("another-event"), prepared.id), allow))
    }
    @Test fun eachUpdateFamilyPreventsDeleteOwnership() = fixture { context, name, store ->
        assertTrue(store.remember(title(), allow))
        assertFalse(store.deleteJournal.remember(prepared, allow))
        for (family in listOf("text", "time")) {
            val otherName = "$name-$family"
            try {
                TitleUpdateAttemptStore(context, otherName).use { other ->
                    if (family == "text") assertTrue(other.textJournal.remember(text(), allow))
                    else assertTrue(other.timeJournal.remember(time(), allow))
                    assertFalse(other.deleteJournal.remember(prepared, allow))
                }
            } finally { context.deleteDatabase(otherName) }
        }
    }
    @Test fun allDayAndLargeProtectedCapsulesReadWithoutCursorWindowOverflow() = fixture { context, name, store ->
        val day = content.copy(time = EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-27")))
        val large = prepared.copy(intent = intent.copy(base = base.copy(content = day), content = day),
            protectedPayload = "p".repeat(4_194_304))
        assertTrue(store.deleteJournal.remember(large, allow)); assertTrue(store.deleteJournal.claim(large, allow))
        val unknown = large.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
        assertTrue(store.deleteJournal.retainEvidence(unknown, "e".repeat(4_194_304), allow))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(unknown.copy(protectedEvidence = "e".repeat(4_194_304)), reopened.deleteJournal.read(scope, large.id, allow))
        }
    }
    @Test fun corruptCapsuleIsAnErrorAndCannotDonateOwnership() = fixture { _, _, store ->
        assertTrue(store.deleteJournal.remember(prepared, allow))
        store.writableDatabase.update("delete_attempts", ContentValues().apply { put("capsule", byteArrayOf(0)) }, null, null)
        corrupt { store.deleteJournal.read(scope, prepared.id, allow) }
        corrupt { store.remember(title(), allow) }
        corrupt { store.textJournal.remember(text(), allow) }
        corrupt { store.timeJournal.remember(time(), allow) }
    }
    @Test fun movedMetadataCannotHideTheRetainedEventOrRequestIdentity() = fixture { _, _, store ->
        assertTrue(store.deleteJournal.remember(prepared, allow))
        store.writableDatabase.update("delete_attempts", ContentValues().apply { put("event", "moved-event") }, null, null)
        corrupt { store.deleteJournal.read(scope, prepared.id, allow) }
        // The original request UUID still discovers and qualifies the full conflicting row.
        corrupt { store.remember(title(EventId("another-event"), prepared.id), allow) }
        val candidate = prepared.copy(id = MutationId("another-request"),
            intent = intent.copy(base = base.copy(eventId = EventId("moved-event"))))
        corrupt { store.deleteJournal.remember(candidate, allow) }
    }
    @Test fun nonNullCorruptUpdateCompletionNeverReleasesDeleteOwnership() = fixture { context, name, _ ->
        for (family in listOf("title", "text", "time")) {
            val otherName = "$name-$family-completion"
            try {
                TitleUpdateAttemptStore(context, otherName).use { other ->
                    when (family) {
                        "title" -> assertTrue(other.remember(title(), allow))
                        "text" -> assertTrue(other.textJournal.remember(text(), allow))
                        else -> assertTrue(other.timeJournal.remember(time(), allow))
                    }
                    val table = "${family}_update_attempts"
                    other.writableDatabase.update(table, ContentValues().apply {
                        if (family == "title") put("completion", "corrupt-completion")
                        else put("completion", byteArrayOf(0))
                    }, null, null)
                    corrupt { other.deleteJournal.remember(prepared, allow) }
                    // A different event cannot reuse a retained UUID, including completed history.
                    val request = when (family) { "title" -> title().id; "text" -> text().id; else -> time().id }
                    val moved = prepared.copy(id = request, intent = intent.copy(base = base.copy(eventId = EventId("another-event"))))
                    corrupt { other.deleteJournal.remember(moved, allow) }
                }
            } finally { context.deleteDatabase(otherName) }
        }
    }
    @Test fun sevenToEightMigrationRetainsOpaqueUpdateBytesAndCreatesEmptyDeleteTable() = fixture { context, name, store ->
        assertTrue(store.remember(title(), allow))
        assertTrue(store.textJournal.remember(text().copy(id = MutationId("another-text-request"), preparation =
            text().preparation.let { p -> LocalTextEditPreparation(p.intent.copy(base = base.copy(eventId = EventId("text-event"))),
                p.timestamp, p.format, p.protectedPayload) }), allow))
        val bytes = store.writableDatabase.rawQuery("SELECT hex(capsule) FROM text_update_attempts", null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        store.writableDatabase.execSQL("DROP TABLE personal_update_attempts")
        store.writableDatabase.execSQL("DROP TABLE delete_attempts")
        store.writableDatabase.execSQL("DROP TABLE conflict_resolution_fences")
        store.writableDatabase.version = 7
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(11, reopened.readableDatabase.version)
            assertEquals(title(), reopened.read(scope, title().id))
            reopened.readableDatabase.rawQuery("SELECT hex(capsule) FROM text_update_attempts", null).use {
                assertTrue(it.moveToFirst()); assertEquals(bytes, it.getString(0))
            }
            assertNull(reopened.deleteJournal.read(scope, prepared.id, allow))
        }
    }
}
