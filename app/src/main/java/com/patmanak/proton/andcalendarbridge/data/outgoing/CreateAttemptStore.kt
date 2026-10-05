// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
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
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateDispatchIdentity
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateBinding
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateAttachmentReceipt
import com.patmanak.proton.andcalendarbridge.sync.CapturedProjectionHold
import com.patmanak.proton.andcalendarbridge.sync.LocalCreateSubmission
import com.patmanak.proton.andcalendarbridge.sync.LocalEventId
import java.time.Instant
import java.time.DateTimeException
import java.util.UUID
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
    SQLiteOpenHelper(context, name, null, 7), CreateAssociationJournal {
    private val lock = Mutex()
    @Volatile var admitAssociation: (CalendarScope) -> Boolean = { true }
    @Volatile var admitDispatch: (CalendarScope) -> Boolean = { true }

    override fun onConfigure(db: SQLiteDatabase) {
        // Admission must return only after SQLite has durably recorded uncertainty.
        db.execSQL("PRAGMA synchronous=FULL")
        db.setForeignKeyConstraintsEnabled(true)
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
        createCapturedLinks(db)
        createCapturedBindings(db)
        createCapturedAttachments(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..6 && newVersion == 7) { "Explicit create journal schema migration required" }
        if (oldVersion == 1) db.execSQL("ALTER TABLE create_attempts ADD COLUMN evidence TEXT")
        if (oldVersion < 3) createBindings(db)
        if (oldVersion < 4) createPauses(db)
        if (oldVersion < 5) createCapturedLinks(db)
        if (oldVersion < 6) createCapturedBindings(db)
        createCapturedAttachments(db)
    }

    private fun createCapturedAttachments(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE captured_create_attachments (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0), mutation TEXT NOT NULL,
                adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
                payload_fingerprint TEXT NOT NULL,
                local_id TEXT NOT NULL, capture_id TEXT NOT NULL,
                capture_adapter TEXT NOT NULL, capture_version INTEGER NOT NULL CHECK(capture_version > 0),
                timestamp INTEGER NOT NULL, content_fingerprint TEXT NOT NULL,
                fingerprint_version INTEGER NOT NULL CHECK(fingerprint_version = 1),
                event TEXT NOT NULL, verified_revision TEXT NOT NULL,
                nonce TEXT NOT NULL UNIQUE, protocol INTEGER NOT NULL CHECK(protocol = 1),
                PRIMARY KEY(account, calendar, generation, mutation),
                UNIQUE(account, calendar, generation, event),
                FOREIGN KEY(account, calendar, generation, mutation)
                    REFERENCES captured_create_bindings(account, calendar, generation, mutation)
            )
        """.trimIndent())
    }

    private fun createCapturedBindings(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE captured_create_bindings (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0), mutation TEXT NOT NULL,
                adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
                payload_fingerprint TEXT NOT NULL,
                local_id TEXT NOT NULL, capture_id TEXT NOT NULL,
                capture_adapter TEXT NOT NULL, capture_version INTEGER NOT NULL CHECK(capture_version > 0),
                timestamp INTEGER NOT NULL, content_fingerprint TEXT NOT NULL,
                fingerprint_version INTEGER NOT NULL CHECK(fingerprint_version = 1),
                event TEXT NOT NULL, verified_revision TEXT NOT NULL,
                PRIMARY KEY(account, calendar, generation, mutation),
                UNIQUE(account, calendar, generation, event),
                FOREIGN KEY(account, calendar, generation, mutation)
                    REFERENCES captured_create_links(account, calendar, generation, mutation)
            )
        """.trimIndent())
    }

    private fun createCapturedLinks(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE captured_create_links (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0), mutation TEXT NOT NULL,
                adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
                payload_fingerprint TEXT NOT NULL,
                local_id TEXT NOT NULL, capture_id TEXT NOT NULL,
                capture_adapter TEXT NOT NULL, capture_version INTEGER NOT NULL CHECK(capture_version > 0),
                timestamp INTEGER NOT NULL, content_fingerprint TEXT NOT NULL,
                fingerprint_version INTEGER NOT NULL CHECK(fingerprint_version = 1),
                PRIMARY KEY(account, calendar, generation, mutation),
                UNIQUE(account, calendar, generation, local_id),
                UNIQUE(account, calendar, generation, capture_id),
                FOREIGN KEY(account, calendar, generation, mutation)
                    REFERENCES create_attempts(account, calendar, generation, mutation)
            )
        """.trimIndent())
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

    /** Pause only unadmitted work. Generic work has no resume; captured work requires fresh qualification. */
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
                check(!hasCapturedWork(db, attempt.scope, attempt.id)) { "Captured association requires its own reconciliation" }
                check(read(db, attempt.scope, attempt.id) == expectedUnknown) { "Binding requires identical unknown work" }
                check(!hasEvent(db, "captured_create_bindings", attempt.scope, wanted.eventId) &&
                    !hasEvent(db, "captured_create_attachments", attempt.scope, wanted.eventId)) {
                    "Remote event already has a captured association"
                }
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

    /** Immutable authenticated proof only. No Provider acknowledgement or admission reset.
     * The owner must compare the exact captured content after strict inspectCaptured verification.
     * This store compares the attempt/link and remote identity/revision, not plaintext content.
     */
    suspend fun bindCaptured(expectedUnknown: CreateAttemptRecord, identity: CapturedCreateDispatchIdentity,
        observation: CreateSubmissionObservation.Verified): CapturedCreateBinding {
        require(expectedUnknown.state == CreateAttemptState.OUTCOME_UNKNOWN) { "Captured binding requires unknown work" }
        require(CreateAttemptIdentity.of(expectedUnknown) == identity.attempt && observation.attempt == identity.attempt) {
            "Captured observation requires identical work"
        }
        val wanted = CapturedCreateBinding(identity, observation.event.eventId, observation.event.revision)
        return access { db ->
            associationTransaction(db, identity.attempt.scope) {
                val parent = read(db, identity.attempt.scope, identity.attempt.id)
                check(parent == expectedUnknown &&
                    readCapturedIdentity(db, identity.attempt.scope, identity.attempt.id, parent) == identity) {
                    "Captured binding requires identical unknown work and local link"
                }
                check(!hasEvent(db, "create_bindings", identity.attempt.scope, wanted.eventId)) {
                    "Remote event already has a generic association"
                }
                val existing = readCapturedBinding(db, identity, parent)
                if (hasCapturedBinding(db, identity.attempt.scope, identity.attempt.id)) {
                    check(existing == wanted) { "Captured binding is immutable" }
                } else {
                    check(!hasCapturedAttachment(db, identity.attempt.scope, identity.attempt.id)) {
                        "Orphaned captured attachment cannot authorize replacement proof"
                    }
                    db.insertOrThrow("captured_create_bindings", null, capturedValues(identity).apply {
                        put("event", wanted.eventId.value); put("verified_revision", wanted.verifiedRevision.value)
                    })
                }
                wanted
            }
        }
    }

    suspend fun capturedBinding(identity: CapturedCreateDispatchIdentity): CapturedCreateBinding? = access { db ->
        try { readCapturedBinding(db, identity, read(db, identity.attempt.scope, identity.attempt.id)) }
        catch (_: IllegalArgumentException) { null }
        catch (_: DateTimeException) { null }
    }

    /** Reserve one immutable metadata-write nonce against exact current proof. No Provider IO/ACK.
     * Replay reuses the first nonce; a missing or damaged proof/receipt never authorizes replacement.
     */
    suspend fun prepareCapturedAttachment(binding: CapturedCreateBinding): CapturedCreateAttachmentReceipt = access { db ->
        associationTransaction(db, binding.identity.attempt.scope) {
            val attempt = binding.identity.attempt
            val parent = read(db, attempt.scope, attempt.id)
            check(readCapturedBinding(db, binding.identity, parent) == binding &&
                !hasEvent(db, "create_bindings", attempt.scope, binding.eventId)) {
                "Attachment requires identical current captured proof"
            }
            if (hasCapturedAttachment(db, attempt.scope, attempt.id)) {
                checkNotNull(readCapturedAttachment(db, binding, parent)) { "Captured attachment is immutable" }
            } else {
                val receipt = CapturedCreateAttachmentReceipt(binding, UUID.randomUUID().toString())
                db.insertOrThrow("captured_create_attachments", null, capturedValues(binding.identity).apply {
                    put("event", binding.eventId.value); put("verified_revision", binding.verifiedRevision.value)
                    put("nonce", receipt.nonce); put("protocol", receipt.protocol)
                })
                receipt
            }
        }
    }

    suspend fun capturedAttachment(binding: CapturedCreateBinding): CapturedCreateAttachmentReceipt? = access { db ->
        try {
            val attempt = binding.identity.attempt
            readCapturedAttachment(db, binding, read(db, attempt.scope, attempt.id))
        } catch (_: IllegalArgumentException) { null }
        catch (_: DateTimeException) { null }
    }

    private fun hasCapturedAttachment(db: SQLiteDatabase, scope: CalendarScope, id: MutationId): Boolean =
        db.query("captured_create_attachments", arrayOf("mutation"), IDENTITY, identity(scope, id),
            null, null, null).use { it.moveToFirst() }

    private fun readCapturedAttachment(db: SQLiteDatabase, binding: CapturedCreateBinding,
        knownParent: CreateAttemptRecord?): CapturedCreateAttachmentReceipt? {
        if (readCapturedBinding(db, binding.identity, knownParent) != binding ||
            hasEvent(db, "create_bindings", binding.identity.attempt.scope, binding.eventId)) return null
        val attempt = binding.identity.attempt
        return db.query("captured_create_attachments", CAPTURE_COLUMNS + arrayOf("event", "verified_revision", "nonce", "protocol"),
            IDENTITY, identity(attempt.scope, attempt.id), null, null, null).use { row ->
            if (!row.moveToFirst() || decodeCapturedLink(row, attempt) != binding.identity ||
                row.getString(10) != binding.eventId.value || row.getString(11) != binding.verifiedRevision.value) null
            else try { CapturedCreateAttachmentReceipt(binding, row.getString(12), row.getInt(13)) }
            catch (_: IllegalArgumentException) { null }
        }
    }

    /** A consistent conservative view of admitted captured work, including damaged raw rows.
     * UNKNOWN without exact proof blocks the whole scope; exact proof holds its remote event.
     * Event IDs from a different import adapter format cannot narrow the scope hold.
     * Valid PREPARED work cannot have sent a request and therefore does not impede import.
     * Database failures propagate so callers cannot interpret unreadable storage as freedom.
     */
    suspend fun capturedProjectionHold(scope: CalendarScope,
        currentImportFormat: AdapterFormat): CapturedProjectionHold = access { db ->
        transaction(db) {
            var blocked = false
            val events = mutableSetOf<EventId>()
            db.query("captured_create_links", arrayOf("mutation"), SCOPE, scopeIdentity(scope),
                null, null, null).use { rows ->
                while (rows.moveToNext()) {
                    try {
                        val id = MutationId(rows.getString(0))
                        val parent = read(db, scope, id)
                        val link = readCapturedIdentity(db, scope, id, parent)
                        if (parent == null || link == null) blocked = true
                        else when (parent.state) {
                            CreateAttemptState.PREPARED -> if (hasCapturedBinding(db, scope, id)) blocked = true
                            CreateAttemptState.OUTCOME_UNKNOWN -> {
                                val proof = readCapturedBinding(db, link, parent)
                                if (link.captureFormat != currentImportFormat || proof == null ||
                                    hasEvent(db, "create_bindings", scope, proof.eventId)) blocked = true
                                else events += proof.eventId
                            }
                        }
                    } catch (_: IllegalArgumentException) { blocked = true }
                    catch (_: DateTimeException) { blocked = true }
                }
            }
            // Out-of-band corruption can leave a proof without its required capture link.
            db.rawQuery("""SELECT 1 FROM captured_create_bindings b WHERE b.account=? AND b.calendar=?
                AND b.generation=? AND NOT EXISTS (SELECT 1 FROM captured_create_links l
                    WHERE l.account=b.account AND l.calendar=b.calendar AND l.generation=b.generation
                        AND l.mutation=b.mutation) LIMIT 1""".trimIndent(), scopeIdentity(scope)).use {
                if (it.moveToFirst()) blocked = true
            }
            db.query("captured_create_attachments", arrayOf("mutation"), SCOPE, scopeIdentity(scope),
                null, null, null).use { rows ->
                while (rows.moveToNext()) {
                    try {
                        val id = MutationId(rows.getString(0))
                        val parent = read(db, scope, id)
                        val link = readCapturedIdentity(db, scope, id, parent)
                        val proof = link?.let { readCapturedBinding(db, it, parent) }
                        if (proof == null || readCapturedAttachment(db, proof, parent) == null) blocked = true
                    } catch (_: IllegalArgumentException) { blocked = true }
                    catch (_: DateTimeException) { blocked = true }
                }
            }
            CapturedProjectionHold(blocked, events.toSet())
        }
    }

    private fun hasEvent(db: SQLiteDatabase, table: String, scope: CalendarScope, event: EventId): Boolean =
        db.query(table, arrayOf("mutation"), "$SCOPE AND event=?", scopeIdentity(scope) + event.value,
            null, null, null).use { it.moveToFirst() }

    private fun hasCapturedBinding(db: SQLiteDatabase, scope: CalendarScope, id: MutationId): Boolean =
        db.query("captured_create_bindings", arrayOf("mutation"), IDENTITY, identity(scope, id),
            null, null, null).use { it.moveToFirst() }

    private fun readCapturedBinding(db: SQLiteDatabase, identity: CapturedCreateDispatchIdentity,
        knownParent: CreateAttemptRecord?): CapturedCreateBinding? {
        if (knownParent == null || knownParent.state != CreateAttemptState.OUTCOME_UNKNOWN ||
            readCapturedIdentity(db, identity.attempt.scope, identity.attempt.id, knownParent) != identity) return null
        return db.query("captured_create_bindings", CAPTURE_COLUMNS + arrayOf("event", "verified_revision"),
            IDENTITY, identity(identity.attempt.scope, identity.attempt.id), null, null, null).use { row ->
            if (!row.moveToFirst() || decodeCapturedLink(row, identity.attempt) != identity) null
            else try { CapturedCreateBinding(identity, EventId(row.getString(10)), Revision(row.getString(11))) }
            catch (_: IllegalArgumentException) { null }
        }
    }

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
        if (hasCapturedWork(db, attempt.scope, attempt.id)) return null
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
                check(!hasCapturedWork(db, record.scope, record.id)) { "Captured work requires its exact local link" }
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
                if (hasCapturedWork(db, expected.scope, expected.id)) false else claimPrepared(db, expected)
            }
        }
    }

    /** Link and PREPARED parent share one commit. Existing generic work is never adopted. */
    suspend fun rememberCaptured(submission: LocalCreateSubmission): CapturedCreateDispatchIdentity {
        val link = CapturedCreateDispatchIdentity.of(submission)
        val record = CreateAttemptRecord(link.attempt.id, link.attempt.scope, link.attempt.format,
            submission.protectedPayload)
        return access { db ->
            dispatchTransaction(db, record.scope, { error("Create dispatch lease is not current") }) {
                val parent = read(db, record.scope, record.id)
                if (hasCapturedLink(db, record.scope, record.id)) {
                    check(parent != null &&
                        (parent.state == CreateAttemptState.OUTCOME_UNKNOWN || parent.protectedEvidence == null) &&
                        parent.copy(state = CreateAttemptState.PREPARED, protectedEvidence = null) == record &&
                        readCapturedIdentity(db, record.scope, record.id, parent) == link) { "Captured work is immutable" }
                } else {
                    check(!hasCapturedBinding(db, record.scope, record.id) &&
                        !hasCapturedAttachment(db, record.scope, record.id)) { "Orphaned captured work cannot be adopted" }
                    check(parent == null) { "Existing generic work cannot be adopted" }
                    db.insertOrThrow("create_attempts", null, ContentValues().apply {
                        put("account", record.scope.account.value); put("calendar", record.scope.calendar.value)
                        put("generation", record.scope.generation); put("mutation", record.id.value)
                        put("adapter", record.format.id); put("version", record.format.version)
                        put("payload", record.protectedPayload); put("state", record.state.name)
                    })
                    db.insertOrThrow("captured_create_links", null, capturedValues(link))
                }
                link
            }
        }
    }

    suspend fun capturedIdentity(scope: CalendarScope, id: MutationId): CapturedCreateDispatchIdentity? =
        access { db ->
            try { readCapturedIdentity(db, scope, id, read(db, scope, id)) }
            catch (_: IllegalArgumentException) { null }
            catch (_: DateTimeException) { null }
        }

    /** Fresh owner qualification may resume only this exact unsent captured payload.
     * UNKNOWN, generic parents and orphan provenance never regain a dispatch right.
     */
    suspend fun resumeCapturedPrepared(submission: LocalCreateSubmission): Boolean {
        val link = CapturedCreateDispatchIdentity.of(submission)
        val expected = CreateAttemptRecord(link.attempt.id, link.attempt.scope, link.attempt.format, submission.protectedPayload)
        return access { db -> dispatchTransaction(db, expected.scope, { false }) {
            val parent = read(db, expected.scope, expected.id)
            if (parent == null) {
                if (hasCapturedWork(db, expected.scope, expected.id) ||
                    db.query("create_pauses", arrayOf("mutation"), IDENTITY, identity(expected.scope, expected.id), null, null, null).use { it.count != 0 })
                    return@dispatchTransaction false
                db.insertOrThrow("create_attempts", null, ContentValues().apply {
                    put("account", expected.scope.account.value); put("calendar", expected.scope.calendar.value)
                    put("generation", expected.scope.generation); put("mutation", expected.id.value)
                    put("adapter", expected.format.id); put("version", expected.format.version)
                    put("payload", expected.protectedPayload); put("state", expected.state.name)
                })
                db.insertOrThrow("captured_create_links", null, capturedValues(link))
            } else {
                if (parent != expected || readCapturedIdentity(db, expected.scope, expected.id, parent) != link ||
                    hasCapturedBinding(db, expected.scope, expected.id) || hasCapturedAttachment(db, expected.scope, expected.id))
                    return@dispatchTransaction false
                db.delete("create_pauses", IDENTITY, identity(expected.scope, expected.id))
            }
            true
        } }
    }

    /** Raw work/proof presence blocks generic paths even when its local link is missing or damaged. */
    suspend fun isCaptured(scope: CalendarScope, id: MutationId): Boolean =
        access { db -> hasCapturedWork(db, scope, id) }

    /** Full immutable record/link comparison and pause check precede durable UNKNOWN admission. */
    suspend fun claimCaptured(expected: CreateAttemptRecord, link: CapturedCreateDispatchIdentity): Boolean {
        if (expected.state != CreateAttemptState.PREPARED || expected.protectedEvidence != null ||
            CreateAttemptIdentity.of(expected) != link.attempt) return false
        return access { db ->
            dispatchTransaction(db, expected.scope, { false }) {
                val parent = try { read(db, expected.scope, expected.id) }
                    catch (_: IllegalArgumentException) { null }
                if (parent != expected || readCapturedIdentity(db, expected.scope, expected.id, parent) != link) false
                else claimPrepared(db, expected)
            }
        }
    }

    private fun hasCapturedLink(db: SQLiteDatabase, scope: CalendarScope, id: MutationId): Boolean =
        db.query("captured_create_links", arrayOf("mutation"), IDENTITY, identity(scope, id), null, null, null)
            .use { it.moveToFirst() }

    private fun hasCapturedWork(db: SQLiteDatabase, scope: CalendarScope, id: MutationId): Boolean =
        hasCapturedLink(db, scope, id) || hasCapturedBinding(db, scope, id) || hasCapturedAttachment(db, scope, id)

    private fun readCapturedIdentity(db: SQLiteDatabase, scope: CalendarScope, id: MutationId,
        knownParent: CreateAttemptRecord?): CapturedCreateDispatchIdentity? {
        if (knownParent == null || knownParent.state == CreateAttemptState.PREPARED &&
            knownParent.protectedEvidence != null) return null
        val attempt = CreateAttemptIdentity.of(knownParent)
        return db.query("captured_create_links", CAPTURE_COLUMNS, IDENTITY, identity(scope, id),
            null, null, null).use { row ->
            if (!row.moveToFirst()) null else decodeCapturedLink(row, attempt)
        }
    }

    private fun capturedValues(link: CapturedCreateDispatchIdentity) = ContentValues().apply {
        put("account", link.attempt.scope.account.value); put("calendar", link.attempt.scope.calendar.value)
        put("generation", link.attempt.scope.generation); put("mutation", link.attempt.id.value)
        put("adapter", link.attempt.format.id); put("version", link.attempt.format.version)
        put("payload_fingerprint", link.attempt.payloadFingerprint)
        put("local_id", link.localId.value); put("capture_id", link.captureId.value)
        put("capture_adapter", link.captureFormat.id); put("capture_version", link.captureFormat.version)
        put("timestamp", link.timestamp.epochSecond); put("content_fingerprint", link.contentFingerprint)
        put("fingerprint_version", link.fingerprintVersion)
    }

    private fun decodeCapturedLink(row: Cursor, attempt: CreateAttemptIdentity): CapturedCreateDispatchIdentity? {
        if (row.getString(0) != attempt.format.id || row.getInt(1) != attempt.format.version ||
            row.getString(2) != attempt.payloadFingerprint) return null
        return try {
            CapturedCreateDispatchIdentity(attempt, LocalEventId(row.getString(3)), MutationId(row.getString(4)),
                AdapterFormat(row.getString(5), row.getInt(6)), Instant.ofEpochSecond(row.getLong(7)),
                row.getString(8), row.getInt(9))
        } catch (_: IllegalArgumentException) { null }
        catch (_: DateTimeException) { null }
    }

    private fun claimPrepared(db: SQLiteDatabase, expected: CreateAttemptRecord): Boolean =
        db.update("create_attempts", ContentValues().apply {
            put("state", CreateAttemptState.OUTCOME_UNKNOWN.name)
        }, "$IDENTITY AND adapter=? AND version=? AND payload=? AND state=? AND evidence IS NULL" +
            " AND NOT EXISTS (SELECT 1 FROM create_pauses p WHERE p.account=create_attempts.account" +
            " AND p.calendar=create_attempts.calendar AND p.generation=create_attempts.generation" +
            " AND p.mutation=create_attempts.mutation)",
            identity(expected.scope, expected.id) + arrayOf(expected.format.id,
                expected.format.version.toString(), expected.protectedPayload,
                CreateAttemptState.PREPARED.name)) == 1

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

    private fun scopeIdentity(scope: CalendarScope) = arrayOf(scope.account.value, scope.calendar.value,
        scope.generation.toString())

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
        const val SCOPE = "account=? AND calendar=? AND generation=?"
        val CAPTURE_COLUMNS = arrayOf("adapter", "version", "payload_fingerprint", "local_id", "capture_id",
            "capture_adapter", "capture_version", "timestamp", "content_fingerprint", "fingerprint_version")
    }
}
