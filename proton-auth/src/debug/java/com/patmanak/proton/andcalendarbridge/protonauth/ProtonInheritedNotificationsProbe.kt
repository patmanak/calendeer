// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext

/** Opt-in QA read witness. No account attributes, raw rows, credentials or send capability. */
object ProtonInheritedNotificationsProbe {
    class Observation(val event: RemoteChange.Upsert, val inherited: Boolean, val defaultEmailsPreserved: Boolean) {
        override fun toString() = "InheritedNotificationObservation(redacted)"
    }
    suspend fun find(context: Context, scope: CalendarScope, titlePrefix: String): Observation = withContext(Dispatchers.IO) {
        require(titlePrefix.startsWith("Calendeer QA B2 "))
        val port = ProtonAuthentication.personalPreparations(context)
        val lock = field(port, "lock") as Mutex
        lock.withLock {
            check(field(port, "authenticated") == true)
            val graph = field(port, "graph") as ProtonAuthenticationGraph
            val adapter = graph.personals as ProtonPersonalPreparationAdapter
            val sessions = field(adapter, "sessions") as CatalogSessionSource
            val session = checkNotNull(sessions.current()).also { check(it.userId.id == scope.account.value) }
            val events = field(adapter, "events") as EventImportRemote
            val originals = field(adapter, "originals") as LocalTitleEventRemote
            val addresses = field(adapter, "addresses") as LocalEventAddresses
            val decoder = ProtonEventDecryptor(AndroidCryptoContext()) { addresses.get(it) }
            val bootstrap = events.bootstrap(session, scope.calendar.value)
            val matches = linkedMapOf<EventId, RemoteChange.Upsert>()
            for (type in 0..3) {
                var ended = false
                for (page in 0..2) {
                    check(sessions.current() == session)
                    val window = EventWindow(LocalDate.of(2026,11,13), LocalDate.of(2026,11,14), 0, type, page, 100, ZoneOffset.UTC)
                    val result = events.page(session, scope.calendar.value, window)
                    check(result.rows.size <= 100)
                    decoder.decodePage(session.userId, scope.calendar.value, bootstrap, result.rows).changes
                        .filter { it.content.title.startsWith(titlePrefix) }.forEach { matches[it.eventId] = it }
                    if (!result.more) { ended = true; break }
                }
                check(ended) { "QA lookup exceeded bounded pages" }
            }
            check(matches.size == 1) { "Exactly one controlled web fixture is required" }
            val row = originals.event(session, scope, matches.keys.single())
            check(sessions.current() == session)
            val event = decoder.decodePage(session.userId, scope.calendar.value, bootstrap, listOf(row)).changes.single()
            check(event.content.title.startsWith(titlePrefix))
            val notifications = row.getValue("Notifications")
            val defaults = bootstrap.obj("CalendarSettings").also { check(it.string("CalendarID") == scope.calendar.value) }
                .array(if (event.content.time is EventTime.AllDay) "DefaultFullDayNotifications" else "DefaultPartDayNotifications")
            val emails = defaults.filter { (it as JsonObject).number("Type") == 0L }
            check(emails.isNotEmpty()) { "Fixture must exercise default email delivery" }
            Observation(event, notifications == JsonNull, notifications == JsonNull ||
                (notifications as JsonArray).filter { (it as JsonObject).number("Type") == 0L } == emails)
        }
    }
    fun preparationKeepsNull(value: PersonalEditPreparation): Boolean =
        ProtonPersonalPreparationCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
            .openLocal(value).originalRow["Notifications"] == JsonNull

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).let {
        it.isAccessible = true; it.get(owner)
    }
}
