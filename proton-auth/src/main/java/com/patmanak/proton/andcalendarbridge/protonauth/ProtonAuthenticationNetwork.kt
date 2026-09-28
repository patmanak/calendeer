// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Patmanak
// Adapted from Contako; see proton-auth/PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import me.proton.core.network.data.ApiManagerFactory
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.NetworkManager
import me.proton.core.network.data.NetworkPrefs
import me.proton.core.network.data.ProtonCookieStore
import me.proton.core.network.data.client.ClientIdProviderImpl
import me.proton.core.network.data.client.ExtraHeaderProviderImpl
import me.proton.core.network.data.cookie.MemoryCookieStorage
import me.proton.core.network.domain.deviceverification.DeviceVerificationListener
import me.proton.core.network.domain.deviceverification.DeviceVerificationMethods
import me.proton.core.network.domain.deviceverification.DeviceVerificationProvider
import me.proton.core.network.domain.feature.FeatureDisabledListener
import me.proton.core.network.domain.client.ClientId
import me.proton.core.network.domain.client.ClientIdProvider
import me.proton.core.network.domain.client.CookieSessionId
import me.proton.core.network.domain.humanverification.HumanVerificationAvailableMethods
import me.proton.core.network.domain.humanverification.HumanVerificationDetails
import me.proton.core.network.domain.humanverification.HumanVerificationListener
import me.proton.core.network.domain.humanverification.HumanVerificationProvider
import me.proton.core.network.domain.scopes.MissingScopeListener
import me.proton.core.network.domain.scopes.MissingScopeResult
import me.proton.core.network.domain.scopes.MissingScopeState
import me.proton.core.network.domain.scopes.Scope
import me.proton.core.network.domain.server.ServerTimeListener
import me.proton.core.network.domain.session.SessionId
import me.proton.core.network.domain.interceptor.InterceptorInfo
import me.proton.core.util.kotlin.DefaultCoroutineScopeProvider
import me.proton.core.util.kotlin.DefaultDispatcherProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal data class GateCHumanVerificationHooks(
    val provider: HumanVerificationProvider,
    val listener: HumanVerificationListener,
    val clear: suspend () -> Unit = {},
) {
    companion object {
        val FailClosed = GateCHumanVerificationHooks(
            provider = EmptyHumanVerificationProvider,
            listener = RejectingHumanVerificationListener,
        )
    }
}

internal data class GateCNetworkGraph(
    val apiProvider: ApiProvider,
    val apiClient: CalendarBridgeApiClient,
    val clientVersionValidator: CalendarBridgeClientVersionValidator,
)

internal class GateCPreAuthClientIdProvider(
    private val delegate: ClientIdProvider,
    private val fallbackIdFactory: () -> String = { UUID.randomUUID().toString() },
) : ClientIdProvider {
    private val guard = Any()
    private var preAuthClientId: ClientId? = null

    override suspend fun getClientId(sessionId: SessionId?): ClientId? {
        if (sessionId != null) {
            return delegate.getClientId(sessionId) ?: ClientId.AccountSession(sessionId)
        }
        synchronized(guard) { preAuthClientId }?.let { return it }

        val delegated = delegate.getClientId(null)
        return synchronized(guard) {
            val selected = preAuthClientId ?: delegated ?: fallbackIdFactory().let { generated ->
                require(generated.isNotBlank() && generated.length <= MAX_LOCAL_CLIENT_ID_LENGTH) {
                    "PRE_AUTH_CLIENT_ID_INVALID"
                }
                ClientId.CookieSession(CookieSessionId(generated))
            }
            preAuthClientId = selected
            selected
        }
    }

    private companion object {
        const val MAX_LOCAL_CLIENT_ID_LENGTH = 128
    }
}

internal object ProtonGateCNetworkFactory {
    private const val PRIMARY_API_BASE_URL = "https://calendar-api.proton.me/"

