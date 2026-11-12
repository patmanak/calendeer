// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateUiState
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.*
import com.patmanak.proton.andcalendarbridge.ui.calendars.*
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic local reader only: no production session, database, Provider or network access. */
@RunWith(AndroidJUnit4::class)
class ConflictArchiveUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val calendar = CalendarScope(AccountId("archive-ui-account"), CalendarId("archive-ui-calendar"), 0)
    private val selectedScope = mutableStateOf(calendar)
    private val visible = mutableStateOf(true)
    private val reader = FakeReader()
    private var closed = 0
    private fun ref(id: Long, scope: CalendarScope = calendar, kind: MutationKind = MutationKind.UPDATE) =
        ConflictArchiveRef(scope, id, EventId("hidden-event-$id"), MutationId("hidden-mutation-$id"), kind)
    private fun detail(ref: ConflictArchiveRef) = ConflictArchiveDetail(ref, AdapterFormat("fixture", 1),
        RemoteChange.Upsert(ref.eventId, Revision("hidden-revision"), content("Retained baseline title")),
        content("Retained local title"))
    private fun content(title: String) = EventContent(title,
        EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneOffset.UTC),
        description = "Synthetic retained description", location = "Synthetic location")

    private fun start(fontScale: Float = 1f) {
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    if (visible.value) ConflictArchivePanel(selectedScope.value, reader,
                        onClose = { closed++; visible.value = false }, modifier = Modifier.width(320.dp).height(540.dp))
                }
            }
        }
    }

    @Test fun emptyErrorsInactiveAndMissingDetailRemainDistinct() {
        reader.listBlock = { ConflictArchiveRead.Available(ConflictArchivePage(it.scope, emptyList(), null)) }
        start()
        compose.onNodeWithTag(ConflictArchiveTags.EMPTY).assertExists()
        reader.listBlock = { ConflictArchiveRead.Unavailable }
        click(ConflictArchiveTags.REFRESH)
        compose.onNodeWithTag(ConflictArchiveTags.ERROR).assertExists()
        compose.onNodeWithTag(ConflictArchiveTags.EMPTY).assertDoesNotExist()
        reader.listBlock = { ConflictArchiveRead.Inactive }
        click(ConflictArchiveTags.REFRESH)
        compose.onNodeWithTag(ConflictArchiveTags.INACTIVE).assertExists()
        reader.listBlock = { ConflictArchiveRead.Unqualified }
        click(ConflictArchiveTags.REFRESH)
        compose.onNodeWithText(text(R.string.conflict_archive_unknown_calendar)).assertExists()
        reader.listBlock = { ConflictArchiveRead.Available(ConflictArchivePage(calendar, listOf(ref(1)), null)) }
        reader.detailBlock = { ConflictArchiveRead.Unqualified }
        click(ConflictArchiveTags.REFRESH)
        click(ConflictArchiveTags.ENTRY)
        compose.onNodeWithTag(ConflictArchiveTags.MISSING).assertExists()
        compose.onNodeWithTag(ConflictArchiveTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(ConflictArchiveTags.REFRESH).assertIsEnabled()
    }

    @Test fun archiveAndObservationPagesReplaceTheirPredecessors() {
        val first = ref(1); val second = ref(2, kind = MutationKind.DELETE)
        val lists = mutableListOf<ConflictArchiveQuery>()
        val observations = mutableListOf<ConflictObservationQuery>()
        reader.listBlock = { query ->
            lists += query
            ConflictArchiveRead.Available(if (query.cursor == null)
                ConflictArchivePage(calendar, listOf(first), ConflictArchiveCursor(calendar, first.id))
            else ConflictArchivePage(calendar, listOf(second), null))
        }
        reader.detailBlock = { assertEquals(second, it); ConflictArchiveRead.Available(detail(it)) }
        reader.observationsBlock = { query ->
            observations += query
            ConflictArchiveRead.Available(if (query.cursor == null) ConflictObservationPage(second,
                listOf(ConflictObservation(1, ConflictObservationKind.VERIFIED, ConflictObservationOrigin.CACHED,
                    null, AdapterFormat("fixture", 1), RemoteChange.Upsert(second.eventId, Revision("remote-one"),
                        content("Earlier observed title")), null)), ConflictObservationCursor(second, 1))
            else ConflictObservationPage(second, listOf(ConflictObservation(2, ConflictObservationKind.UNOBSERVED,
                ConflictObservationOrigin.CACHED, null, null, null, null)), null))
        }
        start()
        click(ConflictArchiveTags.NEXT)
        compose.onAllNodesWithTag(ConflictArchiveTags.ENTRY).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.conflict_archive_local_edit)).assertDoesNotExist()
        click(ConflictArchiveTags.ENTRY)
        compose.onNodeWithText("Earlier observed title").performScrollTo().assertIsDisplayed()
        click(ConflictArchiveTags.NEXT)
        compose.onNodeWithText("Earlier observed title").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.conflict_archive_unobserved)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(ConflictArchiveTags.OBSERVATION).assertCountEquals(1)
        compose.runOnIdle {
            assertEquals(listOf(null, 1L), lists.map { it.cursor?.afterId })
            assertEquals(listOf(null, 1L), observations.map { it.cursor?.afterId })
            assertTrue(lists.all { it.limit == 20 }); assertTrue(observations.all { it.limit == 20 })
        }
    }

    @Test fun lateReplyFromPreviousScopeCannotPopulateNewCalendar() {
        val started = CompletableDeferred<Unit>()
        val oldReply = CompletableDeferred<ConflictArchiveRead<ConflictArchivePage>>()
        val nextScope = calendar.copy(generation = 1)
        reader.listBlock = { query ->
            if (query.scope == calendar) {
                started.complete(Unit)
                withContext(NonCancellable) { oldReply.await() }
            } else ConflictArchiveRead.Available(ConflictArchivePage(nextScope,
                listOf(ref(2, nextScope, MutationKind.DELETE)), null))
        }
        start()
        compose.waitUntil { started.isCompleted }
        compose.onNodeWithTag(ConflictArchiveTags.LOADING).assertExists()
        compose.runOnIdle { selectedScope.value = nextScope }
        compose.onNodeWithText(text(R.string.conflict_archive_local_delete)).assertExists()
        compose.runOnIdle { oldReply.complete(ConflictArchiveRead.Available(ConflictArchivePage(calendar, listOf(ref(1)), null))) }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.conflict_archive_local_edit)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.conflict_archive_local_delete)).assertExists()
    }

    @Test fun closingDuringDelayedDetailDiscardsItWithoutReadingObservations() {
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<ConflictArchiveRead<ConflictArchiveDetail>>()
        val target = ref(1)
        var observationsRead = 0
        reader.listBlock = { ConflictArchiveRead.Available(ConflictArchivePage(calendar, listOf(target), null)) }
        reader.detailBlock = { started.complete(Unit); withContext(NonCancellable) { reply.await() } }
        reader.observationsBlock = { observationsRead++; ConflictArchiveRead.Available(ConflictObservationPage(target, emptyList(), null)) }
        start(); click(ConflictArchiveTags.ENTRY)
        compose.waitUntil { started.isCompleted }
        click(ConflictArchiveTags.CLOSE)
        compose.runOnIdle { reply.complete(ConflictArchiveRead.Available(detail(target))) }
        compose.waitForIdle()
        compose.onNodeWithTag(ConflictArchiveTags.PANEL).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closed); assertEquals(0, observationsRead) }
    }

    @Test fun wrongScopeReplyIsUnavailableRatherThanAnEmptyOrForeignList() {
        val other = calendar.copy(calendar = CalendarId("other-calendar"))
        reader.listBlock = { ConflictArchiveRead.Available(ConflictArchivePage(other, listOf(ref(1, other)), null)) }
        start()
        compose.onNodeWithTag(ConflictArchiveTags.ERROR).assertExists()
        compose.onNodeWithTag(ConflictArchiveTags.EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(ConflictArchiveTags.ENTRY).assertDoesNotExist()
    }

    @Test fun pausedUnavailableCalendarWithNoCurrentConflictCounterStillOpensItsArchive() {
        val selection = ImportStore.Selection(calendar, CalendarSummary(calendar.calendar, "Synthetic calendar"),
            enabled = false, status = "PAUSED", completedAt = null, available = false)
        var opened: ImportStore.Selection? = null
        compose.setContent {
            MaterialTheme {
                CalendarDashboard(CalendarCatalogState(CalendarCatalogStatus.READY, emptyList(), account = calendar.account),
                    ImportUiState(listOf(selection), hasPermission = false, authenticated = true), SystemCreateUiState(),
                    onRefresh = { error("Archive navigation must not refresh Proton") },
                    onSetEnabled = { _, _ -> error("Archive navigation must not change import mode") },
                    onSync = { error("Archive navigation must not schedule synchronization") },
                    onRequestPermission = { error("Archive navigation must not request permission") },
                    onOpenSettings = { error("Archive navigation must not open settings") },
                    onMode = { _, _ -> error("Archive navigation must not enable creation") },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    onOpenConflicts = { opened = it })
            }
        }
        click(CalendarDashboardTags.OPTIONS)
        compose.onNodeWithTag(ConflictArchiveTags.OPEN).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(selection, opened) }
    }

    @Test fun reminderAndColorOnlyDifferencesExplicitlyWarnThatVisibleFieldsAreNotTheWholeVersion() {
        val target = ref(1)
        val original = content("Identical visible title").copy(details = EventDetails())
        val base = RemoteChange.Upsert(target.eventId, Revision("base"), original)
        val local = original.copy(details = EventDetails(reminders = listOf(EventReminder(15))))
        val remote = base.copy(revision = Revision("color-only"), content = original.copy(colorRgb = 0x3366FF))
        reader.listBlock = { ConflictArchiveRead.Available(ConflictArchivePage(calendar, listOf(target), null)) }
        reader.detailBlock = { ConflictArchiveRead.Available(ConflictArchiveDetail(target, AdapterFormat("fixture", 1), base, local)) }
        reader.observationsBlock = { ConflictArchiveRead.Available(ConflictObservationPage(target,
            listOf(ConflictObservation(1, ConflictObservationKind.VERIFIED, ConflictObservationOrigin.CACHED,
                null, AdapterFormat("fixture", 1), remote, null)), null)) }
        start(); click(ConflictArchiveTags.ENTRY)
        compose.onNodeWithText(text(R.string.conflict_archive_partial_comparison)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Identical visible title").assertCountEquals(3)
        compose.onAllNodesWithText(text(R.string.conflict_archive_hidden_properties)).assertCountEquals(2)
        compose.onAllNodesWithText(text(R.string.conflict_archive_hidden_properties))[0].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.conflict_archive_hidden_properties))[1].performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conflict_archive_unknown_details)).assertDoesNotExist()
    }

    @Test fun extremeEventAndCollectionDatesKeepTheRecordReadableWithoutCrashing() {
        val target = ref(1)
        val base = content("Extreme retained title").copy(time = EventTime.Timed(
            Instant.MAX.minusSeconds(120), Instant.MAX.minusSeconds(60), ZoneOffset.UTC))
        val local = base.copy(time = EventTime.Timed(Instant.MIN, Instant.MIN.plusSeconds(60), ZoneOffset.UTC))
        reader.listBlock = { ConflictArchiveRead.Available(ConflictArchivePage(calendar, listOf(target), null)) }
        reader.detailBlock = { ConflictArchiveRead.Available(ConflictArchiveDetail(target, AdapterFormat("fixture", 1),
            RemoteChange.Upsert(target.eventId, Revision("extreme-retained"), base), local)) }
        reader.observationsBlock = { ConflictArchiveRead.Available(ConflictObservationPage(target,
            listOf(ConflictObservation(1, ConflictObservationKind.DEFERRED, ConflictObservationOrigin.RECEIVED,
                Instant.MAX, AdapterFormat("fixture", 1), null, DeferredEventReason.UNVERIFIED_CONTENT)), null)) }
        start(); click(ConflictArchiveTags.ENTRY)
        compose.onAllNodesWithText("Extreme retained title").assertCountEquals(2)
        compose.onAllNodesWithText(text(R.string.conflict_archive_event_date_unavailable)).assertCountEquals(2)
        compose.onNodeWithText(text(R.string.conflict_archive_collection_date_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conflict_archive_unverified)).performScrollTo().assertIsDisplayed()
        click(ConflictArchiveTags.CLOSE)
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun unreadableCachedObservationHasItsOwnMarkerWithoutExposingPrivateStoredContent() {
        val target = ref(1)
        // This synthetic private cache belongs to the fake reader, never to its public result.
        val privateCache = mapOf(target to "{broken synthetic-private-cached-payload")
        val privateFormat = AdapterFormat("synthetic-private-cache-format", 1)
        reader.listBlock = { ConflictArchiveRead.Available(ConflictArchivePage(calendar, listOf(target), null)) }
        reader.detailBlock = { ConflictArchiveRead.Available(detail(it)) }
        reader.observationsBlock = { query ->
            check(privateCache.containsKey(query.ref))
            ConflictArchiveRead.Available(ConflictObservationPage(target,
                listOf(ConflictObservation(1, ConflictObservationKind.UNREADABLE, ConflictObservationOrigin.CACHED,
                    null, privateFormat, null, null)), null))
        }
        start(); click(ConflictArchiveTags.ENTRY)
        compose.onNodeWithText(text(R.string.conflict_archive_unreadable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conflict_archive_cached)).assertExists()
        compose.onNodeWithText(text(R.string.conflict_archive_unobserved)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.conflict_archive_unverified)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.conflict_archive_recorded_remote)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.conflict_archive_collection_date_unavailable)).assertDoesNotExist()
        compose.onAllNodesWithTag(ConflictArchiveTags.SNAPSHOT).assertCountEquals(2)
        for (privateValue in listOf(privateCache.getValue(target), privateFormat.id, target.eventId.value, target.mutationId.value))
            compose.onNodeWithText(privateValue, substring = true).assertDoesNotExist()
    }

    @Test fun largeFontRetainsReadableHistoryAndCloseWithoutEditControlsOrIdentifiers() {
        val target = ref(1)
        reader.listBlock = { ConflictArchiveRead.Available(ConflictArchivePage(calendar, listOf(target), null)) }
        reader.detailBlock = { ConflictArchiveRead.Available(detail(it)) }
        reader.observationsBlock = { ConflictArchiveRead.Available(ConflictObservationPage(target,
            listOf(ConflictObservation(1, ConflictObservationKind.DEFERRED, ConflictObservationOrigin.RECEIVED,
                Instant.parse("2030-10-28T12:00:00Z"), AdapterFormat("fixture", 1), null,
                DeferredEventReason.UNVERIFIED_CONTENT)), null)) }
        start(fontScale = 1.8f)
        click(ConflictArchiveTags.ENTRY)
        compose.onNodeWithText("Retained local title").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.conflict_archive_unknown_details)).assertCountEquals(2)
        compose.onNodeWithText(text(R.string.conflict_archive_unverified)).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(0)
        for (privateValue in listOf(target.eventId.value, target.mutationId.value, calendar.account.value, "hidden-revision"))
            compose.onNodeWithText(privateValue).assertDoesNotExist()
        click(ConflictArchiveTags.CLOSE)
        compose.runOnIdle { assertEquals(1, closed) }
    }

    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun text(resource: Int) = compose.activity.getString(resource)

    private class FakeReader : ConflictArchiveReader {
        var listBlock: suspend (ConflictArchiveQuery) -> ConflictArchiveRead<ConflictArchivePage> = { ConflictArchiveRead.Unavailable }
        var detailBlock: suspend (ConflictArchiveRef) -> ConflictArchiveRead<ConflictArchiveDetail> = { ConflictArchiveRead.Unavailable }
        var observationsBlock: suspend (ConflictObservationQuery) -> ConflictArchiveRead<ConflictObservationPage> = { ConflictArchiveRead.Unavailable }
        override suspend fun list(query: ConflictArchiveQuery) = listBlock(query)
        override suspend fun detail(ref: ConflictArchiveRef) = detailBlock(ref)
        override suspend fun observations(query: ConflictObservationQuery) = observationsBlock(query)
    }
}
