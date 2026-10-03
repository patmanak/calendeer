// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.LocalEditPreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.proton.core.crypto.android.context.AndroidCryptoContext

/** Debug-only retained-record verification. No payload, identity, key or event text is returned. */
object ProtonLocalTitlePreparationProbe {
    /** Fresh offline storage opening only. No account lookup, bootstrap or signature revalidation. */
    suspend fun openHistorical(record: LocalEditPreparation): Boolean = withContext(Dispatchers.IO) {
        try {
            ProtonTitlePreparationCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
                .openLocal(record).changed
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { false }
    }

    /** Fresh storage opening and native signature/decryption checks, with a read-only bootstrap.
     * The reconstructed row exists only in memory; it is never an UPDATE request.
     * This verifies historical evidence, not current remote access or concurrent-write admission.
     */
    suspend fun verifyRecovered(context: Context, record: LocalEditPreparation): Boolean = withContext(Dispatchers.IO) {
        try {
            val fragments = ProtonTitlePreparationCodec(
                ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)).openLocal(record)
            if (!fragments.changed) return@withContext false
            val port = ProtonAuthentication.localEditPreparations(context)
            val lock = field(port, "lock") as Mutex
            lock.withLock {
                if (field(port, "authenticated") != true) return@withLock false
                val graph = field(port, "graph") as ProtonAuthenticationGraph
                val adapter = graph.localEdits as? ProtonLocalTitlePreparationAdapter ?: return@withLock false
                val sessions = field(adapter, "sessions") as CatalogSessionSource
                val session = sessions.current()?.takeIf { it.userId.id == record.intent.scope.account.value }
                    ?: return@withLock false
                val remote = field(adapter, "events") as EventImportRemote
                val bootstrap = remote.bootstrap(session, record.intent.scope.calendar.value)
                if (sessions.current() != session) return@withLock false
                val decoder = field(graph.events, "decoder") as ProtonEventDecryptor
                val uid = fragments.originalRow.string("UID")
                val original = decoder.decodePage(session.userId, record.intent.scope.calendar.value,
                    bootstrap, listOf(fragments.originalRow), uid)
                if (sessions.current() != session || original.deferred.isNotEmpty() ||
                    original.changes.singleOrNull() != record.intent.base) return@withLock false
                val patched = JsonObject(fragments.originalRow + ("SharedEvents" to JsonArray(listOf(
                    fragments.clearSharedPart, fragments.encryptedSharedPart))))
                val modified = decoder.decodePage(session.userId, record.intent.scope.calendar.value,
                    bootstrap, listOf(patched), uid)
                sessions.current() == session && modified.deferred.isEmpty() &&
                    modified.changes.singleOrNull()?.let {
                        it.eventId == record.intent.base.eventId && it.content == record.intent.content
                    } == true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { false }
    }

    // Debug reflection keeps native inspection out of the production/public authentication API.
    // Field drift fails closed through the boolean result above.
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(value)
}
