package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.checkStagingCapacity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.sameTextPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalTextEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.sameTimePreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalTimeEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.samePersonalPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readPersonalEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.hasForeignLocalPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readPreparationOwner
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.activeProjectionScope
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.sync.*

/** Preparation SQL only; ImportStore retains IO, mutex, cancellation and transaction ownership. */
internal class ImportStorePreparationSql(
    private val conflicts: (SQLiteDatabase) -> ConflictArchiveSql,
    private val localTextPreparationAdmission: (SQLiteDatabase, LocalEditIntent) -> LocalTextPreparationAdmission,
    private val localTimePreparationAdmission: (SQLiteDatabase, LocalEditIntent) -> LocalTimePreparationAdmission,
    private val personalPreparationAdmission: (SQLiteDatabase, LocalEditIntent) -> PersonalPreparationAdmission,
    private val localTitlePreparationAdmission: (SQLiteDatabase, LocalEditIntent) -> LocalTitlePreparationAdmission,
) {
    fun retainCurrentLocalTextPreparation(db: SQLiteDatabase, frozen: LocalTextEditPreparation, base: String, local: String): LocalTextPreparationAdmission = run {
        val admission = localTextPreparationAdmission(db, frozen.intent)
        if (admission == LocalTextPreparationAdmission.HISTORICAL) {
            val retained = readLocalTextEditPreparation(db, frozen.intent.scope, frozen.intent.base.eventId)
            return@run if (retained != null && sameTextPreparation(retained, frozen)) admission
                else LocalTextPreparationAdmission.UNQUALIFIED
        }
        if (admission != LocalTextPreparationAdmission.ELIGIBLE) return@run admission
        insertLocalTextPreparation(db, frozen, base, local)
        LocalTextPreparationAdmission.STORED
    }

    fun conflictLocalTextPreparation(db: SQLiteDatabase, frozen: LocalEditIntent): LocalTextPreparationAdmission = run {
        val intent = frozen
        val admission = localTextPreparationAdmission(db, intent)
        if (admission !in listOf(LocalTextPreparationAdmission.ELIGIBLE, LocalTextPreparationAdmission.HISTORICAL))
            return@run admission
        check(db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
            "scope=? AND event=? AND mutation=? AND kind='UPDATE'",
            arrayOf(key(intent.scope), intent.base.eventId.value, intent.id.value)) == 1)
        conflicts(db).ensure(intent.scope, intent.base.eventId)
        LocalTextPreparationAdmission.CONFLICT
    }

    fun retainCurrentLocalTimePreparation(db: SQLiteDatabase, frozen: LocalTimeEditPreparation, base: String, local: String): LocalTimePreparationAdmission = run {
        val admission = localTimePreparationAdmission(db, frozen.intent)
        if (admission == LocalTimePreparationAdmission.HISTORICAL) {
            val retained = readLocalTimeEditPreparation(db, frozen.intent.scope, frozen.intent.base.eventId)
            return@run if (retained != null && sameTimePreparation(retained, frozen)) admission
                else LocalTimePreparationAdmission.UNQUALIFIED
        }
        if (admission != LocalTimePreparationAdmission.ELIGIBLE) return@run admission
        insertLocalTimePreparation(db, frozen, base, local)
        LocalTimePreparationAdmission.STORED
    }

    fun conflictLocalTimePreparation(db: SQLiteDatabase, frozen: LocalEditIntent): LocalTimePreparationAdmission = run {
        val intent = frozen
        val admission = localTimePreparationAdmission(db, intent)
        if (admission !in listOf(LocalTimePreparationAdmission.ELIGIBLE, LocalTimePreparationAdmission.HISTORICAL))
            return@run admission
        check(db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
            "scope=? AND event=? AND mutation=? AND kind='UPDATE'",
            arrayOf(key(intent.scope), intent.base.eventId.value, intent.id.value)) == 1)
        conflicts(db).ensure(intent.scope, intent.base.eventId)
        LocalTimePreparationAdmission.CONFLICT
    }

    fun retainCurrentPersonalPreparation(db: SQLiteDatabase, frozen: PersonalEditPreparation, base: String, local: String): PersonalPreparationAdmission = run {
        val admission = personalPreparationAdmission(db, frozen.intent)
        if (admission == PersonalPreparationAdmission.HISTORICAL) {
            val retained = readPersonalEditPreparation(db, frozen.intent.scope, frozen.intent.base.eventId)
            return@run if (retained != null && samePersonalPreparation(retained, frozen)) admission
                else PersonalPreparationAdmission.UNQUALIFIED
        }
        if (admission != PersonalPreparationAdmission.ELIGIBLE) return@run admission
        insertPersonalPreparation(db, frozen, base, local)
        PersonalPreparationAdmission.STORED
    }

    fun conflictPersonalPreparation(db: SQLiteDatabase, frozen: LocalEditIntent): PersonalPreparationAdmission = run {
        val intent = frozen
        val admission = personalPreparationAdmission(db, intent)
        if (admission !in listOf(PersonalPreparationAdmission.ELIGIBLE, PersonalPreparationAdmission.HISTORICAL))
            return@run admission
        check(db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
            "scope=? AND event=? AND mutation=? AND kind='UPDATE'",
            arrayOf(key(intent.scope), intent.base.eventId.value, intent.id.value)) == 1)
        conflicts(db).ensure(intent.scope, intent.base.eventId)
        PersonalPreparationAdmission.CONFLICT
    }

    fun retainCurrentLocalTitlePreparation(db: SQLiteDatabase, frozen: LocalEditPreparation, base: String, local: String): LocalTitlePreparationAdmission = run {
        val admission = localTitlePreparationAdmission(db, frozen.intent)
        if (admission != LocalTitlePreparationAdmission.ELIGIBLE) return@run admission
        insertLocalTitlePreparation(db, frozen, base, local)
        LocalTitlePreparationAdmission.STORED
    }

    fun conflictLocalTitlePreparation(db: SQLiteDatabase, intent: LocalEditIntent): LocalTitlePreparationAdmission = run {
        val admission = localTitlePreparationAdmission(db, intent)
        if (admission !in listOf(LocalTitlePreparationAdmission.ELIGIBLE, LocalTitlePreparationAdmission.HISTORICAL))
            return@run admission
        check(db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
            "scope=? AND event=? AND mutation=? AND kind='UPDATE'",
            arrayOf(key(intent.scope), intent.base.eventId.value, intent.id.value)) == 1)
        conflicts(db).ensure(intent.scope, intent.base.eventId)
        LocalTitlePreparationAdmission.CONFLICT
    }

    fun retainLocalEditPreparation(db: SQLiteDatabase, frozen: LocalEditPreparation, intent: LocalEditIntent, base: String, local: String): Boolean = run {
        check(!hasForeignLocalPreparation(db, intent.scope, intent.base.eventId, "local_edit_preparations")) {
            "Another preparation already owns this event"
        }
        val previous = readLocalEditPreparation(db, intent.scope, intent.base.eventId)
        if (previous != null) {
            require(previous == frozen) { "Local edit preparation is immutable" }
            return@run false
        }
        check(activeProjectionScope(db, intent.scope)) { "Inactive local preparation scope" }
        require(readPreparationOwner(db, intent.scope, intent.base.eventId) == intent) { "Stale local preparation owner" }
        insertLocalTitlePreparation(db, frozen, base, local)
        true
    }

    private fun insertLocalTextPreparation(db: SQLiteDatabase, value: LocalTextEditPreparation, base: String, local: String) =
        ImportStoreLocalPreparations.insertLocalTextPreparation(db, value, base, local, ::checkStagingCapacity)

    private fun insertLocalTimePreparation(db: SQLiteDatabase, value: LocalTimeEditPreparation, base: String, local: String) =
        ImportStoreLocalPreparations.insertLocalTimePreparation(db, value, base, local, ::checkStagingCapacity)

    private fun insertPersonalPreparation(db: SQLiteDatabase, value: PersonalEditPreparation, base: String, local: String) =
        ImportStoreLocalPreparations.insertPersonalPreparation(db, value, base, local, ::checkStagingCapacity)

    private fun insertLocalTitlePreparation(db: SQLiteDatabase, value: LocalEditPreparation, base: String, local: String) =
        ImportStoreLocalPreparations.insertLocalTitlePreparation(db, value, base, local, ::checkStagingCapacity)

}
