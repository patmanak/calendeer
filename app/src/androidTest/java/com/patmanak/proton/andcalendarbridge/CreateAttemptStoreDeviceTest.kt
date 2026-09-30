// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in isolated SQLite fixtures (-e providerFixture true); no account/network/Provider access.
 * The synthetic string payload tests opacity/persistence, not actual Keystore encryption.
 */
@RunWith(AndroidJUnit4::class)
class CreateAttemptStoreDeviceTest {
    private val scope = CalendarScope(AccountId("create-fixture-account"), CalendarId("create-fixture-calendar"), 0)
    private val prepared = CreateAttemptRecord(MutationId("fixture-mutation"), scope,
        AdapterFormat("synthetic-protected-fixture", 1), "synthetic-opaque-string-not-keystore-proof")
    private val evidence = "synthetic-opaque-response-not-keystore-proof"

    private fun fixture(block: suspend (Context, String, CreateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "create-attempt-fixture-${UUID.randomUUID()}.db"
        val store = CreateAttemptStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun preparedAndUnknownSurviveReopenWithoutResendingOrResetting() = fixture { context, name, store ->
        store.remember(prepared)
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(prepared, reopened.read(scope, prepared.id))
            assertTrue(reopened.claim(prepared))
        }
        CreateAttemptStore(context, name).use { reopened ->
            val unknown = prepared.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
            assertEquals(unknown, reopened.read(scope, prepared.id))
            reopened.remember(prepared)
            assertEquals(unknown, reopened.read(scope, prepared.id))
            assertFalse(reopened.claim(prepared))
            assertFalse(CreateAttemptAdmission(reopened).dispatch(prepared) { fail("Must not resend") })
        }
    }

    @Test fun rememberIsImmutableAndOnlyAcceptsPreparedRecords() = fixture { _, _, store ->
        store.remember(prepared)
        store.remember(prepared)
        for (changed in listOf(prepared.copy(protectedPayload = "different-synthetic-fragment"),
            prepared.copy(format = prepared.format.copy(version = 2)),
            prepared.copy(format = AdapterFormat("another-fixture-adapter", 1)))) {
            try { store.remember(changed); fail("Expected immutable work rejection") }
            catch (_: IllegalStateException) { }
            assertFalse(store.claim(changed))
            assertEquals(prepared, store.read(scope, prepared.id))
        }
        try {
            store.remember(prepared.copy(state = CreateAttemptState.OUTCOME_UNKNOWN))
            fail("Expected state rejection")
        } catch (_: IllegalArgumentException) { }
        assertFalse(store.claim(prepared.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)))
        assertEquals(prepared, store.read(scope, prepared.id))
    }

    @Test fun scopeIdentityIncludesAccountCalendarGenerationAndMutation() = fixture { _, _, store ->
        store.remember(prepared)
        val others = listOf(prepared.copy(scope = scope.copy(account = AccountId("another-fixture-account"))),
            prepared.copy(scope = scope.copy(calendar = CalendarId("another-fixture-calendar"))),
            prepared.copy(scope = scope.copy(generation = 1)),
            prepared.copy(id = MutationId("another-fixture-mutation")))
        for (other in others) {
            assertNull(store.read(other.scope, other.id))
            assertFalse(store.claim(other))
            store.remember(other)
        }
        assertTrue(store.claim(prepared))
        for (other in others) assertEquals(other, store.read(other.scope, other.id))
    }

