// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite fixtures (-e providerFixture true): no Provider, account or service access.
 * Opaque synthetic strings prove persistence only, not Keystore or Proton compatibility.
 */
@RunWith(AndroidJUnit4::class)
class TitleUpdateAttemptStoreDeviceTest {
    private val scope = CalendarScope(AccountId("title-fixture-account"), CalendarId("title-fixture-calendar"), 3)
    private val content = EventContent("Synthetic original title",
        EventTime.Timed(Instant.parse("2026-10-05T10:15:00.123456789Z"),
            Instant.parse("2026-10-05T11:15:00.987654321Z"), ZoneId.of("Europe/Paris")),
        "Synthetic preserved description\nSecond line", "Synthetic preserved location", details = EventDetails())
    private val base = RemoteChange.Upsert(EventId("title-fixture-event"), Revision("opaque-title-base-revision"), content)
    private val intent = LocalEditIntent(MutationId("title-fixture-local-intent"), scope,
        AdapterFormat("synthetic-import-format", 4), base, content.copy(title = "Synthetic modified title"))
    private val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-05T12:00:01Z"),
        AdapterFormat("synthetic-protected-title-preparation", 2), "synthetic-opaque-preparation-not-keystore-proof")
    private val prepared = TitleUpdateAttemptRecord(MutationId("title-fixture-attempt"), preparation,
        AdapterFormat("synthetic-protected-title-update", 1), "synthetic-opaque-update-not-keystore-proof")
    private val evidence = "synthetic-opaque-title-readback-not-keystore-proof"

    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "title-attempt-fixture-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); context.deleteDatabase(name) }
    }

    private fun allowed(store: TitleUpdateAttemptStore): TitleUpdateAttemptStore = store.apply {
        admitDispatch = { it == scope }
    }
    private fun changedIntent(value: LocalEditIntent) = prepared.copy(preparation = preparation.copy(intent = value))
    private fun changedBase(value: RemoteChange.Upsert) = changedIntent(intent.copy(base = value,
        content = value.content.copy(title = intent.content.title)))
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected immutable title work rejection") }
        catch (_: IllegalStateException) { }
    }

    @Test fun offlinePreparationReopensAndUnknownCommitIsVisibleBeforeSending() = fixture { context, name, store ->
        store.remember(prepared)
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(prepared, reopened.read(scope, prepared.id))
            allowed(reopened)
            var sends = 0
            assertTrue(TitleUpdateAttemptAdmission(reopened).dispatch(prepared) { admitted ->
                sends++
                assertEquals(prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), admitted)
                TitleUpdateAttemptStore(context, name).use { observer ->
                    assertEquals(admitted, observer.read(scope, prepared.id))
                    observer.readableDatabase.rawQuery("PRAGMA synchronous", null).use { setting ->
                        assertTrue(setting.moveToFirst()); assertEquals(2, setting.getInt(0))
                    }
                }
            })
            assertEquals(1, sends)
        }
        TitleUpdateAttemptStore(context, name).use { reopened ->
            val unknown = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
            assertEquals(unknown, reopened.read(scope, prepared.id))
            reopened.remember(prepared)
            assertEquals(unknown, reopened.read(scope, prepared.id))
            allowed(reopened)
            assertFalse(TitleUpdateAttemptAdmission(reopened).dispatch(prepared) { fail("Never resend") })
        }
    }

    @Test fun allDayAndNanosecondTimedSnapshotsRemainExactWithoutOpaquePayloadDecoding() = fixture { context, name, store ->
        val dayContent = content.copy(time = EventTime.AllDay(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-27")))
        val allDay = changedBase(base.copy(eventId = EventId("all-day-title-fixture"), content = dayContent))
            .copy(id = MutationId("all-day-title-attempt"))
        store.remember(prepared); store.remember(allDay)
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(prepared, reopened.read(scope, prepared.id))
            assertEquals(allDay, reopened.read(scope, allDay.id))
            assertEquals(TitleUpdateAttemptIdentity.of(prepared), TitleUpdateAttemptIdentity.of(reopened.read(scope, prepared.id)!!))
        }
    }

    @Test fun twoIndependentHandlesCommitExactlyOneClaim() = fixture { context, name, store ->
        allowed(store)
        TitleUpdateAttemptStore(context, name).use { other ->
            allowed(other)
            repeat(8) { index ->
                val record = changedBase(base.copy(eventId = EventId("racing-title-event-$index")))
                    .copy(id = MutationId("racing-title-attempt-$index"))
                store.remember(record)
                assertEquals(record, other.read(scope, record.id))
                coroutineScope {
                    val start = CompletableDeferred<Unit>()
                    val first = async(Dispatchers.IO) { start.await(); store.claim(record) }
                    val second = async(Dispatchers.IO) { start.await(); other.claim(record) }
                    start.complete(Unit)
                    assertEquals(1, listOf(first.await(), second.await()).count { it })
                }
                assertEquals(record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), other.read(scope, record.id))
                assertFalse(store.claim(record)); assertFalse(other.claim(record))
            }
        }
    }

    @Test fun completePreparationAndWireContextAreImmutableAtRememberAndClaim() = fixture { _, _, store ->
        store.remember(prepared); store.remember(prepared)
        allowed(store)
        val altered = listOf(
            prepared.copy(protectedPayload = "different-protected-update"),
            prepared.copy(format = prepared.format.copy(version = 2)),
            prepared.copy(format = AdapterFormat("different-update-adapter", 1)),
            prepared.copy(preparation = preparation.copy(protectedPayload = "different-protected-preparation")),
            prepared.copy(preparation = preparation.copy(format = preparation.format.copy(version = 3))),
            prepared.copy(preparation = preparation.copy(timestamp = preparation.timestamp.plusSeconds(1))),
            changedIntent(intent.copy(id = MutationId("different-local-intent"))),
            changedIntent(intent.copy(format = intent.format.copy(version = 5))),
            changedIntent(intent.copy(content = intent.content.copy(title = "Another synthetic title"))),
            changedBase(base.copy(revision = Revision("different-opaque-base"))),
            changedBase(base.copy(eventId = EventId("different-existing-event"))),
            changedBase(base.copy(content = content.copy(description = "Different protected baseline description"))),
            changedBase(base.copy(content = content.copy(time = EventTime.Timed(
                Instant.parse("2026-10-05T10:15:00Z"), Instant.parse("2026-10-05T11:15:00Z"), ZoneId.of("UTC")))))
        )
        for (record in altered) {
            rejects { store.remember(record) }
            assertFalse(store.claim(record))
            assertEquals(prepared, store.read(scope, prepared.id))
        }
    }

    @Test fun rememberingAnUnknownOrEvidenceBearingRecordCannotInventAdmission() = fixture { _, _, store ->
        for (record in listOf(prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN),
            prepared.copy(protectedEvidence = evidence))) {
            try { store.remember(record); fail("Only fresh prepared work can be remembered") }
            catch (_: IllegalArgumentException) { }
            assertNull(store.read(scope, prepared.id))
        }
        store.remember(prepared)
        allowed(store)
        assertFalse(store.claim(prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)))
        assertFalse(store.claim(prepared.copy(protectedEvidence = evidence)))
        assertEquals(prepared, store.read(scope, prepared.id))
    }

    @Test fun accountCalendarGenerationAndAttemptIdentityDoNotCrossScopes() = fixture { _, _, store ->
        store.remember(prepared)
        allowed(store)
        val otherScopes = listOf(scope.copy(account = AccountId("other-title-fixture-account")),
            scope.copy(calendar = CalendarId("other-title-fixture-calendar")))
        for (otherScope in otherScopes) {
            val record = changedIntent(intent.copy(scope = otherScope))
            assertNull(store.read(otherScope, record.id))
            assertFalse(store.claim(record))
            store.remember(record)
            assertEquals(record, store.read(otherScope, record.id))
            assertFalse(store.claim(record))
        }
        val differentId = prepared.copy(id = MutationId("different-title-attempt"))
        assertNull(store.read(scope, differentId.id))
        assertFalse(store.claim(differentId))
        assertEquals(prepared, store.read(scope, prepared.id))
    }

    @Test fun newGenerationOrBaseCannotBypassTheUnresolvedRemoteEventHold() = fixture { context, name, store ->
        store.remember(prepared)
        val nextScope = scope.copy(generation = scope.generation + 1)
        val next = changedIntent(intent.copy(scope = nextScope,
            base = base.copy(revision = Revision("another-generation-observation"))))
            .copy(id = MutationId("another-generation-title-attempt"))
        assertNull(store.read(nextScope, next.id))
        rejects { store.remember(next) }
        allowed(store); assertTrue(store.claim(prepared))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            rejects { reopened.remember(next) }
            reopened.admitDispatch = { it == nextScope }
            assertFalse(reopened.claim(next))
            assertNull(reopened.read(nextScope, next.id))
            assertEquals(prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), reopened.read(scope, prepared.id))
        }
    }

    @Test fun newAttemptIdCannotBypassPreparedUnknownOrVerifiedHistoricalWork() = fixture { context, name, store ->
        store.remember(prepared)
        val differentId = prepared.copy(id = MutationId("bypass-title-attempt"))
        rejects { store.remember(differentId) }
        allowed(store); assertTrue(store.claim(prepared))
        rejects { store.remember(differentId) }
        val unknown = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        store.retainEvidence(unknown, evidence)
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            rejects { reopened.remember(differentId) }
            assertNull(reopened.read(scope, differentId.id))
            assertEquals(unknown.copy(protectedEvidence = evidence), reopened.read(scope, prepared.id))
        }
    }

    @Test fun twoHelpersCannotRememberDifferentIdsForTheSameScopedEvent() = fixture { context, name, store ->
        TitleUpdateAttemptStore(context, name).use { other ->
            // Initialize both schemas before racing the insert transactions.
            assertNull(store.read(scope, prepared.id)); assertNull(other.read(scope, prepared.id))
            val second = prepared.copy(id = MutationId("racing-new-title-attempt"))
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                suspend fun rememberOnce(handle: TitleUpdateAttemptStore, record: TitleUpdateAttemptRecord): Boolean {
                    start.await()
                    return try { handle.remember(record); true } catch (_: IllegalStateException) { false }
                }
                val one = async(Dispatchers.IO) { rememberOnce(store, prepared) }
                val two = async(Dispatchers.IO) { rememberOnce(other, second) }
                start.complete(Unit)
                assertEquals(1, listOf(one.await(), two.await()).count { it })
            }
            assertEquals(1, listOfNotNull(store.read(scope, prepared.id), other.read(scope, second.id)).size)
        }
    }

    @Test fun dispatchDefaultsOffAndRevocationDuringClaimRollsBackPreparedWork() = fixture { _, _, store ->
        store.remember(prepared)
        assertFalse(TitleUpdateAttemptAdmission(store).dispatch(prepared) { fail("Default-off must not send") })
        assertEquals(prepared, store.read(scope, prepared.id))
        var checks = 0
        store.admitDispatch = { it == scope && ++checks == 1 }
        rejects { TitleUpdateAttemptAdmission(store).dispatch(prepared) { fail("Revocation must not send") } }
        assertEquals(2, checks)
        assertEquals(prepared, store.read(scope, prepared.id))
        allowed(store); assertTrue(store.claim(prepared))
    }

    @Test fun deferredCommitFailureCannotAdmitDispatchAndKeepsPreparedAfterReopen() = fixture { context, name, store ->
        store.remember(prepared)
        val path = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.setForeignKeyConstraintsEnabled(true)
            raw.execSQL("CREATE TABLE title_fixture_parent (id INTEGER PRIMARY KEY)")
            raw.execSQL("""CREATE TABLE title_fixture_child (parent INTEGER REFERENCES title_fixture_parent(id)
                DEFERRABLE INITIALLY DEFERRED)""".trimIndent())
            raw.execSQL("""CREATE TRIGGER title_fixture_reject_commit AFTER UPDATE OF state ON title_update_attempts
                WHEN NEW.state='OUTCOME_UNKNOWN' BEGIN INSERT INTO title_fixture_child(parent) VALUES(1); END""".trimIndent())
        }
        var checks = 0
        store.admitDispatch = { checks++; it == scope }
        try {
            TitleUpdateAttemptAdmission(store).dispatch(prepared) { fail("Failed commit must not send") }
            fail("Expected deferred SQLite commit failure")
        } catch (_: SQLiteException) { }
        // Two lease checks show UPDATE succeeded and the failure occurred during final commit.
        assertEquals(2, checks)
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(prepared, reopened.read(scope, prepared.id))
            reopened.writableDatabase.execSQL("DROP TRIGGER title_fixture_reject_commit")
            allowed(reopened); assertTrue(reopened.claim(prepared))
        }
    }

    @Test fun failureOrCancellationAfterCommittedClaimCannotResetOrResend() = fixture { context, name, store ->
        allowed(store)
        val cancelled = changedBase(base.copy(eventId = EventId("cancelled-title-event")))
            .copy(id = MutationId("cancelled-title-attempt"))
        for ((index, record) in listOf(prepared, cancelled).withIndex()) {
            store.remember(record)
            try {
                TitleUpdateAttemptAdmission(store).dispatch(record) { admitted ->
                    TitleUpdateAttemptStore(context, name).use { observer ->
                        assertEquals(admitted, observer.read(scope, record.id))
                    }
                    if (index == 0) error("Synthetic transport failure")
                    throw CancellationException("Synthetic transport cancellation")
                }
                fail("Expected interrupted transport")
            } catch (_: CancellationException) { } catch (_: IllegalStateException) { }
            store.remember(record)
            assertEquals(record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), store.read(scope, record.id))
            assertFalse(TitleUpdateAttemptAdmission(store).dispatch(record) { fail("Never resend") })
        }
    }

    @Test fun evidenceAttachesOnceToExactUnknownWorkAndReopensWithCompleteSnapshot() = fixture { context, name, store ->
        store.remember(prepared)
        val unknown = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        try { store.retainEvidence(prepared, evidence); fail("Prepared evidence must fail") }
        catch (_: IllegalArgumentException) { }
        rejects { store.retainEvidence(unknown, evidence) }
        allowed(store); assertTrue(store.claim(prepared))
        rejects { store.retainEvidence(unknown.copy(protectedPayload = "changed-wire-payload"), evidence) }
        rejects { store.retainEvidence(unknown.copy(preparation = preparation.copy(timestamp = preparation.timestamp.plusSeconds(1))), evidence) }
        try { store.retainEvidence(unknown, ""); fail("Empty evidence must fail") }
        catch (_: IllegalArgumentException) { }
        store.admitDispatch = { false }
        store.retainEvidence(unknown, evidence); store.retainEvidence(unknown, evidence)
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            val retained = unknown.copy(protectedEvidence = evidence)
            assertEquals(retained, reopened.read(scope, prepared.id))
            rejects { reopened.retainEvidence(unknown, "different-synthetic-evidence") }
            try { reopened.retainEvidence(unknown.copy(protectedEvidence = "wrong-expected-evidence"), evidence)
                fail("Changed expected evidence must fail") } catch (_: IllegalArgumentException) { }
            reopened.remember(prepared)
            assertEquals(retained, reopened.read(scope, prepared.id))
            allowed(reopened); assertFalse(reopened.claim(prepared))
        }
    }

    @Test fun corruptPreparationCannotBeReinterpretedOrAdmitDispatch() = fixture { _, _, store ->
        store.remember(prepared)
        store.writableDatabase.execSQL("UPDATE title_update_attempts SET preparation='{}'")
        allowed(store)
        rejects { store.read(scope, prepared.id) }
        try { assertFalse(store.claim(prepared)) } catch (_: IllegalStateException) { }
    }
}
