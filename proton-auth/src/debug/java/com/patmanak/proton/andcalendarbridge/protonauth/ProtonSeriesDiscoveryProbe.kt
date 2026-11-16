// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext

/** Bounded discovery only. Display windows never establish the absence of detached children. */
object ProtonSeriesDiscoveryProbe {
    suspend fun find(context: Context, scope: CalendarScope, identityHash: String,
        admit: () -> Boolean): List<RemoteChange.Upsert> = withContext(Dispatchers.IO) {
        try {
            require(identityHash.matches(Regex("[a-f0-9]{64}")))
            val port = ProtonAuthentication.personalPreparations(context)
            (field(port, "lock") as Mutex).withLock {
                val graph = field(port, "graph") as ProtonAuthenticationGraph
                val adapter = graph.personals as ProtonPersonalPreparationAdapter
                val sessions = field(adapter, "sessions") as CatalogSessionSource
                val session = requireNotNull(sessions.current()).also { require(it.userId.id == scope.account.value) }
                suspend fun checkpoint() {
                    currentCoroutineContext().ensureActive()
                    require(admit() && field(port, "authenticated") == true && sessions.current() == session)
                    require(graph.matchesIdentityHash(scope.account, identityHash))
                    require(admit() && field(port, "authenticated") == true && sessions.current() == session)
                }
                checkpoint()
                val events = field(adapter, "events") as EventImportRemote
                val bootstrap = events.bootstrap(session, scope.calendar.value)
                checkpoint()
                val local = (field(adapter, "addresses") as LocalEventAddresses).get(session.userId).map { it.copy(keys = it.keys.toList()) }
                checkpoint()
                val decoder = ProtonEventDecryptor(AndroidCryptoContext()) { user ->
                    checkpoint(); require(user == session.userId); local
                }
                var window: EventWindow? = EventWindow(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-01"),
                    0, 0, 0, 100, ZoneOffset.UTC)
                val found = mutableMapOf<EventId, RemoteChange.Upsert>()
                repeat(20) {
                    val current = window ?: return@withLock found.values.toList()
                    val page = events.page(session, scope.calendar.value, current)
                    checkpoint()
                    for (row in page.rows) {
                        if (row["RRule"] !is JsonPrimitive || row["RRule"] == JsonNull) continue
                        val decoded = decoder.decodePage(session.userId, scope.calendar.value, bootstrap, listOf(row), row.string("UID"))
                        checkpoint()
                        val candidate = decoded.changes.singleOrNull()?.takeIf {
                            decoded.deferred.isEmpty() && (it.content.title.startsWith("BridgeQA-") || it.content.title.startsWith("Calendeer QA"))
                        } ?: continue
                        val master = try { candidate.copy(content = SeriesEditProfile.freezeMaster(candidate.content)) }
                            catch (_: IllegalArgumentException) { continue }
                        found[master.eventId] = master
                        if (found.size >= 2) return@withLock found.values.toList()
                    }
                    window = current.next(page.more)
                }
                found.values.toList()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { emptyList() }
    }

    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(value)
    }
}
