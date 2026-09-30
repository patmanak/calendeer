// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.patmanak.proton.andcalendarbridge.sync.AdapterFormat
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.data.calendar.OwnedEventWitness
import com.patmanak.proton.andcalendarbridge.sync.CreateAssociationJournal
import com.patmanak.proton.andcalendarbridge.sync.CreateAttemptIdentity
import com.patmanak.proton.andcalendarbridge.sync.CreateSubmissionObservation
import com.patmanak.proton.andcalendarbridge.sync.CreatedEventBinding
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.Revision
import com.patmanak.proton.andcalendarbridge.sync.CreateAttemptRecord
import com.patmanak.proton.andcalendarbridge.sync.CreateAttemptState
import com.patmanak.proton.andcalendarbridge.sync.MutationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Permanent dispatch hold; historical work and any later evidence remain intact. */
enum class CreatePauseReason {
    PROCESS_RESTART, SESSION_CHANGED, CALENDAR_DISABLED, CALENDAR_UNAVAILABLE, PERMISSION_LOST
}

/**
 * App-private, backup-excluded dispatch journal. Payloads must already be adapter-protected.
 * Independent SQLite helpers/processes arbitrate admission through the same committed row.
 * This does not capture Provider edits, send requests, or acknowledge remote creation.
 */
