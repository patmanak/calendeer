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

/** Guarded personal-v2 UPDATE. A fresh read and one PUT do not provide server-side compare-and-swap. */
internal class ProtonPersonalUpdateSubmissionAdapter(
    private val preparations: CalendarPersonalPreparationPort,
    private val protector: EventPayloadProtector,
    private val sessions: CatalogSessionSource,
    private val context: TitleUpdateContextQualification,
    private val remote: TitleUpdateSubmissionRemote,
    private val events: EventImportRemote,
    private val verifier: CreateEventVerifier,
    private val seriesVerifier: SeriesFamilyEventVerifier? = null,
    private val seriesQualification: PersonalSeriesQualification? = null,
) : CalendarPersonalUpdatePort {
    override val personalUpdateFormat = PERSONAL_UPDATE_REQUEST_FORMAT
    override val seriesUpdateFormat get() = PERSONAL_SERIES_UPDATE_REQUEST_FORMAT.takeIf {
        seriesVerifier != null && seriesQualification != null &&
            preparations.seriesFormat == PERSONAL_SERIES_PREPARATION_FORMAT && remote is SeriesUpdateSubmissionRemote
    }
    private val codec = ProtonPersonalUpdateRequestCodec(protector)
    private val readback = ProtonPersonalUpdateReadbackVerifier(codec, verifier, seriesVerifier)

    override suspend fun prepareSubmission(id: MutationId, preparation: PersonalEditPreparation,
        admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord? {
        currentCoroutineContext().ensureActive()
        val frozen = freeze(preparation)
        val lease = UpdateRequestLease(frozen.intent.scope, admit, currentCoroutineContext())
        return try {
            lease.check()
            if (frozen.isSeries && seriesUpdateFormat == null) return null
            val session = session(frozen.intent.scope, lease)
            val checkpoint: suspend () -> Unit = { checkSessionAndLease(session, lease) }
            ProtonPersonalPreparationCodec(protector).openLocal(frozen)
            checkpoint()
            if (frozen.isSeries) {
                val qualify = seriesQualification ?: return null
                qualify.qualify(session, frozen.intent.scope, frozen.intent.base, checkpoint)
                checkpoint()
                if (seriesUpdateFormat == null) return null
            }
            val qualified = snapshot(context.qualify(session, frozen.intent.scope, checkpoint))
            checkpoint()
            val protected = codec.seal(id, frozen, qualified)
            checkpoint()
            PersonalUpdateAttemptRecord(protected.id, protected.preparation, protected.format, protected.protectedPayload)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: UpdateRequestLease.Closed) { currentCoroutineContext().ensureActive(); null }
          catch (failure: Exception) { currentCoroutineContext().ensureActive(); preparationFailure(failure) }
          finally { lease.close() }
    }

    override suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
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
            if (!accepts(frozen, sending = true) || frozen.state != PersonalUpdateAttemptState.PREPARED ||
                frozen.protectedEvidence != null) return PersonalUpdateSubmissionObservation.Unknown
            source()
            val retained = journal.read(frozen.scope, frozen.id, lease::allows)?.frozen()
            source()
            if (retained != frozen) return PersonalUpdateSubmissionObservation.Unknown
            val session = session(frozen.scope, lease)
            val checkpoint: suspend () -> Unit = {
                checkSessionAndLease(session, lease)
                // Strictly local: this callback must never re-enter the authentication facade.
                source()
            }
            checkpoint()
            val request = codec.open(protected(frozen))
            checkpoint()
            val fresh = if (frozen.preparation.isSeries)
                preparations.prepareSeries(frozen.preparation.intent, frozen.preparation.timestamp, lease::allows)
            else preparations.preparePersonal(frozen.preparation.intent, frozen.preparation.timestamp, lease::allows)
            checkpoint()
            if (fresh == null || fresh.intent != frozen.preparation.intent || fresh.timestamp != frozen.preparation.timestamp ||
                fresh.format != frozen.preparation.format || fresh.isSeries != frozen.preparation.isSeries)
                return PersonalUpdateSubmissionObservation.Unknown
            val fragments = ProtonPersonalPreparationCodec(protector).openLocal(fresh)
            checkpoint()
            if (!fragments.changed || fragments.originalRow != request.fragments.originalRow)
                return PersonalUpdateSubmissionObservation.Unknown
            val qualified = snapshot(context.qualify(session, frozen.scope, checkpoint))
            checkpoint()
            if (qualified != request.context) return PersonalUpdateSubmissionObservation.Unknown
            if (!accepts(frozen, sending = true)) return PersonalUpdateSubmissionObservation.Unknown
            val claimed = journal.claim(frozen, lease::allows)
            operation.ensureActive()
            if (!claimed) return PersonalUpdateSubmissionObservation.Unknown
            // A revoked or cancelled operation may leave UNKNOWN without ever transmitting.
            checkpoint()
            val unknown = frozen.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
            var response: String? = null
            try {
                response = if (frozen.preparation.isSeries)
                    (remote as SeriesUpdateSubmissionRemote).sendSeries(session, frozen.scope.calendar.value, request.request) { lease.allows(frozen.scope) }
                else remote.send(session, frozen.scope.calendar.value, request.request) { lease.allows(frozen.scope) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.ensureActive() }
            operation.ensureActive()
            evidenceLease.check()
            val current = journal.read(unknown.scope, unknown.id, evidenceLease::allows)?.frozen()
            evidenceLease.check()
            if (current != unknown) return PersonalUpdateSubmissionObservation.Unknown
            val sealed = protector.seal(evidence(unknown, response).toString())
            evidenceLease.check()
            val withEvidence = unknown.copy(protectedEvidence = sealed)
            val saved = journal.retainEvidence(unknown, sealed, evidenceLease::allows)
            evidenceLease.check()
            if (!saved) return PersonalUpdateSubmissionObservation.Unknown
            checkpoint()
            val latest = journal.read(unknown.scope, unknown.id, lease::allows)?.frozen()
            checkpoint()
            if (latest != withEvidence) return PersonalUpdateSubmissionObservation.Unknown
            val observed = inspect(latest, journal, lease::allows)
            checkpoint()
            observed
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: LocalEditPreparationException) {
              operation.ensureActive()
              if (failure.reason == LocalEditPreparationFailure.NETWORK || failure.reason == LocalEditPreparationFailure.RETRY)
                  throw OutgoingReadUnavailableException()
              PersonalUpdateSubmissionObservation.Unknown
          }
          catch (_: Exception) { operation.ensureActive(); PersonalUpdateSubmissionObservation.Unknown }
          finally { lease.close(); evidenceLease.close() }
    }

    override suspend fun inspect(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        val lease = UpdateRequestLease(frozen.scope, admit, currentCoroutineContext())
        return try {
            if (!accepts(frozen, sending = false) || frozen.state != PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
                return PersonalUpdateSubmissionObservation.Unknown
            lease.check()
            val retained = journal.read(frozen.scope, frozen.id, lease::allows)?.frozen()
            lease.check()
            if (retained != frozen) return PersonalUpdateSubmissionObservation.Unknown
            val session = session(frozen.scope, lease)
            val checkpoint: suspend () -> Unit = { checkSessionAndLease(session, lease) }
            val request = codec.open(protected(frozen))
            checkpoint()
            frozen.protectedEvidence?.let { validateEvidence(frozen, protector.open(it)) }
            checkpoint()
            val qualified = snapshot(context.qualify(session, frozen.scope, checkpoint))
            checkpoint()
            if (qualified != request.context) return PersonalUpdateSubmissionObservation.Unknown
            val row = snapshot(remote.event(session, frozen.scope.calendar.value, frozen.eventId.value))
            checkpoint()
            val bootstrap = snapshot(events.bootstrap(session, frozen.scope.calendar.value))
            checkpoint()
            val verified = readback.verify(session, protected(frozen), qualified, bootstrap, row)
            checkpoint()
            if (verified == null) return PersonalUpdateSubmissionObservation.Unknown
            if (frozen.preparation.isSeries) {
                val qualify = seriesQualification ?: return PersonalUpdateSubmissionObservation.Unknown
                qualify.qualify(session, frozen.scope, verified, checkpoint)
                checkpoint()
            }
            // Membership/key selection is observed again after the actual GET and signature decode.
            // These remain successive observations, never a claimed atomic server precondition.
            val finalContext = snapshot(context.qualify(session, frozen.scope, checkpoint))
            checkpoint()
            if (finalContext != qualified || finalContext != request.context) return PersonalUpdateSubmissionObservation.Unknown
            val current = journal.read(frozen.scope, frozen.id, lease::allows)?.frozen()
            checkpoint()
            if (current != frozen) return PersonalUpdateSubmissionObservation.Unknown
            PersonalUpdateSubmissionObservation.Verified(frozen, verified)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); PersonalUpdateSubmissionObservation.Unknown }
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

    private fun freeze(value: PersonalEditPreparation): PersonalEditPreparation {
        if (value.isSeries) freezeSeriesRequest(value.intent, value.timestamp) else freezePersonalRequest(value.intent, value.timestamp)
        if (value.format != if (value.isSeries) PERSONAL_SERIES_PREPARATION_FORMAT else PERSONAL_PREPARATION_FORMAT)
            localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
        return value.frozen()
    }

    private fun accepts(record: PersonalUpdateAttemptRecord, sending: Boolean): Boolean = if (record.preparation.isSeries) {
        record.format == PERSONAL_SERIES_UPDATE_REQUEST_FORMAT && record.preparation.format == PERSONAL_SERIES_PREPARATION_FORMAT &&
            (if (sending) seriesUpdateFormat != null else seriesVerifier != null && seriesQualification != null)
    } else record.format == personalUpdateFormat && record.preparation.format == PERSONAL_PREPARATION_FORMAT

    private fun protected(value: PersonalUpdateAttemptRecord) =
        ProtonProtectedPersonalUpdateRequest(value.id, value.preparation, value.format, value.protectedPayload)

    private fun evidence(record: PersonalUpdateAttemptRecord, body: String?): JsonObject {
        body?.let(::bounded)
        val identity = PersonalUpdateAttemptIdentity.of(record)
        return buildJsonObject {
            put("kind", if (record.preparation.isSeries) "personal-series-v3-evidence" else "personal-edit-v2-evidence")
            put("version", if (record.preparation.isSeries) 3 else 2)
            put("attempt", record.id.value); put("payload", identity.payloadFingerprint)
            put("preparation", identity.preparationFingerprint)
            put("complete", body != null); put("body", body ?: "")
        }.also { bounded(it.toString()) }
    }

    private fun validateEvidence(record: PersonalUpdateAttemptRecord, text: String) {
        bounded(text)
        require(UniqueJsonKeys(text).validate())
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Invalid personal evidence")
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
        require(value.length <= PersonalEditPreparation.MAX_PAYLOAD_BYTES)
        require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <=
            PersonalEditPreparation.MAX_PAYLOAD_BYTES)
    }

    private fun preparationFailure(failure: Exception): Nothing = when (failure) {
        is LocalEditPreparationException -> throw failure
        is ProtonPersonalUpdateRequestException -> localEditUnavailable(if (failure.reason == ProtonPersonalUpdateRequestFailure.STORAGE)
            LocalEditPreparationFailure.STORAGE else LocalEditPreparationFailure.UNSUPPORTED)
        is ProtonPersonalPreparationException -> localEditUnavailable(if (failure.reason == ProtonPersonalPreparationFailure.STORAGE)
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
