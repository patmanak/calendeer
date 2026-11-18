// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonScopedOccurrenceProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** The operator creates a synthetic signed series and edits one instance in the calendar client.
 * SEND is single-use. INSPECT cannot prepare, claim or submit. No calendar settings are changed. */
class CalendarScopedOccurrenceLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun qualifyOneRetainedClientEdit() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("occurrenceLivePhase")
        assumeTrue(phase == "SEND" || phase == "INSPECT")
        val token = args.getString("occurrenceFixture").orEmpty()
        require(token.matches(Regex("[a-f0-9]{12}")))
        fun hashArg(key: String) = args.getString(key).orEmpty().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val accountHash = hashArg("occurrenceAccountHash")
        val calendarHash = hashArg("occurrenceCalendarHash")
        val masterHash = hashArg("occurrenceMasterHash")
        val revisionHash = hashArg("occurrenceRevisionHash")
        val originalSlot = requireNotNull(args.getString("occurrenceOriginalUtc")).let(Instant::parse)
        val timestamp = requireNotNull(args.getString("occurrenceCaptureUtc")).let(Instant::parse)
        val generation = requireNotNull(args.getString("occurrenceGeneration")).toLong()
        require(originalSlot.nano == 0 && timestamp.nano == 0)
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty() }
        val target = app.imports.state.value.selections.single { it.enabled && it.available &&
            it.scope.generation == generation && digest(it.scope.calendar.value) == calendarHash }.scope
        check(ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
        check(SystemCreateWorkStore(app).use { it.modeEnabled(target) })
        fun id(kind: String) = UUID.nameUUIDFromBytes("calendeer-occurrence-$kind-$token".toByteArray(Charsets.UTF_8)).toString()
        val local = LocalEventId(id("local"))
        val capture = MutationId(id("capture"))
        val attempt = MutationId(id("attempt"))
        val master = ImportStore(app).use { store ->
            store.admitTransaction = { it == target }
            var after: EventId? = null
            var found: RemoteChange.Upsert? = null
            while (true) {
                val page = store.projectedEventIds(target, after, 100)
                for (event in page) if (digest(event.value) == masterHash) {
                    check(found == null)
                    found = checkNotNull(store.stabilizedProjection(target, event))
                }
                if (page.size < 100) break
                after = page.last()
            }
            checkNotNull(found)
        }
        check(master.content.title == "Calendeer series $token" && digest(master.revision.value) == revisionHash)
        val existing = CreateAttemptStore(app).use { it.read(target, attempt) }
        val intent = LocalCreateStore(app).use { captures ->
            captures.admitCapture = { it == target }
            if (phase == "SEND") {
                check(existing == null && captures.readReservation(target, local) == null)
                val row = requireNotNull(args.getString("occurrenceChildRow")).toLong()
                val native = AndroidCalendarProjection(app)
                native.admitOperation = { itIsCurrent(app, target) }
                val before = checkNotNull(native.captureOwnedOccurrenceEdit(target, master, row)).candidate
                check(before.original == RecurrencePoint.Timed(originalSlot) && before.content.title == "Calendeer exception $token")
                check(app.imports.captureLocalOccurrence(target, master, row, local, capture, timestamp) == LocalEditCaptureResult.RETAINED)
                check(captures.readIntent(target, local)?.occurrence == before)
            } else check(existing?.state == CreateAttemptState.OUTCOME_UNKNOWN)
            checkNotNull(captures.readIntent(target, local)).also {
                check(it.reservation.id == capture && it.reservation.timestamp == timestamp && it.occurrence?.master == master &&
                    it.occurrence?.original == RecurrencePoint.Timed(originalSlot) && it.occurrence?.content?.title == "Calendeer exception $token")
            }
        }
        val port = ProtonScopedOccurrenceProbe.submission(app, intent, attempt, accountHash)
        if (phase == "SEND") {
            check(app.imports.stageLocalCreateSubmission(target, local, capture, attempt, port) == LocalCreateSubmissionResult.RETAINED)
            app.imports.submitLocalCreate(target, local, capture, attempt, port) { itIsCurrent(app, target) }
        }
        val role = CreateAttemptStore(app).use { journal ->
            check(journal.read(target, attempt)?.state == CreateAttemptState.OUTCOME_UNKNOWN)
            journal.capturedBinding(checkNotNull(journal.capturedIdentity(target, attempt)))?.let { binding ->
                ImportStore(app).use { store -> store.admitTransaction = { it == target }; store.capturedHandoff(target, binding.eventId) }
            }
        }
        if (role == null) {
            assertEquals(CapturedCreateBindingResult.RETAINED, app.imports.bindLocalCreateObservation(target, local, capture, attempt, port))
            assertEquals(CapturedCreateAttachmentResult.ATTACHED, app.imports.attachLocalCreateBinding(target, local, capture, attempt))
            assertEquals(CapturedCreateBaselineResult.RETAINED, app.imports.retainLocalCreateBaseline(target, local, capture, attempt))
            assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, app.imports.acknowledgeLocalCreate(target, local, capture, attempt))
            assertEquals(CapturedCreateCleanResult.CLEAN, app.imports.qualifyAcknowledgedLocalCreate(target, local, capture, attempt))
        }
        assertEquals(CapturedCreateHandoffResult.HANDED_OFF, app.imports.handoffLocalCreate(target, local, capture, attempt))
    }

    private fun itIsCurrent(app: BridgeApplication, target: CalendarScope) = app.imports.state.value.let { state ->
        state.authenticated && state.hasPermission && state.selections.any { it.scope == target && it.enabled && it.available }
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
