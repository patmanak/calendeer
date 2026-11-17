// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import me.proton.core.network.domain.ApiException

/** Guarded text UPDATE. A fresh read and one PUT do not provide server-side compare-and-swap. */
internal class ProtonTextUpdateSubmissionAdapter(
    private val preparations: CalendarLocalTextPreparationPort,
    private val protector: EventPayloadProtector,
    private val sessions: CatalogSessionSource,
    private val context: TitleUpdateContextQualification,
    private val remote: TitleUpdateSubmissionRemote,
    private val events: EventImportRemote,
    private val verifier: CreateEventVerifier,
) : CalendarTextUpdateSubmissionPort {
    override val textUpdateFormat = TEXT_UPDATE_REQUEST_FORMAT
    private val codec = ProtonTextUpdateRequestCodec(protector)
    private val readback = ProtonTextUpdateReadbackVerifier(codec, verifier)

    override suspend fun prepareSubmission(id: MutationId, preparation: LocalTextEditPreparation,
        admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
        currentCoroutineContext().ensureActive()
        val frozen = freeze(preparation)
        val lease = UpdateRequestLease(frozen.intent.scope, admit, currentCoroutineContext())
        return try {
            lease.check()
            val session = session(frozen.intent.scope, lease)
            val checkpoint: suspend () -> Unit = { checkSessionAndLease(session, lease) }
            ProtonTextPreparationCodec(protector).openLocal(frozen)
            checkpoint()
            val qualified = snapshot(context.qualify(session, frozen.intent.scope, checkpoint))
            checkpoint()
            val protected = codec.seal(id, frozen, qualified)
            checkpoint()
            TextUpdateAttemptRecord(protected.id, protected.preparation, protected.format, protected.protectedPayload)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: UpdateRequestLease.Closed) { currentCoroutineContext().ensureActive(); null }
          catch (failure: Exception) { currentCoroutineContext().ensureActive(); preparationFailure(failure) }
          finally { lease.close() }
    }

    override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        val operation = currentCoroutineContext()
        val lease = UpdateRequestLease(frozen.scope, admit, operation)
        // This lease grants only factual retention in the original account/scope namespace.
        // It is deliberately independent of the edit-mode lease and expires with this call.
        val evidenceLease = UpdateRequestLease(frozen.scope, admitEvidence, operation)
        suspend fun source() {
            lease.check()
            val qualified = try { qualifySource(frozen.scope) }
            catch (failure: Exception) { lease.close(); operation.ensureActive(); throw failure }
            if (!qualified) lease.close()
            operation.ensureActive()
            lease.check()
        }
        return try {
            if (frozen.format != textUpdateFormat || frozen.state != TextUpdateAttemptState.PREPARED ||
                frozen.protectedEvidence != null) return TextUpdateSubmissionObservation.Unknown
            source()
            val retained = journal.read(frozen.scope, frozen.id, lease::allows)?.frozen()
            source()
            if (retained != frozen) return TextUpdateSubmissionObservation.Unknown
            val session = session(frozen.scope, lease)
            val checkpoint: suspend () -> Unit = {
                checkSessionAndLease(session, lease)
                // Strictly local: this callback must never re-enter the authentication facade.
                source()
            }
            checkpoint()
            val request = codec.open(protected(frozen))
            checkpoint()
            val fresh = preparations.prepareLocalText(frozen.preparation.intent, frozen.preparation.timestamp, lease::allows)
            checkpoint()
            if (fresh == null || fresh.intent != frozen.preparation.intent || fresh.timestamp != frozen.preparation.timestamp ||
                fresh.format != frozen.preparation.format) return TextUpdateSubmissionObservation.Unknown
            val fragments = ProtonTextPreparationCodec(protector).openLocal(fresh)
            checkpoint()
            if (!fragments.changed || fragments.originalRow != request.fragments.originalRow)
                return TextUpdateSubmissionObservation.Unknown
            val qualified = snapshot(context.qualify(session, frozen.scope, checkpoint))
            checkpoint()
            if (qualified != request.context) return TextUpdateSubmissionObservation.Unknown
            val claimed = journal.claim(frozen, lease::allows)
            operation.ensureActive()
            if (!claimed) return TextUpdateSubmissionObservation.Unknown
            // A revoked or cancelled operation may leave UNKNOWN without ever transmitting.
            checkpoint()
            val unknown = frozen.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
            var response: String? = null
            try { response = remote.send(session, frozen.scope.calendar.value, request.request) { lease.allows(frozen.scope) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.ensureActive() }
            operation.ensureActive()
            evidenceLease.check()
            val current = journal.read(unknown.scope, unknown.id, evidenceLease::allows)?.frozen()
            evidenceLease.check()
            if (current != unknown) return TextUpdateSubmissionObservation.Unknown
            val sealed = protector.seal(evidence(unknown, response).toString())
            evidenceLease.check()
            val withEvidence = unknown.copy(protectedEvidence = sealed)
            val saved = journal.retainEvidence(unknown, sealed, evidenceLease::allows)
            evidenceLease.check()
            if (!saved) return TextUpdateSubmissionObservation.Unknown
            checkpoint()
            val latest = journal.read(unknown.scope, unknown.id, lease::allows)?.frozen()
            checkpoint()
            if (latest != withEvidence) return TextUpdateSubmissionObservation.Unknown
            val observed = inspect(latest, journal, lease::allows)
            checkpoint()
            observed
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { operation.ensureActive(); TextUpdateSubmissionObservation.Unknown }
          finally { lease.close(); evidenceLease.close() }
    }

    override suspend fun inspect(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        val lease = UpdateRequestLease(frozen.scope, admit, currentCoroutineContext())
        return try {
            if (frozen.format != textUpdateFormat || frozen.state != TextUpdateAttemptState.OUTCOME_UNKNOWN)
                return TextUpdateSubmissionObservation.Unknown
            lease.check()
            val retained = journal.read(frozen.scope, frozen.id, lease::allows)?.frozen()
            lease.check()
            if (retained != frozen) return TextUpdateSubmissionObservation.Unknown
            val session = session(frozen.scope, lease)
            val checkpoint: suspend () -> Unit = { checkSessionAndLease(session, lease) }
            val request = codec.open(protected(frozen))
            checkpoint()
            frozen.protectedEvidence?.let { validateEvidence(frozen, protector.open(it)) }
            checkpoint()
            val qualified = snapshot(context.qualify(session, frozen.scope, checkpoint))
            checkpoint()
            if (qualified != request.context) return TextUpdateSubmissionObservation.Unknown
            val row = snapshot(remote.event(session, frozen.scope.calendar.value, frozen.eventId.value))
            checkpoint()
            val bootstrap = snapshot(events.bootstrap(session, frozen.scope.calendar.value))
            checkpoint()
            val verified = readback.verify(session, protected(frozen), qualified, bootstrap, row)
            checkpoint()
            if (verified == null) return TextUpdateSubmissionObservation.Unknown
            val current = journal.read(frozen.scope, frozen.id, lease::allows)?.frozen()
            checkpoint()
            if (current != frozen) return TextUpdateSubmissionObservation.Unknown
            TextUpdateSubmissionObservation.Verified(frozen, verified)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); TextUpdateSubmissionObservation.Unknown }
          finally { lease.close() }
    }

    private suspend fun session(scope: CalendarScope, lease: UpdateRequestLease): CatalogSession {
        lease.check()
        val current = sessions.current()
        lease.check()
        if (current == null || current.userId.id != scope.account.value)
            localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
        return current
    }

    private suspend fun checkSessionAndLease(expected: CatalogSession, lease: UpdateRequestLease) {
        lease.check()
        val current = sessions.current()
        lease.check()
        if (current != expected) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
    }

    private fun freeze(value: LocalTextEditPreparation): LocalTextEditPreparation {
        val intent = freezeLocalTextRequest(value.intent, value.timestamp)
        if (value.format != LOCAL_TEXT_PREPARATION_FORMAT) localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
        return LocalTextEditPreparation(intent, value.timestamp, value.format, value.protectedPayload)
    }

    private fun protected(value: TextUpdateAttemptRecord) =
        ProtonProtectedTextUpdateRequest(value.id, value.preparation, value.format, value.protectedPayload)

    private fun evidence(record: TextUpdateAttemptRecord, body: String?): JsonObject {
        body?.let(::bounded)
        val identity = TextUpdateAttemptIdentity.of(record)
        return buildJsonObject {
            put("kind", "text-update-evidence"); put("version", 1)
            put("attempt", record.id.value); put("payload", identity.payloadFingerprint)
            put("preparation", identity.preparationFingerprint)
            put("complete", body != null); put("body", body ?: "")
        }.also { bounded(it.toString()) }
    }

    private fun validateEvidence(record: TextUpdateAttemptRecord, text: String) {
        bounded(text)
        require(UniqueJsonKeys(text).validate())
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Invalid text evidence")
        require(root.keys == setOf("kind", "version", "attempt", "payload", "preparation", "complete", "body"))
        val complete = (root["complete"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        require(complete != null)
        val body = root.string("body")
        require(complete || body.isEmpty())
        require(root == evidence(record, body.takeIf { complete }))
    }

    private fun snapshot(value: JsonObject): JsonObject {
        val text = value.toString(); bounded(text)
        return Json.parseToJsonElement(text) as JsonObject
    }

    private fun bounded(value: String) {
        require(value.length <= LocalTextEditPreparation.MAX_PAYLOAD_BYTES)
        require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <=
            LocalTextEditPreparation.MAX_PAYLOAD_BYTES)
    }

    private fun preparationFailure(failure: Exception): Nothing = when (failure) {
        is LocalEditPreparationException -> throw failure
        is ProtonTextUpdateRequestException -> localEditUnavailable(if (failure.reason == ProtonTextUpdateRequestFailure.STORAGE)
            LocalEditPreparationFailure.STORAGE else LocalEditPreparationFailure.UNSUPPORTED)
        is ProtonTextPreparationException -> localEditUnavailable(if (failure.reason == ProtonTextPreparationFailure.STORAGE)
            LocalEditPreparationFailure.STORAGE else LocalEditPreparationFailure.UNSUPPORTED)
        is GateCProtectedStorageUnavailable -> localEditUnavailable(LocalEditPreparationFailure.STORAGE)
        is CreatePreparationException -> localEditUnavailable(LocalEditPreparationFailure.valueOf(failure.reason.name))
        else -> {
            val remote = when (failure) {
                is RemoteImportException -> failure
                is ApiException, is java.io.IOException -> failure.importFailure()
                else -> null
            }
            localEditUnavailable(when (remote?.reason) {
                RemoteImportFailure.AUTHENTICATION -> LocalEditPreparationFailure.AUTHENTICATION
                RemoteImportFailure.NETWORK -> LocalEditPreparationFailure.NETWORK
                RemoteImportFailure.RETRY -> LocalEditPreparationFailure.RETRY
                RemoteImportFailure.LOCAL_KEYS -> LocalEditPreparationFailure.LOCAL_KEYS
                RemoteImportFailure.NOT_FOUND -> LocalEditPreparationFailure.NOT_FOUND
                else -> LocalEditPreparationFailure.UNSUPPORTED
            })
        }
    }

}
