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

/** Controlled authenticated reads only; fake protection grants no delivery authority. */
class ProtonLocalTimePreparationAdapterTest {
    private val scope = CalendarScope(AccountId("synthetic-user"), CalendarId("synthetic-calendar"), 1)
    private val initial = CatalogSession(UserId(scope.account.value), SessionId("synthetic-session"))
    private var current: CatalogSession? = initial
    private val timestamp = Instant.parse("2026-10-17T12:00:00Z")
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private val metadata = json("""{"ID":"synthetic-calendar","Type":0}""")
    private val bootstrap = json("""{"Passphrase":{"ID":"pass","CalendarID":"synthetic-calendar","Flags":1,
      "MemberPassphrases":[{"MemberID":"member","Passphrase":"protected","Signature":"signature"}]},
      "Members":[{"ID":"member","CalendarID":"synthetic-calendar","AddressID":"address","Flags":1,"Permissions":16}],
      "Keys":[{"ID":"primary","CalendarID":"synthetic-calendar","PassphraseID":"pass","Flags":3,"PrivateKey":"encrypted"}],
      "CalendarSettings":{"CalendarID":"synthetic-calendar","DefaultFullDayNotifications":[],"DefaultPartDayNotifications":[]}}""")
    private val row = json("""{"ID":"synthetic-event","CalendarID":"synthetic-calendar","UID":"synthetic@example.invalid","ModifyTime":1,"Notifications":[]}""")
    private val content = EventContent("Unchanged title", EventTime.AllDay(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 27)),
        description = "Unchanged notes", location = "Unchanged place", details = EventDetails())
    private val intent = LocalEditIntent(MutationId("synthetic-time-edit"), scope, EVENT_FORMAT,
        RemoteChange.Upsert(EventId("synthetic-event"), row.revision(bootstrap), content),
        content.copy(time = EventTime.AllDay(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 29))))
    private var expectedIntent = intent
    private val addressId = AddressId("address")
    private val address = UserAddress(initial.userId, addressId, "synthetic@example.invalid", canSend = true,
        canReceive = true, enabled = true, order = 0, keys = listOf(UserAddressKey(addressId, 1, 3, active = true,
            keyId = KeyId("signer"), privateKey = PrivateKey("synthetic-key", true, passphrase = EncryptedByteArray(byteArrayOf(1))))), signedKeyList = null)
    private val stages = listOf("user", "remote-addresses", "metadata", "bootstrap", "event", "addresses", "prepare")
    private val steps = mutableListOf<String>()
    private var after: suspend (String) -> Unit = {}
    private var sessionReads = 0
    private var prepareCalls = 0
    private var retainedCheckpoint: (suspend () -> Unit)? = null
    private var transform: (LocalTimeEditPreparation) -> LocalTimeEditPreparation = { it }
    private suspend fun stage(name: String) { steps += name; after(name) }
    private fun adapter(meta: JsonObject = metadata, boot: JsonObject = bootstrap, original: JsonObject = row,
        local: List<UserAddress> = listOf(address), guarded: Boolean = true) = ProtonLocalTimePreparationAdapter({
            sessionReads++; current
        }, { stage("legacy-refresh") },
        CreateCalendarMetadataRemote { _, _ -> stage("metadata"); meta }, object : EventImportRemote {
            override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No page reads")
            override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { stage("bootstrap"); return boot }
        }, LocalTitleEventRemote { session, target, id ->
            assertEquals(initial, session); assertEquals(expectedIntent.scope, target); assertEquals(expectedIntent.base.eventId, id)
            stage("event"); original
        }, LocalEventAddresses { assertEquals(initial.userId, it); stage("addresses"); local },
        LocalTimeContextPreparation { session, frozen, uid, time, observedMeta, observedBoot, observedRow, observedLocal ->
            prepareCalls++; stage("prepare")
            assertEquals(initial, session); assertEquals(expectedIntent, frozen); assertEquals(timestamp, time)
            assertEquals(row.string("UID"), uid); assertEquals(metadata, observedMeta); assertEquals(bootstrap, observedBoot)
            assertEquals(row, observedRow); assertEquals(listOf(address), observedLocal)
            transform(LocalTimeEditPreparation(frozen, time, LOCAL_TIME_PREPARATION_FORMAT, "synthetic-protected"))
        }, guardedRefresh = if (guarded) LocalTitleContextRefresh { session, checkpoint ->
            retainedCheckpoint = checkpoint
            localTitleContextRefresh({ stage("user") }, { stage("remote-addresses") }).refresh(session, checkpoint)
        } else null)

    @Test fun exactReadOrderPreservesAllDayAndTimedMovementAndDuration() = runTest {
        val timed = content.copy(time = EventTime.Timed(Instant.parse("2026-10-24T12:00:00Z"),
            Instant.parse("2026-10-24T13:00:00Z"), ZoneId.of("Europe/Paris")))
        val profiles = listOf(intent, intent.copy(base = intent.base.copy(content = timed),
            content = timed.copy(time = EventTime.Timed(Instant.parse("2026-10-25T13:00:00Z"),
                Instant.parse("2026-10-25T15:00:00Z"), ZoneId.of("Europe/Paris")))))
        for (candidate in profiles) {
            steps.clear(); expectedIntent = candidate
            val result = adapter().prepareLocalTime(candidate, timestamp) { it == scope }
            assertNotNull(result); assertEquals(candidate, result!!.intent)
            assertEquals(timestamp, result.timestamp); assertEquals(LOCAL_TIME_PREPARATION_FORMAT, result.format)
            assertEquals(stages, steps)
        }
        assertEquals(2, prepareCalls)
    }

    @Test fun formatDeclarationAndDeniedOrUnsupportedLeaseReadNothing() = runTest {
        assertEquals(LOCAL_TIME_PREPARATION_FORMAT, adapter().localTimeFormat)
        assertNull(adapter().prepareLocalTime(intent, timestamp) { false })
        assertNull(adapter(guarded = false).prepareLocalTime(intent, timestamp) { true })
        assertEquals(0, sessionReads); assertTrue(steps.isEmpty())
    }

    @Test fun combinedTextKindZoneAndForeignFormatsAreRejectedBeforeIo() = runTest {
        val timed = content.copy(time = EventTime.Timed(timestamp, timestamp.plusSeconds(3600), ZoneId.of("Europe/Paris")))
        for (candidate in listOf(intent.copy(state = LocalEditState.CONFLICT),
            intent.copy(format = LOCAL_TEXT_PREPARATION_FORMAT), intent.copy(format = EVENT_FORMAT.copy(version = 99)),
            intent.copy(content = intent.content.copy(title = "Combined")),
            intent.copy(content = intent.content.copy(description = "Combined")),
            intent.copy(content = intent.content.copy(location = "Combined")),
            intent.copy(content = timed), intent.copy(base = intent.base.copy(content = timed),
                content = timed.copy(time = EventTime.Timed(timestamp.plusSeconds(60), timestamp.plusSeconds(7200), ZoneId.of("UTC"))))))
            refused(LocalEditPreparationFailure.UNSUPPORTED) { adapter().prepareLocalTime(candidate, timestamp) { true } }
        for (time in listOf(timestamp.plusNanos(1), Instant.EPOCH.minusSeconds(1), Instant.parse("+10000-01-01T00:00:00Z")))
            refused(LocalEditPreparationFailure.UNSUPPORTED) { adapter().prepareLocalTime(intent, time) { true } }
        assertEquals(0, sessionReads); assertTrue(steps.isEmpty())
    }

    @Test fun unrestrictedPreparationHasItsOwnReadOnlyRefresh() = runTest {
        assertEquals(intent, adapter().prepareLocalTime(intent, timestamp).intent)
        assertEquals(listOf("legacy-refresh") + stages.drop(2), steps)
    }

    @Test fun leaseRevokedAfterEachStageNeverResumesAndExpiresAfterSuccessOrFailure() = runTest {
        for ((index, point) in stages.withIndex()) {
            steps.clear(); var allowed = true
            after = { if (it == point) allowed = false }
            assertNull(adapter().prepareLocalTime(intent, timestamp) { allowed })
            assertEquals(stages.take(index + 1), steps)
            allowed = true; checkpointIsClosed()
        }
        after = {}; assertNotNull(adapter().prepareLocalTime(intent, timestamp) { true }); checkpointIsClosed()
        after = { if (it == "event") throw java.io.IOException("private network detail") }
        refused(LocalEditPreparationFailure.NETWORK) { adapter().prepareLocalTime(intent, timestamp) { true } }
        checkpointIsClosed()
    }

    @Test fun nestedRevocationCannotBeReopenedByAnOlderTrueCallback() = runTest {
        var afterUser = false; var nested = false; var callbacks = 0; var denials = 0
        after = { if (it == "user") afterUser = true }
        assertNull(adapter().prepareLocalTime(intent, timestamp) {
            if (nested) false else {
                if (afterUser && ++callbacks == 2) {
                    nested = true
                    try { runBlocking { checkNotNull(retainedCheckpoint).invoke() }; fail("Expected denial") }
                    catch (_: RuntimeException) { denials++ }
                    finally { nested = false }
                }
                true
            }
        })
        assertEquals(1, denials); assertEquals(listOf("user"), steps); assertEquals(0, prepareCalls)
    }

    @Test fun missingForeignAndReplacedSessionsCannotReachOrEscapeCrypto() = runTest {
        for (session in listOf(null, initial.copy(userId = UserId("foreign")))) {
            current = session
            refused(LocalEditPreparationFailure.AUTHENTICATION) { adapter().prepareLocalTime(intent, timestamp) { true } }
            assertTrue(steps.isEmpty())
        }
        for ((index, point) in stages.withIndex()) {
            steps.clear(); current = initial
            after = { if (it == point) current = initial.copy(sessionId = SessionId("replacement")) }
            refused(LocalEditPreparationFailure.AUTHENTICATION) { adapter().prepareLocalTime(intent, timestamp) { true } }
            assertEquals(stages.take(index + 1), steps)
        }
    }

    @Test fun identityRevisionAndCurrentPermissionOrKeyFailuresPreventCrypto() = runTest {
        for (wrong in listOf(JsonObject(row + ("ID" to JsonPrimitive("foreign"))),
            JsonObject(row + ("CalendarID" to JsonPrimitive("foreign"))), JsonObject(row - "UID"),
            JsonObject(row + ("UID" to JsonPrimitive(" ")))))
            refused(LocalEditPreparationFailure.CONTEXT) { adapter(original = wrong).prepareLocalTime(intent, timestamp) { true } }
        refused(LocalEditPreparationFailure.CONFLICT) {
            adapter(original = JsonObject(row + ("ModifyTime" to JsonPrimitive(2)))).prepareLocalTime(intent, timestamp) { true }
        }
        val member = bootstrap.array("Members").single() as JsonObject
        val readOnly = JsonObject(bootstrap + ("Members" to JsonArray(listOf(JsonObject(member + ("Permissions" to JsonPrimitive(2)))))))
        refused(LocalEditPreparationFailure.PERMISSION) {
            adapter(boot = readOnly).prepareLocalTime(intent.copy(base = intent.base.copy(revision = row.revision(readOnly))), timestamp) { true }
        }
        refused(LocalEditPreparationFailure.LOCAL_KEYS) {
            adapter(local = listOf(address.copy(keys = emptyList()))).prepareLocalTime(intent, timestamp) { true }
        }
        refused(LocalEditPreparationFailure.CONTEXT) {
            adapter(meta = JsonObject(metadata + ("Type" to JsonPrimitive(1)))).prepareLocalTime(intent, timestamp) { true }
        }
        assertEquals(0, prepareCalls)
    }

    @Test fun laterSuspensionsCannotMutateFrozenIntentJsonOrAddressLists() = runTest {
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
            .prepareLocalTime(candidate, timestamp) { true }
        assertNotNull(result); assertEquals(intent, result!!.intent); assertEquals(stages, steps)
    }

    @Test fun transportStorageAndTimeCodecFailuresAreSanitizedAndDistinct() = runTest {
        for ((point, failure, reason) in listOf(
            Triple("event", java.io.IOException("private network detail"), LocalEditPreparationFailure.NETWORK),
            Triple("addresses", java.io.IOException("private storage detail"), LocalEditPreparationFailure.STORAGE),
            Triple("prepare", ProtonTimePreparationException(ProtonTimePreparationFailure.UNSUPPORTED), LocalEditPreparationFailure.UNSUPPORTED),
            Triple("prepare", ProtonTimePreparationException(ProtonTimePreparationFailure.STORAGE), LocalEditPreparationFailure.STORAGE),
            Triple("prepare", GateCProtectedStorageUnavailable(), LocalEditPreparationFailure.STORAGE))) {
            after = { if (it == point) throw failure }
            refused(reason) { adapter().prepareLocalTime(intent, timestamp) { true } }
        }
    }

    @Test fun cancellationSurvivesEachStageAndAdmissionEvenWhenFollowedByIoFailure() = runTest {
        for (point in stages + "admission") {
            steps.clear(); var returns = 0; var cancellations = 0; var ordinaryFailures = 0
            supervisorScope {
                val task = async {
                    val callContext = currentCoroutineContext()
                    after = { if (it == point) { callContext.cancel(); throw java.io.IOException("private cancelled detail") } }
                    try {
                        adapter().prepareLocalTime(intent, timestamp) {
                            if (point == "admission") { callContext.cancel(); throw java.io.IOException("private cancelled detail") }
                            true
                        }
                        returns++
                    } catch (failure: CancellationException) { cancellations++; throw failure }
                      catch (failure: Exception) { ordinaryFailures++; throw failure }
                }
                try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertEquals(1, cancellations); assertEquals(0, ordinaryFailures); assertEquals(0, returns)
            if (point == "admission") assertTrue(steps.isEmpty()) else assertEquals(point, steps.last())
        }
    }

    @Test fun substitutedFullIntentTimestampAndTextFormatCannotEscape() = runTest {
        val otherTime = EventTime.AllDay(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 30))
        for (other in listOf(intent.copy(id = MutationId("other")), intent.copy(scope = scope.copy(generation = 2)),
            intent.copy(scope = scope.copy(account = AccountId("other"))), intent.copy(scope = scope.copy(calendar = CalendarId("other"))),
            intent.copy(base = intent.base.copy(eventId = EventId("other"))), intent.copy(base = intent.base.copy(revision = Revision("other"))),
            intent.copy(content = intent.content.copy(time = otherTime)),
            intent.copy(base = intent.base.copy(content = content.copy(description = "substituted")),
                content = intent.content.copy(description = "substituted")))) {
            transform = { LocalTimeEditPreparation(other, timestamp, LOCAL_TIME_PREPARATION_FORMAT, "synthetic-protected") }
            refused(LocalEditPreparationFailure.CONTEXT) { adapter().prepareLocalTime(intent, timestamp) { true } }
        }
        for ((time, format) in listOf(timestamp.plusSeconds(1) to LOCAL_TIME_PREPARATION_FORMAT,
            timestamp to LOCAL_TEXT_PREPARATION_FORMAT)) {
            transform = { LocalTimeEditPreparation(intent, time, format, "synthetic-protected") }
            refused(LocalEditPreparationFailure.CONTEXT) { adapter().prepareLocalTime(intent, timestamp) { true } }
        }
    }

    private suspend fun checkpointIsClosed() {
        val before = sessionReads
        try { checkNotNull(retainedCheckpoint).invoke(); fail("Retained checkpoint must expire") }
        catch (failure: RuntimeException) { assertFalse(failure.toString().contains("synthetic")); assertNull(failure.cause) }
        assertEquals(before, sessionReads)
    }
    private suspend fun refused(reason: LocalEditPreparationFailure, action: suspend () -> Any?) {
        try { action(); fail("Unqualified context must not produce preparation") }
        catch (failure: LocalEditPreparationException) {
            assertEquals(reason, failure.reason); assertNull(failure.cause)
            assertFalse(failure.toString().contains("private")); assertFalse(failure.toString().contains("synthetic"))
        }
    }
}
