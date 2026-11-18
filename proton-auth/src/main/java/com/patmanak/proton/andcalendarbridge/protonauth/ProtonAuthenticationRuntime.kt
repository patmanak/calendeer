// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Contako; see proton-auth/PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.CalendarDeleteSubmissionPort
import com.patmanak.proton.andcalendarbridge.sync.CalendarTextUpdateSubmissionPort

import android.content.Context
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationFlowPort
import com.patmanak.proton.andcalendarbridge.sync.CalendarTitleUpdateSubmissionPort
import com.patmanak.proton.andcalendarbridge.sync.CalendarDeltaRemote
import com.patmanak.proton.andcalendarbridge.sync.CalendarCreatePreparationPort
import com.patmanak.proton.andcalendarbridge.sync.CalendarCreateSubmissionPort
import com.patmanak.proton.andcalendarbridge.sync.CalendarLocalEditPreparationPort
import com.patmanak.proton.andcalendarbridge.sync.CalendarLocalTextPreparationPort
import com.patmanak.proton.andcalendarbridge.sync.CalendarLocalTimePreparationPort
import com.patmanak.proton.andcalendarbridge.sync.CalendarTimeUpdateSubmissionPort
import com.patmanak.proton.andcalendarbridge.sync.CalendarPersonalPreparationPort
import com.patmanak.proton.andcalendarbridge.sync.CalendarPersonalUpdatePort
import com.patmanak.proton.andcalendarbridge.sync.LocalEditPreparationFailure
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import kotlinx.coroutines.flow.first
import com.patmanak.proton.andcalendarbridge.sync.CreatePreparationFailure
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogPort
import android.database.SQLException
import com.patmanak.proton.andcalendarbridge.protonauth.AccountScope
import com.patmanak.proton.andcalendarbridge.protonauth.AuthenticationState
import com.patmanak.proton.andcalendarbridge.protonauth.GatewayFailureCategory
import com.patmanak.proton.andcalendarbridge.protonauth.GatewayOutcome
import com.patmanak.proton.andcalendarbridge.protonauth.OperationSecret
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthenticationGateway
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSessionGateway
import com.patmanak.proton.andcalendarbridge.protonauth.SecondFactorMethod
import com.patmanak.proton.andcalendarbridge.protonauth.SessionState
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Optional
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.proton.core.account.data.repository.AccountRepositoryImpl
import me.proton.core.accountrecovery.domain.repository.AccountRecoveryRepository
import me.proton.core.auth.data.repository.AuthRepositoryImpl
import me.proton.core.auth.domain.entity.ScopeInfo
import me.proton.core.auth.domain.entity.SecondFactor
import me.proton.core.auth.domain.entity.SessionInfo
import me.proton.core.auth.domain.repository.AuthRepository
import me.proton.core.auth.domain.usecase.LoginChallengeConfig
import me.proton.core.auth.domain.usecase.PerformSecondFactor
import me.proton.core.auth.domain.usecase.ValidateServerProof
import me.proton.core.auth.fido.domain.entity.SecondFactorProof
import me.proton.core.challenge.data.ChallengeManagerImpl
import me.proton.core.challenge.data.repository.ChallengeRepositoryImpl
import me.proton.core.challenge.domain.ChallengeManager
import me.proton.core.challenge.domain.useFlow
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.android.srp.GOpenPGPSrpCrypto
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.srp.SrpCrypto
import me.proton.core.domain.entity.Product
import me.proton.core.domain.entity.UserId
import me.proton.core.key.data.repository.KeySaltRepositoryImpl
import me.proton.core.key.data.repository.PrivateKeyRepositoryImpl
import me.proton.core.key.domain.entity.key.Key
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.session.SessionId
import me.proton.core.user.data.UserAddressKeySecretProvider
import me.proton.core.user.data.UserManagerImpl
import me.proton.core.user.data.repository.UserAddressRemoteDataSourceImpl
import me.proton.core.user.data.repository.UserAddressRepositoryImpl
import me.proton.core.user.data.repository.UserLocalDataSourceImpl
import me.proton.core.user.data.repository.UserRemoteDataSourceImpl
import me.proton.core.user.data.repository.UserRepositoryImpl
import me.proton.core.user.data.usecase.GenerateSignedKeyList
import me.proton.core.user.domain.SignedKeyListChangeListener
import me.proton.core.user.domain.UserManager
import me.proton.core.user.domain.repository.PassphraseRepository
import me.proton.core.auth.domain.usecase.sso.GetEncryptedSecret
import me.proton.core.util.kotlin.DefaultCoroutineScopeProvider
import me.proton.core.util.kotlin.DefaultDispatcherProvider