    fun build(
        context: Context,
        sessionCoordinator: ProtonCoreSessionCoordinator,
        humanVerification: GateCHumanVerificationHooks = GateCHumanVerificationHooks.FailClosed,
    ): GateCNetworkGraph {
        val releaseVersion = "0.1.0"
        val client = CalendarBridgeApiClient.forReleaseVersion(releaseVersion)
        val validator = CalendarBridgeClientVersionValidator.forReleaseVersion(releaseVersion)
        check(validator.validate(client.appVersionHeader))

        val appContext = context.applicationContext
        val dispatcherProvider = DefaultDispatcherProvider()
        val scopeProvider = DefaultCoroutineScopeProvider(dispatcherProvider)
        val baseUrl = PRIMARY_API_BASE_URL.toHttpUrl()
        val cookieStore = ProtonCookieStore(
            persistentStorage = MemoryCookieStorage(),
            sessionStorage = MemoryCookieStorage(),
        )
        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        val clientIdProvider = GateCPreAuthClientIdProvider(ClientIdProviderImpl(baseUrl, cookieStore))
        val managerFactory = ApiManagerFactory(
            baseUrl = baseUrl,
            apiClient = client,
            clientIdProvider = clientIdProvider,
            serverTimeListener = GateCServerTimeListener,
            networkManager = NetworkManager(appContext),
            prefs = NetworkPrefs(appContext),
            sessionProvider = sessionCoordinator,
            sessionListener = sessionCoordinator,
            humanVerificationProvider = humanVerification.provider,
            humanVerificationListener = humanVerification.listener,
            deviceVerificationProvider = EmptyDeviceVerificationProvider,
            deviceVerificationListener = RejectingDeviceVerificationListener,
            missingScopeListener = RejectingMissingScopeListener(),
            featureDisabledListener = GateCFeatureDisabledListener,
            cookieStore = cookieStore,
            scope = scopeProvider.GlobalIOSupervisedScope,
            extraHeaderProvider = ExtraHeaderProviderImpl(),
            clientVersionValidator = validator,
            dohAlternativesListener = null,
            okHttpClient = okHttpClient,
            interceptors = emptySet(),
        )
        return GateCNetworkGraph(
            apiProvider = ApiProvider(managerFactory, sessionCoordinator, dispatcherProvider),
            apiClient = client,
            clientVersionValidator = validator,
        )
    }

}

private object EmptyHumanVerificationProvider : HumanVerificationProvider {
    override suspend fun getHumanVerificationDetails(
        clientId: me.proton.core.network.domain.client.ClientId,
    ): HumanVerificationDetails? = null
}

/** Closed signal emitted only by Contako's fail-closed human-verification listener. */
internal class GateCHumanVerificationRequired : IllegalStateException()

private object RejectingHumanVerificationListener : HumanVerificationListener {
    override suspend fun onHumanVerificationNeeded(
        clientId: me.proton.core.network.domain.client.ClientId,
        methods: HumanVerificationAvailableMethods,
    ): HumanVerificationListener.HumanVerificationResult = throw GateCHumanVerificationRequired()

    override suspend fun onHumanVerificationInvalid(
        clientId: me.proton.core.network.domain.client.ClientId,
    ) = throw GateCHumanVerificationRequired()
}

private object EmptyDeviceVerificationProvider : DeviceVerificationProvider {
    override suspend fun getSolvedChallenge(sessionId: SessionId?): String? = null
    override suspend fun getSolvedChallenge(challengePayload: String): String? = null
    override suspend fun setSolvedChallenge(sessionId: SessionId, challengePayload: String, solved: String) = Unit
}

private object RejectingDeviceVerificationListener : DeviceVerificationListener {
    override suspend fun onDeviceVerification(
        sessionId: SessionId,
        methods: DeviceVerificationMethods,
    ): DeviceVerificationListener.DeviceVerificationResult =
        DeviceVerificationListener.DeviceVerificationResult.Failure
}

private class RejectingMissingScopeListener : MissingScopeListener {
    override val state = MutableSharedFlow<MissingScopeState>(extraBufferCapacity = 1)

    override suspend fun onMissingScope(
        userId: me.proton.core.domain.entity.UserId,
        scopes: List<Scope>,
    ): MissingScopeResult = MissingScopeResult.Failure

    override suspend fun onMissingScopeSuccess() = Unit
    override suspend fun onMissingScopeFailure() = Unit
}

private object GateCFeatureDisabledListener : FeatureDisabledListener {
    override suspend fun onFeatureDisabled(sessionId: SessionId?) = Unit
}

private object GateCServerTimeListener : ServerTimeListener {
    // Core's bare timestamp has no request timing/uncertainty. The verified network
    // interceptor records bounded calibration instead; this callback is not evidence.
    override fun onServerTimeMillisUpdated(epochMillis: Long) = Unit
}

internal class GateCLocalRequestBudgetExceeded : IOException("LOCAL_REQUEST_BUDGET_EXHAUSTED")
