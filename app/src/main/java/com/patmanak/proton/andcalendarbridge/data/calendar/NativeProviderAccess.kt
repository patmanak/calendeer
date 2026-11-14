// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import com.patmanak.proton.andcalendarbridge.data.calendar.NativeCalendarSelection as Selection
import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ACCOUNT_TYPE
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.CAPTURED_ORIGIN_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.FINITE_RECEIPT_PREFIX
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.OWNER_MARKER
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.digest
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.fail
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.projectionLock

/** Validate the response before returning success. Expected counts remain enforced by the
 * Provider's operations; fixed proof batches additionally check the exact returned counts. */
internal fun AndroidCalendarProjection.checkedBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
    val results: Array<ContentProviderResult?>? = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
    if (results == null || results.size != operations.size) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    return Array(results.size) { index ->
        val result = results[index] ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (result.extras != null || result.exception != null) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        val operation = operations[index]
        val count = result.count
        if (operation.isInsert) {
            val uri = result.uri ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            val segments = uri.pathSegments
            if (count != null || uri.scheme != "content" || uri.authority != operation.uri.authority ||
                segments.dropLast(1) != operation.uri.pathSegments ||
                segments.lastOrNull()?.toLongOrNull()?.let { it > 0 } != true)
                fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        } else if (result.uri != null || count == null || count < 0) {
            fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        }
        result
    }
}

/** A lost or malformed response cannot establish a write or assertion witness. */
internal fun AndroidCalendarProjection.capturedBatch(operations: ArrayList<ContentProviderOperation>, expected: IntArray) {
    val results = checkedBatch(operations)
    if (results.size != expected.size) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    results.forEachIndexed { index, result ->
        if (result.count != expected[index] || result.uri != null) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    }
}

internal fun AndroidCalendarProjection.removeChildren(operations: ArrayList<ContentProviderOperation>, uri: Uri, calendar: Long, series: String) {
    val children = Selection("${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_SYNC_ID}=?",
        arrayOf(calendar.toString(), series))
    requirePermissions()
    val count = resolver.query(uri, arrayOf(Events.SYNC_DATA1) + CAPTURED_ORIGIN_COLUMNS + Events.SYNC_DATA3, children.sql, children.args, null)?.use {
        while (it.moveToNext()) {
            if (it.getString(0) != OWNER_MARKER || (1..CAPTURED_ORIGIN_COLUMNS.size).any { column -> !it.isNull(column) })
                fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
            if (finiteMarker(it.getString(1 + CAPTURED_ORIGIN_COLUMNS.size))) fail(CalendarProjectionFailure.PROVIDER_REJECTED)
        }
        it.count
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    operations += ContentProviderOperation.newAssertQuery(uri).withSelection(children.sql, children.args)
        .withExpectedCount(count).apply { if (count > 0) {
            withValue(Events.SYNC_DATA1, OWNER_MARKER)
            withValue(Events.DIRTY, 0); withValue(Events.DELETED, 0)
            CAPTURED_ORIGIN_COLUMNS.forEach { withValue(it, null) }
        } }.build()
    operations += ContentProviderOperation.newAssertQuery(uri)
        .withSelection("${children.sql} AND ${Events.SYNC_DATA3} LIKE ?", children.args + "$FINITE_RECEIPT_PREFIX%")
        .withExpectedCount(0).build()
    if (count > 0) operations += ContentProviderOperation.newDelete(uri).withSelection(children.sql, children.args)
        .withExpectedCount(count).build()
}

internal fun AndroidCalendarProjection.masterSyncId(uri: Uri, event: Long): String? {
    requirePermissions()
    val cursor = resolver.query(uri, arrayOf(Events._SYNC_ID, Events.RRULE, Events.RDATE),
        "${Events._ID}=?", arrayOf(event.toString()), null)
        ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    return cursor.use {
        if (!it.moveToFirst()) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
        if (!it.getString(1).isNullOrEmpty() || !it.getString(2).isNullOrEmpty()) it.getString(0) else null
    }
}

internal fun AndroidCalendarProjection.ownedRow(uri: Uri, selection: Selection, ownerColumn: String): Long? {
    requirePermissions()
    val cursor = resolver.query(uri, arrayOf("_id", ownerColumn), selection.sql, selection.args, null)
        ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    return cursor.use {
        if (!it.moveToFirst()) null
        else {
            if (it.count != 1 || it.getString(1) != OWNER_MARKER) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
            it.getLong(0)
        }
    }
}

internal fun AndroidCalendarProjection.assertion(uri: Uri, selection: Selection, existing: Long?): ContentProviderOperation {
    val builder = ContentProviderOperation.newAssertQuery(uri).withSelection(selection.sql, selection.args)
        .withExpectedCount(if (existing == null) 0 else 1)
    if (existing != null) {
        builder.withValue("_id", existing)
        builder.withValue(if (uri.pathSegments.firstOrNull() == "calendars") Calendars.CAL_SYNC1 else Events.SYNC_DATA1,
            OWNER_MARKER)
    }
    return builder.build()
}

internal fun AndroidCalendarProjection.ensureAccountInternal(accountId: AccountId): Account {
    val account = accountFor(accountId)
    if (accounts.getAccountsByType(ACCOUNT_TYPE).none { it == account } &&
        !accounts.addAccountExplicitly(account, null, null) &&
        accounts.getAccountsByType(ACCOUNT_TYPE).none { it == account }) {
        fail(CalendarProjectionFailure.ACCOUNT_UNAVAILABLE)
    }
    return account
}

internal fun AndroidCalendarProjection.calendarRow(scope: CalendarScope, account: Account): Long? =
    ownedRow(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), Calendars.CAL_SYNC1)

internal fun AndroidCalendarProjection.calendarSyncId(scope: CalendarScope): String = digest("calendar-v1", scope.account.value,
    scope.calendar.value, scope.generation.toString())

internal fun AndroidCalendarProjection.calendarSelection(scope: CalendarScope, account: Account) = Selection(
    "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars._SYNC_ID}=?",
    arrayOf(account.name, account.type, calendarSyncId(scope)))

internal fun AndroidCalendarProjection.syncUri(uri: Uri, account: Account): Uri = uri.buildUpon()
    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
    .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
    .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build()

internal fun AndroidCalendarProjection.requirePermissions() {
    if (!admitOperation()) throw CancellationException("Inactive import lease")
    if (!hasPermissions()) fail(CalendarProjectionFailure.PERMISSION_REQUIRED)
}

internal suspend fun <T> AndroidCalendarProjection.operation(block: () -> T): T = withContext(Dispatchers.IO) {
    projectionLock.withLock {
        requirePermissions()
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (known: CalendarProjectionException) { throw known }
        catch (_: SecurityException) {
            fail(if (hasPermissions()) CalendarProjectionFailure.ACCOUNT_UNAVAILABLE else CalendarProjectionFailure.PERMISSION_REQUIRED)
        }
        catch (failure: Exception) { fail(providerFailureCategory(failure)) }
    }
}
