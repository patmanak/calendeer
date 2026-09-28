package com.patmanak.proton.andcalendarbridge.data

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteTableLockedException

/** Environmental failures can recover without discarding data or changing its schema. */
internal fun transientStorageFailure(failure: Exception): Boolean = when (failure) {
    is SQLiteFullException, is SQLiteDiskIOException, is SQLiteCantOpenDatabaseException,
    is SQLiteDatabaseLockedException, is SQLiteTableLockedException -> true
    else -> false
}
