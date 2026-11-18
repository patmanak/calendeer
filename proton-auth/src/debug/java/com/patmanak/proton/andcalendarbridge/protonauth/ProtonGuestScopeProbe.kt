package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.util.Locale
import me.proton.core.account.data.db.AccountConverters
import me.proton.core.account.domain.entity.AccountState
import me.proton.core.account.domain.entity.SessionState
import me.proton.core.data.room.db.CommonConverters

/** Reads the locally persisted grant only. No account/session restoration, HTTP or token access. */
object ProtonGuestScopeProbe {
    enum class Result { CACHED_MAIL, CACHED_NO_MAIL, UNAVAILABLE }

    fun inspect(context: Context, enrolledUsernameHash: String): Result {
        if (!enrolledUsernameHash.matches(Regex("[0-9a-f]{64}"))) return Result.UNAVAILABLE
        val file = context.getDatabasePath("proton-auth-v1.db")
        if (!file.isFile) return Result.UNAVAILABLE
        val converters = AccountConverters()
        val query = """
            SELECT a.username, s.scopes
            FROM AccountEntity a JOIN SessionEntity s ON s.userId = a.userId AND s.sessionId = a.sessionId
            WHERE a.state = ? AND a.sessionState = ? AND (SELECT COUNT(*) FROM AccountEntity) = 1
        """.trimIndent()
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(query, arrayOf(converters.fromAccountStateToString(AccountState.Ready),
                    converters.fromSessionStateToString(SessionState.Authenticated))).use { row ->
                    if (row.count != 1 || !row.moveToFirst() || row.isNull(0) || row.isNull(1)) return Result.UNAVAILABLE
                    val username = row.getString(0).trim().lowercase(Locale.ROOT)
                    if (username.isEmpty()) return Result.UNAVAILABLE
                    val hash = MessageDigest.getInstance("SHA-256").digest(username.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(Locale.ROOT, it) }
                    if (hash != enrolledUsernameHash) return Result.UNAVAILABLE
                    val scopes = CommonConverters().fromStringToListOfString(row.getString(1)) ?: return Result.UNAVAILABLE
                    if ("mail" in scopes) Result.CACHED_MAIL else Result.CACHED_NO_MAIL
                }
            }
        } catch (_: Exception) { Result.UNAVAILABLE }
    }
}
