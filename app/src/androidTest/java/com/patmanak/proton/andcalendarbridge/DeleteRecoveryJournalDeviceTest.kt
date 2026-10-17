// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite fixtures; no account, Provider or network. */
@RunWith(AndroidJUnit4::class)
class DeleteRecoveryJournalDeviceTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 3)
    private val content = EventContent("Synthetic deletion", EventTime.Timed(
        Instant.parse("2026-10-25T10:00:00Z"), Instant.parse("2026-10-25T11:00:00Z"), ZoneId.of("Europe/Paris")),
        "Synthetic notes\r\nSecond line", "Synthetic place", details = EventDetails())
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"), content)
    private val prepared = DeleteAttemptRecord(MutationId("synthetic-request"),
        LocalDeleteIntent(MutationId("synthetic-owner"), scope, AdapterFormat("observation", 1), base, content),
        AdapterFormat("delete-wire", 1), "opaque-request")
    private val unknown = prepared.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
    private val verified = DeleteSubmissionObservation.Verified(unknown)
    private val result = DeleteVerifiedResult.of(unknown, verified)
    private val receipt = DeleteAcknowledgementReceipt.of(verified, "00000000-0000-0000-0000-000000000001")
    private val completion = DeleteCompletion.of(result, DeleteAcknowledgementRecord(receipt, DeleteAcknowledgementState.RESTORED))

    private val allow: (CalendarScope) -> Boolean = { it == scope }
    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "delete-recovery-fixture-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun retain(store: TitleUpdateAttemptStore) {
        assertTrue(store.deleteJournal.remember(prepared, allow))
        assertTrue(store.deleteJournal.claim(prepared, allow))
        assertTrue(store.deleteJournal.retainVerified(result, allow))
    }
    @Test fun completionReopensWithoutReclaimAndKeepsPermanentEventAndRequestHolds() = fixture { context, name, store ->
        retain(store)
        assertTrue(store.deleteJournal.retainEvidence(unknown, "opaque-evidence", allow))
        assertTrue(store.deleteJournal.retainCompletion(completion, allow))
        assertTrue(store.deleteJournal.retainCompletion(completion, allow))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(result, reopened.deleteJournal.readVerified(scope, prepared.id, allow))
            assertEquals(completion, reopened.deleteJournal.readCompletion(scope, prepared.id, allow))
            assertEquals("opaque-evidence", reopened.deleteJournal.read(scope, prepared.id, allow)?.protectedEvidence)
            assertFalse(reopened.deleteJournal.claim(prepared, allow))
            val nextScope = scope.copy(generation = 4)
            val titleIntent = LocalEditIntent(MutationId("later-title-owner"), nextScope, prepared.intent.format,
                base, content.copy(title = "Synthetic replacement"))
            val title = TitleUpdateAttemptRecord(MutationId("later-title-request"),
                LocalEditPreparation(titleIntent, Instant.parse("2026-10-25T12:00:00Z"),
                    AdapterFormat("title-preparation", 1), "opaque-preparation"),
                AdapterFormat("title-wire", 1), "opaque-title-request")
            assertFalse(reopened.remember(title) { true })
            val later = prepared.copy(id = MutationId("later-request"), intent = prepared.intent.copy(scope = scope.copy(generation = 4)))
            assertFalse(reopened.deleteJournal.remember(later) { true })
            val reused = prepared.copy(intent = prepared.intent.copy(scope = scope.copy(generation = 4), base = base.copy(eventId = EventId("another-event"))))
            assertFalse(reopened.deleteJournal.remember(reused) { true })
            assertTrue(reopened.deleteJournal.page(DeleteWorkQuery(scope), allow)!!.entries.isEmpty())
        }
    }
    @Test fun completionRequiresRetainedResultAndLeaseCommitAdmission() = fixture { _, _, store ->
        assertTrue(store.deleteJournal.remember(prepared, allow)); assertTrue(store.deleteJournal.claim(prepared, allow))
        assertFalse(store.deleteJournal.retainCompletion(completion, allow))
        assertTrue(store.deleteJournal.retainVerified(result, allow))
        var calls = 0
        assertFalse(store.deleteJournal.retainCompletion(completion) { ++calls == 1 })
        assertNull(store.deleteJournal.readCompletion(scope, prepared.id, allow))
        assertTrue(store.deleteJournal.retainCompletion(completion, allow))
        val replacement = DeleteCompletion.of(result, DeleteAcknowledgementRecord(receipt.copy(nonce = "00000000-0000-0000-0000-000000000002"), DeleteAcknowledgementState.RESTORED))
        assertFalse(store.deleteJournal.retainCompletion(replacement, allow))
    }
    @Test fun corruptCompletionStaysHiddenFromDiscoveryButBlocksAllOwnershipReads() = fixture { _, _, store ->
        retain(store); assertTrue(store.deleteJournal.retainCompletion(completion, allow))
        store.writableDatabase.execSQL("UPDATE delete_attempts SET completion=x'00'")
        assertTrue(store.deleteJournal.page(DeleteWorkQuery(scope), allow)!!.entries.isEmpty())
        invalid { store.deleteJournal.read(scope, prepared.id, allow) }
        invalid { store.deleteJournal.readVerified(scope, prepared.id, allow) }
        invalid { store.deleteJournal.readCompletion(scope, prepared.id, allow) }
        invalid { store.deleteJournal.remember(prepared.copy(id = MutationId("new-request")), allow) }
    }
    @Test fun oversizedWrongTypedAndTruncatedProofsNeverReachDecodeAsAbsence() = fixture { _, _, store ->
        retain(store)
        for (assignment in listOf("zeroblob(16777217)", "'text'", "x'00'")) {
            store.writableDatabase.execSQL("UPDATE delete_attempts SET result=$assignment")
            invalid { store.deleteJournal.readVerified(scope, prepared.id, allow) }
        }
    }
    @Test fun metadataPaginationIsBoundedAndDoesNotReadCapsules() = fixture { _, _, store ->
        for (i in 0..2) {
            val record = prepared.copy(id = MutationId("request-$i"), intent = prepared.intent.copy(base = base.copy(eventId = EventId("event-$i"))))
            assertTrue(store.deleteJournal.remember(record, allow))
        }
        store.writableDatabase.execSQL("UPDATE delete_attempts SET capsule=x'00'")
        val first = store.deleteJournal.page(DeleteWorkQuery(scope, 2), allow)!!
        assertEquals(listOf("event-0", "event-1"), first.entries.map { it.eventId.value })
        val last = store.deleteJournal.page(DeleteWorkQuery(scope, 2, first.next), allow)!!
        assertEquals(listOf("event-2"), last.entries.map { it.eventId.value }); assertNull(last.next)
    }
    @Test fun versionEightUpgradePreservesOpaqueUnknownBytesAndAddsNoProof() = fixture { context, name, store ->
        val bytes = DeleteAttemptStorageCodec.encode(unknown.copy(protectedEvidence = "opaque-evidence"))
        val db = store.writableDatabase
        db.execSQL("DROP TABLE delete_attempts")
        db.execSQL("CREATE TABLE delete_attempts(account TEXT,calendar TEXT,generation INTEGER,mutation TEXT,event TEXT,state TEXT,capsule BLOB, PRIMARY KEY(account,calendar,generation,mutation),UNIQUE(account,calendar,mutation))")
        db.execSQL("CREATE UNIQUE INDEX delete_unresolved_event ON delete_attempts(account,calendar,event)")
        db.insertOrThrow("delete_attempts", null, ContentValues().apply {
            put("account",scope.account.value); put("calendar",scope.calendar.value); put("generation",scope.generation)
            put("mutation",prepared.id.value); put("event",base.eventId.value); put("state","OUTCOME_UNKNOWN"); put("capsule",bytes)
        })
        db.version = 8; store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(9, reopened.readableDatabase.version)
            reopened.readableDatabase.rawQuery("SELECT capsule,result,completion FROM delete_attempts", null).use { row ->
                assertTrue(row.moveToFirst()); assertArrayEquals(bytes,row.getBlob(0)); assertTrue(row.isNull(1)); assertTrue(row.isNull(2))
            }
            assertNull(reopened.deleteJournal.readVerified(scope, prepared.id, allow))
            assertFalse(reopened.deleteJournal.claim(prepared, allow))
            val nextScope = scope.copy(generation = 4)
            val titleIntent = LocalEditIntent(MutationId("later-title-owner"), nextScope, prepared.intent.format,
                base, content.copy(title = "Synthetic replacement"))
            val title = TitleUpdateAttemptRecord(MutationId("later-title-request"),
                LocalEditPreparation(titleIntent, Instant.parse("2026-10-25T12:00:00Z"),
                    AdapterFormat("title-preparation", 1), "opaque-preparation"),
                AdapterFormat("title-wire", 1), "opaque-title-request")
            assertFalse(reopened.remember(title) { true })
        }
    }
    private suspend fun invalid(action: suspend () -> Unit) {
        try { action(); fail("Corrupt proof accepted") } catch (failure: IllegalStateException) { assertNull(failure.cause) }
    }
}