class CreateAttemptStore(context: Context, name: String = "calendar-create-attempt-v1.db") :
    SQLiteOpenHelper(context, name, null, 4), CreateAssociationJournal {
    private val lock = Mutex()
    @Volatile var admitAssociation: (CalendarScope) -> Boolean = { true }
    @Volatile var admitDispatch: (CalendarScope) -> Boolean = { true }

    override fun onConfigure(db: SQLiteDatabase) {
        // Admission must return only after SQLite has durably recorded uncertainty.
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE create_attempts (
                account TEXT NOT NULL,
                calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0),
                mutation TEXT NOT NULL,
                adapter TEXT NOT NULL,
                version INTEGER NOT NULL CHECK(version > 0),
                payload TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('PREPARED', 'OUTCOME_UNKNOWN')),
                evidence TEXT,
                PRIMARY KEY(account, calendar, generation, mutation)
            )
        """.trimIndent())
        createBindings(db)
        createPauses(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..3 && newVersion == 4) { "Explicit create journal schema migration required" }
        if (oldVersion == 1) db.execSQL("ALTER TABLE create_attempts ADD COLUMN evidence TEXT")
        if (oldVersion < 3) createBindings(db)
        createPauses(db)
    }

    private fun createPauses(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE create_pauses (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0), mutation TEXT NOT NULL,
                reason TEXT NOT NULL CHECK(reason IN (
                    'PROCESS_RESTART', 'SESSION_CHANGED', 'CALENDAR_DISABLED',
                    'CALENDAR_UNAVAILABLE', 'PERMISSION_LOST')),
                PRIMARY KEY(account, calendar, generation, mutation)
            )
        """.trimIndent())
    }

    /** Pause only unadmitted work. The first reason is retained and no resume operation exists. */
    suspend fun pausePrepared(reason: CreatePauseReason, account: AccountId? = null, target: CalendarScope? = null) {
        val selection: String
        val arguments: Array<String>
        when {
            target != null -> {
                selection = " AND account=? AND calendar=? AND generation=?"
                arguments = arrayOf(target.account.value, target.calendar.value, target.generation.toString())
            }
            account != null -> {
                selection = " AND account=?"
                arguments = arrayOf(account.value)
            }
            else -> { selection = ""; arguments = emptyArray() }
        }
        access { db ->
            transaction(db) {
                db.execSQL("""INSERT OR IGNORE INTO create_pauses (account, calendar, generation, mutation, reason)
                    SELECT account, calendar, generation, mutation, ? FROM create_attempts
                    WHERE state='PREPARED' AND evidence IS NULL$selection""".trimIndent(),
                    arrayOf(reason.name) + arguments)
            }
        }
    }

    suspend fun pauseReason(scope: CalendarScope, id: MutationId): CreatePauseReason? = access { db ->
        db.query("create_pauses", arrayOf("reason"), IDENTITY, identity(scope, id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) CreatePauseReason.valueOf(cursor.getString(0)) else null
        }
    }

    private fun createBindings(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE create_bindings (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0), mutation TEXT NOT NULL,
                adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
                fingerprint TEXT NOT NULL, event TEXT NOT NULL, verified_revision TEXT NOT NULL,
                provider_calendar INTEGER, provider_event INTEGER, provider_revision TEXT,
                PRIMARY KEY(account, calendar, generation, mutation),
                UNIQUE(account, calendar, generation, event),
                CHECK ((provider_calendar IS NULL AND provider_event IS NULL AND provider_revision IS NULL)
                    OR (provider_calendar IS NOT NULL AND provider_event IS NOT NULL AND provider_revision IS NOT NULL
                        AND provider_calendar > 0 AND provider_event > 0))
            )
        """.trimIndent())
    }

    override suspend fun bind(expectedUnknown: CreateAttemptRecord,
        observation: CreateSubmissionObservation.Verified): CreatedEventBinding {
        require(expectedUnknown.state == CreateAttemptState.OUTCOME_UNKNOWN) { "Binding requires unknown work" }
        val attempt = CreateAttemptIdentity.of(expectedUnknown)
        require(observation.attempt == attempt) { "Observation requires identical work" }
        val wanted = CreatedEventBinding(attempt, observation.event.eventId, observation.event.revision)
        return access { db ->
            associationTransaction(db, attempt.scope) {
                check(read(db, attempt.scope, attempt.id) == expectedUnknown) { "Binding requires identical unknown work" }
                val existing = readBinding(db, attempt)
                if (existing != null) {
                    check(existing == wanted) { "Create binding is immutable" }
                } else {
                    db.insertOrThrow("create_bindings", null, ContentValues().apply {
                        put("account", attempt.scope.account.value)
                        put("calendar", attempt.scope.calendar.value)
                        put("generation", attempt.scope.generation)
                        put("mutation", attempt.id.value)
                        put("adapter", attempt.format.id)
                        put("version", attempt.format.version)
                        put("fingerprint", attempt.payloadFingerprint)
                        put("event", wanted.eventId.value)
                        put("verified_revision", wanted.verifiedRevision.value)
                    })
                }
                wanted
            }
        }
    }

    override suspend fun binding(identity: CreateAttemptIdentity): CreatedEventBinding? =
        access { db -> readBinding(db, identity) }

    /** Historical locator only: callers must inspect the current cache and owned Provider row again. */
    suspend fun retainProjection(binding: CreatedEventBinding, witness: OwnedEventWitness) {
        access { db ->
            associationTransaction(db, binding.attempt.scope) {
                check(readBinding(db, binding.attempt) == binding) { "Projection requires identical binding" }
                check(db.update("create_bindings", ContentValues().apply {
                    put("provider_calendar", witness.calendarRow)
                    put("provider_event", witness.eventRow)
                    put("provider_revision", witness.revision.value)
                }, IDENTITY, identity(binding.attempt.scope, binding.attempt.id)) == 1) {
                    "Projection witness was not retained"
                }
            }
        }
    }

    suspend fun projectionWitness(attempt: CreateAttemptIdentity): OwnedEventWitness? = access { db ->
        if (readBinding(db, attempt) == null) null else db.query("create_bindings",
            arrayOf("provider_calendar", "provider_event", "provider_revision"), IDENTITY,
            identity(attempt.scope, attempt.id), null, null, null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1) || cursor.isNull(2)) null
            else OwnedEventWitness(cursor.getLong(0), cursor.getLong(1), Revision(cursor.getString(2)))
        }
    }

    suspend fun clearProjection(attempt: CreateAttemptIdentity) {
        access { db ->
            associationTransaction(db, attempt.scope) {
                if (readBinding(db, attempt) != null) db.update("create_bindings", ContentValues().apply {
                    putNull("provider_calendar"); putNull("provider_event"); putNull("provider_revision")
                }, IDENTITY, identity(attempt.scope, attempt.id))
            }
        }
    }

    private fun readBinding(db: SQLiteDatabase, attempt: CreateAttemptIdentity): CreatedEventBinding? {
        val parent = read(db, attempt.scope, attempt.id) ?: return null
        if (parent.state != CreateAttemptState.OUTCOME_UNKNOWN || CreateAttemptIdentity.of(parent) != attempt) return null
        return db.query("create_bindings", arrayOf("adapter", "version", "fingerprint", "event", "verified_revision"),
            IDENTITY, identity(attempt.scope, attempt.id), null, null, null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != attempt.format.id ||
                cursor.getInt(1) != attempt.format.version || cursor.getString(2) != attempt.payloadFingerprint) null
            else CreatedEventBinding(attempt, EventId(cursor.getString(3)), Revision(cursor.getString(4)))
        }
    }

    private suspend fun <T> associationTransaction(db: SQLiteDatabase, scope: CalendarScope, action: () -> T): T {
        currentCoroutineContext().ensureActive()
        check(admitAssociation(scope)) { "Create association lease is not current" }
        return transaction(db) {
            val result = action()
            check(admitAssociation(scope)) { "Create association lease is not current" }
            result
        }
    }

    override suspend fun remember(record: CreateAttemptRecord) {
        require(record.state == CreateAttemptState.PREPARED && record.protectedEvidence == null) {
            "Only prepared work without evidence can be remembered"
        }
        access { db ->
            dispatchTransaction(db, record.scope, { error("Create dispatch lease is not current") }) {
                val existing = read(db, record.scope, record.id)
                if (existing != null) {
                    check((existing.state == CreateAttemptState.OUTCOME_UNKNOWN || existing.protectedEvidence == null) &&
                        existing.copy(state = CreateAttemptState.PREPARED, protectedEvidence = null) == record) {
                        "Create attempt is immutable"
                    }
                } else {
                    db.insertOrThrow("create_attempts", null, ContentValues().apply {
                        put("account", record.scope.account.value)
                        put("calendar", record.scope.calendar.value)
                        put("generation", record.scope.generation)
                        put("mutation", record.id.value)
                        put("adapter", record.format.id)
                        put("version", record.format.version)
                        put("payload", record.protectedPayload)
                        put("state", CreateAttemptState.PREPARED.name)
                    })
                }
            }
        }
    }

    override suspend fun read(scope: CalendarScope, id: MutationId): CreateAttemptRecord? =
        access { db -> read(db, scope, id) }

    override suspend fun claim(expected: CreateAttemptRecord): Boolean {
        if (expected.state != CreateAttemptState.PREPARED || expected.protectedEvidence != null) return false
        return access { db ->
            dispatchTransaction(db, expected.scope, { false }) {
                db.update("create_attempts", ContentValues().apply {
                    put("state", CreateAttemptState.OUTCOME_UNKNOWN.name)
                }, "$IDENTITY AND adapter=? AND version=? AND payload=? AND state=? AND evidence IS NULL" +
                    " AND NOT EXISTS (SELECT 1 FROM create_pauses p WHERE p.account=create_attempts.account" +
                    " AND p.calendar=create_attempts.calendar AND p.generation=create_attempts.generation" +
                    " AND p.mutation=create_attempts.mutation)",
                    identity(expected.scope, expected.id) + arrayOf(expected.format.id,
                        expected.format.version.toString(), expected.protectedPayload,
                        CreateAttemptState.PREPARED.name)) == 1
            }
        }
    }

    override suspend fun retainEvidence(expectedUnknown: CreateAttemptRecord, protectedEvidence: String) {
        require(expectedUnknown.state == CreateAttemptState.OUTCOME_UNKNOWN) {
            "Evidence requires an unknown attempt"
        }
        require(protectedEvidence.isNotBlank()) { "Protected evidence must not be blank" }
        access { db ->
            transaction(db) {
                val existing = read(db, expectedUnknown.scope, expectedUnknown.id)
                check(existing != null && existing.state == CreateAttemptState.OUTCOME_UNKNOWN &&
                    existing.copy(protectedEvidence = null) == expectedUnknown.copy(protectedEvidence = null)) {
                    "Evidence requires identical unknown work"
                }
                if (existing.protectedEvidence != null) {
                    check(existing.protectedEvidence == protectedEvidence) { "Create evidence is immutable" }
                } else {
                    check(db.update("create_attempts", ContentValues().apply {
                        put("evidence", protectedEvidence)
                    }, "$IDENTITY AND adapter=? AND version=? AND payload=? AND state=? AND evidence IS NULL",
                        identity(expectedUnknown.scope, expectedUnknown.id) + arrayOf(expectedUnknown.format.id,
                            expectedUnknown.format.version.toString(), expectedUnknown.protectedPayload,
                            CreateAttemptState.OUTCOME_UNKNOWN.name)) == 1) {
                        "Create evidence was not retained"
                    }
                }
            }
        }
    }

    private fun read(db: SQLiteDatabase, scope: CalendarScope, id: MutationId): CreateAttemptRecord? =
        db.query("create_attempts", arrayOf("adapter", "version", "payload", "state", "evidence"),
            IDENTITY, identity(scope, id), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) null else CreateAttemptRecord(id, scope,
                AdapterFormat(cursor.getString(0), cursor.getInt(1)), cursor.getString(2),
                CreateAttemptState.valueOf(cursor.getString(3)),
                if (cursor.isNull(4)) null else cursor.getString(4))
        }

    private fun identity(scope: CalendarScope, id: MutationId) = arrayOf(scope.account.value,
        scope.calendar.value, scope.generation.toString(), id.value)

    private suspend fun <T> access(action: suspend (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            action(writableDatabase)
        }
    }

    private suspend fun <T> transaction(db: SQLiteDatabase, action: () -> T): T {
        db.beginTransaction()
        try {
            val result = action()
            currentCoroutineContext().ensureActive()
            db.setTransactionSuccessful()
            return result
        } finally {
            // A failed commit throws before the caller can admit dispatch.
            db.endTransaction()
        }
    }

    private suspend fun <T> dispatchTransaction(db: SQLiteDatabase, scope: CalendarScope,
        denied: () -> T, action: () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!admitDispatch(scope)) return denied()
        return transaction(db) {
            val result = action()
            check(admitDispatch(scope)) { "Create dispatch lease is not current" }
            result
        }
    }

    private companion object {
        const val IDENTITY = "account=? AND calendar=? AND generation=? AND mutation=?"
    }
}
