// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.proton.core.crypto.common.keystore.EncryptedByteArray
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.entity.key.KeyId
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.network.domain.session.SessionId
import me.proton.core.user.domain.entity.*
import org.junit.Assert.*
import org.junit.Test

/** Controlled read/crypto boundaries only; fabricated packets do not prove service compatibility. */
class ProtonLocalTextPreparationAdapterTest {
    private val scope = CalendarScope(AccountId("synthetic-user"), CalendarId("synthetic-calendar"), 1)
    private val initial = CatalogSession(UserId(scope.account.value), SessionId("synthetic-session"))
    private var current: CatalogSession? = initial
    private val timestamp = Instant.parse("2026-10-03T12:00:00Z")
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private val metadata = json("""{"ID":"synthetic-calendar","Type":0}""")
    private val bootstrap = json("""{"Passphrase":{"ID":"pass","CalendarID":"synthetic-calendar","Flags":1,
      "MemberPassphrases":[{"MemberID":"member","Passphrase":"protected","Signature":"signature"}]},
      "Members":[{"ID":"member","CalendarID":"synthetic-calendar","AddressID":"address","Flags":1,"Permissions":16}],
      "Keys":[{"ID":"primary","CalendarID":"synthetic-calendar","PassphraseID":"pass","Flags":3,"PrivateKey":"encrypted"}],
      "CalendarSettings":{"CalendarID":"synthetic-calendar","DefaultFullDayNotifications":[],"DefaultPartDayNotifications":[]}}""")
    private val row = json("""{"ID":"synthetic-event","CalendarID":"synthetic-calendar","UID":"synthetic@example.invalid","ModifyTime":1,"Notifications":[]}""")
    private val content = EventContent("Original", EventTime.AllDay(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 27)),
        description = "Original notes", location = "Original place", details = EventDetails())
    private val intent = LocalEditIntent(MutationId("synthetic-edit"), scope, EVENT_FORMAT,
        RemoteChange.Upsert(EventId("synthetic-event"), row.revision(bootstrap), content),
        content.copy(title = "Replacement", description = "New notes", location = ""))
    private var expectedIntent = intent
    private val addressId = AddressId("address")
    private val address = UserAddress(initial.userId, addressId, "synthetic@example.invalid", canSend = true,
        canReceive = true, enabled = true, order = 0, keys = listOf(UserAddressKey(addressId, 1, 3, active = true,
            keyId = KeyId("signer"), privateKey = PrivateKey("synthetic-key", true, passphrase = EncryptedByteArray(byteArrayOf(1))))), signedKeyList = null)
    private val stages = listOf("user", "remote-addresses", "metadata", "bootstrap", "event", "addresses", "prepare")
    private val steps = mutableListOf<String>()
    private var after: suspend (String) -> Unit = {}
    private var sessionReads = 0
    private var sessionFailureAt = -1
    private var prepareCalls = 0
    private var retainedCheckpoint: (suspend () -> Unit)? = null
    private var transform: (LocalTextEditPreparation) -> LocalTextEditPreparation = { it }
    private suspend fun stage(name: String) { steps += name; after(name) }
    private fun adapter(meta: JsonObject = metadata, boot: JsonObject = bootstrap, original: JsonObject = row,
        local: List<UserAddress> = listOf(address), guarded: Boolean = true) = ProtonLocalTextPreparationAdapter({
            if (++sessionReads == sessionFailureAt) throw java.io.IOException("private storage detail")
            current
        }, { stage("legacy-refresh") },
        CreateCalendarMetadataRemote { _, _ -> stage("metadata"); meta }, object : EventImportRemote {
            override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No page reads")
            override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { stage("bootstrap"); return boot }
        }, LocalTitleEventRemote { session, target, id ->
            assertEquals(initial, session); assertEquals(expectedIntent.scope, target); assertEquals(expectedIntent.base.eventId, id)
            stage("event"); original
        }, LocalEventAddresses { assertEquals(initial.userId, it); stage("addresses"); local },
        LocalTextContextPreparation { session, frozen, uid, time, observedMeta, observedBoot, observedRow, observedLocal ->
            prepareCalls++; stage("prepare")
            assertEquals(initial, session); assertEquals(expectedIntent, frozen); assertEquals(timestamp, time)
            assertEquals(row.string("UID"), uid); assertEquals(metadata, observedMeta); assertEquals(bootstrap, observedBoot)
            assertEquals(row, observedRow); assertEquals(listOf(address), observedLocal)
            transform(LocalTextEditPreparation(frozen, time, LOCAL_TEXT_PREPARATION_FORMAT, "synthetic-protected"))
        }, guardedRefresh = if (guarded) LocalTitleContextRefresh { session, checkpoint ->
            retainedCheckpoint = checkpoint
            localTitleContextRefresh({ stage("user") }, { stage("remote-addresses") }).refresh(session, checkpoint)
        } else null)

    @Test fun freshGuardedMultiTextReadsUseTheFullIntentAndExactReadOrder() = runTest {
        val result = adapter().prepareLocalText(intent, timestamp) { it == scope }
        assertNotNull(result); assertEquals(intent, result!!.intent)
        assertEquals(timestamp, result.timestamp); assertEquals(LOCAL_TEXT_PREPARATION_FORMAT, result.format)
        assertEquals(stages, steps); assertEquals(1, prepareCalls)
    }
    @Test fun unrestrictedTextPreparationRetainsItsSeparateReadOnlyContract() = runTest {
        val result = adapter().prepareLocalText(intent, timestamp)
        assertEquals(intent, result.intent)
        assertEquals(listOf("legacy-refresh") + stages.drop(2), steps)
        assertEquals(LOCAL_TEXT_PREPARATION_FORMAT, result.format)
    }
    @Test fun descriptionOnlyLocationOnlyAndTimedProfilesAreNotReducedToTitle() = runTest {
        val timed = content.copy(time = EventTime.Timed(Instant.parse("2026-10-24T12:00:00Z"),
            Instant.parse("2026-10-24T13:00:00Z"), ZoneId.of("Europe/Paris")))
        for ((base, next) in listOf(content to content.copy(description = "Only notes"),
            content to content.copy(location = "Only place"), timed to timed.copy(description = "Timed notes", location = "Timed place"))) {
            steps.clear()
            expectedIntent = intent.copy(base = intent.base.copy(content = base), content = next)
            assertEquals(expectedIntent, adapter().prepareLocalText(expectedIntent, timestamp) { true }!!.intent)
            assertEquals(stages, steps)
        }
    }
    @Test fun denialAndUnsupportedGuardedRefresherCannotReachLegacyOrSessionReads() = runTest {
        assertNull(adapter().prepareLocalText(intent, timestamp) { false })
        assertNull(adapter(guarded = false).prepareLocalText(intent, timestamp) { true })
        assertEquals(0, sessionReads); assertTrue(steps.isEmpty()); assertEquals(0, prepareCalls)
    }
    @Test fun invalidProfileFormatAndTimeAreRejectedBeforeAnyRead() = runTest {
        for (candidate in listOf(intent.copy(state = LocalEditState.CONFLICT), intent.copy(format = EVENT_FORMAT.copy(version = 99)),
            intent.copy(content = intent.content.copy(time = EventTime.AllDay(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 27))))))
            refused(LocalEditPreparationFailure.UNSUPPORTED) { adapter().prepareLocalText(candidate, timestamp) { true } }
        for (time in listOf(timestamp.plusNanos(1), Instant.EPOCH.minusSeconds(1), Instant.parse("+10000-01-01T00:00:00Z")))
            refused(LocalEditPreparationFailure.UNSUPPORTED) { adapter().prepareLocalText(intent, time) { true } }
        assertEquals(0, sessionReads); assertTrue(steps.isEmpty())
    }
    @Test fun revokedAdmissionStopsAfterEveryControlledStageAndNeverResumes() = runTest {
        for ((index, point) in stages.withIndex()) {
            steps.clear(); var allowed = true
            after = { if (it == point) allowed = false }
            assertNull(adapter().prepareLocalText(intent, timestamp) { allowed })
            assertEquals(stages.take(index + 1), steps)
            allowed = true
            checkpointIsClosed()
        }
    }
    @Test fun retainedCheckpointExpiresOnSuccessfulReturnAndTypedFailure() = runTest {
        assertNotNull(adapter().prepareLocalText(intent, timestamp) { true }); checkpointIsClosed()
        after = { if (it == "event") throw java.io.IOException("private network detail") }
        refused(LocalEditPreparationFailure.NETWORK) { adapter().prepareLocalText(intent, timestamp) { true } }
        checkpointIsClosed()
    }

    @Test fun denialObservedInsideAnAdmissionCallbackCannotAuthorizeTheNextRead() = runTest {
        var afterUser = false
        var nestedDenial = false
        var checkpointsAfterUser = 0
        var denials = 0
        after = { if (it == "user") afterUser = true }
        val prepared = adapter().prepareLocalText(intent, timestamp) {
            if (nestedDenial) false
            else {
                // Revoke through a retained checkpoint during the final admission in the
                // user-read checkpoint, then return a stale true from the outer callback.
                if (afterUser && ++checkpointsAfterUser == 2) {
                    nestedDenial = true
                    try { runBlocking { checkNotNull(retainedCheckpoint).invoke() }; fail("Nested denial must stop its checkpoint") }
                    catch (_: RuntimeException) { denials++ }
                    finally { nestedDenial = false }
                }
                true
            }
        }
        assertNull(prepared)
        assertEquals(1, denials)
        assertEquals(listOf("user"), steps)
        assertEquals(0, prepareCalls)
    }
    @Test fun missingForeignAndReplacedSessionsCannotReachOrEscapeCrypto() = runTest {
        for (session in listOf(null, initial.copy(userId = UserId("foreign")))) {
            current = session
            refused(LocalEditPreparationFailure.AUTHENTICATION) { adapter().prepareLocalText(intent, timestamp) { true } }
            assertTrue(steps.isEmpty())
        }
        for ((index, point) in stages.withIndex()) {
            steps.clear(); current = initial
            after = { if (it == point) current = initial.copy(sessionId = SessionId("replacement")) }
            refused(LocalEditPreparationFailure.AUTHENTICATION) { adapter().prepareLocalText(intent, timestamp) { true } }
            assertEquals(stages.take(index + 1), steps)
        }
    }
    @Test fun sessionStorageErrorsRemainStorageAtInitialAndFinalCheckpoints() = runTest {
        assertNotNull(adapter().prepareLocalText(intent, timestamp) { true })
        val successfulReads = sessionReads
        for (read in listOf(1, successfulReads)) {
            sessionReads = 0; sessionFailureAt = read
            refused(LocalEditPreparationFailure.STORAGE) { adapter().prepareLocalText(intent, timestamp) { true } }
        }
    }
    @Test fun wrongEventIdentityMissingUidAndChangedRevisionPreventCrypto() = runTest {
        for (wrong in listOf(JsonObject(row + ("ID" to JsonPrimitive("foreign"))),
            JsonObject(row + ("CalendarID" to JsonPrimitive("foreign"))), JsonObject(row - "UID"),
            JsonObject(row + ("UID" to JsonPrimitive(" ")))))
            refused(LocalEditPreparationFailure.CONTEXT) { adapter(original = wrong).prepareLocalText(intent, timestamp) { true } }
        refused(LocalEditPreparationFailure.CONFLICT) {
            adapter(original = JsonObject(row + ("ModifyTime" to JsonPrimitive(2)))).prepareLocalText(intent, timestamp) { true }
        }
        val settings = JsonObject(bootstrap.obj("CalendarSettings") + ("OtherSetting" to JsonPrimitive(1)))
        refused(LocalEditPreparationFailure.CONFLICT) {
            adapter(boot = JsonObject(bootstrap + ("CalendarSettings" to settings))).prepareLocalText(intent, timestamp) { true }
        }
        assertEquals(0, prepareCalls)
    }
    @Test fun freshPermissionContextAndKeyQualificationPrecedeCrypto() = runTest {
        val member = bootstrap.array("Members").single() as JsonObject
        val readOnly = JsonObject(bootstrap + ("Members" to JsonArray(listOf(JsonObject(member + ("Permissions" to JsonPrimitive(2)))))))
        refused(LocalEditPreparationFailure.PERMISSION) { adapter(boot = readOnly).prepareLocalText(
            intent.copy(base = intent.base.copy(revision = row.revision(readOnly))), timestamp) { true } }
        refused(LocalEditPreparationFailure.LOCAL_KEYS) {
            adapter(local = listOf(address.copy(keys = emptyList()))).prepareLocalText(intent, timestamp) { true }
        }
        refused(LocalEditPreparationFailure.CONTEXT) {
            adapter(meta = JsonObject(metadata + ("Type" to JsonPrimitive(1)))).prepareLocalText(intent, timestamp) { true }
        }
        assertEquals(0, prepareCalls)
    }
    @Test fun intentJsonAndAddressListsAreFrozenBeforeLaterSuspensions() = runTest {
        val mutableMeta = metadata.toMutableMap(); val mutableBoot = bootstrap.toMutableMap(); val mutableRow = row.toMutableMap()
        val keys = address.keys.toMutableList(); val local = mutableListOf(address.copy(keys = keys))
        val reminders = mutableListOf<EventReminder>()
        val candidate = intent.copy(base = intent.base.copy(content = content.copy(details = EventDetails(reminders = reminders))),
            content = intent.content.copy(details = EventDetails(reminders = reminders)))
        after = {
            yield()
            when (it) {
                "user" -> reminders.add(EventReminder(10))
                "bootstrap" -> mutableMeta["Type"] = JsonPrimitive(1)
                "event" -> mutableBoot["CalendarSettings"] = JsonObject(emptyMap())
                "addresses" -> mutableRow["UID"] = JsonPrimitive("mutated")
                "prepare" -> { keys.clear(); local.clear() }
            }
        }
        val result = adapter(JsonObject(mutableMeta), JsonObject(mutableBoot), JsonObject(mutableRow), local)
            .prepareLocalText(candidate, timestamp) { true }
        assertNotNull(result); assertEquals(intent, result!!.intent); assertEquals(stages, steps)
    }
    @Test fun transportFailuresAndLocalStorageFailuresStayDistinctAndRedacted() = runTest {
        for (point in stages.take(5)) {
            after = { if (it == point) throw java.io.IOException("private transport detail") }
            refused(LocalEditPreparationFailure.NETWORK) { adapter().prepareLocalText(intent, timestamp) { true } }
            for ((status, reason) in listOf(401 to LocalEditPreparationFailure.AUTHENTICATION,
                403 to LocalEditPreparationFailure.AUTHENTICATION, 404 to LocalEditPreparationFailure.NOT_FOUND,
                429 to LocalEditPreparationFailure.RETRY)) {
                after = { if (it == point) throw me.proton.core.network.domain.ApiException(
                    me.proton.core.network.domain.ApiResult.Error.Http(status, "private response")) }
                refused(reason) { adapter().prepareLocalText(intent, timestamp) { true } }
            }
        }
        after = { if (it == "addresses") throw java.io.IOException("private local detail") }
        refused(LocalEditPreparationFailure.STORAGE) { adapter().prepareLocalText(intent, timestamp) { true } }
    }
    @Test fun textCryptoAndProtectionFailuresNeverProduceRecords() = runTest {
        for ((failure, reason) in listOf(
            RemoteImportException(RemoteImportFailure.LOCAL_KEYS) to LocalEditPreparationFailure.LOCAL_KEYS,
            RemoteImportException(RemoteImportFailure.UNSUPPORTED) to LocalEditPreparationFailure.UNSUPPORTED,
            ProtonTextPreparationException(ProtonTextPreparationFailure.UNSUPPORTED) to LocalEditPreparationFailure.UNSUPPORTED,
            ProtonTextPreparationException(ProtonTextPreparationFailure.STORAGE) to LocalEditPreparationFailure.STORAGE,
            GateCProtectedStorageUnavailable() to LocalEditPreparationFailure.STORAGE)) {
            after = { if (it == "prepare") throw failure }
            refused(reason) { adapter().prepareLocalText(intent, timestamp) { true } }
        }
    }
    @Test fun everySuspendingStagePropagatesThrownAndSwallowedCancellation() = runTest {
        for (point in stages) {
            steps.clear(); after = { if (it == point) throw CancellationException("synthetic cancellation") }
            try { adapter().prepareLocalText(intent, timestamp) { true }; fail("Cancellation must propagate") }
            catch (_: CancellationException) { }
            for (throwAfterCancel in listOf(false, true)) {
                steps.clear(); var returns = 0; var cancellations = 0; var ordinaryFailures = 0
                supervisorScope {
                    val task = async {
                        val callContext = currentCoroutineContext()
                        after = { if (it == point) {
                            callContext.cancel()
                            if (throwAfterCancel) throw java.io.IOException("private cancelled failure")
                        } }
                        try {
                            adapter().prepareLocalText(intent, timestamp) { true }
                            returns++
                        } catch (failure: CancellationException) {
                            cancellations++; throw failure
                        } catch (failure: Exception) {
                            ordinaryFailures++; throw failure
                        }
                    }
                    try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
                }
                assertEquals(1, cancellations); assertEquals(0, ordinaryFailures); assertEquals(0, returns)
                assertEquals(point, steps.last())
            }
        }
    }
    @Test fun admissionCancellationReturningFalseCannotReturnNormally() = runTest {
        var returns = 0; var cancellations = 0; var ordinaryFailures = 0
        supervisorScope {
            val task = async {
                val callContext = currentCoroutineContext()
                try {
                    adapter().prepareLocalText(intent, timestamp) { callContext.cancel(); false }
                    returns++
                } catch (failure: CancellationException) {
                    cancellations++; throw failure
                } catch (failure: Exception) {
                    ordinaryFailures++; throw failure
                }
            }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertEquals(1, cancellations); assertEquals(0, ordinaryFailures); assertEquals(0, returns)
        assertEquals(0, sessionReads)
    }
    @Test fun substitutedFullIntentTimestampAndFormatCannotEscapeTheAdapter() = runTest {
        val differentTime = EventTime.AllDay(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 27))
        val intents = listOf(intent.copy(id = MutationId("other")), intent.copy(scope = scope.copy(generation = 2)),
            intent.copy(scope = scope.copy(account = AccountId("other"))), intent.copy(scope = scope.copy(calendar = CalendarId("other"))),
            intent.copy(base = intent.base.copy(eventId = EventId("other"))), intent.copy(base = intent.base.copy(revision = Revision("other"))),
            intent.copy(base = intent.base.copy(content = content.copy(time = differentTime)), content = intent.content.copy(time = differentTime)),
            intent.copy(content = intent.content.copy(description = "substituted")))
        for (other in intents) {
            transform = { LocalTextEditPreparation(other, timestamp, LOCAL_TEXT_PREPARATION_FORMAT, "synthetic-protected") }
            refused(LocalEditPreparationFailure.CONTEXT) { adapter().prepareLocalText(intent, timestamp) { true } }
        }
        for ((time, format) in listOf(timestamp.plusSeconds(1) to LOCAL_TEXT_PREPARATION_FORMAT,
            timestamp to LOCAL_TITLE_PREPARATION_FORMAT)) {
            transform = { LocalTextEditPreparation(intent, time, format, "synthetic-protected") }
            refused(LocalEditPreparationFailure.CONTEXT) { adapter().prepareLocalText(intent, timestamp) { true } }
        }
    }
    @Test fun declaredFormatDoesNotOpenAnySessionOrReadAnyData() {
        assertEquals(LOCAL_TEXT_PREPARATION_FORMAT, adapter().localTextFormat)
        assertEquals(0, sessionReads); assertTrue(steps.isEmpty())
    }
    private suspend fun checkpointIsClosed() {
        val readsBefore = sessionReads
        val checkpoint = checkNotNull(retainedCheckpoint)
        try { checkpoint(); fail("Retained checkpoint must expire with its operation") }
        catch (failure: RuntimeException) {
            assertFalse(failure.toString().contains("synthetic")); assertNull(failure.cause)
        }
        assertEquals(readsBefore, sessionReads)
    }
    private suspend fun refused(reason: LocalEditPreparationFailure, action: suspend () -> Any?) {
        try { action(); fail("Unqualified context must not produce preparation") }
        catch (failure: LocalEditPreparationException) {
            assertEquals(reason, failure.reason); assertNull(failure.cause)
            assertFalse(failure.toString().contains("private")); assertFalse(failure.toString().contains("synthetic"))
        }
    }
}
