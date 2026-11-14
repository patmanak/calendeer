// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated Provider fixtures; never accesses a Proton session or existing calendars. */
class PersonalNativeProfileDeviceTest {
    @Test fun finiteDisplayProofKeepsItsBarrierAndUnknownFiniteVersionsAreUntouched() {
        var mode = EventColorMode.EVENT
        fixture(colorMode = { mode }) {
            val unknown = "calendeer-finite-daily-dates-v1-receipt-v2:${UUID.randomUUID()}"
            mutate(ContentValues().apply { put(Events.DIRTY, 0); put(Events.SYNC_DATA3, unknown) })
            val color = field(Events.EVENT_COLOR); mode = EventColorMode.CALENDAR
            projection.reprojectEventColors(scope)
            assertEquals(unknown, field(Events.SYNC_DATA3)); assertEquals(color, field(Events.EVENT_COLOR))
            val known = unknown.replace("receipt-v2:", "receipt-v1:")
            mutate(ContentValues().apply { put(Events.SYNC_DATA3, known) })
            projection.reprojectEventColors(scope)
            assertNull(field(Events.EVENT_COLOR)); assertTrue(field(Events.SYNC_DATA3)!!.startsWith("calendeer-finite-"))
            try { projection.apply(ProjectionIntent(scope, base)); fail("Finite barrier must remain active") }
            catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
            mode = EventColorMode.EVENT; projection.reprojectEventColors(scope)
            assertEquals(known, field(Events.SYNC_DATA3)); assertEquals(color, field(Events.EVENT_COLOR))
        }
    }
    @Test fun displayRewriteCannotConsumeAConcurrentEditAndLostResponseReplaysWithoutEcho() {
        var mode = EventColorMode.EVENT
        fixture(colorMode = { mode }) {
            mutate(ContentValues().apply { put(Events.DIRTY, 0) }); mode = EventColorMode.CALENDAR
            val racing = colorProjection(before = { mutate(ContentValues().apply {
                put(Events.TITLE, "Concurrent phone edit"); put(Events.DIRTY, 1)
            }) })
            try { racing.reprojectEventColors(scope); fail("Dirty CAS must reject") }
            catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
            assertEquals("1", field(Events.DIRTY)); assertNull(field(Events.SYNC_DATA3))
            assertEquals("Concurrent phone edit", field(Events.TITLE))
            mutate(ContentValues().apply { put(Events.DIRTY, 0) })
            val lost = colorProjection(after = { throw android.os.RemoteException("Synthetic lost response") })
            try { lost.reprojectEventColors(scope); fail("No successful response") }
            catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_UNAVAILABLE, failure.category) }
            val marker = field(Events.SYNC_DATA3); assertNotNull(marker)
            projection.reprojectEventColors(scope)
            assertEquals(marker, field(Events.SYNC_DATA3)); assertNull(field(Events.EVENT_COLOR))
            assertEquals("0", field(Events.DIRTY)); assertEquals("Concurrent phone edit", field(Events.TITLE))
        }
    }
    @Test fun localDisplayRewriteRestoresColorsOfflineWithoutDirtyEchoOrChangingReceiptAndChildren() {
        var mode = EventColorMode.EVENT
        fixture(colorMode = { mode }) {
            for (color in listOf(null, 0, 0xff000000.toInt(), 0xff123456.toInt())) {
                mutate(ContentValues().apply { put(Events.DIRTY, 0); put(Events.EVENT_COLOR, color) })
                val children = reminders(); val revision = field(Events.SYNC_DATA2)
                mode = EventColorMode.CALENDAR
                assertFalse(projection.reprojectEventColors(scope).hasMore)
                assertNull(field(Events.EVENT_COLOR)); assertEquals("0", field(Events.DIRTY))
                assertEquals(if (color == null || color == 0) null else color and 0xffffff,
                    NativeColorStyle.read(field(Events.SYNC_DATA3))!!.colorRgb)
                val masked = field(Events.SYNC_DATA3)
                projection.reprojectEventColors(scope)
                assertEquals(masked, field(Events.SYNC_DATA3))
                mode = EventColorMode.EVENT
                projection.reprojectEventColors(scope)
                assertEquals(if (color == 0) null else color?.toString(), field(Events.EVENT_COLOR))
                assertEquals(revision, field(Events.SYNC_DATA2)); assertNull(field(Events.SYNC_DATA3))
                assertEquals(children, reminders()); assertEquals("0", field(Events.DIRTY))
            }
        }
    }
    @Test fun localDisplayRewritePreservesDirtyUnknownReservedAndUnexpectedColorRows() {
        var mode = EventColorMode.EVENT
        fixture(colorMode = { mode }) {
            mode = EventColorMode.CALENDAR
            val originalColor = field(Events.EVENT_COLOR)
            projection.reprojectEventColors(scope)
            assertEquals(originalColor, field(Events.EVENT_COLOR)); assertEquals("1", field(Events.DIRTY))
            mutate(ContentValues().apply { put(Events.DIRTY, 0); put(Events.SYNC_DATA8, "unknown-profile") })
            projection.reprojectEventColors(scope); assertEquals(originalColor, field(Events.EVENT_COLOR))
            mutate(ContentValues().apply { putNull(Events.SYNC_DATA8); put(Events.SYNC_DATA3, "future-receipt") })
            projection.reprojectEventColors(scope); assertEquals("future-receipt", field(Events.SYNC_DATA3))
            mutate(ContentValues().apply { put(Events.SYNC_DATA3, "|calendeer-color-v2:none") })
            projection.reprojectEventColors(scope); assertEquals(originalColor, field(Events.EVENT_COLOR))
            mutate(ContentValues().apply { putNull(Events.SYNC_DATA3) })
            projection.reprojectEventColors(scope)
            val marker = field(Events.SYNC_DATA3)
            mutate(ContentValues().apply { put(Events.EVENT_COLOR, 0xff654321.toInt()) })
            mode = EventColorMode.EVENT
            projection.reprojectEventColors(scope)
            assertEquals(marker, field(Events.SYNC_DATA3)); assertEquals(0xff654321.toInt().toString(), field(Events.EVENT_COLOR))
        }
    }
    @Test fun pagedDisplayRewriteResumesAndConvergesAfterTheDesiredModeChanges() {
        var mode = EventColorMode.EVENT
        fixture(colorMode = { mode }) {
            mutate(ContentValues().apply { put(Events.DIRTY, 0) })
            repeat(5) { projection.apply(ProjectionIntent(scope, base.copy(eventId = EventId("color-page-$it")))) }
            mode = EventColorMode.CALENDAR
            val first = projection.reprojectEventColors(scope, limit = 2)
            assertTrue(first.hasMore); assertTrue(first.afterRow > 0)
            // A setting change queues another full pass; an older pass never owns the setting.
            mode = EventColorMode.EVENT
            var next = projection.reprojectEventColors(scope, first.afterRow, 2)
            while (next.hasMore) next = projection.reprojectEventColors(scope, next.afterRow, 2)
            assertNull(field(Events.EVENT_COLOR))
            next = projection.reprojectEventColors(scope, limit = 2)
            var pages = 1
            while (next.hasMore) { next = projection.reprojectEventColors(scope, next.afterRow, 2); pages++ }
            assertEquals(3, pages)
            assertEquals((original.colorRgb!! or 0xff000000.toInt()).toString(), field(Events.EVENT_COLOR))
            assertEquals("0", field(Events.DIRTY)); assertNull(field(Events.SYNC_DATA3))
        }
    }
    @Test fun maskedColorPreservesCanonicalColorAcrossTitleEditAcknowledgementAndLaterInspection() =
        fixture(colorMode = { EventColorMode.CALENDAR }) {
            assertNull(field(Events.EVENT_COLOR))
            assertEquals(original.colorRgb, NativeColorStyle.read(field(Events.SYNC_DATA3))!!.colorRgb)
            assertEquals(intent.content, projection.captureOwnedPersonalEdit(scope, base)?.content)
            assertEquals(AppliedPersonalUpdateAcknowledgement(receipt), projection.acknowledgePersonalUpdate(receipt))
            assertNull(field(Events.EVENT_COLOR)); assertEquals("0", field(Events.DIRTY))
            mutate(ContentValues().apply { put(Events.TITLE, "Later synthetic edit"); put(Events.DIRTY, 1) })
            assertEquals(AppliedPersonalUpdateAcknowledgement(receipt), projection.resumePersonalUpdateAcknowledgement(receipt))
            assertEquals("Later synthetic edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        }
    @Test fun explicitPhoneColorOnAMaskedRowStaysUnsupportedAndDirty() =
        fixture(colorMode = { EventColorMode.CALENDAR }) {
            val marker = field(Events.SYNC_DATA3)
            mutate(ContentValues().apply { put(Events.EVENT_COLOR, 0xff654321.toInt()) })
            assertNull(projection.captureOwnedPersonalEdit(scope, base))
            assertNull(projection.acknowledgePersonalUpdate(receipt))
            assertEquals("1", field(Events.DIRTY)); assertEquals(marker, field(Events.SYNC_DATA3))
            assertEquals(0xff654321.toInt().toString(), field(Events.EVENT_COLOR))
        }
    @Test fun desiredModeChangeCannotReinterpretAnOlderMaskedDirtyRowAsAColorClear() {
        var mode = EventColorMode.CALENDAR
        fixture(colorMode = { mode }) {
            mode = EventColorMode.EVENT
            assertEquals(intent.content, projection.captureOwnedPersonalEdit(scope, base)?.content)
            assertEquals(AppliedPersonalUpdateAcknowledgement(receipt), projection.acknowledgePersonalUpdate(receipt))
            assertEquals((original.colorRgb!! or 0xff000000.toInt()).toString(), field(Events.EVENT_COLOR))
            assertFalse(NativeColorStyle.read(field(Events.SYNC_DATA3))!!.masked)
        }
    }
    @Test fun maskedProjectionReceiptRecoversWithoutChangingItsIdentity() =
        fixture(colorMode = { EventColorMode.CALENDAR }) {
            mutate(ContentValues().apply { put(Events.DIRTY, 0) })
            val frozen = receipt.projection
            projection.applyTracked(frozen)
            assertEquals(AppliedProjectionWitness(frozen), projection.inspectProjectionReceipt(frozen))
            assertEquals(frozen.nonce, NativeColorStyle.read(field(Events.SYNC_DATA3))!!.proof!!.substringAfterLast(':'))
            assertNull(field(Events.EVENT_COLOR)); assertEquals("0", field(Events.DIRTY))
        }
    @Test fun reminderOnlyEditsCaptureAndAcknowledgeTheExactChildrenWithoutReplacingThem() {
        for (offsets in listOf(emptyList(), listOf(0, 20), listOf(-10, 5), listOf(-525600, 525600)))
            fixture(simpleTitle = true) {
                context.contentResolver.delete(sync(Reminders.CONTENT_URI), "${Reminders.EVENT_ID}=?", arrayOf(row.toString()))
                offsets.forEach(::addReminder)
                val after = original.copy(details = EventDetails(offsets.map(::EventReminder)))
                mutate(ContentValues().apply { put(Events.TITLE, original.title); put(Events.DIRTY, 1) })
                val changed = intent.copy(content = after)
                val current = receipt.copy(intent = changed, projection = receipt.projection.copy(change = observed.copy(content = after)))
                val children = reminders()
                assertEquals(after, projection.captureOwnedPersonalEdit(scope, base)?.content)
                assertNull(projection.captureOwnedSimpleEdit(scope, base))
                assertNull(projection.acknowledgePersonalUpdate(receipt))
                assertEquals(AppliedPersonalUpdateAcknowledgement(current), projection.acknowledgePersonalUpdate(current))
                assertEquals(children, reminders()); assertEquals("0", field(Events.DIRTY))
                addReminder(25); mutate(ContentValues().apply { put(Events.DIRTY, 1) })
                assertEquals(AppliedPersonalUpdateAcknowledgement(current), projection.resumePersonalUpdateAcknowledgement(current))
                assertEquals("1", field(Events.DIRTY)); assertEquals(children.size + 1, reminders().size)
            }
    }
    @Test fun eventColorAndCalendarFallbackAreCapturedAndAcknowledgedWithoutChangingChildren() {
        for (color in listOf(0, 0x654321, null)) fixture(simpleTitle = true) {
            val after = original.copy(colorRgb = color)
            mutate(ContentValues().apply { put(Events.TITLE, original.title); put(Events.DIRTY, 1)
                if (color == null) putNull(Events.EVENT_COLOR) else put(Events.EVENT_COLOR, color or 0xff000000.toInt()) })
            val changed = intent.copy(content = after)
            val current = receipt.copy(intent = changed, projection = receipt.projection.copy(change = observed.copy(content = after)))
            val children = reminders()
            assertEquals(after, projection.captureOwnedPersonalEdit(scope, base)?.content)
            assertNull(projection.acknowledgePersonalUpdate(receipt))
            assertEquals(AppliedPersonalUpdateAcknowledgement(current), projection.acknowledgePersonalUpdate(current))
            assertEquals(children, reminders()); assertEquals("0", field(Events.DIRTY))
        }
    }
    @Test fun bareLegacyTitleWithUnqualifiedPersonalMarkerBecomesVisibleAndKeepsItsVariant() =
        fixture(richMetadata = false, simpleTitle = true) {
            val token = UUID.randomUUID().toString()
            val name = "bare-provenance-$token.db"
            val preferences = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val wrapped = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
                    val scoped = "bare-provenance-$token-$key"; preferences.add(scoped)
                    return context.getSharedPreferences(scoped, mode)
                }
            }
            val store = ImportStore(wrapped, name)
            val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val remote = object : ReadOnlyCalendarRemote {
                override val format = this@fixture.format
                override suspend fun currentAccount() = scope.account
                override suspend fun fetch(request: PageRequest): RemotePage = error("No service IO in provenance fixture")
                override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No dispatch in provenance fixture")
            }
            val scheduler = object : ImportScheduler {
                override fun cancel() = Unit
                override fun requestLocal() = Unit
                override fun requestNetwork() = Unit
                override fun continueWork(network: Boolean) = Unit
            }
            val owner = ImportCoordinator(wrapped, store, remote, projection, jobs, scheduler,
                systemCreatesEnabled = { true }, systemTitlesEnabled = { true }, systemTextsEnabled = { true }, systemTimesEnabled = { true })
            try {
                store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic provenance fixture")))
                store.setEnabled(scope, true)
                store.transaction(scope) { checkpoint = PageCheckpoint(scope, format); putRemote(base); putProjection(ProjectionIntent(scope, base)) }
                assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
                assertEquals(intent, store.captureLocalEdit(intent))
                mutate(ContentValues().apply { put(Events.SYNC_DATA8, "synthetic-unqualified-marker") })
                val inspection = AndroidCalendarProjection(context)
                assertNotNull(inspection.captureOwnedSimpleEdit(scope, base))
                assertNull(inspection.captureOwnedPersonalEdit(scope, base))
                owner.authenticationChanged(true)
                withTimeout(10_000) { while (!owner.state.value.selectionsLoaded) delay(10) }
                assertFalse(store.hasUnsupportedLegacyUpdates(scope))
                val run = owner.systemPersonalRunAdmission(scope)
                assertNull(owner.personalOwners.observe(scope, base.eventId, intent.id, run))
                val reference = RetainedLocalChangeRef(base.eventId, intent.id, MutationKind.UPDATE, LocalEditState.PAUSED)
                assertTrue(owner.retainUnsupportedBarePersonalUpdate(scope, reference, run, owner.systemPersonalConsentAdmission(scope)))
                assertTrue(store.hasUnsupportedLegacyUpdates(scope))
                ImportStore(wrapped, name).use { reopened ->
                    assertEquals(intent.copy(state = LocalEditState.CONFLICT), reopened.localEdit(scope, base.eventId))
                    assertTrue(reopened.hasUnsupportedLegacyUpdates(scope))
                    assertNull(reopened.personalEditPreparation(scope, base.eventId))
                }
                assertEquals(intent.content.title, field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
                assertEquals("synthetic-unqualified-marker", field(Events.SYNC_DATA8))
            } finally {
                owner.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
                store.close(); context.deleteDatabase(name)
                preferences.toList().forEach { context.deleteSharedPreferences(it) }
            }
        }
    @Test fun personalAllDayAcceptsFixedUtcEndAliasesWithoutChangingRawZonesOrLegacyCapture() {
        for (zone in listOf("Z", "Etc/UTC", "GMT")) fixture {
            mutate(ContentValues().apply { put(Events.EVENT_END_TIMEZONE, zone) })
            assertNull(projection.captureOwnedSimpleEdit(scope, base))
            assertEquals(intent.content, projection.captureOwnedPersonalEdit(scope, base)?.content)
            assertEquals(AppliedPersonalUpdateAcknowledgement(receipt), projection.acknowledgePersonalUpdate(receipt))
            assertEquals(zone, field(Events.EVENT_END_TIMEZONE)); assertEquals("UTC", field(Events.EVENT_TIMEZONE))
            assertEquals("0", field(Events.DIRTY))
        }
    }
    @Test fun personalAllDayRejectsNonUtcSeasonalAndInvalidEndZones() = fixture {
        for (zone in listOf("Europe/London", "Europe/Paris", "+01:00", "Invalid/Fixture")) {
            mutate(ContentValues().apply { put(Events.EVENT_END_TIMEZONE, zone) })
            assertNull(projection.captureOwnedPersonalEdit(scope, base))
            assertNull(projection.acknowledgePersonalUpdate(receipt))
            assertEquals("1", field(Events.DIRTY)); assertEquals(zone, field(Events.EVENT_END_TIMEZONE))
        }
        mutate(ContentValues().apply { putNull(Events.EVENT_END_TIMEZONE) })
        assertNull(projection.captureOwnedPersonalEdit(scope, base))
        assertEquals("1", field(Events.DIRTY))
    }
    @Test fun otherwiseQualifiedLegacySnapshotKeepsItsOriginalUtcAliasPolicy() = fixture(richMetadata = false) {
        assertEquals(intent.content, projection.captureOwnedSimpleEdit(scope, base)?.content)
        mutate(ContentValues().apply { put(Events.EVENT_END_TIMEZONE, "Z") })
        assertNull(projection.captureOwnedSimpleEdit(scope, base))
        assertEquals(intent.content, projection.captureOwnedPersonalEdit(scope, base)?.content)
    }
    @Test fun otherwiseQualifiedTimedPersonalSnapshotDoesNotGainTheAllDayException() = fixture {
        val timed = original.time as EventTime.Timed
        mutate(ContentValues().apply {
            put(Events.ALL_DAY, 0); put(Events.DTSTART, timed.start.toEpochMilli()); put(Events.DTEND, timed.end.toEpochMilli())
            put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
        })
        assertNotNull(projection.captureOwnedPersonalEdit(scope, base))
        mutate(ContentValues().apply { put(Events.EVENT_END_TIMEZONE, "Z") })
        assertNull(projection.captureOwnedPersonalEdit(scope, base)); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun interruptedPendingAckFinishesOnceThenPreservesLaterNativeEdit() = fixture {
        assertEquals(1, projection.personalUpdateAcknowledgementRecoveryProtocol)
        assertNull(projection.inspectPersonalUpdateAcknowledgement(receipt))
        val children = reminders()
        assertEquals(AppliedPersonalUpdateAcknowledgement(receipt), projection.resumePersonalUpdateAcknowledgement(receipt))
        assertEquals("0", field(Events.DIRTY)); assertEquals(children, reminders())
        mutate(ContentValues().apply { put(Events.TITLE, "Newer local content"); put(Events.DIRTY, 1) })
        assertEquals(AppliedPersonalUpdateAcknowledgement(receipt), projection.resumePersonalUpdateAcknowledgement(receipt))
        assertEquals("Newer local content", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        assertEquals(children, reminders())
    }
    @Test fun interruptedPendingAckCannotConsumeAChangedNativeOwner() = fixture {
        mutate(ContentValues().apply { put(Events.TITLE, "Different edit before ACK") })
        assertNull(projection.resumePersonalUpdateAcknowledgement(receipt))
        assertEquals("Different edit before ACK", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        assertNull(projection.inspectPersonalUpdateAcknowledgement(receipt))
    }
    @Test fun combinedKindChangeCapturesAndAcknowledgesWithoutReplacingReminders() = fixture {
        assertNull(projection.captureOwnedSimpleEdit(scope, base))
        assertEquals(intent.content, projection.captureOwnedPersonalEdit(scope, base)!!.content)
        val children = reminders()
        assertEquals(AppliedPersonalUpdateAcknowledgement(receipt), projection.acknowledgePersonalUpdate(receipt))
        assertEquals(children, reminders())
        assertEquals("0", field(Events.DIRTY))
        assertEquals("Personal\nedit", field(Events.TITLE))
        assertEquals("1", field(Events.ALL_DAY))
        assertEquals("UTC", field(Events.EVENT_TIMEZONE))
        assertEquals((0xff000000.toInt() or 0x123456).toString(), field(Events.EVENT_COLOR))
        assertEquals("1", field(Events.HAS_ALARM))
        mutate(ContentValues().apply { put(Events.TITLE, "Later edit"); put(Events.DIRTY, 1) })
        assertEquals(AppliedPersonalUpdateAcknowledgement(receipt), projection.inspectPersonalUpdateAcknowledgement(receipt))
        assertEquals("Later edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        assertNull(projection.acknowledgePersonalUpdate(receipt))
    }
    @Test fun changedReminderOffsetsAreCapturedButCannotUseThePreviousAcknowledgement() = fixture {
        for ((column, value) in listOf(Reminders.MINUTES to 11, Reminders.METHOD to Reminders.METHOD_EMAIL)) {
            context.contentResolver.update(sync(Reminders.CONTENT_URI), ContentValues().apply { put(column, value) },
                "${Reminders.EVENT_ID}=?", arrayOf(row.toString()))
            if (column == Reminders.METHOD) assertNull(projection.captureOwnedPersonalEdit(scope, base))
            else assertEquals(listOf(EventReminder(11)), projection.captureOwnedPersonalEdit(scope, base)?.content?.details?.reminders)
            assertNull(projection.acknowledgePersonalUpdate(receipt)); assertEquals("1", field(Events.DIRTY))
            context.contentResolver.update(sync(Reminders.CONTENT_URI), ContentValues().apply {
                put(Reminders.MINUTES, 10); put(Reminders.METHOD, Reminders.METHOD_ALERT)
            }, "${Reminders.EVENT_ID}=?", arrayOf(row.toString()))
        }
        addReminder(20)
        assertEquals(listOf(EventReminder(10), EventReminder(20)), projection.captureOwnedPersonalEdit(scope, base)?.content?.details?.reminders)
        assertNull(projection.acknowledgePersonalUpdate(receipt)); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun unknownDetailsParticipantsAndChangedMetadataStayUnqualified() = fixture {
        assertNull(projection.captureOwnedPersonalEdit(scope, base.copy(content = original.copy(details = null))))
        val attendee = context.contentResolver.insert(sync(Attendees.CONTENT_URI), ContentValues().apply {
            put(Attendees.EVENT_ID, row); put(Attendees.ATTENDEE_EMAIL, "fixture@example.invalid")
            put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
            put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
        })!!
        assertNull(projection.captureOwnedPersonalEdit(scope, base))
        assertNull(projection.acknowledgePersonalUpdate(receipt))
        context.contentResolver.delete(attendee, null, null)
        // HAS_ALARM is Provider-maintained; reminders above exercise its real child path.
        for ((column, value) in listOf(Events.EVENT_COLOR to 0xff654321.toInt())) {
            val old = field(column)!!
            mutate(ContentValues().apply { put(column, value) })
            assertEquals(0x654321, projection.captureOwnedPersonalEdit(scope, base)?.content?.colorRgb)
            assertNull(projection.acknowledgePersonalUpdate(receipt))
            assertEquals("1", field(Events.DIRTY))
            mutate(ContentValues().apply { put(column, old.toInt()) })
        }
    }
    @Test fun reminderReplacementBetweenReadAndBatchAbortsEvenWithSameCountAndOffset() = fixture {
        val real = context.contentResolver
        var replaced = false
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) = real.query(uri, columns, selection, args, sort)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected insert")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Unexpected update")
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Unexpected delete")
            override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                if (!replaced) {
                    replaced = true
                    // Insert first, forcing a different child ID before removing the original.
                    val replacement = addReminder(10)
                    real.delete(sync(Reminders.CONTENT_URI), "${Reminders.EVENT_ID}=? AND ${Reminders._ID}!=?",
                        arrayOf(row.toString(), ContentUris.parseId(replacement).toString()))
                }
                return real.applyBatch(CalendarContract.AUTHORITY, operations)
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = ContentResolver.wrap(provider)
        }
        try { AndroidCalendarProjection(wrapped).acknowledgePersonalUpdate(receipt); fail("Changed child identity must abort") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
        assertTrue(replaced); assertEquals("1", field(Events.DIRTY))
        assertEquals("Personal\r\nedit", field(Events.TITLE)); assertEquals(1, reminders().size)
    }

    private class Fixture(val context: Context, richMetadata: Boolean = true, val simpleTitle: Boolean = false,
        val colorMode: () -> EventColorMode = { EventColorMode.EVENT }) {
        val scope = CalendarScope(AccountId("personal-native-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val projection = AndroidCalendarProjection(context, colorMode)
        val format = AdapterFormat("synthetic-personal-native", 1)
        val original = EventContent("Original", EventTime.Timed(Instant.parse("2030-05-01T09:00:00Z"),
            Instant.parse("2030-05-01T10:00:00Z"), ZoneId.of("Europe/Paris")), colorRgb = if (richMetadata) 0x123456 else null,
            details = EventDetails(reminders = if (richMetadata) listOf(EventReminder(10)) else emptyList()))
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("old"), original)
        val intent = LocalEditIntent(MutationId("local"), scope, format, base, if (simpleTitle) original.copy(title = "Personal\r\nedit") else
            original.copy(title = "Personal\r\nedit", description = "New detail", location = "New place",
                time = EventTime.AllDay(java.time.LocalDate.of(2030, 5, 2), java.time.LocalDate.of(2030, 5, 4))))
        val observed = base.copy(revision = Revision("new"), content = intent.content.copy(title = "Personal\nedit"))
        val receipt = PersonalUpdateAcknowledgementReceipt(PersonalUpdateAttemptIdentity(scope, MutationId("attempt"), intent.id,
            base.eventId, base.revision, format, format, "a".repeat(64), "b".repeat(64)), intent,
            ProjectionReceipt(scope, format, observed, UUID.randomUUID().toString(), base))
        var row = -1L
        fun sync(uri: Uri): Uri = projection.accountFor(scope.account).let { uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, it.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, it.type).build() }
        fun mutate(values: ContentValues) { assertEquals(1, context.contentResolver.update(sync(Events.CONTENT_URI), values, "${Events._ID}=?", arrayOf(row.toString()))) }
        fun field(column: String): String? = context.contentResolver.query(sync(Events.CONTENT_URI), arrayOf(column), "${Events._ID}=?", arrayOf(row.toString()), null)!!.use { check(it.moveToFirst()); it.getString(0) }
        fun reminders(): List<List<String?>> = context.contentResolver.query(sync(Reminders.CONTENT_URI), arrayOf(Reminders._ID, Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID}=?", arrayOf(row.toString()), Reminders._ID)!!.use { cursor -> buildList { while(cursor.moveToNext()) add((0..3).map { cursor.getString(it) }) } }
        fun addReminder(minutes: Int): Uri = context.contentResolver.insert(sync(Reminders.CONTENT_URI), ContentValues().apply { put(Reminders.EVENT_ID, row); put(Reminders.MINUTES, minutes); put(Reminders.METHOD, Reminders.METHOD_ALERT) })!!
        fun colorProjection(before: () -> Unit = {}, after: () -> Unit = {}): AndroidCalendarProjection {
            val real = context.contentResolver
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Use batch")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Use batch")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Use batch")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?) =
                    real.query(uri, columns, selection, args, order)
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    before(); val result = real.applyBatch(CalendarContract.AUTHORITY, operations); after(); return result
                }
            }
            val wrapped = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = ContentResolver.wrap(provider)
            }
            return AndroidCalendarProjection(wrapped, colorMode)
        }
        suspend fun seed() {
            val calendar = projection.ensureCalendarState(scope, CalendarSummary(scope.calendar, "Synthetic personal fixture"), true).id
            projection.apply(ProjectionIntent(scope, base))
            row = context.contentResolver.query(sync(Events.CONTENT_URI), arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { check(it.count == 1 && it.moveToFirst()); it.getLong(0) }
            mutate(ContentValues().apply {
                put(Events.TITLE, intent.content.title); put(Events.DESCRIPTION, intent.content.description)
                put(Events.EVENT_LOCATION, intent.content.location); put(Events.DIRTY, 1)
                if (!simpleTitle) {
                    put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
                    val time = intent.content.time as EventTime.AllDay
                    put(Events.DTSTART, time.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.DTEND, time.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                }
            })
        }
        suspend fun close() {
            AndroidCalendarProjection(context).removeOwnedCalendar(scope)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(scope.account))
        }
    }
    private fun fixture(richMetadata: Boolean = true, simpleTitle: Boolean = false,
        colorMode: () -> EventColorMode = { EventColorMode.EVENT }, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), richMetadata, simpleTitle, colorMode)
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
