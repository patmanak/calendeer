// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.user.domain.entity.UserAddress

internal fun freezePersonalRequest(intent: LocalEditIntent, timestamp: Instant): LocalEditIntent = try {
    val frozen = PersonalEditCandidate(intent).intent
    if (frozen.format != EVENT_FORMAT || timestamp.nano != 0 || timestamp.atOffset(ZoneOffset.UTC).year !in 1970..9999)
        localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
    frozen
} catch (cancelled: CancellationException) { throw cancelled }
  catch (failure: LocalEditPreparationException) { throw failure }
  catch (_: Exception) { localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED) }

internal fun freezeSeriesRequest(intent: LocalEditIntent, timestamp: Instant): LocalEditIntent = try {
    val frozen = SeriesEditCandidate(intent).intent
    if (frozen.format != EVENT_FORMAT || timestamp.nano != 0 || timestamp.atOffset(ZoneOffset.UTC).year !in 1970..9999)
        localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
    frozen
} catch (cancelled: CancellationException) { throw cancelled }
  catch (failure: LocalEditPreparationException) { throw failure }
  catch (_: Exception) { localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED) }

internal fun interface PersonalSeriesQualification {
    suspend fun qualify(session: CatalogSession, scope: CalendarScope, base: RemoteChange.Upsert,
        checkpoint: suspend () -> Unit)
}

internal fun interface PersonalContextPreparation {
    suspend fun prepare(session: CatalogSession, intent: LocalEditIntent, uid: String, timestamp: Instant,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject, addresses: List<UserAddress>): PersonalEditPreparation
}

/** Exact production crypto composition, also used by the synthetic native integration fixture. */
internal fun personalCryptoPreparation(crypto: CryptoContext, protector: EventPayloadProtector) =
    PersonalContextPreparation { session, intent, uid, time, metadata, bootstrap, row, addresses ->
        ProtonEventDecryptor(crypto) { user ->
            if (user != session.userId) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
            addresses
        }.prepareAndSealPersonalEdit(session, intent, uid, time, metadata, bootstrap, row, protector)
    }

internal fun seriesCryptoPreparation(crypto: CryptoContext, protector: EventPayloadProtector) =
    PersonalContextPreparation { session, intent, uid, time, metadata, bootstrap, row, addresses ->
        ProtonEventDecryptor(crypto) { user ->
            if (user != session.userId) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
            addresses
        }.prepareAndSealSeriesEdit(session, intent, uid, time, metadata, bootstrap, row, protector)
    }