internal data class ProtonAuthenticationGraph(
    val authentication: AuthenticationFlowPort,
    val events: CalendarDeltaRemote,
    val preparations: CalendarCreatePreparationPort = CalendarCreatePreparationPort { createUnavailable(CreatePreparationFailure.UNSUPPORTED) },
    val submissions: CalendarCreateSubmissionPort? = null,
    val occurrenceQualification: (() -> CalendarCreateSubmissionPort)? = null,
    val matchesIdentityHash: suspend (AccountId, String) -> Boolean = { _, _ -> false },
    val localEdits: CalendarLocalEditPreparationPort = CalendarLocalEditPreparationPort { _, _ -> localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED) },
    val titleUpdates: CalendarTitleUpdateSubmissionPort? = null,
    val localTexts: CalendarLocalTextPreparationPort = CalendarLocalTextPreparationPort { _, _ -> localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED) },
    val localTimes: CalendarLocalTimePreparationPort = CalendarLocalTimePreparationPort { _, _ -> localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED) },
    val textUpdates: CalendarTextUpdateSubmissionPort? = null,
    val timeUpdates: CalendarTimeUpdateSubmissionPort? = null,
    val personals: CalendarPersonalPreparationPort = CalendarPersonalPreparationPort { _, _ -> localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED) },
    val personalUpdates: CalendarPersonalUpdatePort? = null,
    val deletions: CalendarDeleteSubmissionPort? = null,
    val connectedAccountLabel: suspend () -> String? = { null },
    val catalog: CalendarCatalogPort,
)

/** Reads local account metadata, never users, addresses, sessions or credentials over HTTP. */
internal suspend fun readConnectedAccountLabel(
    readAccounts: suspend () -> List<me.proton.core.account.domain.entity.Account>,
): String? {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    return try {
        val accounts = readAccounts()
        caller.ensureActive()
        val account = accounts.singleOrNull()
        if (account == null || account.state != me.proton.core.account.domain.entity.AccountState.Ready ||
            account.sessionState != me.proton.core.account.domain.entity.SessionState.Authenticated ||
            account.sessionId == null) null
        else safeConnectedAccountLabel(account.username) ?: safeConnectedAccountLabel(account.email)
    } catch (failure: Exception) {
        caller.ensureActive()
        if (failure is CancellationException) throw failure
        null
    }
}

/** A bounded display label, with no fallback to account or session identifiers. */
internal fun safeConnectedAccountLabel(value: String?): String? {
    if (value == null || value.length > 256 || value.any {
            it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() ||
                Character.getType(it) == Character.LINE_SEPARATOR.toInt() ||
                Character.getType(it) == Character.PARAGRAPH_SEPARATOR.toInt()
        }) return null
    val label = value.trim().takeIf { it.isNotEmpty() } ?: return null
    return try {
        val bytes = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(label))
        label.takeIf { bytes.remaining() <= 1024 }
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }
}

