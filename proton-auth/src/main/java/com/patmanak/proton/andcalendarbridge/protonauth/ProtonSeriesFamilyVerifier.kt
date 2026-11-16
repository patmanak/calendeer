// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Full signed finite master; the broader import decoder alone is not sufficient evidence. */
internal fun seriesFamilyVerifier(decoder: ProtonEventDecryptor) = SeriesFamilyEventVerifier { session, calendar, bootstrap, row, uid ->
    try {
        currentCoroutineContext().ensureActive()
        require(row.string("CalendarID") == calendar && row.string("UID") == uid)
        ProtonPersonalEventWire.qualify(JsonObject(row + ("RRule" to JsonNull)))
        val decoded = decoder.decodePage(session.userId, calendar, bootstrap, listOf(row), uid)
        currentCoroutineContext().ensureActive()
        require(decoded.deferred.isEmpty())
        val result = requireNotNull(decoded.changes.singleOrNull())
        val master = result.copy(content = SeriesEditProfile.freezeMaster(result.content))
        require(master.eventId.value == row.string("ID"))
        ProtonSeriesEventUpdate.qualify(row, master.content)
        master
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: IllegalArgumentException) { currentCoroutineContext().ensureActive(); null }
}
