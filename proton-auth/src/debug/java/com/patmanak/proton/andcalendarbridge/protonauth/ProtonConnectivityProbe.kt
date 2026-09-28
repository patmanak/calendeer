// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.proton.core.account.data.repository.AccountRepositoryImpl
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.domain.entity.Product
import me.proton.core.network.data.NetworkManager
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.TimeoutOverride

/**
 * Opt-in debug-only anonymous probe. It never opens the real account database and has no
 * authentication repository, so it cannot read an existing token or issue an account session.
 * Results contain only closed categories, booleans and bounded protocol status numbers.
 */
object ProtonConnectivityProbe {
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = connectivity.activeNetwork
        val capabilities = active?.let(connectivity::getNetworkCapabilities)
        val legacyConnected = NetworkManager(appContext).isConnectedToNetwork()
        val network = "LEGACY_CONNECTED=$legacyConnected;ACTIVE_NETWORK=${active != null}" +
            ";INTERNET=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true}" +
            ";VALIDATED=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}"
        val fixtureName = "proton-connectivity-probe-${UUID.randomUUID()}.db"
        var database: ProtonGateCDatabase? = null
        var stage = Stage.INITIALIZE
        var cleanup = true
        val diagnostic = try {
            withTimeout(20_000) {
                val crypto = AndroidCryptoContext()
                val protectedStorage = GateCProtectedStorageGuard.requireAvailable(crypto.keyStoreCrypto)
                val fixture = ProtonGateCDatabase.build(appContext, protectedStorage, fixtureName)
                database = fixture
                val accounts = AccountRepositoryImpl(Product.Calendar, fixture, crypto.keyStoreCrypto)
                val sessions = ProtonCoreSessionCoordinator(accounts)
                val graph = ProtonGateCNetworkFactory.build(appContext, sessions)
                stage = Stage.PING
                graph.apiProvider.get<BaseRetrofitApi>(sessionId = null)
                    .invoke(forceNoRetryOnConnectionErrors = true) {
                        ping(TimeoutOverride(
                            connectionTimeoutSeconds = 5,
                            readTimeoutSeconds = 5,
                            writeTimeoutSeconds = 5,
                        ))
                    }.toClosedDiagnostic()
            }
        } catch (_: TimeoutCancellationException) {
            "RESULT=TIMEOUT;CAUSE=PROBE_DEADLINE"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            "RESULT=EXCEPTION;CAUSE=${classifyCause(failure)}"
        } catch (_: LinkageError) {
            "RESULT=EXCEPTION;CAUSE=LINKAGE"
        } finally {
            try {
                database?.close()
            } catch (_: Exception) {
                cleanup = false
            }
            // This exact randomly named fixture was created above; the production database is
            // neither opened nor removed. No user-supplied path reaches this call.
            try {
                if (appContext.getDatabasePath(fixtureName).exists()) {
                    cleanup = appContext.deleteDatabase(fixtureName) && cleanup
                }
            } catch (_: Exception) {
                cleanup = false
            }
        }
        "$network;STAGE=$stage;$diagnostic;FIXTURE_CLEANUP=$cleanup"
    }

    private fun ApiResult<*>.toClosedDiagnostic(): String = when (this) {
        is ApiResult.Success -> "RESULT=SUCCESS;CAUSE=NONE"
        is ApiResult.Error.Certificate -> "RESULT=TLS;CAUSE=${classifyCause(cause)}"
        is ApiResult.Error.NoInternet -> "RESULT=NO_INTERNET;CAUSE=${classifyCause(cause)}"
        is ApiResult.Error.Timeout -> "RESULT=TIMEOUT;CAUSE=${classifyCause(cause)}"
        is ApiResult.Error.Connection -> {
            val classified = classifyCause(cause)
            "RESULT=${if (classified == Cause.DNS) "DNS" else "CONNECTION"};CAUSE=$classified"
        }
        is ApiResult.Error.Parse -> "RESULT=PARSE;CAUSE=${classifyCause(cause)}"
        is ApiResult.Error.Http -> "RESULT=HTTP;HTTP=${httpCode.takeIf { it in 100..599 } ?: 0}" +
            ";PROTON=${proton?.code?.takeIf { it in 0..999_999 } ?: 0};CAUSE=${classifyCause(cause)}"
    }

    private fun classifyCause(failure: Throwable?): Cause {
        var current = failure
        repeat(8) {
            val inspected = current ?: return if (failure == null) Cause.NONE else Cause.OTHER
            val known = when (inspected) {
                is SSLPeerUnverifiedException -> Cause.SSL_PEER
                is SSLHandshakeException -> Cause.SSL_HANDSHAKE
                is CertificateException -> Cause.CERTIFICATE
                is UnknownHostException -> Cause.DNS
                is SocketTimeoutException -> Cause.SOCKET_TIMEOUT
                is ConnectException -> Cause.CONNECT
                is GateCProtectedStorageUnavailable -> Cause.PROTECTED_STORAGE
                is SecurityException -> Cause.SECURITY
                is android.database.SQLException -> Cause.DATABASE
                is IOException -> Cause.IO
                else -> null
            }
            if (known != null) return known
            current = inspected.cause?.takeUnless { it === inspected }
        }
        return Cause.OTHER
    }

    private enum class Stage { INITIALIZE, PING }
    private enum class Cause {
        NONE, SSL_PEER, SSL_HANDSHAKE, CERTIFICATE, DNS, SOCKET_TIMEOUT, CONNECT,
        PROTECTED_STORAGE, SECURITY, DATABASE, IO, OTHER,
    }
}
