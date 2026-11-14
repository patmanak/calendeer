// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** The store owns the lock and captures the original coroutine before entering IO. */
internal fun <T> guardedImportTransaction(db: SQLiteDatabase, scope: CalendarScope,
    coroutine: CoroutineContext, admit: (CalendarScope) -> Boolean, requireKnown: (SQLiteDatabase, CalendarScope) -> Unit,
    action: (SQLiteDatabase) -> T): T {
    coroutine.ensureActive()
    check(admit(scope)) { "Inactive import lease" }
    db.beginTransaction()
    try {
        requireKnown(db, scope)
        val result = action(db)
        check(admit(scope)) { "Inactive import lease" }
        coroutine.ensureActive()
        db.setTransactionSuccessful()
        return result
    } finally { db.endTransaction() }
}
