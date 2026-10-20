package com.patmanak.proton.andcalendarbridge.data.importing

import android.database.Cursor
import android.database.CursorWindow
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.sync.LocalEditPreparation
import com.patmanak.proton.andcalendarbridge.sync.ReceivedCalendarPage
import java.nio.charset.CodingErrorAction

/** Bounded SQL materialization only. The calling store owns connection, transaction and lease. */
internal object ImportSqlValues {
    const val COVERAGE_MAX_BYTES = 256 * 1024

    fun archiveText(column: String, limit: Int = LocalEditPreparation.MAX_PAYLOAD_BYTES) =
        // CursorWindow TEXT includes a terminator in getBlob(); select actual UTF-8 bytes.
        "CASE WHEN typeof($column)='text' AND length(CAST($column AS BLOB))<=$limit THEN CAST($column AS BLOB) END"
    fun archiveVersion() = "CASE WHEN typeof(version)='integer' AND version BETWEEN 1 AND 2147483647 THEN version END"
    fun archiveString(row: Cursor, column: Int): String {
        check(row.getType(column) == Cursor.FIELD_TYPE_BLOB) { "Invalid conflict text" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(row.getBlob(column))).toString()
    }
    // The default CursorWindow is smaller than an admitted sealed page. Bound it explicitly;
    // four UTF-8 bytes per allowed character also accommodates non-ASCII adapter envelopes.
    fun stagedQuery(db: SQLiteDatabase, sql: String, args: Array<String>): SQLiteCursor =
        (db.rawQuery(sql, args) as SQLiteCursor).apply {
            window = CursorWindow("Calendar sealed import", ReceivedCalendarPage.MAX_PAYLOAD_CHARS.toLong() * 4 + COVERAGE_MAX_BYTES + 64 * 1024)
        }

}
