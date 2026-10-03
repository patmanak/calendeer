// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import org.json.JSONArray
import java.util.UUID

/** Inject pre-existing ordinary evidence to exercise legacy refusal/replay.
 * Production generic preparation now refuses captured provenance before any replay.
 */
internal fun retainLegacyOrdinaryProjectionFixture(store: ImportStore, scope: CalendarScope,
    format: AdapterFormat, change: RemoteChange.Upsert): ProjectionReceipt {
    val key = JSONArray().put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()
    val payload = store.readableDatabase.rawQuery("SELECT payload FROM projection_bases WHERE scope=? AND event=?",
        arrayOf(key, change.eventId.value)).use { check(it.moveToFirst()); it.getString(0) }
    val receipt = ProjectionReceipt(scope, format, change, UUID.randomUUID().toString(), change)
    store.writableDatabase.insertOrThrow("projection_receipts", null, ContentValues().apply {
        put("scope", key); put("event", change.eventId.value); put("nonce", receipt.nonce); put("payload", payload)
        put("adapter", format.id); put("version", format.version); put("previous_base", payload); put("protocol", receipt.protocol)
    })
    return receipt
}
