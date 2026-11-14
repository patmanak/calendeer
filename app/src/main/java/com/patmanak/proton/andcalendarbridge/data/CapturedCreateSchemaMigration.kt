// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data

import android.database.sqlite.SQLiteDatabase

/** Rebuild existing captured identity constraints inside SQLiteOpenHelper's upgrade transaction.
 * Tables are supplied parent first. Temporary copies are not a second durable journal. */
internal object CapturedCreateSchemaMigration {
    fun rebuild(db: SQLiteDatabase, tables: List<String>, create: () -> Unit) {
        check(db.inTransaction()) { "Captured schema migration requires an upgrade transaction" }
        require(tables.isNotEmpty() && tables.distinct() == tables && tables.all { it.matches(Regex("[a-z_]+")) })
        val columns = tables.associateWith { table ->
            columns(db, table).also { names ->
                check(names.isNotEmpty() && names.all { it.matches(Regex("[a-z_]+")) })
                db.execSQL("CREATE TEMP TABLE migration_$table AS SELECT * FROM main.$table")
            }
        }
        for (table in tables.asReversed()) db.execSQL("DROP TABLE main.$table")
        create()
        for (table in tables) {
            val names = columns.getValue(table)
            check(columns(db, table) == names) { "Captured migration changed columns" }
            val fields = names.joinToString(",")
            db.execSQL("INSERT INTO main.$table($fields) SELECT $fields FROM temp.migration_$table")
            db.execSQL("DROP TABLE temp.migration_$table")
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "Captured migration broke a foreign key" } }
    }

    private fun columns(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA main.table_info($table)", null).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(1)) }
        }
}
