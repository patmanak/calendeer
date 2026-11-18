package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.key.data.api.KeyApi
import me.proton.core.key.data.api.response.toPublicAddressInfo
import me.proton.core.key.domain.entity.key.*
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Query

internal interface GuestMailSettingsReadApi : BaseRetrofitApi {
    @Streaming @GET("mail/v4/settings") suspend fun settings(): ResponseBody
    @Streaming @GET("core/v4/addresses/canonical")
    suspend fun canonical(@Query("Emails[]") email: String): ResponseBody
    @Streaming @GET("contacts/v4/contacts/emails")
    suspend fun contacts(@Query("Page") page: Int, @Query("PageSize") size: Int): ResponseBody
}

/** Read-only service observation. Reports no Mail settings, public keys, addresses or stable IDs. */
object ProtonGuestDirectoryProbe {
    enum class Status { ENROLLMENT_UNAVAILABLE, SETTINGS_UNAVAILABLE, KEYS_UNAVAILABLE, RECIPIENT_TYPE_UNAVAILABLE,
        DIRECTORY_DISAGREEMENT, INTERNAL_NO_ADDRESS_KEY, INTERNAL_CATCH_ALL, UNVERIFIED_INTERNAL_KEY,
        EXTERNAL_WITH_KEY, EXTERNAL_WITHOUT_DIRECTORY_KEY, UNSUPPORTED_KEY_SET, INTERNAL_KEY_OBSERVED,
        CANONICAL_UNAVAILABLE, CONTACT_PREFERENCES_UNAVAILABLE, INTERNAL_DEFAULT_CONTACT, INTERNAL_CUSTOM_CONTACT,
        INTERNAL_NO_MATCHING_CONTACT }

