// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.OwnedEventWitness
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite fixtures; no account, network, or Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class CreatedEventBindingStoreDeviceTest {
    private val scope = CalendarScope(AccountId("binding-fixture-account"), CalendarId("binding-fixture-calendar"), 0)
    private val prepared = CreateAttemptRecord(MutationId("binding-fixture-mutation"), scope,
        AdapterFormat("synthetic-protected-fixture", 1), "synthetic-opaque-payload-not-keystore-proof")
    private val unknown = prepared.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
    private val identity = CreateAttemptIdentity.of(unknown)
    private val event = RemoteChange.Upsert(EventId("binding-fixture-event"), Revision("fixture-first-revision"),
        EventContent("Synthetic fixture", EventTime.AllDay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))))
    private val observation = CreateSubmissionObservation.Verified(identity, event)
    private val binding = CreatedEventBinding(identity, event.eventId, event.revision)
    private val evidence = "synthetic-opaque-evidence-not-keystore-proof"

    private fun fixture(block: suspend (Context, String, CreateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "create-binding-fixture-${UUID.randomUUID()}.db"
        val store = CreateAttemptStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); context.deleteDatabase(name) }
    }

    private suspend fun admit(store: CreateAttemptStore) {
        store.remember(prepared)
        assertTrue(store.claim(prepared))
    }

    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected synthetic rejection") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: SQLiteException) { }
    }

    @Test fun onlyExactAdmittedUnknownWorkAndExactObservationCanBind() = fixture { _, _, store ->
        reject { store.bind(unknown, observation) }
        store.remember(prepared)
        reject { store.bind(prepared, observation) }
        reject { store.bind(unknown, observation) }
        assertTrue(store.claim(prepared))
        for (changed in listOf(
            unknown.copy(scope = scope.copy(account = AccountId("other-fixture-account"))),
            unknown.copy(scope = scope.copy(calendar = CalendarId("other-fixture-calendar"))),
            unknown.copy(scope = scope.copy(generation = 1)),
            unknown.copy(id = MutationId("other-fixture-mutation")),
            unknown.copy(format = AdapterFormat("other-fixture-format", 1)),
            unknown.copy(format = unknown.format.copy(version = 2)),
            unknown.copy(protectedPayload = "other-fixture-payload"))) {
            reject { store.bind(changed, CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(changed), event)) }
            reject { store.bind(unknown, CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(changed), event)) }
        }
        assertNull(store.binding(identity))
        store.retainEvidence(unknown, evidence)
        reject { store.bind(unknown, observation) }
        assertEquals(binding, store.bind(unknown.copy(protectedEvidence = evidence), observation))
        assertEquals(unknown.copy(protectedEvidence = evidence), store.read(scope, unknown.id))
    }

    @Test fun firstEventAndVerifiedRevisionAreImmutableAndSameProofIsIdempotent() = fixture { _, _, store ->
        admit(store)
        assertEquals(binding, store.bind(unknown, observation))
        assertEquals(binding, store.bind(unknown, observation))
        reject { store.bind(unknown, CreateSubmissionObservation.Verified(identity,
            event.copy(eventId = EventId("another-fixture-event")))) }
        reject { store.bind(unknown, CreateSubmissionObservation.Verified(identity,
            event.copy(revision = Revision("another-fixture-revision")))) }
        assertEquals(binding, store.binding(identity))
        assertEquals(unknown, store.read(scope, unknown.id))
        assertFalse(store.claim(prepared))
    }

    @Test fun oneRemoteEventCannotBindTwoAttemptsInTheSameScope() = fixture { context, name, store ->
        admit(store)
        store.bind(unknown, observation)
        val anotherPrepared = prepared.copy(id = MutationId("second-fixture-mutation"))
        val anotherUnknown = anotherPrepared.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        val anotherIdentity = CreateAttemptIdentity.of(anotherUnknown)
        CreateAttemptStore(context, name).use { other ->
            other.remember(anotherPrepared)
            assertTrue(other.claim(anotherPrepared))
            reject { other.bind(anotherUnknown, CreateSubmissionObservation.Verified(anotherIdentity, event)) }
            assertNull(other.binding(anotherIdentity))
            assertEquals(anotherUnknown, other.read(scope, anotherUnknown.id))
        }
        // Remote IDs are calendar-scoped: the same opaque ID in another generation is distinct.
        val nextPrepared = prepared.copy(scope = scope.copy(generation = 1))
        store.remember(nextPrepared); assertTrue(store.claim(nextPrepared))
        val nextUnknown = nextPrepared.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        assertEquals(event.eventId, store.bind(nextUnknown,
            CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(nextUnknown), event)).eventId)
        assertEquals(binding, store.binding(identity))
    }

    @Test fun associationEvidenceUnknownAndHistoricalProjectionSurviveReopenWithoutResend() = fixture { context, name, store ->
        admit(store)
        store.retainEvidence(unknown, evidence)
        val retained = unknown.copy(protectedEvidence = evidence)
        store.bind(retained, observation)
        val witness = OwnedEventWitness(31, 45, Revision("fixture-current-imported-revision"))
        store.retainProjection(binding, witness)
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(retained, reopened.read(scope, unknown.id))
            assertEquals(binding, reopened.binding(identity))
            assertEquals(witness, reopened.projectionWitness(identity))
            assertEquals(binding, reopened.bind(retained, observation))
            reopened.remember(prepared)
            assertEquals(retained, reopened.read(scope, unknown.id))
            assertFalse(CreateAttemptAdmission(reopened).dispatch(prepared) { fail("Binding must not admit resend") })
        }
    }

    @Test fun substitutedParentOrRequestedFormatCannotExposeBindingOrWitness() = fixture { context, name, store ->
        admit(store); store.bind(unknown, observation)
        store.retainProjection(binding, OwnedEventWitness(31, 45, event.revision))
        assertNull(store.binding(identity.copy(format = AdapterFormat("wrong-fixture-format", 1))))
        assertNull(store.binding(identity.copy(payloadFingerprint = "0".repeat(64))))
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("UPDATE create_attempts SET payload=?", arrayOf("substituted-synthetic-payload"))
        }
        assertNull(store.binding(identity)); assertNull(store.projectionWitness(identity))
        reject { store.retainProjection(binding, OwnedEventWitness(32, 46, event.revision)) }
        store.clearProjection(identity)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("UPDATE create_attempts SET payload=?", arrayOf(unknown.protectedPayload))
        }
        assertEquals(binding, store.binding(identity))
        assertEquals(OwnedEventWitness(31, 45, event.revision), store.projectionWitness(identity))
    }

    @Test fun revokedLeaseBeforeAdmissionOrBeforeCommitRollsBackAndCannotResetUnknown() = fixture { _, _, store ->
        admit(store)
        store.admitAssociation = { false }
        reject { store.bind(unknown, observation) }
        assertNull(store.binding(identity))
        var checks = 0
        store.admitAssociation = { checked -> assertEquals(scope, checked); ++checks == 1 }
        reject { store.bind(unknown, observation) }
        assertEquals(2, checks); assertNull(store.binding(identity))
        assertEquals(unknown, store.read(scope, unknown.id)); assertFalse(store.claim(prepared))
        store.admitAssociation = { true }
        assertEquals(binding, store.bind(unknown, observation))
        val witness = OwnedEventWitness(31, 45, event.revision)
        checks = 0; store.admitAssociation = { ++checks == 1 }
        reject { store.retainProjection(binding, witness) }
        assertNull(store.projectionWitness(identity)); assertEquals(binding, store.binding(identity))
    }

    @Test fun cancellationBeforeCommitRollsBackOnlyAssociation() = fixture { _, _, store ->
        admit(store)
        coroutineScope {
            val cancelled = async {
                val job = currentCoroutineContext().job
                var checks = 0
                store.admitAssociation = { if (++checks == 2) job.cancel(); true }
                store.bind(unknown, observation)
            }
            try { cancelled.await(); fail("Expected synthetic cancellation") }
            catch (_: CancellationException) { }
        }
        assertNull(store.binding(identity)); assertEquals(unknown, store.read(scope, unknown.id))
        store.admitAssociation = { true }
        assertEquals(binding, store.bind(unknown, observation))
    }

    @Test fun failedAssociationInsertRollsBackWithoutChangingAdmissionOrEvidence() = fixture { context, name, store ->
        admit(store); store.retainEvidence(unknown, evidence)
        val retained = unknown.copy(protectedEvidence = evidence)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("""CREATE TRIGGER fixture_reject_binding BEFORE INSERT ON create_bindings
                BEGIN SELECT RAISE(ABORT, 'synthetic fixture rejection'); END""".trimIndent())
        }
        reject { store.bind(retained, observation) }
        assertNull(store.binding(identity)); assertEquals(retained, store.read(scope, unknown.id))
        assertFalse(store.claim(prepared))
    }

    @Test fun historicalWitnessCanBeReplacedOrClearedWithoutChangingRemoteAnchor() = fixture { _, _, store ->
        admit(store); store.bind(unknown, observation)
        assertNull(store.projectionWitness(identity))
        val first = OwnedEventWitness(31, 45, event.revision)
        val later = OwnedEventWitness(32, 46, Revision("fixture-later-imported-revision"))
        store.retainProjection(binding, first)
        store.retainProjection(binding, first)
        store.retainProjection(binding, later)
        assertEquals(later, store.projectionWitness(identity))
        reject { store.retainProjection(binding.copy(verifiedRevision = later.revision), first) }
        assertEquals(later, store.projectionWitness(identity))
        store.admitAssociation = { false }
        reject { store.clearProjection(identity) }
        assertEquals(later, store.projectionWitness(identity))
        store.admitAssociation = { true }
        store.clearProjection(identity); store.clearProjection(identity)
        assertNull(store.projectionWitness(identity)); assertEquals(binding, store.binding(identity))
        assertEquals(unknown, store.read(scope, unknown.id))
    }

    @Test fun versionTwoMigrationPreservesCiphertextEvidenceAndUnknownWithoutInventingProof() = fixture { context, name, store ->
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val retained = unknown.copy(protectedEvidence = evidence)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("""CREATE TABLE create_attempts (
                account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL CHECK(generation >= 0),
                mutation TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
                payload TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PREPARED', 'OUTCOME_UNKNOWN')), evidence TEXT,
                PRIMARY KEY(account, calendar, generation, mutation)
            )""".trimIndent())
            for (record in listOf(retained, prepared.copy(id = MutationId("legacy-prepared-fixture")))) {
                legacy.execSQL("INSERT INTO create_attempts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(
                    record.scope.account.value, record.scope.calendar.value, record.scope.generation, record.id.value,
                    record.format.id, record.format.version, record.protectedPayload, record.state.name, record.protectedEvidence))
            }
            legacy.version = 2
        }
        assertEquals(retained, store.read(scope, unknown.id))
        assertEquals(prepared.copy(id = MutationId("legacy-prepared-fixture")),
            store.read(scope, MutationId("legacy-prepared-fixture")))
        assertNull(store.binding(identity)); assertNull(store.projectionWitness(identity))
        assertFalse(store.claim(prepared))
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { migrated ->
            assertEquals(3, migrated.version)
        }
        assertEquals(binding, store.bind(retained, observation))
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(retained, reopened.read(scope, unknown.id))
            assertEquals(binding, reopened.binding(identity))
            assertFalse(CreateAttemptAdmission(reopened).dispatch(prepared) { fail("Migration must not admit resend") })
        }
    }
}
