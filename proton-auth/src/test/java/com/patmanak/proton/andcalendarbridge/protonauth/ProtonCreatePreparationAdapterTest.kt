package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

class ProtonCreatePreparationAdapterTest {
    private val scope = CalendarScope(AccountId("synthetic-user"), CalendarId("synthetic-calendar"), 1)
    private val draft = CalendarCreateDraft(scope, MutationId("synthetic-correlation"), Instant.parse("2026-09-29T10:00:00Z"),
        EventContent("Synthetic", EventTime.AllDay(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"))))
    private val initial = CatalogSession(UserId(scope.account.value), SessionId("synthetic-session"))
    private var current: CatalogSession? = initial
    private val steps = mutableListOf<String>()
    private var after: (String) -> Unit = {}
    private var sealed: JsonObject? = null
    private var sessionReads = 0
    private var sessionFailureAt = -1
    private fun adapter(submission: Boolean = false) = ProtonCreatePreparationAdapter({
        sessionReads++
        if (sessionReads == sessionFailureAt) throw java.io.IOException("private session storage detail")
        current
    }, { steps += "refresh"; after("refresh") },
        CreateCalendarMetadataRemote { _, _ -> steps += "metadata"; after("metadata"); buildJsonObject {} },
        object : EventImportRemote {
            override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No event fetch needed")
            override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject {
                steps += "bootstrap"; after("bootstrap"); return buildJsonObject {}
            }
        }, LocalEventAddresses { steps += "addresses"; after("addresses"); emptyList() },
        CreateContextPreparation { _, _, _, _, _, _ -> steps += "prepare"; after("prepare"); ProtonPreparedCreateEvent(buildJsonObject { put("fixture", "synthetic") }) },
        object : EventPayloadProtector {
            override fun seal(plain: String): String { steps += "seal"; sealed = Json.parseToJsonElement(plain) as JsonObject; after("seal"); return "synthetic-sealed" }
            override fun open(protected: String): String = error("Preparation must not reopen")
        }, submission = submission)

    @Test fun refreshReadPrepareAndSealKeepScopePrivateAndCorrelationStable() = runTest {
        val port = adapter()
        val receipt = port.prepareCreate(draft)
        assertEquals(listOf("refresh", "metadata", "bootstrap", "addresses", "prepare", "seal"), steps)
        assertEquals(scope, receipt.scope); assertEquals(CREATE_PREPARATION_FORMAT, receipt.format)
        assertEquals("synthetic-sealed", receipt.protectedPayload)
        assertEquals("synthetic-calendar", sealed!!.string("calendar"))
        assertEquals(port.correlationUid(draft), sealed!!.string("uid"))
        assertEquals(port.correlationUid(draft), adapter().correlationUid(draft))
        assertNotEquals(port.correlationUid(draft), port.correlationUid(draft.copy(scope = scope.copy(generation = 2))))
        assertNotEquals(port.correlationUid(draft), port.correlationUid(draft.copy(correlation = MutationId("other"))))
        assertFalse(receipt.toString().contains("synthetic")); assertFalse(draft.toString().contains("Synthetic"))
    }
    @Test fun absentForeignAndChangedSessionsNeverYieldPreparation() = runTest {
        current = null
        refused(CreatePreparationFailure.AUTHENTICATION) { adapter().prepareCreate(draft) }
        assertTrue(steps.isEmpty())
        current = CatalogSession(UserId("foreign"), SessionId("foreign"))
        refused(CreatePreparationFailure.AUTHENTICATION) { adapter().prepareCreate(draft) }
        assertTrue(steps.isEmpty())
        for (stage in listOf("refresh", "metadata", "bootstrap", "addresses", "prepare", "seal")) {
            current = initial; steps.clear()
            after = { if (it == stage) current = initial.copy(sessionId = SessionId("replaced")) }
            refused(CreatePreparationFailure.AUTHENTICATION) { adapter().prepareCreate(draft) }
            assertEquals(stage, steps.last())
        }
    }
    @Test fun cancellationStorageAndNetworkFailuresAreNotConvertedToSuccess() = runTest {
        after = { if (it == "refresh") throw CancellationException("synthetic") }
        try { adapter().prepareCreate(draft); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        after = { if (it == "seal") throw GateCProtectedStorageUnavailable() }
        refused(CreatePreparationFailure.STORAGE) { adapter().prepareCreate(draft) }
        after = { if (it == "bootstrap") throw RemoteImportException(RemoteImportFailure.NETWORK) }
        refused(CreatePreparationFailure.NETWORK) { adapter().prepareCreate(draft) }
    }
    @Test fun rawReadFailuresAndLocalStorageFailuresStayDistinct() = runTest {
        for (stage in listOf("refresh", "metadata", "bootstrap")) {
            after = { if (it == stage) throw java.io.IOException("private read detail") }
            refused(CreatePreparationFailure.NETWORK) { adapter().prepareCreate(draft) }
        }
        for (stage in listOf("addresses", "seal")) {
            after = { if (it == stage) throw java.io.IOException("private storage detail") }
            refused(CreatePreparationFailure.STORAGE) { adapter().prepareCreate(draft) }
        }
        for (stage in listOf("refresh", "metadata", "bootstrap")) {
            for ((code, expected) in listOf(401 to CreatePreparationFailure.AUTHENTICATION,
                403 to CreatePreparationFailure.AUTHENTICATION, 429 to CreatePreparationFailure.RETRY)) {
                after = { if (it == stage) throw me.proton.core.network.domain.ApiException(
                    me.proton.core.network.domain.ApiResult.Error.Http(code, "private read detail")) }
                refused(expected) { adapter().prepareCreate(draft) }
            }
        }
    }
    @Test fun initialAndSubsequentSessionStoreFailuresAreStorageFailures() = runTest {
        for (read in listOf(1, 2)) {
            sessionReads = 0; sessionFailureAt = read
            refused(CreatePreparationFailure.STORAGE) { adapter().prepareCreate(draft) }
        }
    }
    @Test fun capturedKnownEmptyProfileHasADedicatedSealedNotificationObligation() = runTest {
        val timed = EventTime.Timed(Instant.parse("2030-10-01T10:00:00Z"), Instant.parse("2030-10-01T11:00:00Z"), java.time.ZoneId.of("UTC"))
        for (time in listOf(draft.content.time, timed)) {
            val captured = draft.copy(content = draft.content.copy(time = time, details = EventDetails()))
            val receipt = adapter().prepareCreate(captured)
            assertEquals(LOCAL_CREATE_PREPARATION_FORMAT, receipt.format)
            assertTrue(sealed!!["knownEmptyDetails"]!!.jsonPrimitive.boolean)
            assertEquals("EXPLICIT_EMPTY_REQUIRED", sealed!!.string("notificationPolicy"))
            assertEquals(captured.timestamp.toString(), sealed!!.string("timestamp"))
            assertTrue(sealed!!.containsKey("event"))
            assertFalse(sealed!!.containsKey("request")); assertFalse(sealed!!.containsKey("context"))
            assertFalse(sealed!!.containsKey("Notifications"))
        }
    }
    @Test fun submissionCannotAcceptKnownEmptyOrNonemptyDetailsBeforeAnyReads() = runTest {
        for (details in listOf(EventDetails(), EventDetails(reminders = listOf(EventReminder(10))))) {
            refused(CreatePreparationFailure.UNSUPPORTED) { adapter(submission = true).prepareCreate(draft.copy(content = draft.content.copy(details = details))) }
            assertEquals(0, sessionReads); assertTrue(steps.isEmpty()); assertNull(sealed)
        }
    }
    @Test fun nonemptyLocalDetailsCannotBeReducedToThePreparationSubset() = runTest {
        refused(CreatePreparationFailure.UNSUPPORTED) {
            adapter().prepareCreate(draft.copy(content = draft.content.copy(details = EventDetails(reminders = listOf(EventReminder(10))))))
        }
        assertEquals(0, sessionReads); assertTrue(steps.isEmpty()); assertNull(sealed)
    }
    private suspend fun refused(reason: CreatePreparationFailure, action: suspend () -> Any) {
        try { action(); fail("Preparation must not escape failed admission") }
        catch (failure: CreatePreparationException) { assertEquals(reason, failure.reason); assertNull(failure.cause) }
    }
}