    suspend fun inspect(context: Context, enrolledHash: String, guest: String, preferences: Boolean): Status = withContext(Dispatchers.IO) {
        if (!enrolledHash.matches(Regex("[0-9a-f]{64}")) || guest.length !in 3..254 ||
            !guest.matches(Regex("[a-zA-Z0-9._+-]+@[a-zA-Z0-9.-]+"))) return@withContext Status.ENROLLMENT_UNAVAILABLE
        fun field(target: Any, name: String): Any? =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        var failure = Status.ENROLLMENT_UNAVAILABLE
        try {
            val port = ProtonAuthentication.personalPreparations(context)
            (field(port, "lock") as Mutex).withLock {
                val graph = field(port, "graph") as ProtonAuthenticationGraph
                val catalog = graph.catalog
                val sessions = field(catalog, "sessions") as CatalogSessionSource
                val session = requireNotNull(sessions.current())
                val provider = field(requireNotNull(field(catalog, "remote")), "provider") as ApiProvider
                suspend fun checkpoint() {
                    currentCoroutineContext().ensureActive()
                    require(field(port, "authenticated") == true && sessions.current() == session &&
                        graph.matchesIdentityHash(AccountId(session.userId.id), enrolledHash) && sessions.current() == session)
                }
                checkpoint()
                failure = Status.SETTINGS_UNAVAILABLE
                provider.get<GuestMailSettingsReadApi>(sessionId = session.sessionId)
                    .invoke(forceNoRetryOnConnectionErrors = true) {
                        settings().use {
                            val response = Json.parseToJsonElement(CalendarCatalogBodyReader.read(it)).jsonObject
                            require(response.number("Code") == 1000L)
                            val settings = response.getValue("MailSettings").jsonObject
                            require(settings.string("DraftMIMEType") in setOf("text/plain", "text/html") &&
                                settings.number("PGPScheme") in setOf(1L, 2L, 4L, 8L, 16L, 32L))
                        }
                    }.valueOrThrow
                checkpoint()
                failure = Status.KEYS_UNAVAILABLE
                val api = provider.get<KeyApi>(sessionId = session.sessionId)
                val info = api
                    .invoke(forceNoRetryOnConnectionErrors = true) {
                        getAllActivePublicKeys(guest, internalOnly = 0).toPublicAddressInfo(guest)
                    }.valueOrThrow
                checkpoint()
                failure = Status.RECIPIENT_TYPE_UNAVAILABLE
                // The pinned Android Calendar branch uses this explicit RecipientType for Mail.
                // IsProton in the newer response is not the Inbox recipient-type discriminator.
                @Suppress("DEPRECATION")
                val legacy = api.invoke(forceNoRetryOnConnectionErrors = true) {
                    getPublicAddressKeys(guest).toPublicAddress(guest)
                }.valueOrThrow
                checkpoint()
                val kind = guestDirectoryKind(info, legacy.recipient, legacy.keys.isNotEmpty())
                if (kind != Status.INTERNAL_KEY_OBSERVED) return@withLock kind
                val primary = info.address.keys.singleOrNull { it.publicKey.isPrimary }
                if (primary == null || !primary.canEncryptEmail() || !primary.canVerifyEmail() ||
                    primary.source?.let { it != PublicAddressKeySource.Internal } == true) return@withLock Status.UNSUPPORTED_KEY_SET
                val pgp = AndroidCryptoContext().pgpCrypto
                if (pgp.isKeyExpired(primary.publicKey.key) || pgp.isKeyRevoked(primary.publicKey.key)) return@withLock Status.UNSUPPORTED_KEY_SET
                if (!preferences) return@withLock Status.INTERNAL_KEY_OBSERVED
                val reads = provider.get<GuestMailSettingsReadApi>(sessionId = session.sessionId)
                failure = Status.CANONICAL_UNAVAILABLE
                val canonicalGuest = reads.invoke(forceNoRetryOnConnectionErrors = true) {
                    canonical(guest).use {
                        val root = Json.parseToJsonElement(CalendarCatalogBodyReader.read(it)).jsonObject
                        require(root.number("Code") == 1001L)
                        val row = root.getValue("Responses").jsonArray.single().jsonObject
                        require(row.string("Email").equals(guest, true))
                        val reply = row.getValue("Response").jsonObject
                        require(reply.number("Code") == 1000L)
                        reply.string("CanonicalEmail").also { email ->
                            require(email.length in 3..254 && email.matches(Regex("[a-zA-Z0-9._+-]+@[a-zA-Z0-9.-]+")))
                        }
                    }
                }.valueOrThrow
                checkpoint()
                failure = Status.CONTACT_PREFERENCES_UNAVAILABLE
                val status = inspectGuestContactIndex(canonicalGuest) { page, size ->
                    checkpoint()
                    reads.invoke(forceNoRetryOnConnectionErrors = true) { contacts(page, size) }.valueOrThrow
                }
                checkpoint()
                status
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { failure }
    }
}

/** Compares the Android recipient type with the distinct sources from the Web Inbox lookup. */
internal fun guestDirectoryKind(info: PublicAddressInfo, recipient: Recipient?, legacyHasKeys: Boolean): ProtonGuestDirectoryProbe.Status {
    if (recipient == null) return ProtonGuestDirectoryProbe.Status.RECIPIENT_TYPE_UNAVAILABLE
    if (info.warnings.isNotEmpty()) return ProtonGuestDirectoryProbe.Status.UNSUPPORTED_KEY_SET
    val direct = info.address.keys
    val catchAll = info.catchAll?.keys.orEmpty()
    val unverified = info.unverified?.keys.orEmpty()
    if (recipient == Recipient.External) {
        if (direct.any { it.canEncryptEmail() && it.canVerifyEmail() } || catchAll.isNotEmpty() ||
            unverified.any { it.source == PublicAddressKeySource.Internal }) return ProtonGuestDirectoryProbe.Status.DIRECTORY_DISAGREEMENT
        if (direct.isNotEmpty()) return ProtonGuestDirectoryProbe.Status.UNSUPPORTED_KEY_SET
        if (unverified.any { it.source !in setOf(PublicAddressKeySource.Wkd, PublicAddressKeySource.Koo) })
            return ProtonGuestDirectoryProbe.Status.UNSUPPORTED_KEY_SET
        return if (legacyHasKeys || unverified.isNotEmpty()) ProtonGuestDirectoryProbe.Status.EXTERNAL_WITH_KEY
            else ProtonGuestDirectoryProbe.Status.EXTERNAL_WITHOUT_DIRECTORY_KEY
    }
    if (direct.isEmpty()) {
        if (catchAll.isNotEmpty()) return ProtonGuestDirectoryProbe.Status.INTERNAL_CATCH_ALL
        if (unverified.any { it.source == PublicAddressKeySource.Internal }) return ProtonGuestDirectoryProbe.Status.UNVERIFIED_INTERNAL_KEY
        if (unverified.isNotEmpty()) return ProtonGuestDirectoryProbe.Status.DIRECTORY_DISAGREEMENT
        return if (legacyHasKeys) ProtonGuestDirectoryProbe.Status.DIRECTORY_DISAGREEMENT
            else ProtonGuestDirectoryProbe.Status.INTERNAL_NO_ADDRESS_KEY
    }
    if (!legacyHasKeys) return ProtonGuestDirectoryProbe.Status.DIRECTORY_DISAGREEMENT
    return ProtonGuestDirectoryProbe.Status.INTERNAL_KEY_OBSERVED
}