internal object ProtonAuthenticationRuntime {
    fun build(context: Context, humanVerification: GateCHumanVerificationHooks): ProtonAuthenticationGraph {
            val cryptoContext = AndroidCryptoContext()
            val protectedStorage = GateCProtectedStorageGuard.requireAvailable(cryptoContext.keyStoreCrypto)
            val database = ProtonGateCDatabase.build(context, protectedStorage)
            val accountRepository = AccountRepositoryImpl(Product.Calendar, database, cryptoContext.keyStoreCrypto)
            val sessionCoordinator = ProtonCoreSessionCoordinator(
                accountRepository = accountRepository,
                addressDao = database.addressDao(),
            )
            val network = ProtonGateCNetworkFactory.build(
                context,
                sessionCoordinator,
                humanVerification,
            )
            val authRepository = AuthRepositoryImpl(
                provider = network.apiProvider,
                context = context,
                product = Product.Calendar,
                validateServerProof = ValidateServerProof(),
            )
            sessionCoordinator.bindAuthRepository(authRepository)
            val scopeProvider = DefaultCoroutineScopeProvider(DefaultDispatcherProvider())
            val userLocal = UserLocalDataSourceImpl(cryptoContext, database)
            val userRepository = UserRepositoryImpl(
                provider = network.apiProvider,
                context = context,
                product = Product.Calendar,
                validateServerProof = ValidateServerProof(),
                scopeProvider = scopeProvider,
                userLocalDataSource = userLocal,
                userRemoteDataSource = UserRemoteDataSourceImpl(network.apiProvider, userLocal),
            )
            val secretProvider = UserAddressKeySecretProvider(userRepository, cryptoContext)
            val userAddressRepository = UserAddressRepositoryImpl(
                db = database,
                userRepository = userRepository,
                userAddressRemoteDataSource = UserAddressRemoteDataSourceImpl(network.apiProvider, userLocal),
                userAddressKeySecretProvider = secretProvider,
                context = cryptoContext,
                scopeProvider = scopeProvider,
            )
            val keySaltRepository = KeySaltRepositoryImpl(database, network.apiProvider, scopeProvider)
            val userManager = UserManagerImpl(
                userRepository = userRepository,
                userAddressRepository = userAddressRepository,
                passphraseRepository = userRepository,
                keySaltRepository = keySaltRepository,
                privateKeyRepository = PrivateKeyRepositoryImpl(network.apiProvider, ValidateServerProof()),
                accountRecoveryRepository = UnsupportedGateCAccountRecovery,
                userAddressKeySecretProvider = secretProvider,
                cryptoContext = cryptoContext,
                generateSignedKeyList = GenerateSignedKeyList(cryptoContext),
                signedKeyListChangeListener = Optional.empty<SignedKeyListChangeListener>(),
                getEncryptedSecret = GetEncryptedSecret(cryptoContext),
            )
            val challengeManager = ChallengeManagerImpl(ChallengeRepositoryImpl(database))
            val operations = ProtonCoreGateCOperations(
                erasableLogin = ErasableProtonLogin(
                    authRepository,
                    GOpenPGPSrpCrypto(DefaultDispatcherProvider()),
                    challengeManager,
                    LoginChallengeConfig(),
                    GateCAuthDiagnostic.Disabled,
                    humanVerification.clear,
                ),
                performSecondFactor = PerformSecondFactor(authRepository),
                authRepository = authRepository,
                userManager = userManager,
                passphraseRepository = userRepository,
            )
            sessionCoordinator.bindForcedLogoutCleanup { userId ->
                if (userId != null) operations.lockAndClear(userId)
            }
            val accountScope = AccountScope("primary")
            val readSession = CatalogSessionSource {
                if (sessionCoordinator.state() != GateCStoredSessionState.READY) null else {
                    val user = sessionCoordinator.currentUserId()
                    val session = sessionCoordinator.currentSessionId()
                    if (user == null || session == null) null else CatalogSession(user, session)
                }
            }
            val eventRemote = ProtonEventImportRemote(network.apiProvider)
            val localAddresses = LocalEventAddresses { user ->
                // Unlike getAddresses(refresh=false), the DAO has no network fallback.
                database.addressWithKeysDao().getByUserId(user).map(ProtonLocalAddressMapper::map)
            }
            val eventDecoder = ProtonEventDecryptor(cryptoContext, localAddresses)
            val eventProtector = ProtonEventPayloadProtector(cryptoContext.keyStoreCrypto)
            val baseline = ProtonEventImportAdapter(readSession, eventRemote, eventDecoder, eventProtector)
            val contextRefresher = EventContextRefresher { session ->
                // Explicit Core reads only; never set up keys or change account attributes.
                userManager.getUser(session.userId, refresh = true)
                userManager.getAddresses(session.userId, refresh = true)
            }
            val calendarMetadata = ProtonCreateCalendarMetadataRemote(network.apiProvider)
            val titlePreparations = ProtonLocalTitlePreparationAdapter(readSession, contextRefresher,
                calendarMetadata, eventRemote, LocalTitleEventRemote { session, scope, id ->
                    ProtonChangeFeedRemote(network.apiProvider).event(session, scope.feedScope(), id.value).obj("Event")
                }, localAddresses, localTitleCryptoPreparation(cryptoContext, eventProtector),
                guardedRefresh = localTitleContextRefresh(
                    { session -> userManager.getUser(session.userId, refresh = true) },
                    { session -> userManager.getAddresses(session.userId, refresh = true) }))
            val textPreparations = ProtonLocalTextPreparationAdapter(readSession, contextRefresher,
                calendarMetadata, eventRemote, LocalTitleEventRemote { session, scope, id ->
                    ProtonChangeFeedRemote(network.apiProvider).event(session, scope.feedScope(), id.value).obj("Event")
                }, localAddresses, localTextCryptoPreparation(cryptoContext, eventProtector),
                guardedRefresh = localTitleContextRefresh(
                    { session -> userManager.getUser(session.userId, refresh = true) },
                    { session -> userManager.getAddresses(session.userId, refresh = true) }))
            val timePreparations = ProtonLocalTimePreparationAdapter(readSession, contextRefresher,
                calendarMetadata, eventRemote, LocalTitleEventRemote { session, scope, id ->
                    ProtonChangeFeedRemote(network.apiProvider).event(session, scope.feedScope(), id.value).obj("Event")
                }, localAddresses, localTimeCryptoPreparation(cryptoContext, eventProtector),
                guardedRefresh = localTitleContextRefresh(
                    { session -> userManager.getUser(session.userId, refresh = true) },
                    { session -> userManager.getAddresses(session.userId, refresh = true) }))
            val seriesReadback = ProtonCreatedEventReadback(readSession, ProtonCreateSubmissionRemote(network.apiProvider),
                eventRemote, eventDecoder::decodeCreated, seriesFamilyVerifier(eventDecoder))
            val personalPreparations = ProtonPersonalPreparationAdapter(readSession, contextRefresher,
                calendarMetadata, eventRemote, LocalTitleEventRemote { session, scope, id ->
                    ProtonChangeFeedRemote(network.apiProvider).event(session, scope.feedScope(), id.value).obj("Event")
                }, localAddresses, personalCryptoPreparation(cryptoContext, eventProtector),
                guardedRefresh = localTitleContextRefresh(
                    { session -> userManager.getUser(session.userId, refresh = true) },
                    { session -> userManager.getAddresses(session.userId, refresh = true) }),
                seriesPreparation = seriesCryptoPreparation(cryptoContext, eventProtector),
                seriesQualification = PersonalSeriesQualification { session, scope, base, checkpoint ->
                    seriesReadback.requireSeries(session, scope, base, checkpoint)
                })
            val titleContext = TitleUpdateContextQualification { session, scope, checkpoint ->
                checkpoint()
                // Keep each suspending read inside the caller's original access lease.
                userManager.getUser(session.userId, refresh = true)
                checkpoint()
                userManager.getAddresses(session.userId, refresh = true)
                checkpoint()
                val metadata = calendarMetadata.calendar(session, scope.calendar.value)
                checkpoint()
                val bootstrap = eventRemote.bootstrap(session, scope.calendar.value)
                checkpoint()
                val addresses = localAddresses.get(session.userId)
                checkpoint()
                val selected = ProtonCreateContextPolicy.select(scope, metadata, bootstrap, addresses)
                ProtonTitleUpdateSubmissionCodec.context(selected, metadata, bootstrap)
            }
        val createRemote = ProtonCreateSubmissionRemote(network.apiProvider)
        val createPreparation = ProtonCreatePreparationAdapter(readSession, contextRefresher, calendarMetadata,
            eventRemote, localAddresses, ProtonCreateContextResolver(cryptoContext)::prepare, eventProtector, submission = true)
        fun submissions(occurrences: ProtonOccurrenceCreateAdapter? = null) = ProtonCreateSubmissionAdapter(createPreparation,
            eventProtector, readSession, createRemote, eventRemote, eventDecoder::decodeCreated, occurrences)
        return ProtonAuthenticationGraph(
            authentication = ProtonAuthenticationFlowAdapter(
                accountScope,
                ProtonCoreAuthenticationAdapter(accountScope, operations, sessionCoordinator),
                ProtonCoreSessionAdapter(accountScope, operations, sessionCoordinator),
            ),
            catalog = ProtonCalendarCatalogAdapter(
                sessions = readSession,
                remote = ProtonCalendarCatalogRemote(network.apiProvider),
            ),
            matchesIdentityHash = { expectedAccount, expectedHash ->
                val account = accountRepository.getAccounts().first().singleOrNull()
                account != null && account.userId.id == expectedAccount.value &&
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(account.username.orEmpty().trim().lowercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) } == expectedHash
            },
            connectedAccountLabel = { readConnectedAccountLabel { accountRepository.getAccounts().first() } },
            preparations = ProtonCreatePreparationAdapter(readSession, contextRefresher,
                ProtonCreateCalendarMetadataRemote(network.apiProvider), eventRemote, localAddresses,
                ProtonCreateContextResolver(cryptoContext)::prepare, eventProtector),
            localEdits = titlePreparations,
            localTexts = textPreparations,
            localTimes = timePreparations,
            personals = personalPreparations,
            personalUpdates = ProtonPersonalUpdateSubmissionAdapter(personalPreparations, eventProtector, readSession, titleContext,
                ProtonPersonalUpdateSubmissionRemote(network.apiProvider), eventRemote, eventDecoder::decodeCreated,
                seriesVerifier = seriesFamilyVerifier(eventDecoder),
                seriesQualification = PersonalSeriesQualification { session, scope, base, checkpoint ->
                    seriesReadback.requireSeries(session, scope, base, checkpoint)
                }),
            titleUpdates = ProtonTitleUpdateSubmissionAdapter(titlePreparations, eventProtector, readSession, titleContext,
                ProtonTitleUpdateSubmissionRemote(network.apiProvider), eventRemote, eventDecoder::decodeCreated),
            textUpdates = ProtonTextUpdateSubmissionAdapter(textPreparations, eventProtector, readSession, titleContext,
                ProtonTitleUpdateSubmissionRemote(network.apiProvider), eventRemote, eventDecoder::decodeCreated),
            timeUpdates = ProtonTimeUpdateSubmissionAdapter(timePreparations, eventProtector, readSession, titleContext,
                ProtonTitleUpdateSubmissionRemote(network.apiProvider), eventRemote, eventDecoder::decodeCreated),
            deletions = ProtonDeleteSubmissionAdapter(eventProtector, readSession, titleContext,
                ProtonDeleteSubmissionRemote(network.apiProvider), eventRemote, eventDecoder::decodeCreated),
            submissions = submissions(),
            occurrenceQualification = { submissions(ProtonOccurrenceCreateAdapter(readSession, contextRefresher,
                calendarMetadata, eventRemote, createRemote, OccurrenceCreateCryptography { session, intent, id, meta, boot, source ->
                    eventDecoder.prepareOccurrenceCreate(session, intent, id, meta, boot, source, eventProtector)
                }, seriesFamilyVerifier(eventDecoder), eventProtector, occurrenceChildVerifier(eventDecoder))) },
            events = ProtonCalendarDeltaAdapter(baseline, readSession,
                ProtonChangeFeed(readSession, ProtonChangeFeedRemote(network.apiProvider)), eventRemote,
                eventDecoder, eventProtector, contextRefresher),
        )
    }
}
