// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.Account
import android.content.*
import android.provider.CalendarContract.Colors
import android.provider.CalendarContract.Events
import kotlinx.coroutines.CancellationException
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.fail

internal enum class NativePaletteDiagnostic { CONFLICT, UNAVAILABLE }

/** Display metadata must not stop event synchronization; admission and cancellation still do. */
internal fun AndroidCalendarProjection.publishOptionalEventPalette(account: Account): NativePaletteDiagnostic? = try {
    ensureEventPalette(account)
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    requirePermissions()
    if (failure is CalendarProjectionException && failure.category !in setOf(
            CalendarProjectionFailure.IDENTITY_CONFLICT, CalendarProjectionFailure.PROVIDER_UNAVAILABLE,
            CalendarProjectionFailure.PROVIDER_REJECTED)) throw failure
    val diagnostic = if (failure is CalendarProjectionException &&
        failure.category == CalendarProjectionFailure.IDENTITY_CONFLICT) NativePaletteDiagnostic.CONFLICT
        else NativePaletteDiagnostic.UNAVAILABLE
    // Never include the provider exception, account or palette values.
    android.util.Log.w("Calendeer", "Optional event palette: ${diagnostic.name}")
    diagnostic
}

/** Inserting metadata cannot recolor events. Never repair a key by updating or deleting it. */
internal fun AndroidCalendarProjection.ensureEventPalette(account: Account) {
    val offered = eventColors.colors().toList()
    if (offered.isEmpty()) return
    if (offered.size > 64 || offered.map { it.key }.toSet().size != offered.size)
        fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
    requirePermissions()
    val uri = syncUri(Colors.CONTENT_URI, account)
    val observed = mutableMapOf<String, MutableList<ContentValues>>()
    resolver.query(uri, arrayOf(Colors._ID, Colors.COLOR_KEY, Colors.COLOR_TYPE, Colors.COLOR),
        "${Colors.ACCOUNT_NAME}=? AND ${Colors.ACCOUNT_TYPE}=?", arrayOf(account.name, account.type), null)?.use { rows ->
        if (rows.count > 4096) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        while (rows.moveToNext()) {
            val key = rows.getString(1)
            if (offered.none { it.key == key }) continue
            observed.getOrPut(key) { mutableListOf() } += ContentValues().apply {
                android.database.DatabaseUtils.cursorRowToContentValues(rows, this)
            }
        }
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    requirePermissions()
    val operations = arrayListOf<ContentProviderOperation>()
    var missing = false
    for (color in offered) {
        val rows = observed[color.key].orEmpty()
        val argb = color.rgb or 0xff000000.toInt()
        if (rows.size > 1 || rows.singleOrNull()?.let {
                it.getAsLong(Colors._ID)?.let { id -> id > 0 } != true ||
                it.getAsInteger(Colors.COLOR_TYPE) != Colors.TYPE_EVENT || it.getAsInteger(Colors.COLOR) != argb
            } == true) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
        val where = "${Colors.ACCOUNT_NAME}=? AND ${Colors.ACCOUNT_TYPE}=? AND ${Colors.COLOR_KEY}=?"
        val args = arrayOf(account.name, account.type, color.key)
        operations += ContentProviderOperation.newAssertQuery(uri).withSelection(where, args)
            .withExpectedCount(rows.size).apply { rows.singleOrNull()?.let { withValues(it) } }.build()
        if (rows.isEmpty()) {
            missing = true
            operations += ContentProviderOperation.newInsert(uri).withValues(ContentValues().apply {
                put(Colors.ACCOUNT_NAME, account.name); put(Colors.ACCOUNT_TYPE, account.type)
                put(Colors.COLOR_KEY, color.key); put(Colors.COLOR_TYPE, Colors.TYPE_EVENT); put(Colors.COLOR, argb)
            }).build()
        }
    }
    requirePermissions()
    if (missing) checkedBatch(operations)
    requirePermissions()
}

/** The returned assertion travels in the same batch as the native snapshot/ACK. */
internal fun nativeEventColorKeyAssertions(resolver: ContentResolver, account: Account, values: ContentValues,
    syncUri: (android.net.Uri, Account) -> android.net.Uri, requirePermissions: () -> Unit,
    fail: (CalendarProjectionFailure) -> Nothing): List<ContentProviderOperation>? {
    val key = values.getAsString(Events.EVENT_COLOR_KEY)
    if (key == null) return emptyList()
    if (key.isEmpty()) return emptyList<ContentProviderOperation>().takeIf {
        values.getAsInteger(Events.EVENT_COLOR) == null || values.getAsInteger(Events.EVENT_COLOR) == 0 }
    val color = values.getAsInteger(Events.EVENT_COLOR) ?: return null
    if (color.ushr(24) != 0xff || key.length > 128) return null
    requirePermissions()
    val uri = syncUri(Colors.CONTENT_URI, account)
    val where = "${Colors.ACCOUNT_NAME}=? AND ${Colors.ACCOUNT_TYPE}=? AND ${Colors.COLOR_TYPE}=? AND ${Colors.COLOR_KEY}=?"
    val args = arrayOf(account.name, account.type, Colors.TYPE_EVENT.toString(), key)
    val row = resolver.query(uri, arrayOf(Colors._ID, Colors.COLOR), where, args, null)?.use {
        if (it.count != 1 || !it.moveToFirst() || it.getLong(0) <= 0 || it.getInt(1) != color) return null
        ContentValues().apply { android.database.DatabaseUtils.cursorRowToContentValues(it, this) }
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    requirePermissions()
    return listOf(ContentProviderOperation.newAssertQuery(uri).withSelection(where, args)
        .withValues(row).withExpectedCount(1).build())
}