/** Read-only personal-edit preparation. The shared facade supplies serialization with logout. */
internal class ProtonPersonalPreparationAdapter(private val sessions: CatalogSessionSource,
    private val context: EventContextRefresher, private val metadata: CreateCalendarMetadataRemote,
    private val events: EventImportRemote, private val originals: LocalTitleEventRemote,
    private val addresses: LocalEventAddresses, private val prepare: PersonalContextPreparation,
    private val guardedRefresh: LocalTitleContextRefresh? = null,
    private val seriesPreparation: PersonalContextPreparation? = null,
    private val seriesQualification: PersonalSeriesQualification? = null) : CalendarPersonalPreparationPort {
    override val personalFormat = PERSONAL_PREPARATION_FORMAT
    override val seriesFormat get() = PERSONAL_SERIES_PREPARATION_FORMAT.takeIf {
        guardedRefresh != null && seriesPreparation != null && seriesQualification != null
    }
    override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant): PersonalEditPreparation =
        checkNotNull(prepareWithAdmission(intent, timestamp, null))
    override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant,
        admit: (CalendarScope) -> Boolean): PersonalEditPreparation? = prepareWithAdmission(intent, timestamp, admit)
    override suspend fun prepareSeries(intent: LocalEditIntent, timestamp: Instant,
        admit: (CalendarScope) -> Boolean): PersonalEditPreparation? = prepareWithAdmission(intent, timestamp, admit, series = true)

    private class AdmissionClosed : RuntimeException()

    private suspend fun prepareWithAdmission(intent: LocalEditIntent, timestamp: Instant,
        admit: ((CalendarScope) -> Boolean)?, series: Boolean = false): PersonalEditPreparation? {
        val closed = AtomicBoolean(false)
        try {
            currentCoroutineContext().ensureActive()
            val frozen = if (series) freezeSeriesRequest(intent, timestamp) else freezePersonalRequest(intent, timestamp)
            val format = if (series) seriesFormat ?: throw AdmissionClosed() else personalFormat
            val crypto = if (series) seriesPreparation ?: throw AdmissionClosed() else prepare
            if (admit != null && guardedRefresh == null) throw AdmissionClosed()
            suspend fun admission() {
                currentCoroutineContext().ensureActive()
                val allowed = !closed.get() && (admit?.invoke(frozen.scope) != false)
                if (!allowed) closed.set(true)
                currentCoroutineContext().ensureActive()
                // The callback can observe revocation through another retained checkpoint.
                // Its earlier true result cannot reopen a lease closed while it was running.
                if (!allowed || closed.get()) throw AdmissionClosed()
            }
            admission()
            val initial = localStorage { sessions.current() }
            admission()
            if (initial == null || initial.userId.id != frozen.scope.account.value)
                localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
            suspend fun checkpoint() {
                admission()
                checkSession(initial)
                admission()
            }
            if (admit == null) readContext { context.refresh(initial) }
            else {
                checkpoint()
                val refresh = guardedRefresh ?: throw AdmissionClosed()
                readContext { refresh.refresh(initial, ::checkpoint) }
            }
            checkpoint()
            val calendar = snapshot(readContext { metadata.calendar(initial, frozen.scope.calendar.value) })
            checkpoint()
            val bootstrap = snapshot(readContext { events.bootstrap(initial, frozen.scope.calendar.value) })
            checkpoint()
            val row = snapshot(readContext { originals.event(initial, frozen.scope, frozen.base.eventId) })
            checkpoint()
            if (row.string("ID") != frozen.base.eventId.value || row.string("CalendarID") != frozen.scope.calendar.value ||
                bootstrap.obj("CalendarSettings").string("CalendarID") != frozen.scope.calendar.value)
                localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
            val uid = row.optionalString("UID") ?: localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
            if (uid.isBlank() || uid.length > PersonalEditPreparation.MAX_CONTEXT_BYTES ||
                uid.toByteArray(Charsets.UTF_8).size > PersonalEditPreparation.MAX_CONTEXT_BYTES)
                localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
            if (row.revision(bootstrap) != frozen.base.revision)
                localEditUnavailable(LocalEditPreparationFailure.CONFLICT)
            val local = localStorage { addresses.get(initial.userId).map { it.copy(keys = it.keys.toList()) } }
            checkpoint()
            ProtonCreateContextPolicy.select(frozen.scope, calendar, bootstrap, local)
            checkpoint()
            if (series) {
                requireNotNull(seriesQualification).qualify(initial, frozen.scope, frozen.base, ::checkpoint)
                checkpoint()
            }
            val result = crypto.prepare(initial, frozen, uid, timestamp, calendar, bootstrap, row, local)
            checkpoint()
            if (result.intent != frozen || result.timestamp != timestamp || result.format != format || result.isSeries != series)
                localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
            return result.frozen()
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: AdmissionClosed) { currentCoroutineContext().ensureActive(); return null }
          catch (failure: LocalEditPreparationException) { currentCoroutineContext().ensureActive(); throw failure }
          catch (failure: CreatePreparationException) {
            currentCoroutineContext().ensureActive()
            localEditUnavailable(when (failure.reason) {
                CreatePreparationFailure.AUTHENTICATION -> LocalEditPreparationFailure.AUTHENTICATION
                CreatePreparationFailure.PERMISSION -> LocalEditPreparationFailure.PERMISSION
                CreatePreparationFailure.CONTEXT -> LocalEditPreparationFailure.CONTEXT
                CreatePreparationFailure.LOCAL_KEYS -> LocalEditPreparationFailure.LOCAL_KEYS
                CreatePreparationFailure.UNSUPPORTED -> LocalEditPreparationFailure.UNSUPPORTED
                CreatePreparationFailure.STORAGE -> LocalEditPreparationFailure.STORAGE
                CreatePreparationFailure.NETWORK -> LocalEditPreparationFailure.NETWORK
                CreatePreparationFailure.RETRY -> LocalEditPreparationFailure.RETRY
            })
        } catch (failure: RemoteImportException) {
            currentCoroutineContext().ensureActive()
            localEditUnavailable(when (failure.reason) {
                RemoteImportFailure.AUTHENTICATION -> LocalEditPreparationFailure.AUTHENTICATION
                RemoteImportFailure.NETWORK -> LocalEditPreparationFailure.NETWORK
                RemoteImportFailure.RETRY -> LocalEditPreparationFailure.RETRY
                RemoteImportFailure.LOCAL_KEYS -> LocalEditPreparationFailure.LOCAL_KEYS
                RemoteImportFailure.NOT_FOUND -> LocalEditPreparationFailure.NOT_FOUND
                RemoteImportFailure.UNSUPPORTED -> LocalEditPreparationFailure.UNSUPPORTED
                else -> LocalEditPreparationFailure.CONTEXT
            })
        } catch (failure: ProtonPersonalPreparationException) {
            currentCoroutineContext().ensureActive()
            localEditUnavailable(if (failure.reason == ProtonPersonalPreparationFailure.STORAGE)
                LocalEditPreparationFailure.STORAGE else LocalEditPreparationFailure.UNSUPPORTED)
        } catch (_: GateCProtectedStorageUnavailable) {
            currentCoroutineContext().ensureActive(); localEditUnavailable(LocalEditPreparationFailure.STORAGE)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive(); localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
        } finally { closed.set(true) }
    }

    private fun snapshot(value: JsonObject): JsonObject {
        val text = value.toString()
        if (text.length > PersonalEditPreparation.MAX_PAYLOAD_BYTES ||
            text.toByteArray(Charsets.UTF_8).size > PersonalEditPreparation.MAX_PAYLOAD_BYTES)
            localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
        return Json.parseToJsonElement(text) as JsonObject
    }
    private suspend fun checkSession(expected: CatalogSession) {
        currentCoroutineContext().ensureActive()
        val current = localStorage { sessions.current() }
        currentCoroutineContext().ensureActive()
        if (current != expected) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
    }
    private suspend fun <T> readContext(action: suspend () -> T): T = try {
        val result = action()
        currentCoroutineContext().ensureActive()
        result
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (closed: AdmissionClosed) { throw closed }
      catch (failure: RemoteImportException) { currentCoroutineContext().ensureActive(); throw failure }
      catch (failure: LocalEditPreparationException) { currentCoroutineContext().ensureActive(); throw failure }
      catch (failure: Exception) { currentCoroutineContext().ensureActive(); throw failure.importFailure() }
    private suspend fun <T> localStorage(action: suspend () -> T): T = try {
        val result = action()
        currentCoroutineContext().ensureActive()
        result
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: LocalEditPreparationException) { currentCoroutineContext().ensureActive(); throw failure }
      catch (_: Exception) { currentCoroutineContext().ensureActive(); localEditUnavailable(LocalEditPreparationFailure.STORAGE) }
}
