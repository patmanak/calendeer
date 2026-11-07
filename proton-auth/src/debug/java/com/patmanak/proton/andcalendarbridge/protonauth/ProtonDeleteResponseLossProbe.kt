// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.crypto.android.context.AndroidCryptoContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Transport observations only. Even a dropped HTTP 200 is not deletion acceptance evidence. */
data class DeleteResponseLossObservation(val calls: Int, val authenticated: Boolean, val responseDropped: Boolean) {
    val measuredLoss: Boolean get() = calls == 1 && authenticated && responseDropped
}

/** Explicit local injection, never classified as natural socket loss or a server response. */
internal class ProtonDeleteResponseLost : IOException("DELETE_RESPONSE_LOSS_INJECTED")

/** Debug-only exact frozen DELETE enrollment. No response bytes, credentials or identifiers escape.
 * Does not claim, send, inspect, acknowledge, change radios or modify the production dispatch guards.
 * A later authenticated absence inspection is required to establish convergence.
 */
object ProtonDeleteResponseLossProbe {
    private val armed = AtomicReference<DeleteResponseLossSession?>()

    suspend fun arm(context: Context, record: DeleteAttemptRecord, expectedIdentityHash: String): Handle {
        require(expectedIdentityHash.matches(Regex("[a-f0-9]{64}")))
        val frozen = record.frozen()
        require(frozen.state == DeleteAttemptState.PREPARED && frozen.protectedEvidence == null &&
            frozen.format == DELETE_SUBMISSION_FORMAT && frozen.id == DeleteAttemptIdentity.requestId(frozen.intent, frozen.format))
        val facade = ProtonAuthentication.deletions(context)
        val mutex = field(facade, "lock") as Mutex
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            require(field(facade, "authenticated") == true)
            val graph = field(facade, "graph") as ProtonAuthenticationGraph
            val adapter = graph.deletions as ProtonDeleteSubmissionAdapter
            val sessions = field(adapter, "sessions") as CatalogSessionSource
            val session = checkNotNull(sessions.current())
            require(session.userId.id == frozen.scope.account.value && graph.matchesIdentityHash(frozen.scope.account, expectedIdentityHash))
            val request = ProtonDeleteRequestCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)).open(frozen)
            currentCoroutineContext().ensureActive()
            require(sessions.current() == session)
            val url = "https://calendar-api.proton.me/".toHttpUrl().newBuilder()
                .addPathSegment("calendar").addPathSegment("v1").addPathSegment(frozen.scope.calendar.value)
                .addPathSegment("events").addPathSegment("sync").build()
            val bytes = deleteOnlyBytes(request.request.toString())
            val hash = try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
            val observation = try { DeleteResponseLossSession(url, session.sessionId.id, hash) } finally { hash.fill(0) }
            if (!armed.compareAndSet(null, observation)) {
                observation.close()
                error("DELETE_RESPONSE_LOSS_ALREADY_ARMED")
            }
            Handle(observation)
        }
    }

    class Handle internal constructor(private val session: DeleteResponseLossSession) : AutoCloseable {
        fun observation(): DeleteResponseLossObservation = session.observation()
        override fun close() { armed.compareAndSet(session, null); session.close() }
    }

    internal fun intercept(chain: Interceptor.Chain): Response = armed.get()?.intercept(chain.call(), chain.request(), chain::proceed)
        ?: chain.proceed(chain.request())

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
}

/** Tests use synthetic calls or MockWebServer; no service acceptance is inferred by this seam. */
internal class DeleteResponseLossSession(private val url: HttpUrl, private val sessionId: String, payloadHash: ByteArray) {
    private val expectedHash = payloadHash.copyOf()
    private var open = true
    private var calls = 0
    private var authenticated = false
    private var dropped = false
    private var owner: Call? = null
    private var tag: ProtonDeleteDispatchAttempt? = null
    private var body: RequestBody? = null

    private fun authenticated(request: Request) = request.headers.values("x-pm-uid") == listOf(sessionId) &&
        request.headers.values("Authorization").let { it.size == 1 && it.single().startsWith("Bearer ") && it.single().length > 7 }

    private fun route(request: Request) = request.url == url && request.method == "PUT"

    private fun matches(request: Request): Boolean {
        if (!route(request)) return false
        val permit = request.tag(ProtonDeleteDispatchAttempt::class.java) ?: return false
        val candidate = request.body ?: return false
        if (candidate !== permit.requestBody || !candidate.isOneShot() ||
            request.tag(ProtonSingleDispatchAttempt::class.java) != null ||
            request.tag(ProtonTitleUpdateDispatchAttempt::class.java) != null ||
            request.tag(ProtonPersonalEditV2DispatchAttempt::class.java) != null) return false
        // Before proceed only: DeleteBody clears its bytes after its one real write.
        // Never write, consume, replace or retain that body to inspect it.
        return try {
            val bytes = candidate.javaClass.getDeclaredField("bytes").apply { isAccessible = true }.get(candidate) as ByteArray
            MessageDigest.isEqual(expectedHash, MessageDigest.getInstance("SHA-256").digest(bytes))
        } catch (_: Exception) { false }
    }

    fun intercept(call: Call, request: Request, proceed: (Request) -> Response): Response {
        val enrolled = synchronized(this) {
            if (!open) false
            else if (call === owner || request.tag(ProtonDeleteDispatchAttempt::class.java)?.let { it === tag } == true || matches(request)) {
                calls++
                if (calls == 1 && route(call.request()) && call.request().body === request.body &&
                    call.request().tag(ProtonDeleteDispatchAttempt::class.java) === request.tag(ProtonDeleteDispatchAttempt::class.java) &&
                    authenticated(request) && !call.isCanceled()) {
                    owner = call; tag = request.tag(ProtonDeleteDispatchAttempt::class.java); body = request.body
                    authenticated = true
                    true
                } else false
            } else false
        }
        val response = proceed(request) // Genuine response only; never parse or peek at its body.
        val drop = synchronized(this) {
            if (enrolled && open && calls == 1 && !dropped && call === owner && !call.isCanceled() &&
                route(response.request) && response.request.body === body &&
                response.request.tag(ProtonDeleteDispatchAttempt::class.java) === tag &&
                authenticated(response.request) && response.request.headers == request.headers) {
                dropped = true
                true
            } else false
        }
        if (drop) {
            try { response.close() } finally { throw ProtonDeleteResponseLost() }
        }
        return response
    }

    @Synchronized fun observation() = DeleteResponseLossObservation(calls, authenticated, dropped)
    @Synchronized fun close() { open = false; expectedHash.fill(0); owner = null; tag = null; body = null }
}
