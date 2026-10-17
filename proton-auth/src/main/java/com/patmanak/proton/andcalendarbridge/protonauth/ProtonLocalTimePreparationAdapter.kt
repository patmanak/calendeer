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

internal fun freezeLocalTimeRequest(intent: LocalEditIntent, timestamp: Instant): LocalEditIntent = try {
    val frozen = LocalTimeEditCandidate(intent).intent
    if (frozen.format != EVENT_FORMAT || timestamp.nano != 0 || timestamp.atOffset(ZoneOffset.UTC).year !in 1970..9999)
        localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
    frozen
} catch (cancelled: CancellationException) { throw cancelled }
  catch (failure: LocalEditPreparationException) { throw failure }
  catch (_: Exception) { localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED) }

internal fun interface LocalTimeContextPreparation {
    suspend fun prepare(session: CatalogSession, intent: LocalEditIntent, uid: String, timestamp: Instant,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject, addresses: List<UserAddress>): LocalTimeEditPreparation
}

/** Exact production crypto composition, also used by the synthetic native integration fixture. */
internal fun localTimeCryptoPreparation(crypto: CryptoContext, protector: EventPayloadProtector) =
    LocalTimeContextPreparation { session, intent, uid, time, metadata, bootstrap, row, addresses ->
        ProtonEventDecryptor(crypto) { user ->
            if (user != session.userId) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
            addresses
        }.prepareAndSealLocalTimeEdit(session, intent, uid, time, metadata, bootstrap, row, protector)
    }

/** Read-only time preparation. The shared facade supplies serialization with logout. */
internal class ProtonLocalTimePreparationAdapter(private val sessions: CatalogSessionSource,
    private val context: EventContextRefresher, private val metadata: CreateCalendarMetadataRemote,
    private val events: EventImportRemote, private val originals: LocalTitleEventRemote,
    private val addresses: LocalEventAddresses, private val prepare: LocalTimeContextPreparation,
    private val guardedRefresh: LocalTitleContextRefresh? = null) : CalendarLocalTimePreparationPort {
    override val localTimeFormat = LOCAL_TIME_PREPARATION_FORMAT
    override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant): LocalTimeEditPreparation =
        checkNotNull(prepareWithAdmission(intent, timestamp, null))
    override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant,
        admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? = prepareWithAdmission(intent, timestamp, admit)

    private class AdmissionClosed : RuntimeException()

    private suspend fun prepareWithAdmission(intent: LocalEditIntent, timestamp: Instant,
        admit: ((CalendarScope) -> Boolean)?): LocalTimeEditPreparation? {
        val closed = AtomicBoolean(false)
        try {
            currentCoroutineContext().ensureActive()
            val frozen = freezeLocalTimeRequest(intent, timestamp)
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
            if (uid.isBlank() || uid.length > LocalTimeEditPreparation.MAX_CONTEXT_BYTES ||
                uid.toByteArray(Charsets.UTF_8).size > LocalTimeEditPreparation.MAX_CONTEXT_BYTES)
                localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
            if (row.revision(bootstrap) != frozen.base.revision)
                localEditUnavailable(LocalEditPreparationFailure.CONFLICT)
            val local = localStorage { addresses.get(initial.userId).map { it.copy(keys = it.keys.toList()) } }
            checkpoint()
            ProtonCreateContextPolicy.select(frozen.scope, calendar, bootstrap, local)
            checkpoint()
            val result = prepare.prepare(initial, frozen, uid, timestamp, calendar, bootstrap, row, local)
            checkpoint()
            if (result.intent != frozen || result.timestamp != timestamp || result.format != localTimeFormat)
                localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
            return LocalTimeEditPreparation(frozen, timestamp, localTimeFormat, result.protectedPayload)
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
        } catch (failure: ProtonTimePreparationException) {
            currentCoroutineContext().ensureActive()
            localEditUnavailable(if (failure.reason == ProtonTimePreparationFailure.STORAGE)
                LocalEditPreparationFailure.STORAGE else LocalEditPreparationFailure.UNSUPPORTED)
        } catch (_: GateCProtectedStorageUnavailable) {
            currentCoroutineContext().ensureActive(); localEditUnavailable(LocalEditPreparationFailure.STORAGE)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive(); localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
        } finally { closed.set(true) }
    }

    private fun snapshot(value: JsonObject): JsonObject {
        val text = value.toString()
        if (text.length > LocalTimeEditPreparation.MAX_PAYLOAD_BYTES ||
            text.toByteArray(Charsets.UTF_8).size > LocalTimeEditPreparation.MAX_PAYLOAD_BYTES)
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