    @Test fun twoIndependentHandlesCommitExactlyOneClaim() = fixture { context, name, store ->
        CreateAttemptStore(context, name).use { other ->
            repeat(12) { index ->
                val record = prepared.copy(id = MutationId("concurrent-fixture-$index"))
                store.remember(record)
                // Both helpers are initialized before the simultaneous admission attempt.
                assertEquals(record, other.read(scope, record.id))
                coroutineScope {
                    val start = CompletableDeferred<Unit>()
                    val first = async(Dispatchers.IO) { start.await(); store.claim(record) }
                    val second = async(Dispatchers.IO) { start.await(); other.claim(record) }
                    start.complete(Unit)
                    assertEquals(1, listOf(first.await(), second.await()).count { it })
                }
                assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, other.read(scope, record.id)?.state)
                assertFalse(store.claim(record)); assertFalse(other.claim(record))
            }
        }
    }

    @Test fun failedTransactionCannotAdmitDispatchAndCanBeClaimedAfterRollback() = fixture { context, name, store ->
        store.remember(prepared)
        val path = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("""CREATE TRIGGER fixture_reject_claim BEFORE UPDATE ON create_attempts
                BEGIN SELECT RAISE(ABORT, 'synthetic fixture rejection'); END""".trimIndent())
        }
        try {
            CreateAttemptAdmission(store).dispatch(prepared) { fail("Failed transaction must not send") }
            fail("Expected SQLite rollback")
        } catch (_: SQLiteException) { }
        assertEquals(prepared, store.read(scope, prepared.id))
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TRIGGER fixture_reject_claim")
        }
        assertTrue(store.claim(prepared))
    }

    @Test fun cancellationOrFailureAfterAdmissionCannotResetUnknown() = fixture { context, name, store ->
        val attempts = listOf(prepared, prepared.copy(id = MutationId("cancelled-fixture-mutation")))
        for ((index, record) in attempts.withIndex()) {
            store.remember(record)
            try {
                CreateAttemptAdmission(store).dispatch(record) { admitted ->
                    assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, admitted.state)
                    CreateAttemptStore(context, name).use { observer ->
                        // Separate connection proves UNKNOWN committed before the send callback.
                        assertEquals(admitted, observer.read(record.scope, record.id))
                    }
                    if (index == 0) error("Synthetic transport exception")
                    throw CancellationException("Synthetic transport cancellation")
                }
                fail("Expected transport interruption")
            } catch (_: CancellationException) { }
            catch (_: IllegalStateException) { }
            store.remember(record)
            assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, store.read(record.scope, record.id)?.state)
            assertFalse(CreateAttemptAdmission(store).dispatch(record) { fail("Must not resend") })
        }
    }

    @Test fun cancellingTheSendingCoroutineLeavesCommittedUnknownAfterReopen() = fixture { context, name, store ->
        store.remember(prepared)
        coroutineScope {
            val sending = CompletableDeferred<Unit>()
            val job = launch {
                CreateAttemptAdmission(store).dispatch(prepared) {
                    sending.complete(Unit)
                    awaitCancellation()
                }
            }
            sending.await()
            job.cancelAndJoin()
        }
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, reopened.read(scope, prepared.id)?.state)
            assertFalse(CreateAttemptAdmission(reopened).dispatch(prepared) { fail("Must not resend") })
        }
    }

    @Test fun evidenceRequiresExistingIdenticalUnknownWork() = fixture { _, _, store ->
        store.remember(prepared)
        try { store.retainEvidence(prepared, evidence); fail("Prepared evidence must be refused") }
        catch (_: IllegalArgumentException) { }
        val unknown = prepared.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        try { store.retainEvidence(unknown, evidence); fail("Unclaimed evidence must be refused") }
        catch (_: IllegalStateException) { }
        assertEquals(prepared, store.read(scope, prepared.id))
        assertTrue(store.claim(prepared))
        try { store.retainEvidence(unknown, " "); fail("Blank evidence must be refused") }
        catch (_: IllegalArgumentException) { }
        for (changed in listOf(
            unknown.copy(scope = scope.copy(account = AccountId("wrong-fixture-account"))),
            unknown.copy(scope = scope.copy(calendar = CalendarId("wrong-fixture-calendar"))),
            unknown.copy(scope = scope.copy(generation = 1)),
            unknown.copy(id = MutationId("wrong-fixture-mutation")),
            unknown.copy(format = AdapterFormat("wrong-fixture-format", 1)),
            unknown.copy(format = unknown.format.copy(version = 2)),
            unknown.copy(protectedPayload = "wrong-synthetic-payload"))) {
            try { store.retainEvidence(changed, evidence); fail("Changed work must be refused") }
            catch (_: IllegalStateException) { }
            assertEquals(unknown, store.read(scope, prepared.id))
        }
        try { prepared.copy(protectedEvidence = " "); fail("Blank record evidence must be refused") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun evidenceIsImmutableAndSurvivesReopenWithoutResetOrResend() = fixture { context, name, store ->
        store.remember(prepared)
        assertTrue(store.claim(prepared))
        val unknown = prepared.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        val retained = unknown.copy(protectedEvidence = evidence)
        store.retainEvidence(unknown, evidence)
        store.retainEvidence(unknown, evidence)
        store.retainEvidence(retained, evidence)
        try { store.retainEvidence(unknown, "different-synthetic-response"); fail("Evidence must be immutable") }
        catch (_: IllegalStateException) { }
        try { store.remember(prepared.copy(protectedEvidence = evidence)); fail("Evidence must not be remembered") }
        catch (_: IllegalArgumentException) { }
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(retained, reopened.read(scope, prepared.id))
            reopened.remember(prepared)
            reopened.retainEvidence(unknown, evidence)
            assertEquals(retained, reopened.read(scope, prepared.id))
            assertFalse(reopened.claim(prepared))
            assertFalse(reopened.claim(prepared.copy(protectedEvidence = evidence)))
            assertFalse(CreateAttemptAdmission(reopened).dispatch(prepared) { fail("Evidence must not enable resend") })
        }
    }

    @Test fun inconsistentPreparedEvidenceCannotBeClaimed() = fixture { context, name, store ->
        store.remember(prepared)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("UPDATE create_attempts SET evidence=?", arrayOf(evidence))
        }
        assertFalse(store.claim(prepared))
        try { store.remember(prepared); fail("Inconsistent prepared evidence must be refused") }
        catch (_: IllegalStateException) { }
        assertEquals(prepared.copy(protectedEvidence = evidence), store.read(scope, prepared.id))
    }

    @Test fun realVersionOneMigrationPreservesPayloadsAndAdmissionStates() = fixture { context, name, store ->
        val legacyUnknown = prepared.copy(id = MutationId("legacy-unknown-fixture"),
            protectedPayload = "another-legacy-synthetic-payload", state = CreateAttemptState.OUTCOME_UNKNOWN)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        // Create the original schema independently, before the current helper opens it.
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("""CREATE TABLE create_attempts (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0), mutation TEXT NOT NULL,
                adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0), payload TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('PREPARED', 'OUTCOME_UNKNOWN')),
                PRIMARY KEY(account, calendar, generation, mutation)
            )""".trimIndent())
            for (record in listOf(prepared, legacyUnknown)) {
                legacy.execSQL("INSERT INTO create_attempts VALUES (?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(
                    record.scope.account.value, record.scope.calendar.value, record.scope.generation,
                    record.id.value, record.format.id, record.format.version, record.protectedPayload,
                    record.state.name))
            }
            legacy.version = 1
        }
        assertEquals(prepared, store.read(scope, prepared.id))
        assertEquals(legacyUnknown, store.read(scope, legacyUnknown.id))
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { migrated ->
            assertEquals(4, migrated.version)
        }
        assertTrue(store.claim(prepared))
        assertFalse(store.claim(legacyUnknown.copy(state = CreateAttemptState.PREPARED)))
        store.retainEvidence(legacyUnknown, evidence)
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(legacyUnknown.copy(protectedEvidence = evidence), reopened.read(scope, legacyUnknown.id))
            assertFalse(CreateAttemptAdmission(reopened).dispatch(
                legacyUnknown.copy(state = CreateAttemptState.PREPARED)) { fail("Migration must not enable resend") })
        }
    }
}
