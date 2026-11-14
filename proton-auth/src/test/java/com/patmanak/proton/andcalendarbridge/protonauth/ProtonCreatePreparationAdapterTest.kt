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
    @Test fun seriesPreparationUsesItsOwnVersionAndPreservesRuleBeforeContextPreparation() = runTest {
        val value = draft.copy(content = draft.content.copy(details = EventDetails()), series = CreateSeriesRule(3))
        val port = adapter()
        val prepared = port.prepareCreate(value)
        assertEquals(AdapterFormat("proton-calendar-local-create-preparation", 4), prepared.format)
        assertEquals(value.series, preparedDraft!!.series)
        assertEquals(EventDetails(), preparedDraft!!.content.details)
        assertEquals("FREQ=DAILY;COUNT=3", sealed!!.string("seriesRule"))
        assertEquals("EXPLICIT_PERSONAL", sealed!!.string("notificationPolicy"))
        assertEquals(JsonArray(emptyList()), sealed!!.obj("event")["Notifications"])
        assertEquals(JsonNull, sealed!!.obj("event")["Color"])
        assertFalse(sealed!!.containsKey("knownEmptyDetails"))
        assertEquals(port.correlationUid(value), sealed!!.string("uid"))
        assertEquals(LOCAL_CREATE_PREPARATION_FORMAT, port.localCreateFormat)
    }
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
    private var preparedEvent = buildJsonObject { put("fixture", "synthetic") }
    private var preparedDraft: CalendarCreateDraft? = null
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
        CreateContextPreparation { _, _, _, _, _, value -> preparedDraft = value; steps += "prepare"; after("prepare"); ProtonPreparedCreateEvent(preparedEvent) },
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
    @Test fun capturedKnownEmptyProfileHasAnExplicitPersonalOverrideInsideTheEvent() = runTest {
        val timed = EventTime.Timed(Instant.parse("2030-10-01T10:00:00Z"), Instant.parse("2030-10-01T11:00:00Z"), java.time.ZoneId.of("UTC"))
        for (time in listOf(draft.content.time, timed)) {
            val captured = draft.copy(content = draft.content.copy(time = time, details = EventDetails()))
            val receipt = adapter().prepareCreate(captured)
            assertEquals(JsonArray(emptyList()), sealed!!.obj("event")["Notifications"])
            assertEquals(LOCAL_CREATE_PREPARATION_FORMAT, receipt.format)
            assertEquals(2, receipt.format.version)
            assertTrue(sealed!!["knownEmptyDetails"]!!.jsonPrimitive.boolean)
            assertEquals("EXPLICIT_EMPTY", sealed!!.string("notificationPolicy"))
            assertEquals(captured.timestamp.toString(), sealed!!.string("timestamp"))
            assertTrue(sealed!!.containsKey("event"))
            assertFalse(sealed!!.containsKey("request")); assertFalse(sealed!!.containsKey("context"))
            assertFalse(sealed!!.containsKey("Notifications"))
        }
    }
    @Test fun unknownProfileKeepsTheOriginalFragmentWithoutAnInventedPersonalOverride() = runTest {
        val receipt = adapter().prepareCreate(draft)
        assertEquals(CREATE_PREPARATION_FORMAT, receipt.format)
        assertEquals(preparedEvent, sealed!!.obj("event"))
        assertFalse(sealed!!.containsKey("notificationPolicy"))
        assertFalse(sealed!!.containsKey("knownEmptyDetails"))
        assertFalse(sealed!!.obj("event").containsKey("Notifications"))
    }
    @Test fun formatDeclarationIsLocalAndAdvertisesOnlyTheCurrentReadOnlyFormat() {
        assertEquals(AdapterFormat("proton-calendar-local-create-preparation", 2), adapter().localCreateFormat)
        assertNull(adapter(submission = true).localCreateFormat)
        assertEquals(0, sessionReads); assertTrue(steps.isEmpty()); assertNull(sealed)
    }
    @Test fun existingConflictingPersonalDataCannotBeSilentlyReplacedOrSealed() = runTest {
        val captured = draft.copy(content = draft.content.copy(details = EventDetails()))
        for (value in listOf(JsonNull, JsonPrimitive("unknown"),
            Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT15M"}]"""),
            Json.parseToJsonElement("""[{"Type":0,"Trigger":"-PT15M"}]"""))) {
            preparedEvent = buildJsonObject { put("fixture", "synthetic"); put("Notifications", value) }
            sealed = null; steps.clear()
            refused(CreatePreparationFailure.CONTEXT) { adapter().prepareCreate(captured) }
            assertNull(sealed); assertFalse(steps.contains("seal"))
        }
    }
    @Test fun anAlreadyExplicitlyEmptyOverridePreservesAllOtherPreparedFields() = runTest {
        preparedEvent = buildJsonObject { put("fixture", "synthetic"); put("Notifications", JsonArray(emptyList())) }
        adapter().prepareCreate(draft.copy(content = draft.content.copy(details = EventDetails())))
        assertEquals(preparedEvent, sealed!!.obj("event"))
    }
    @Test fun submissionCannotAcceptParticipantsBeforeAnyReads() = runTest {
        for (details in listOf(EventDetails(organizer = EventPerson("synthetic@calendeer.invalid")))) {
            refused(CreatePreparationFailure.UNSUPPORTED) { adapter(submission = true).prepareCreate(draft.copy(content = draft.content.copy(details = details))) }
            assertEquals(0, sessionReads); assertTrue(steps.isEmpty()); assertNull(sealed)
        }
    }
    @Test fun participantsCannotBeReducedToThePreparationSubset() = runTest {
        refused(CreatePreparationFailure.UNSUPPORTED) {
            adapter().prepareCreate(draft.copy(content = draft.content.copy(details = EventDetails(organizer = EventPerson("synthetic@calendeer.invalid")))))
        }
        assertEquals(0, sessionReads); assertTrue(steps.isEmpty()); assertNull(sealed)
    }
    @Test fun richPersonalCreateUsesNewFormatAndKeepsMetadataOutsideSharedCrypto() = runTest {
        val content = draft.content.copy(details = EventDetails(listOf(EventReminder(10), EventReminder(0), EventReminder(-5))), colorRgb = 0x123456)
        val receipt = adapter().prepareCreate(draft.copy(content = content))
        assertEquals(AdapterFormat("proton-calendar-local-create-preparation", 3), receipt.format)
        assertEquals(EventDetails(), preparedDraft!!.content.details)
        assertNull(preparedDraft!!.content.colorRgb)
        assertEquals(content.title, preparedDraft!!.content.title)
        assertEquals("EXPLICIT_PERSONAL", sealed!!.string("notificationPolicy"))
        assertEquals(Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT10M"},{"Type":1,"Trigger":"PT0S"},{"Type":1,"Trigger":"PT5M"}]"""), sealed!!.obj("event")["Notifications"])
        assertEquals(JsonPrimitive("#123456"), sealed!!.obj("event")["Color"])
        assertFalse(sealed!!.containsKey("knownEmptyDetails"))
    }
    @Test fun colorOnlyCreateStillUsesPersonalVersionThreeAndExplicitEmptyReminders() = runTest {
        val receipt = adapter().prepareCreate(draft.copy(content = draft.content.copy(details = EventDetails(), colorRgb = 0)))
        assertEquals(3, receipt.format.version)
        assertEquals(JsonArray(emptyList()), sealed!!.obj("event")["Notifications"])
        assertEquals(JsonPrimitive("#000000"), sealed!!.obj("event")["Color"])
    }
    @Test fun callerReminderMutationDuringContextReadsCannotChangeTheFrozenPreparation() = runTest {
        val reminders = mutableListOf(EventReminder(10))
        val content = draft.content.copy(details = EventDetails(reminders))
        after = { if (it == "refresh") { reminders.clear(); reminders += EventReminder(30) } }
        adapter().prepareCreate(draft.copy(content = content))
        assertEquals(Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT10M"}]"""), sealed!!.obj("event")["Notifications"])
        assertEquals(listOf(EventReminder(30)), reminders)
    }
    private suspend fun refused(reason: CreatePreparationFailure, action: suspend () -> Any) {
        try { action(); fail("Preparation must not escape failed admission") }
        catch (failure: CreatePreparationException) { assertEquals(reason, failure.reason); assertNull(failure.cause) }
    }
}
