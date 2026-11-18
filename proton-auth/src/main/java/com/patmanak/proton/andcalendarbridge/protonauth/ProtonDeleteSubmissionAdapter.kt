// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Experimental simple-event DELETE. Fresh verification is not an atomic server precondition. */
internal class ProtonDeleteSubmissionAdapter(
    private val protector: EventPayloadProtector,
    private val sessions: CatalogSessionSource,
    private val context: TitleUpdateContextQualification,
    private val remote: DeleteSubmissionRemote,
    private val events: EventImportRemote,
    private val verifier: CreateEventVerifier,
    private val seriesReadback: ProtonCreatedEventReadback? = null,
) : CalendarDeleteSubmissionPort {
    override val deleteFormat = DELETE_SUBMISSION_FORMAT
    private val codec = ProtonDeleteRequestCodec(protector)
    private val seriesCodec = ProtonEntireSeriesRemovalCodec(protector)

    override suspend fun inspectSeriesRemoval(scope: CalendarScope, base: RemoteChange.Upsert, target: SeriesRemovalTarget,
        admit: (CalendarScope) -> Boolean): SeriesRemovalPlan? {
        val family = seriesReadback ?: return null
        val lease = Lease(scope, admit, currentCoroutineContext())
        return try {
            val master = freezeSeriesRemovalMaster(base)
            val session = session(scope, lease)
            family.inspectSeriesRemoval(session, scope, master, target) { checkSession(session, lease) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
          finally { lease.close() }
    }

    /** No journal state or transport permit is created by this preparation. */
    suspend fun prepareEntireSeriesRemoval(id: MutationId, scope: CalendarScope, base: RemoteChange.Upsert,
        admit: (CalendarScope) -> Boolean): ProtonSealedSeriesRemoval? {
        val family = seriesReadback ?: return null
        val lease = Lease(scope, admit, currentCoroutineContext())
        return try {
            val master = freezeSeriesRemovalMaster(base)
            val key = ProtonSeriesRemovalKey(id, scope, master.eventId, master.revision, requireNotNull(master.content.recurrence).uid)
            val session = session(scope, lease)
            val checkpoint: suspend () -> Unit = { checkSession(session, lease) }
            val qualified = deleteSnapshot(context.qualify(session, scope, checkpoint)).also(::validateDeleteContext)
            checkpoint()
            val plan = family.inspectSeriesRemoval(session, scope, master, SeriesRemovalTarget.Entire,
                qualified.string("bootstrap"), checkpoint) ?: return null
            checkpoint()
            val after = deleteSnapshot(context.qualify(session, scope, checkpoint))
            checkpoint()
            if (after != qualified) return null
            seriesCodec.seal(key, plan, qualified).also { checkpoint() }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
          finally { lease.close() }
    }

    /** Inspect-only evidence about this complete family. Cannot acknowledge or submit an attempt. */
    suspend fun inspectEntireSeriesAbsence(sealed: ProtonSealedSeriesRemoval, admit: (CalendarScope) -> Boolean): Boolean {
        val family = seriesReadback ?: return false
        val key = sealed.key
        val scope = key.scope
        val lease = Lease(scope, admit, currentCoroutineContext())
        return try {
            val session = session(scope, lease)
            val checkpoint: suspend () -> Unit = { checkSession(session, lease) }
            val batch = seriesCodec.open(sealed)
            checkpoint()
            suspend fun sameContext(): Boolean {
                val current = context.qualify(session, scope, checkpoint)
                checkpoint()
                return current == batch.context
            }
            suspend fun emptyFamily() = family.uidFamily(session, scope, key.uid, 0, checkpoint = checkpoint)?.isEmpty() == true
            if (!sameContext() || !emptyFamily()) return false
            for ((member, _) in batch.members) {
                val read = readEvent(session, scope, member)
                checkpoint()
                if (read !is ProtonDeleteEventRead.Absent || read.calendar != scope.calendar.value || read.eventId != member.value) return false
            }
            emptyFamily() && sameContext()
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: OutgoingReadUnavailableException) { currentCoroutineContext().ensureActive(); throw failure }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); false }
          finally { lease.close() }
    }

    override suspend fun prepareSubmission(id: MutationId, intent: LocalDeleteIntent,
        admit: (CalendarScope) -> Boolean): DeleteAttemptRecord? {
        currentCoroutineContext().ensureActive()
        val frozen = DeleteSubmissionPolicy.freezeIntent(intent)
        val lease = Lease(frozen.scope, admit, currentCoroutineContext())
        return try {
            val session = session(frozen.scope, lease)
            val checkpoint: suspend () -> Unit = { checkSession(session, lease) }
            val qualified = deleteSnapshot(context.qualify(session, frozen.scope, checkpoint)); checkpoint()
            val read = readEvent(session, frozen.scope, frozen.base.eventId); checkpoint()
            val row = (read as? ProtonDeleteEventRead.Present)?.row ?: return null
            val actual = verified(session, frozen.scope, frozen.base.eventId, row, qualified, checkpoint) ?: return null
            if (actual != frozen.base) return null
            val after = deleteSnapshot(context.qualify(session, frozen.scope, checkpoint)); checkpoint()
            if (after != qualified) return null
            val record = codec.seal(id, frozen, qualified, row); checkpoint()
            record
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: OutgoingReadUnavailableException) { currentCoroutineContext().ensureActive(); throw failure }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
          finally { lease.close() }
    }

    override suspend fun submit(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        val operation = currentCoroutineContext()
        val lease = Lease(frozen.scope, admit, operation)
        val evidenceLease = Lease(frozen.scope, admitEvidence, operation)
        suspend fun source() {
            lease.check()
            val allowed = try { qualifySource(frozen.scope) }
            catch (failure: Exception) { lease.close(); operation.ensureActive(); throw failure }
            if (!allowed) lease.close()
            operation.ensureActive(); lease.check()
        }
        return try {
            if (frozen.format != deleteFormat || frozen.state != DeleteAttemptState.PREPARED || frozen.protectedEvidence != null)
                return DeleteSubmissionObservation.Unknown
            source()
            val retained = journal.read(frozen.scope, frozen.id, lease::allows)?.frozen(); source()
            if (retained != frozen) return DeleteSubmissionObservation.Unknown
            val session = session(frozen.scope, lease)
            val checkpoint: suspend () -> Unit = { checkSession(session, lease); source() }
            val request = codec.open(frozen); checkpoint()
            val qualified = deleteSnapshot(context.qualify(session, frozen.scope, checkpoint)); checkpoint()
            if (qualified != request.context) return DeleteSubmissionObservation.Unknown
            val read = readEvent(session, frozen.scope, frozen.eventId); checkpoint()
            val row = (read as? ProtonDeleteEventRead.Present)?.row ?: return DeleteSubmissionObservation.Unknown
            val actual = verified(session, frozen.scope, frozen.eventId, row, qualified, checkpoint)
                ?: return DeleteSubmissionObservation.Unknown
            val after = deleteSnapshot(context.qualify(session, frozen.scope, checkpoint)); checkpoint()
            if (after != qualified) return DeleteSubmissionObservation.Unknown
            if (actual != frozen.intent.base) return conflictIfRetained(frozen, actual, journal, lease, checkpoint)
            // Exact original row protects unknown raw metadata too. Signature/content equality alone is insufficient.
            if (row != request.originalRow) return DeleteSubmissionObservation.Unknown
            val claimed = journal.claim(frozen, lease::allows); operation.ensureActive()
            if (!claimed) return DeleteSubmissionObservation.Unknown
            checkpoint()
            val unknown = frozen.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
            var response: String? = null
            try { response = remote.send(session, frozen.scope.calendar.value, request.request) { lease.allows(frozen.scope) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.ensureActive() }
            operation.ensureActive(); evidenceLease.check()
            val current = journal.read(unknown.scope, unknown.id, evidenceLease::allows)?.frozen(); evidenceLease.check()
            if (current != unknown) return DeleteSubmissionObservation.Unknown
            val sealed = protector.seal(evidence(unknown, response).toString()); evidenceLease.check()
            val saved = journal.retainEvidence(unknown, sealed, evidenceLease::allows); evidenceLease.check()
            if (!saved) return DeleteSubmissionObservation.Unknown
            checkpoint()
            val latest = journal.read(unknown.scope, unknown.id, lease::allows)?.frozen(); checkpoint()
            if (latest != unknown.copy(protectedEvidence = sealed)) return DeleteSubmissionObservation.Unknown
            val observed = inspect(latest, journal, lease::allows); checkpoint()
            observed
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: OutgoingReadUnavailableException) { operation.ensureActive(); throw failure }
          catch (_: Exception) { operation.ensureActive(); DeleteSubmissionObservation.Unknown }
          finally { lease.close(); evidenceLease.close() }
    }

    override suspend fun inspect(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
        admit: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        val lease = Lease(frozen.scope, admit, currentCoroutineContext())
        return try {
            if (frozen.format != deleteFormat || frozen.state != DeleteAttemptState.OUTCOME_UNKNOWN)
                return DeleteSubmissionObservation.Unknown
            lease.check()
            val retained = journal.read(frozen.scope, frozen.id, lease::allows)?.frozen(); lease.check()
            if (retained != frozen) return DeleteSubmissionObservation.Unknown
            val session = session(frozen.scope, lease)
            val checkpoint: suspend () -> Unit = { checkSession(session, lease) }
            val request = codec.open(frozen); checkpoint()
            frozen.protectedEvidence?.let { validateEvidence(frozen, protector.open(it)) }; checkpoint()
            val qualified = deleteSnapshot(context.qualify(session, frozen.scope, checkpoint)); checkpoint()
            if (qualified != request.context) return DeleteSubmissionObservation.Unknown
            val read = readEvent(session, frozen.scope, frozen.eventId); checkpoint()
            val actual = if (read is ProtonDeleteEventRead.Present)
                verified(session, frozen.scope, frozen.eventId, read.row, qualified, checkpoint) else null
            checkpoint()
            // Re-read membership/rights and session after either success or structured absence.
            val after = deleteSnapshot(context.qualify(session, frozen.scope, checkpoint)); checkpoint()
            if (after != qualified) return DeleteSubmissionObservation.Unknown
            val current = journal.read(frozen.scope, frozen.id, lease::allows)?.frozen(); checkpoint()
            if (current != frozen) return DeleteSubmissionObservation.Unknown
            when {
                read is ProtonDeleteEventRead.Absent && read.calendar == frozen.scope.calendar.value && read.eventId == frozen.eventId.value ->
                    DeleteSubmissionObservation.Verified(frozen)
                actual != null && actual != frozen.intent.base -> DeleteSubmissionObservation.Conflict(frozen, actual)
                else -> DeleteSubmissionObservation.Unknown
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: OutgoingReadUnavailableException) { currentCoroutineContext().ensureActive(); throw failure }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); DeleteSubmissionObservation.Unknown }
          finally { lease.close() }
    }

    private suspend fun readEvent(session: CatalogSession, scope: CalendarScope, event: EventId): ProtonDeleteEventRead =
        try { remote.event(session, scope.calendar.value, event.value) }
        catch (_: java.io.IOException) { currentCoroutineContext().ensureActive(); throw OutgoingReadUnavailableException() }

    private suspend fun verified(session: CatalogSession, scope: CalendarScope, event: EventId, supplied: JsonObject,
        qualified: JsonObject, checkpoint: suspend () -> Unit): RemoteChange.Upsert? {
        val row = deleteSnapshot(supplied)
        ProtonSimpleEventUpdate.qualify(row)
        if (row.string("ID") != event.value || row.string("CalendarID") != scope.calendar.value) return null
        checkpoint()
        val bootstrap = deleteSnapshot(events.bootstrap(session, scope.calendar.value)); checkpoint()
        if (ProtonSimpleEventUpdate.hash(bootstrap.toString()) != qualified.string("bootstrap")) return null
        val actual = verifier.verify(session, scope.calendar.value, bootstrap, row, row.string("UID"), CreatedEventReadbackPolicy.KNOWN_EMPTY_V2)
        checkpoint()
        if (actual == null || actual.eventId != event || actual.revision != row.revision(bootstrap)) return null
        // Freeze the same narrow known-empty profile even when returning a conflict.
        val snapshot = LocalDeleteIntent(MutationId("delete-readback"), scope, EVENT_FORMAT, actual, actual.content)
        return DeleteSubmissionPolicy.freezeIntent(snapshot).base
    }

    private suspend fun conflictIfRetained(record: DeleteAttemptRecord, actual: RemoteChange.Upsert,
        journal: DeleteAttemptJournal, lease: Lease, checkpoint: suspend () -> Unit): DeleteSubmissionObservation {
        val current = journal.read(record.scope, record.id, lease::allows)?.frozen(); checkpoint()
        return if (current == record) DeleteSubmissionObservation.Conflict(record, actual) else DeleteSubmissionObservation.Unknown
    }
    private suspend fun session(scope: CalendarScope, lease: Lease): CatalogSession {
        lease.check(); val current = sessions.current(); lease.check()
        if (current == null || current.userId.id != scope.account.value) throw Closed()
        return current
    }
    private suspend fun checkSession(expected: CatalogSession, lease: Lease) {
        lease.check(); val current = sessions.current(); lease.check(); if (current != expected) throw Closed()
    }
    private fun evidence(record: DeleteAttemptRecord, body: String?): JsonObject {
        body?.let { deleteBounded(it) }
        val identity = DeleteAttemptIdentity.of(record)
        return buildJsonObject {
            put("kind", "delete-evidence"); put("version", 1); put("attempt", record.id.value)
            put("payload", identity.payloadFingerprint); put("intent", identity.intentFingerprint)
            put("complete", body != null); put("body", body ?: "")
        }.also { deleteBounded(it.toString()) }
    }
    private fun validateEvidence(record: DeleteAttemptRecord, plain: String) {
        deleteBounded(plain); require(UniqueJsonKeys(plain).validate())
        val root = Json.parseToJsonElement(plain) as JsonObject
        val complete = (root["complete"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        require(complete != null)
        val body = root.string("body"); require(complete || body.isEmpty())
        require(root == evidence(record, body.takeIf { complete }))
        // Parsing is informational. A response code never substitutes for exact authenticated absence.
        if (complete) ProtonDeleteResponseParser.parse(body)
    }
    private class Closed : RuntimeException()
    private class Lease(val scope: CalendarScope, private val admit: (CalendarScope) -> Boolean,
        private val operation: CoroutineContext) {
        private val closed = AtomicBoolean()
        fun allows(requested: CalendarScope): Boolean {
            if (requested != scope || closed.get()) return false
            operation.ensureActive()
            val allowed = try { admit(requested) }
            catch (failure: Exception) { closed.set(true); operation.ensureActive(); throw failure }
            if (!allowed) closed.set(true)
            operation.ensureActive(); return allowed && !closed.get()
        }
        fun check() { operation.ensureActive(); if (!allows(scope)) throw Closed() }
        fun close() { closed.set(true) }
    }
}
