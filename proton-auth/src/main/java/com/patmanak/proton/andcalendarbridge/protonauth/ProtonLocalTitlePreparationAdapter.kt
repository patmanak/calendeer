// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.proton.core.user.domain.entity.UserAddress
import me.proton.core.crypto.common.context.CryptoContext

internal fun localEditUnavailable(reason: LocalEditPreparationFailure): Nothing = throw LocalEditPreparationException(reason)
internal fun freezeLocalTitleRequest(intent: LocalEditIntent, timestamp: Instant): LocalEditIntent = try {
    val frozen = LocalEditPreparation.freezeIntent(intent)
    if (frozen.format != EVENT_FORMAT || timestamp.nano != 0 || timestamp.atOffset(ZoneOffset.UTC).year !in 1970..9999)
        localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
    frozen
} catch (failure: LocalEditPreparationException) { throw failure }
  catch (_: Exception) { localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED) }
internal fun interface LocalTitleEventRemote {
    suspend fun event(session: CatalogSession, scope: CalendarScope, eventId: EventId): JsonObject
}
internal fun interface LocalTitleContextPreparation {
    suspend fun prepare(session: CatalogSession, intent: LocalEditIntent, uid: String, timestamp: Instant,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject, addresses: List<UserAddress>): LocalEditPreparation
}
/** Shared by production wiring and the synthetic native integration fixture. */
internal fun localTitleCryptoPreparation(crypto: CryptoContext, protector: EventPayloadProtector) =
    LocalTitleContextPreparation { session, intent, uid, time, metadata, bootstrap, row, addresses ->
        ProtonEventDecryptor(crypto) { user ->
            if (user != session.userId) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
            addresses
        }.prepareAndSealLocalTitleEdit(session, intent, uid, time, metadata, bootstrap, row, protector)
    }

/** Read-only preparation boundary; the shared authentication facade supplies logout serialization. */
internal class ProtonLocalTitlePreparationAdapter(private val sessions: CatalogSessionSource,
    private val context: EventContextRefresher, private val metadata: CreateCalendarMetadataRemote,
    private val events: EventImportRemote, private val originals: LocalTitleEventRemote,
    private val addresses: LocalEventAddresses, private val prepare: LocalTitleContextPreparation) : CalendarLocalEditPreparationPort {
    override val localTitleFormat = LOCAL_TITLE_PREPARATION_FORMAT
    override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant): LocalEditPreparation = try {
        val frozen = freezeLocalTitleRequest(intent, timestamp)
        val initial = localStorage { sessions.current() }?.takeIf { it.userId.id == frozen.scope.account.value }
            ?: localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
        readContext { context.refresh(initial) }
        checkSession(initial)
        val calendar = snapshot(readContext { metadata.calendar(initial, frozen.scope.calendar.value) })
        checkSession(initial)
        val bootstrap = snapshot(readContext { events.bootstrap(initial, frozen.scope.calendar.value) })
        checkSession(initial)
        val row = snapshot(readContext { originals.event(initial, frozen.scope, frozen.base.eventId) })
        checkSession(initial)
        if (row.string("ID") != frozen.base.eventId.value || row.string("CalendarID") != frozen.scope.calendar.value ||
            bootstrap.obj("CalendarSettings").string("CalendarID") != frozen.scope.calendar.value)
            localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
        val uid = row.optionalString("UID") ?: localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
        if (uid.isBlank() || uid.length > LocalEditPreparation.MAX_CONTEXT_BYTES)
            localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
        if (row.revision(bootstrap) != frozen.base.revision) localEditUnavailable(LocalEditPreparationFailure.CONFLICT)
        val local = localStorage { addresses.get(initial.userId).map { it.copy(keys = it.keys.toList()) } }
        checkSession(initial)
        ProtonCreateContextPolicy.select(frozen.scope, calendar, bootstrap, local)
        val result = prepare.prepare(initial, frozen, uid, timestamp, calendar, bootstrap, row, local)
        checkSession(initial)
        if (result.intent != frozen || result.timestamp != timestamp || result.format != localTitleFormat)
            localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
        result.copy(intent = frozen)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: LocalEditPreparationException) { throw failure }
      catch (failure: CreatePreparationException) {
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
          localEditUnavailable(when (failure.reason) {
              RemoteImportFailure.AUTHENTICATION -> LocalEditPreparationFailure.AUTHENTICATION
              RemoteImportFailure.NETWORK -> LocalEditPreparationFailure.NETWORK
              RemoteImportFailure.RETRY -> LocalEditPreparationFailure.RETRY
              RemoteImportFailure.LOCAL_KEYS -> LocalEditPreparationFailure.LOCAL_KEYS
              RemoteImportFailure.NOT_FOUND -> LocalEditPreparationFailure.NOT_FOUND
              RemoteImportFailure.UNSUPPORTED -> LocalEditPreparationFailure.UNSUPPORTED
              else -> LocalEditPreparationFailure.CONTEXT
          })
      } catch (failure: ProtonTitlePreparationException) {
          localEditUnavailable(if (failure.reason == ProtonTitlePreparationFailure.STORAGE)
              LocalEditPreparationFailure.STORAGE else LocalEditPreparationFailure.UNSUPPORTED)
      } catch (_: GateCProtectedStorageUnavailable) { localEditUnavailable(LocalEditPreparationFailure.STORAGE) }
        catch (_: Exception) { localEditUnavailable(LocalEditPreparationFailure.CONTEXT) }

    private fun snapshot(value: JsonObject) = Json.parseToJsonElement(value.toString()) as JsonObject
    private suspend fun checkSession(expected: CatalogSession) {
        if (localStorage { sessions.current() } != expected) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
    }
    private suspend fun <T> readContext(action: suspend () -> T): T = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RemoteImportException) { throw failure }
        catch (failure: LocalEditPreparationException) { throw failure }
        catch (failure: Exception) { throw failure.importFailure() }
    private suspend fun <T> localStorage(action: suspend () -> T): T = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LocalEditPreparationException) { throw failure }
        catch (_: Exception) { localEditUnavailable(LocalEditPreparationFailure.STORAGE) }
}
