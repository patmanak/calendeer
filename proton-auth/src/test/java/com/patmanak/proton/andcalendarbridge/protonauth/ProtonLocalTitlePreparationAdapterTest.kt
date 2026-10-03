// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.entity.key.KeyId
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.network.domain.session.SessionId
import me.proton.core.user.domain.entity.*
import org.junit.Assert.*
import org.junit.Test

class ProtonLocalTitlePreparationAdapterTest {
    private val scope = CalendarScope(AccountId("synthetic-user"), CalendarId("synthetic-calendar"), 1)
    private val initial = CatalogSession(UserId(scope.account.value), SessionId("synthetic-session"))
    private var current: CatalogSession? = initial
    private val timestamp = Instant.parse("2026-10-03T12:00:00Z")
    private val metadata = json("""{"ID":"synthetic-calendar","Type":0}""")
    private val bootstrap = json("""{"Passphrase":{"ID":"pass","CalendarID":"synthetic-calendar","Flags":1,
      "MemberPassphrases":[{"MemberID":"member","Passphrase":"protected","Signature":"signature"}]},
      "Members":[{"ID":"member","CalendarID":"synthetic-calendar","AddressID":"address","Flags":1,"Permissions":16}],
      "Keys":[{"ID":"primary","CalendarID":"synthetic-calendar","PassphraseID":"pass","Flags":3,"PrivateKey":"encrypted"}],
      "CalendarSettings":{"CalendarID":"synthetic-calendar","DefaultFullDayNotifications":[],"DefaultPartDayNotifications":[]}}""")
    private val row = json("""{"ID":"synthetic-event","CalendarID":"synthetic-calendar","UID":"synthetic@example.invalid","ModifyTime":1,"Notifications":[]}""")
    private val content = EventContent("Original", EventTime.AllDay(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5)), details = EventDetails())
    private val intent = LocalEditIntent(MutationId("synthetic-edit"), scope, EVENT_FORMAT,
        RemoteChange.Upsert(EventId("synthetic-event"), row.revision(bootstrap), content), content.copy(title = "Replacement"))
    private val addressId = AddressId("address")
    private val address = UserAddress(initial.userId, addressId, "synthetic@example.invalid", canSend = true,
        canReceive = true, enabled = true, order = 0, keys = listOf(UserAddressKey(addressId, 1, 3, active = true,
            keyId = KeyId("signer"), privateKey = PrivateKey("synthetic-key", true, passphrase = EncryptedByteArray(byteArrayOf(1))))), signedKeyList = null)
    private val steps = mutableListOf<String>()
    private var after: (String) -> Unit = {}
    private var sessionReads = 0
    private var sessionFailureAt = -1
    private var resultTransform: (LocalEditPreparation) -> LocalEditPreparation = { it }
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun stage(name: String) { steps += name; after(name) }
    private fun adapter(meta: JsonObject = metadata, boot: JsonObject = bootstrap, original: JsonObject = row,
        local: List<UserAddress> = listOf(address)) = ProtonLocalTitlePreparationAdapter({
            if (++sessionReads == sessionFailureAt) throw java.io.IOException("private storage detail")
            current
        }, { stage("refresh") },
        CreateCalendarMetadataRemote { _, _ -> stage("metadata"); meta }, object : EventImportRemote {
            override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No pagination")
            override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { stage("bootstrap"); return boot }
        }, LocalTitleEventRemote { session, target, id ->
            assertEquals(initial, session); assertEquals(scope, target); assertEquals(intent.base.eventId, id)
            stage("event"); original
        }, LocalEventAddresses { assertEquals(initial.userId, it); stage("addresses"); local },
        LocalTitleContextPreparation { session, frozen, uid, time, observedMeta, observedBoot, observedRow, observedLocal ->
            stage("prepare")
            assertEquals(initial, session); assertEquals(intent, frozen); assertEquals(timestamp, time)
            assertEquals(row.string("UID"), uid); assertEquals(metadata, observedMeta); assertEquals(bootstrap, observedBoot)
            assertEquals(row, observedRow); assertEquals(listOf(address), observedLocal)
            resultTransform(LocalEditPreparation(frozen, time, LOCAL_TITLE_PREPARATION_FORMAT, "synthetic-protected"))
        })
    @Test fun freshContextAndOriginalAreReadBeforeScopedPreparation() = runTest {
        val prepared = adapter().prepareLocalTitle(intent, timestamp)
        assertEquals(listOf("refresh", "metadata", "bootstrap", "event", "addresses", "prepare"), steps)
        assertEquals(intent, prepared.intent); assertEquals(timestamp, prepared.timestamp)
        assertEquals(LOCAL_TITLE_PREPARATION_FORMAT, prepared.format)
        assertFalse(prepared.toString().contains("synthetic"))
    }
    @Test fun absentForeignAndReplacedSessionsNeverReturnPreparation() = runTest {
        for (session in listOf(null, initial.copy(userId = UserId("foreign")))) {
            current = session
            refused(LocalEditPreparationFailure.AUTHENTICATION) { adapter().prepareLocalTitle(intent, timestamp) }
            assertTrue(steps.isEmpty())
        }
        for (point in listOf("refresh", "metadata", "bootstrap", "event", "addresses", "prepare")) {
            steps.clear(); current = initial
            after = { if (it == point) current = initial.copy(sessionId = SessionId("replaced")) }
            refused(LocalEditPreparationFailure.AUTHENTICATION) { adapter().prepareLocalTitle(intent, timestamp) }
            assertEquals(point, steps.last())
        }
    }
    @Test fun sessionStoreFailuresRemainStorageFailuresAtEveryCheckpoint() = runTest {
        for (read in 1..7) {
            sessionReads = 0; sessionFailureAt = read
            refused(LocalEditPreparationFailure.STORAGE) { adapter().prepareLocalTitle(intent, timestamp) }
        }
    }
    @Test fun unsupportedFormatProfileStateAndTimestampAreRefusedBeforeReads() = runTest {
        for (candidate in listOf(intent.copy(format = EVENT_FORMAT.copy(version = 2)),
            intent.copy(state = LocalEditState.CONFLICT),
            intent.copy(content = intent.content.copy(location = "unsupported local edit")))) {
            refused(LocalEditPreparationFailure.UNSUPPORTED) { adapter().prepareLocalTitle(candidate, timestamp) }
        }
        for (time in listOf(timestamp.plusNanos(1), Instant.EPOCH.minusSeconds(1), Instant.parse("+10000-01-01T00:00:00Z")))
            refused(LocalEditPreparationFailure.UNSUPPORTED) { adapter().prepareLocalTitle(intent, time) }
        assertEquals(0, sessionReads); assertTrue(steps.isEmpty())
    }
    @Test fun changedOriginalOrSettingsRevisionRequiresReconciliationBeforeCrypto() = runTest {
        val changedRow = JsonObject(row + ("ModifyTime" to JsonPrimitive(2)))
        val changedSettings = JsonObject(bootstrap + ("CalendarSettings" to JsonObject(bootstrap.obj("CalendarSettings") + ("NewSetting" to JsonPrimitive(true)))))
        refused(LocalEditPreparationFailure.CONFLICT) { adapter(original = changedRow).prepareLocalTitle(intent, timestamp) }
        refused(LocalEditPreparationFailure.CONFLICT) { adapter(boot = changedSettings).prepareLocalTitle(intent, timestamp) }
        assertFalse(steps.contains("addresses")); assertFalse(steps.contains("prepare"))
    }
    @Test fun foreignIdentityAndMissingUidCannotReachCrypto() = runTest {
        for (wrong in listOf(JsonObject(row + ("ID" to JsonPrimitive("foreign"))),
            JsonObject(row + ("CalendarID" to JsonPrimitive("foreign"))), JsonObject(row - "UID"),
            JsonObject(row + ("UID" to JsonPrimitive(" "))))) {
            refused(LocalEditPreparationFailure.CONTEXT) { adapter(original = wrong).prepareLocalTitle(intent, timestamp) }
        }
        assertFalse(steps.contains("prepare"))
    }
    @Test fun freshlyReadPermissionAndLocalKeyFailuresPreventPreparation() = runTest {
        val member = bootstrap.array("Members").single() as JsonObject
        val readOnly = JsonObject(bootstrap + ("Members" to JsonArray(listOf(JsonObject(member + ("Permissions" to JsonPrimitive(2)))))))
        refused(LocalEditPreparationFailure.PERMISSION) { adapter(boot = readOnly).prepareLocalTitle(
            intent.copy(base = intent.base.copy(revision = row.revision(readOnly))), timestamp) }
        refused(LocalEditPreparationFailure.LOCAL_KEYS) { adapter(local = listOf(address.copy(keys = emptyList()))).prepareLocalTitle(intent, timestamp) }
        refused(LocalEditPreparationFailure.CONTEXT) { adapter(meta = JsonObject(metadata + ("Type" to JsonPrimitive(1)))).prepareLocalTitle(intent, timestamp) }
        assertFalse(steps.contains("prepare"))
    }
    @Test fun frozenIntentAndEachReceivedJsonSurviveLaterSuspensions() = runTest {
        val mutableMeta = metadata.toMutableMap(); val mutableBoot = bootstrap.toMutableMap(); val mutableRow = row.toMutableMap()
        val mutableLocal = mutableListOf(address); val reminders = mutableListOf<EventReminder>()
        val candidate = intent.copy(base = intent.base.copy(content = content.copy(details = EventDetails(reminders = reminders))),
            content = intent.content.copy(details = EventDetails(reminders = reminders)))
        after = {
            when (it) {
                "refresh" -> reminders.add(EventReminder(10))
                "bootstrap" -> mutableMeta["Type"] = JsonPrimitive(1)
                "event" -> mutableBoot["CalendarSettings"] = JsonObject(emptyMap())
                "addresses" -> mutableRow["UID"] = JsonPrimitive("changed")
                "prepare" -> mutableLocal.clear()
            }
        }
        assertEquals(intent, adapter(JsonObject(mutableMeta), JsonObject(mutableBoot), JsonObject(mutableRow), mutableLocal)
            .prepareLocalTitle(candidate, timestamp).intent)
    }
    @Test fun rawReadsAndHttpFailuresUseSanitizedNetworkCategories() = runTest {
        for (point in listOf("refresh", "metadata", "bootstrap", "event")) {
            after = { if (it == point) throw java.io.IOException("private remote detail") }
            refused(LocalEditPreparationFailure.NETWORK) { adapter().prepareLocalTitle(intent, timestamp) }
            for ((code, reason) in listOf(401 to LocalEditPreparationFailure.AUTHENTICATION,
                403 to LocalEditPreparationFailure.AUTHENTICATION, 404 to LocalEditPreparationFailure.NOT_FOUND,
                429 to LocalEditPreparationFailure.RETRY)) {
                after = { if (it == point) throw me.proton.core.network.domain.ApiException(
                    me.proton.core.network.domain.ApiResult.Error.Http(code, "private remote detail")) }
                refused(reason) { adapter().prepareLocalTitle(intent, timestamp) }
            }
        }
        after = { if (it == "addresses") throw java.io.IOException("private storage detail") }
        refused(LocalEditPreparationFailure.STORAGE) { adapter().prepareLocalTitle(intent, timestamp) }
    }
    @Test fun cryptoAndProtectedStorageFailuresCannotEscapeAsPreparedRecords() = runTest {
        for ((failure, reason) in listOf(
            RemoteImportException(RemoteImportFailure.LOCAL_KEYS) to LocalEditPreparationFailure.LOCAL_KEYS,
            RemoteImportException(RemoteImportFailure.UNSUPPORTED) to LocalEditPreparationFailure.UNSUPPORTED,
            ProtonTitlePreparationException(ProtonTitlePreparationFailure.STORAGE) to LocalEditPreparationFailure.STORAGE,
            GateCProtectedStorageUnavailable() to LocalEditPreparationFailure.STORAGE)) {
            after = { if (it == "prepare") throw failure }
            refused(reason) { adapter().prepareLocalTitle(intent, timestamp) }
        }
    }
    @Test fun cancellationPropagatesFromEverySuspendingStage() = runTest {
        for (point in listOf("refresh", "metadata", "bootstrap", "event", "addresses", "prepare")) {
            after = { if (it == point) throw CancellationException("synthetic cancellation") }
            try { adapter().prepareLocalTitle(intent, timestamp); fail("Cancellation must propagate") }
            catch (_: CancellationException) { }
        }
    }
    @Test fun incompatiblePreparationResultIsRefusedWithoutChangingTheIntent() = runTest {
        for (change in listOf<(LocalEditPreparation) -> LocalEditPreparation>(
            { it.copy(intent = it.intent.copy(id = MutationId("different"))) },
            { it.copy(timestamp = timestamp.plusSeconds(1)) }, { it.copy(format = TITLE_PREPARATION_FORMAT) })) {
            resultTransform = change
            refused(LocalEditPreparationFailure.CONTEXT) { adapter().prepareLocalTitle(intent, timestamp) }
        }
        assertEquals("Replacement", intent.content.title)
    }
    @Test fun formatDeclarationPerformsNoAccountOrNetworkWork() {
        assertEquals(LOCAL_TITLE_PREPARATION_FORMAT, adapter().localTitleFormat)
        assertEquals(0, sessionReads); assertTrue(steps.isEmpty())
    }
    private suspend fun refused(reason: LocalEditPreparationFailure, action: suspend () -> Any) {
        try { action(); fail("Failed qualification must not return a preparation") }
        catch (failure: LocalEditPreparationException) {
            assertEquals(reason, failure.reason); assertNull(failure.cause)
            assertFalse(failure.toString().contains("private")); assertFalse(failure.toString().contains("synthetic"))
        }
    }
}
