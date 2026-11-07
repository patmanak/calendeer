// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import me.proton.core.crypto.android.context.AndroidCryptoContext

/** Closed observations only: no addresses, identifiers, headers, bodies or exceptions escape. */
data class DeleteNetworkInterruptionObservation(val calls: Int, val authenticated: Boolean,
    val bodyStarts: Int, val hookCompleted: Boolean, val hookFailed: Boolean,
    val socketFailure: Boolean, val callEnded: Boolean, val cancelledAfterLoss: Boolean = false) {
    val measuredFailure: Boolean get() = calls == 1 && authenticated && bodyStarts == 1 &&
        hookCompleted && !hookFailed && socketFailure && !callEnded
    // WorkManager may stop a CONNECTED CoroutineWorker after the real radio cut. This
    // is a separate observation, never natural socket-failure evidence.
    val measuredCancellation: Boolean get() = calls == 1 && authenticated && bodyStarts == 1 &&
        hookCompleted && !hookFailed && cancelledAfterLoss && !callEnded
}

/** Debug-only, one enrolled deletion and authenticated session. Never opens or retries a call.
 * The callback may interrupt the device network; it cannot donate a response or a proof.
 * Reflection drift fails before arming. Existing dispatch/TLS/authentication guards stay intact.
 */
object ProtonDeleteNetworkInterruptionProbe {
    private val armed = AtomicReference<DeleteNetworkObservationSession?>()

    suspend fun arm(context: Context, record: DeleteAttemptRecord, expectedIdentityHash: String,
        interruptNetwork: () -> Unit): Handle {
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
            require(sessions.current() == session)
            currentCoroutineContext().ensureActive()
            val url = "https://calendar-api.proton.me/".toHttpUrl().newBuilder()
                .addPathSegment("calendar").addPathSegment("v1").addPathSegment(frozen.scope.calendar.value)
                .addPathSegment("events").addPathSegment("sync").build()
            val request = ProtonDeleteRequestCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)).open(frozen)
            currentCoroutineContext().ensureActive()
            require(sessions.current() == session)
            val bytes = deleteOnlyBytes(request.request.toString())
            val fingerprint = try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
            val observation = DeleteNetworkObservationSession(url, session.sessionId.id, fingerprint, interruptNetwork)
            check(armed.compareAndSet(null, observation)) { "DELETE_NETWORK_PROBE_ALREADY_ARMED" }
            Handle(observation)
        }
    }

    class Handle internal constructor(private val session: DeleteNetworkObservationSession) : AutoCloseable {
        fun observation(): DeleteNetworkInterruptionObservation = session.observation()
        override fun close() { armed.compareAndSet(session, null); session.close() }
    }

    internal fun listener(call: Call): EventListener = armed.get()?.listener(call) ?: EventListener.NONE
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
}

internal fun OkHttpClient.Builder.withDeleteNetworkQualification(): OkHttpClient.Builder =
    eventListenerFactory(ProtonDeleteNetworkInterruptionProbe::listener)
        .addNetworkInterceptor(ProtonDeleteResponseLossProbe::intercept)

/** Internal seam also exercised with synthetic calls; no real network is used by host tests. */
internal class DeleteNetworkObservationSession(private val url: HttpUrl, private val sessionId: String, expectedPayloadHash: ByteArray,
    private val interruptNetwork: () -> Unit) {
    private val payloadHash = expectedPayloadHash.copyOf()
    private var open = true
    private var calls = 0
    private var authenticated = false
    private var bodyStarts = 0
    private var hookCompleted = false
    private var hookFailed = false
    private var socketFailure = false
    private var callEnded = false
    private var cancelledAfterLoss = false
    private fun matches(request: Request): Boolean {
        if (request.url != url || request.method != "PUT") return false
        val tag = request.tag(ProtonDeleteDispatchAttempt::class.java) ?: return false
        val body = request.body ?: return false
        if (body !== tag.requestBody || !body.isOneShot()) return false
        // Debug inspection only: never call writeTo or consume/replace the one-shot body.
        // Any implementation drift fails closed. Only the enrolled frozen wire may cut Wi-Fi.
        return try {
            val bytes = body.javaClass.getDeclaredField("bytes").apply { isAccessible = true }.get(body) as ByteArray
            MessageDigest.isEqual(payloadHash, MessageDigest.getInstance("SHA-256").digest(bytes))
        } catch (_: Exception) { false }
    }

    @Synchronized fun listener(call: Call): EventListener {
        if (!open || !matches(call.request())) return EventListener.NONE
        calls++
        val owner = call
        return object : EventListener() {
            override fun requestHeadersEnd(call: Call, request: Request) = synchronized(this@DeleteNetworkObservationSession) {
                if (call === owner && open && calls == 1 && matches(request) && request.body === call.request().body &&
                    request.tag(ProtonDeleteDispatchAttempt::class.java) === call.request().tag(ProtonDeleteDispatchAttempt::class.java)) {
                    authenticated = request.headers.values("x-pm-uid") == listOf(sessionId) &&
                        request.headers.values("Authorization").let { it.size == 1 && it.single().startsWith("Bearer ") && it.single().length > 7 }
                }
            }
            override fun requestBodyStart(call: Call) {
                if (call !== owner) return
                val run = synchronized(this@DeleteNetworkObservationSession) {
                    bodyStarts++
                    open && calls == 1 && authenticated && bodyStarts == 1 && !call.isCanceled()
                }
                if (run) {
                    try {
                        interruptNetwork()
                        synchronized(this@DeleteNetworkObservationSession) { hookCompleted = true }
                    } catch (failure: Exception) {
                        synchronized(this@DeleteNetworkObservationSession) { hookFailed = true }
                        throw failure
                    }
                }
            }
            override fun callFailed(call: Call, ioe: IOException) = synchronized(this@DeleteNetworkObservationSession) {
                // A local cancellation, listener error, timeout or dispatch denial is not a
                // measured socket interruption. Do not classify nested synthetic exceptions.
                if (call === owner) socketFailure = open && hookCompleted && !hookFailed && !call.isCanceled() && ioe is SocketException
                if (call === owner) cancelledAfterLoss = open && calls == 1 && authenticated && bodyStarts == 1 &&
                    hookCompleted && !hookFailed && !callEnded && call.isCanceled() &&
                    ioe !is InterruptedIOException && ioe !is ProtonSingleDispatchDenied
            }
            override fun callEnd(call: Call) = synchronized(this@DeleteNetworkObservationSession) { if (call === owner) callEnded = true }
        }
    }
    @Synchronized fun observation() = DeleteNetworkInterruptionObservation(calls, authenticated, bodyStarts,
        hookCompleted, hookFailed, socketFailure, callEnded, cancelledAfterLoss)
    @Synchronized fun close() { open = false; payloadHash.fill(0) }
}
