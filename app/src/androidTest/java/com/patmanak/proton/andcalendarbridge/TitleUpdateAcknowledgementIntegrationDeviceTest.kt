// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTitleRecoveryController
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTitleRecoveryProgress
import com.patmanak.proton.andcalendarbridge.data.outgoing.AndroidTitleRecoveryCursorStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CoordinatorSystemTitleOperations
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemUpdateController
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Provider, SQLite journals and coordinator; synthetic protected work/result only.
 * No HTTP or cryptographic verification occurs: these fixtures qualify local composition only.
 */
@RunWith(AndroidJUnit4::class)
class TitleUpdateAcknowledgementIntegrationDeviceTest {
    private class Fixture(val context: Context, allDay: Boolean, val transferred: Boolean = false,
        private val preparedOnly: Boolean = false, private val pendingProjection: String? = null,
        allDayDays: Long = 2) {
        private val token = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("title-ack-integration-$token"), CalendarId("fixture"), 0)
        private val importName = "title-ack-integration-$token.db"
        private val attemptName = "title-ack-integration-attempt-$token.db"
        private val captureName = "title-ack-integration-capture-$token.db"
        private val createAttemptName = "title-ack-integration-create-attempt-$token.db"
        private val format = AdapterFormat("synthetic-title-ack-integration", 1)
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-original-revision"),
            EventContent("Synthetic imported original", if (allDay)
                EventTime.AllDay(LocalDate.of(2030, 10, 26), LocalDate.of(2030, 10, 26).plusDays(allDayDays)) else
                EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
                "Synthetic description\nPreserved second line", "Synthetic location", details = EventDetails()))
        val local = base.content.copy(title = "Synthetic submitted system title")
        private val firstId = MutationId("synthetic-first-intent")
        private val intent = LocalEditIntent(firstId, target, format, base, local)
        val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-05T12:00:00Z"),
            AdapterFormat("synthetic-sealed-title-preparation", 1), "synthetic-opaque-preparation-no-crypto-proof")
        val prepared = TitleUpdateAttemptRecord(MutationId("synthetic-first-attempt"), preparation,
            AdapterFormat("synthetic-sealed-title-wire", 1), "synthetic-opaque-wire-no-crypto-proof")
        val unknown = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        val verified = base.copy(revision = Revision("synthetic-verified-revision"), content = local)
        val result = TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(unknown), verified))
        val native = AndroidCalendarProjection(context)
        val writes = AtomicInteger()
        val inspections = AtomicInteger()
        var beforeFreshAcknowledgement: (suspend () -> Unit)? = null
        var rejectFreshAcknowledgement = false
        var afterFreshAcknowledgement: (suspend () -> Unit)? = null
        var afterHandoffSourceRead: (suspend () -> Unit)? = null
        var afterCleanHandoffRead: (suspend () -> Unit)? = null
        private val projection = object : CalendarProjection by native {
            override suspend fun inspectCapturedHandoffBase(frame: CapturedCreateHandoffFrame): CapturedHandoffCleanWitness? {
                val observation = native.inspectCapturedHandoffBase(frame)
                val hook = afterCleanHandoffRead
                afterCleanHandoffRead = null
                hook?.invoke()
                return observation
            }
            override suspend fun captureCapturedHandoffCurrent(frame: CapturedCreateHandoffFrame): CapturedHandoffCurrentObservation {
                val observation = native.captureCapturedHandoffCurrent(frame)
                val hook = afterHandoffSourceRead
                afterHandoffSourceRead = null
                hook?.invoke()
                return observation
            }
            override suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                writes.incrementAndGet()
                beforeFreshAcknowledgement?.invoke()
                if (rejectFreshAcknowledgement) return null
                val witness = native.acknowledgeTitleUpdate(receipt)
                if (witness != null) {
                    val hook = afterFreshAcknowledgement
                    afterFreshAcknowledgement = null
                    hook?.invoke()
                }
                return witness
            }
            override suspend fun inspectTitleUpdateAcknowledgement(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                inspections.incrementAndGet()
                return native.inspectTitleUpdateAcknowledgement(receipt)
            }
        }
        var store = ImportStore(context, importName)
            private set
        var journal = TitleUpdateAttemptStore(context, attemptName)
            private set
        private var captures = LocalCreateStore(context, captureName)
        private var createAttempts = CreateAttemptStore(context, createAttemptName)
        private var originalSubmission: LocalCreateSubmission? = null
        private var jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val schedules = AtomicInteger()
        private val localContinuations = AtomicInteger()
        private val networkContinuations = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) {
                if (network) networkContinuations.incrementAndGet() else localContinuations.incrementAndGet()
            }
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId {
                if (submitting) forbiddenAccountReentries++
                check(!submitting) { "Local qualification must not re-enter authentication" }
                return target.account
            }
            override suspend fun fetch(request: PageRequest): RemotePage = error("Synthetic result fixture cannot download")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("Synthetic result fixture cannot upload")
        }
        private var titlesAllowed = false
        private fun newCoordinator() = ImportCoordinator(context, store, remote, projection, jobs, scheduler,
            creates = if (transferred) createAttempts else null,
            localCreates = if (transferred) captures else null,
            systemCreatesEnabled = { it == target }, systemTitlesEnabled = { it == target && titlesAllowed })
        var coordinator = newCoordinator()
            private set
        private var calendar = -1L
        var row = -1L
            private set
        private val uri get() = syncUri(Events.CONTENT_URI)
        var frame: CapturedCreateHandoffFrame? = null
            private set
        private var submitting = false
        private var forbiddenAccountReentries = 0
        private var ordinaryReceipt: ProjectionReceipt? = null

        /** Only the remote boundary is synthetic; owner checks and claim/result commits are real. */
        private inner class ControlledTitlePort : CalendarTitleUpdateSubmissionPort {
            override val titleUpdateFormat = prepared.format
            var preparations = 0
            var submissions = 0
            var sends = 0
            var forbiddenCalls = 0
            var beforeClaim: suspend () -> Unit = {}
            var afterSend: suspend () -> Unit = {}
            private fun forbidden(): Nothing { forbiddenCalls++; error("No unrestricted operation or service inspection") }
            override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = forbidden()
            override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
                admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
                assertTrue(admit(target)); assertFalse(admit(target.copy(generation = 1)))
                assertEquals(TitleUpdatePreparationReservation.of(prepared), reservation)
                preparations++
                return prepared
            }
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation = forbidden()
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation = forbidden()
            override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = forbidden()
            override suspend fun inspect(record: TitleUpdateAttemptRecord,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation = forbidden()
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean,
                qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                submissions++; submitting = true
                try {
                    assertEquals(prepared, record)
                    assertTrue(admit(target)); assertFalse(admit(target.copy(generation = 1)))
                    if (!qualifySource(target)) return TitleUpdateSubmissionObservation.Unknown
                    beforeClaim()
                    if (!qualifySource(target) || !journal.claim(record, admit)) return TitleUpdateSubmissionObservation.Unknown
                    // Dispatch starts only after durable UNKNOWN and another current native read.
                    assertEquals(unknown, journal.read(target, record.id))
                    if (!qualifySource(target) || !admit(target)) return TitleUpdateSubmissionObservation.Unknown
                    sends++
                    journal.retainEvidence(unknown, "synthetic-composed-title-response")
                    afterSend()
                    if (!qualifySource(target) || !admit(target)) return TitleUpdateSubmissionObservation.Unknown
                    return TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), verified)
                } finally { submitting = false }
            }
            fun assertOneExchange() {
                assertEquals(1, preparations); assertEquals(1, submissions); assertEquals(1, sends)
                assertEquals(0, forbiddenCalls); assertEquals(0, forbiddenAccountReentries)
            }
        }

        private inner class ControlledCapsulePort : CalendarLocalEditPreparationPort {
            override val localTitleFormat = preparation.format
            var guardedCalls = 0
            var forbiddenCalls = 0
            var beforeReturn: suspend () -> Unit = {}
            var returnNull = false
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant): LocalEditPreparation {
                forbiddenCalls++; error("Production must never use unrestricted capsule preparation")
            }
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): LocalEditPreparation? {
                guardedCalls++
                assertTrue(admit(target)); assertFalse(admit(target.copy(generation = 1)))
                assertEquals(preparation.intent, intent); assertEquals(preparation.timestamp, timestamp)
                beforeReturn()
                return if (returnNull) null else preparation
            }
        }

        private fun toggleTitles(enabled: Boolean) {
            titlesAllowed = enabled
            coordinator.systemCreatePolicyChanged(target, enabled)
        }

        private suspend fun reserveProductionIdentity() {
            assertEquals(TitleUpdatePreparationReservation.of(prepared),
                journal.reservePreparation(TitleUpdatePreparationReservation.of(prepared)) { it == target })
        }

        private suspend fun prepareProduction(capsule: ControlledCapsulePort, port: ControlledTitlePort,
            timestamp: Instant = preparation.timestamp.plusSeconds(7200),
            admission: (CalendarScope) -> Boolean = { it == target && titlesAllowed }, existingOnly: Boolean = false) = coordinator.prepareSystemTitle(
                target, base.eventId, preparation.intent.id, MutationId(UUID.randomUUID().toString()), timestamp,
                journal, capsule, port, admission, existingOnly)

        suspend fun existingOnlyRejectsNewCapsulesAndKeepsTheOriginalPreparation() {
            check(preparedOnly)
            toggleTitles(true)
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort(); val before = values()
            allowSql()
            assertEquals(1, store.writableDatabase.delete("local_edit_preparations", null, null))
            assertEquals(LocalTitleAttemptPreparation.Unsupported, prepareProduction(capsule, port, existingOnly = true))
            assertEquals(0, capsule.guardedCalls); assertEquals(0, capsule.forbiddenCalls)
            assertEquals(0, port.preparations); assertEquals(0, port.submissions)
            assertEquals(before, values()); assertNull(journal.read(target, prepared.id))
            allowSql(); assertNull(store.localEditPreparation(target, base.eventId))
            assertTrue(store.retainLocalEditPreparation(preparation))
            reserveProductionIdentity()
            val retained = prepareProduction(capsule, port, existingOnly = true) as LocalTitleAttemptPreparation.Prepared
            assertEquals(prepared, retained.record)
            assertEquals(0, capsule.guardedCalls); assertEquals(0, capsule.forbiddenCalls)
            assertEquals(1, port.preparations); assertEquals(0, port.submissions)
            assertEquals(before, values())
        }

        suspend fun productionTitleOptionOffNeverCallsPorts() {
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort(); val before = values()
            assertEquals(LocalTitleAttemptPreparation.Inactive, prepareProduction(capsule, port))
            assertNull(coordinator.submitSystemTitle(prepared, journal, port) { it == target })
            assertEquals(0, capsule.guardedCalls); assertEquals(0, capsule.forbiddenCalls)
            assertEquals(0, port.preparations); assertEquals(0, port.submissions); assertEquals(0, port.forbiddenCalls)
            assertNull(journal.read(target, prepared.id)); assertEquals(before, values())
            allowSql(); assertEquals(preparation, store.localEditPreparation(target, base.eventId))
        }

        suspend fun productionTitleControllerUsesCanonicalWorkAndCompletesTheRealNativeOwner() {
            toggleTitles(true); reserveProductionIdentity()
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort(); val before = values()
            (base.content.time as? EventTime.AllDay)?.let { dates ->
                assertEquals("1", before[Events.ALL_DAY])
                assertEquals("UTC", before[Events.EVENT_TIMEZONE])
                assertEquals(dates.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli().toString(), before[Events.DTSTART])
                assertEquals(dates.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli().toString(), before[Events.DTEND])
            }
            val changePrefs = "system-title-integration-changes-$token"
            val attemptPrefs = "system-title-integration-attempts-$token"
            var wakes = 0
            try {
                val controller = SystemUpdateController(AndroidTitleRecoveryCursorStore(context, changePrefs),
                    AndroidTitleRecoveryCursorStore(context, attemptPrefs),
                    CoordinatorSystemTitleOperations(coordinator, journal, capsule, port), {
                        assertEquals(target, it); wakes++
                    }, pageSize = 1)
                assertEquals(SystemTitleRecoveryProgress(more = true), controller.run(target, network = true) { titlesAllowed })
                port.assertOneExchange(); assertEquals(0, capsule.guardedCalls); assertEquals(0, capsule.forbiddenCalls)
                assertEquals(1, wakes)
                val admitted = unknown.copy(protectedEvidence = "synthetic-composed-title-response")
                assertEquals(admitted, journal.read(target, prepared.id)); assertEquals(result, journal.readVerified(target, prepared.id))
                assertEquals(preparation.timestamp, journal.read(target, prepared.id)!!.preparation.timestamp)
                assertEquals(result, journal.readCompletion(target, prepared.id)!!.result)
                assertSqlRestored(admitted)
                assertOriginalCreateProvenance()
                val after = values()
                assertEquals(before - setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3),
                    after - setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3))
                assertEquals("0", after[Events.DIRTY]); assertSingleOriginalRow(); assertEquals(1, writes.get())
                assertEquals(SystemTitleRecoveryProgress(), controller.run(target, network = true, fresh = false) { titlesAllowed })
                assertEquals(1, wakes); port.assertOneExchange(); assertEquals(after, values())
                reopenOwner(); assertSqlRestored(admitted)
                assertEquals(result, journal.readCompletion(target, prepared.id)!!.result)
                assertOriginalCreateProvenance()
            } finally { context.deleteSharedPreferences(changePrefs); context.deleteSharedPreferences(attemptPrefs) }
        }

        suspend fun completedTransferredTitleClosesCreateFollowupWithoutNativeWrite(pendingEcho: Boolean = false, distinctRevision: Boolean = false) {
            check(transferred)
            productionTitleControllerUsesCanonicalWorkAndCompletesTheRealNativeOwner()
            allowSql()
            val settled = requireNotNull(store.capturedHandoff(target, base.eventId)).frame
            assertEquals(verified, settled.base)
            if (pendingEcho) {
                assertEquals(1, store.writableDatabase.update("records", ContentValues().apply { put("pending", 1) },
                    null, null))
                assertEquals(ProjectionIntent(target, verified), store.capturedHandoffPending(settled))
            }
            if (distinctRevision) {
                val distinct = verified.copy(revision = Revision("synthetic-newer-distinct-remote-revision"))
                val pending = ProjectionIntent(target, distinct)
                store.transaction(target) {
                    putRemote(distinct)
                    putProjection(pending)
                }
                assertEquals(pending, store.capturedHandoffPending(settled))
            }
            assertTrue(store.waitCapturedHandoff(settled))
            val localBefore = localContinuations.get()
            val networkBefore = networkContinuations.get()
            val nativeBefore = values()
            val titleBefore = journal.read(target, prepared.id)
            val completionBefore = journal.readCompletion(target, prepared.id)
            val source = CapturedCreateDispatchIdentity.of(requireNotNull(originalSubmission))
            assertEquals(CapturedCreateReconciliationResult.UNCHANGED,
                coordinator.reconcileHandoffLocalCreate(target, source.localId, source.captureId, source.attempt.id))
            allowSql()
            assertEquals(CapturedCreateHandoffRecord(settled, CapturedCreateHandoffState.RELEASED),
                store.capturedHandoff(target, base.eventId))
            assertEquals(nativeBefore, values())
            assertEquals(titleBefore, journal.read(target, prepared.id))
            assertEquals(completionBefore, journal.readCompletion(target, prepared.id))
            assertEquals(localBefore + if (pendingEcho) 1 else 0, localContinuations.get())
            assertEquals(networkBefore, networkContinuations.get())
            assertEquals(1, writes.get())
            assertOriginalCreateProvenance()
            assertEquals(CapturedCreateReconciliationResult.UNCHANGED,
                coordinator.reconcileHandoffLocalCreate(target, source.localId, source.captureId, source.attempt.id))
            assertEquals(nativeBefore, values())
            assertEquals(localBefore + if (pendingEcho) 1 else 0, localContinuations.get())
            assertEquals(networkBefore, networkContinuations.get())
        }

        suspend fun changedSettledHandoffCannotCloseCreateFollowup(case: String) {
            check(transferred)
            productionTitleControllerUsesCanonicalWorkAndCompletesTheRealNativeOwner()
            allowSql()
            val settled = requireNotNull(store.capturedHandoff(target, base.eventId)).frame
            assertTrue(store.waitCapturedHandoff(settled))
            val source = CapturedCreateDispatchIdentity.of(requireNotNull(originalSubmission))
            val completionBefore = journal.readCompletion(target, prepared.id)
            val localBefore = localContinuations.get()
            val networkBefore = networkContinuations.get()
            var expectedNative = values()
            when (case) {
                "owner" -> assertNotNull(store.retainCapturedHandoffLocalChange(
                    CapturedHandoffCurrent(settled, settled.base.content.copy(title = "Synthetic newer retained title"), false),
                    MutationId(UUID.randomUUID().toString())))
                "projection" -> damageTransferredSource("projection")
                "late_title", "late_delete", "origin", "revoked", "cancelled_read" -> afterCleanHandoffRead = {
                    when (case) {
                        "late_title" -> mutateTitle("Synthetic later native title")
                        "late_delete" -> context.contentResolver.update(uri, ContentValues().apply {
                            put(Events.DIRTY, 1); put(Events.DELETED, 1)
                        }, "${Events._ID}=?", arrayOf(row.toString()))
                        "origin" -> damageTransferredSource("attachment")
                        "revoked" -> toggleTitles(false)
                        "cancelled_read" -> currentCoroutineContext().cancel()
                    }
                    expectedNative = values()
                }
                else -> error("Unknown settled handoff fixture")
            }
            if (case == "cancelled_read") {
                val boundary = Job()
                var propagated = false
                try {
                    withContext(boundary) {
                        coordinator.reconcileHandoffLocalCreate(target, source.localId, source.captureId, source.attempt.id)
                    }
                } catch (_: CancellationException) { propagated = true }
                finally { boundary.cancel() }
                assertTrue("A captured clean witness cannot absorb cancellation", propagated)
            } else assertNotEquals(CapturedCreateReconciliationResult.UNCHANGED,
                coordinator.reconcileHandoffLocalCreate(target, source.localId, source.captureId, source.attempt.id))
            allowSql()
            assertEquals(CapturedCreateHandoffRecord(settled, CapturedCreateHandoffState.RELEASED, waiting = true),
                store.capturedHandoff(target, base.eventId))
            assertEquals(expectedNative, values())
            assertEquals(completionBefore, journal.readCompletion(target, prepared.id))
            assertEquals(localBefore, localContinuations.get())
            assertEquals(networkBefore, networkContinuations.get())
            assertEquals(1, writes.get())
        }

        private fun damageTransferredSource(case: String) {
            check(transferred)
            when (case) {
                "frame" -> assertEquals(1, store.writableDatabase.delete("captured_create_handoffs", null, null))
                "origin" -> assertEquals(1, store.writableDatabase.delete("captured_create_bases", null, null))
                "attachment" -> assertEquals(1, createAttempts.writableDatabase.delete("captured_create_attachments", null, null))
                "binding" -> assertEquals(1, createAttempts.writableDatabase.update("captured_create_bindings", ContentValues().apply {
                    put("verified_revision", "synthetic-distinct-original-binding")
                }, null, null))
                "submission" -> assertEquals(1, captures.writableDatabase.delete("local_create_submissions", null, null))
                "submission_payload" -> assertEquals(1, captures.writableDatabase.update("local_create_submissions", ContentValues().apply {
                    put("payload", "synthetic-distinct-original-wire")
                }, null, null))
                "waiting" -> assertEquals(1, store.writableDatabase.update("captured_create_handoffs", ContentValues().apply {
                    put("waiting", 0)
                }, null, null))
                "release" -> assertEquals(1, store.writableDatabase.update("captured_create_handoffs", ContentValues().apply {
                    put("state", "PENDING")
                }, null, null))
                "nonce" -> assertEquals(1, store.writableDatabase.update("captured_create_handoffs", ContentValues().apply {
                    put("nonce", "synthetic-distinct-current-frame")
                }, null, null))
                "projection" -> store.writableDatabase.execSQL("INSERT INTO projection_receipts " +
                    "(scope,event,nonce,payload,adapter,version,previous_base,protocol) " +
                    "SELECT scope,event,?,payload,?,?,payload,1 FROM projection_bases",
                    arrayOf<Any>(UUID.randomUUID().toString(), format.id, format.version))
                "scope_hold" -> {
                    createAttempts.writableDatabase.execSQL("INSERT INTO create_attempts " +
                        "(account,calendar,generation,mutation,adapter,version,payload,state,evidence) " +
                        "SELECT account,calendar,generation,'synthetic-other-unknown',adapter,version,payload,state,evidence FROM create_attempts")
                    createAttempts.writableDatabase.execSQL("INSERT INTO captured_create_links " +
                        "(account,calendar,generation,mutation,adapter,version,payload_fingerprint,local_id,capture_id," +
                        "capture_adapter,capture_version,timestamp,content_fingerprint,fingerprint_version) " +
                        "SELECT account,calendar,generation,'synthetic-other-unknown',adapter,version,payload_fingerprint," +
                        "'synthetic-other-local','synthetic-other-capture',capture_adapter,capture_version,timestamp," +
                        "content_fingerprint,fingerprint_version FROM captured_create_links")
                }
                else -> error("Unknown synthetic source case")
            }
        }

        suspend fun productionInvalidTransferredSourceNeverCallsPorts(case: String) {
            toggleTitles(true)
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort(); val before = values()
            damageTransferredSource(case)
            assertFalse(prepareProduction(capsule, port) is LocalTitleAttemptPreparation.Prepared)
            assertNull(coordinator.submitSystemTitle(prepared, journal, port) { it == target && titlesAllowed })
            assertEquals(0, capsule.guardedCalls); assertEquals(0, capsule.forbiddenCalls)
            assertEquals(0, port.preparations); assertEquals(0, port.submissions); assertEquals(0, port.forbiddenCalls)
            assertNull(journal.read(target, prepared.id)); assertEquals(before, values())
            allowSql(); assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(intent, store.localEdit(target, base.eventId)); assertSingleOriginalRow()
            assertEquals(0, writes.get()); assertEquals(0, inspections.get())
            if (case == "projection") assertNotNull(store.capturedHandoffProjection(frame!!))
        }

        suspend fun productionTransferredSourceChangingBeforeClaimCannotSend(case: String) {
            toggleTitles(true); reserveProductionIdentity()
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort(); val before = values()
            assertEquals(LocalTitleAttemptPreparation.Prepared(prepared), prepareProduction(capsule, port))
            port.beforeClaim = { damageTransferredSource(case) }
            assertNull(coordinator.submitSystemTitle(prepared, journal, port) { it == target && titlesAllowed })
            assertEquals(1, port.submissions); assertEquals(0, port.sends); assertEquals(0, port.forbiddenCalls)
            assertEquals(prepared, journal.read(target, prepared.id)); assertNull(journal.readVerified(target, prepared.id))
            assertEquals(before, values()); assertEquals(0, writes.get()); assertSingleOriginalRow()
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId))
            if (case == "projection") assertNotNull(store.capturedHandoffProjection(frame!!))
        }

        suspend fun productionTransferredScopeHoldAppearingAfterNativeReadCannotPrepare() {
            toggleTitles(true)
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort(); val before = values()
            afterHandoffSourceRead = { damageTransferredSource("scope_hold") }
            assertFalse(prepareProduction(capsule, port) is LocalTitleAttemptPreparation.Prepared)
            assertEquals(0, port.preparations); assertEquals(0, capsule.guardedCalls)
            assertTrue(createAttempts.capturedProjectionHold(target, format).scopeBlocked)
            assertEquals(before, values()); assertEquals(0, writes.get()); assertOriginalCreateProvenance()
        }

        suspend fun productionHistoricalInspectionPreservesLaterNativeChange(case: String) {
            check(transferred && !preparedOnly)
            toggleTitles(true)
            journal.writableDatabase.execSQL("UPDATE title_update_attempts SET result=NULL")
            assertNull(journal.readVerified(target, prepared.id))
            when (case) {
                "later_title", "revoke", "attachment", "inspection_attachment", "inspection_role", "projection" ->
                    mutateTitle("Synthetic later title while earlier outcome is unknown")
                "later_date" -> assertEquals(1, context.contentResolver.update(uri, ContentValues().apply {
                    put(Events.DTEND, values()[Events.DTEND]!!.toLong() + 86_400_000L); put(Events.DIRTY, 1)
                }, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())))
                "deleted" -> assertEquals(1, context.contentResolver.update(uri, ContentValues().apply {
                    put(Events.DELETED, 1); put(Events.DIRTY, 1)
                }, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())))
                else -> error("Unknown synthetic historical case")
            }
            if (case == "attachment") damageTransferredSource("attachment")
            if (case == "projection") damageTransferredSource("projection")
            // A historical read must not require the old mutable waiting marker.
            if (case == "later_title") damageTransferredSource("waiting")
            val later = values(); var serviceInspections = 0; var wakes = 0
            val lease = coordinator.systemTitleRunAdmission(target)
            val port = object : CalendarTitleUpdateSubmissionPort {
                override val titleUpdateFormat = prepared.format
                override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
                    error("Historical inspection cannot prepare")
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
                    error("Historical inspection cannot send")
                override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation =
                    error("Historical inspection must remain guarded")
                override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                    assertEquals(unknown, record); assertTrue(admit(target))
                    assertFalse(admit(target.copy(generation = 1))); serviceInspections++
                    if (case == "revoke") { toggleTitles(false); toggleTitles(true) }
                    if (case == "inspection_attachment") damageTransferredSource("attachment")
                    if (case == "inspection_role") damageTransferredSource("waiting")
                    return TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), verified)
                }
            }
            val progress = coordinator.inspectSystemTitles(TitleUpdateWorkQuery(target), journal, port, lease) { wakes++ }
            if (case in listOf("attachment", "revoke", "inspection_attachment", "inspection_role")) {
                assertNull(journal.readVerified(target, prepared.id)); assertEquals(0, wakes)
                assertEquals(if (case == "attachment") 0 else 1, serviceInspections)
            } else {
                assertEquals(TitleUpdateRecoveryProgress(held = 1), progress)
                assertEquals(1, serviceInspections); assertEquals(1, wakes)
                assertEquals(result, journal.readVerified(target, prepared.id))
                assertEquals(TitleUpdateRecoveryProgress(held = 1), coordinator.inspectSystemTitles(
                    TitleUpdateWorkQuery(target), journal, port, lease) { wakes++ })
                assertEquals(1, serviceInspections); assertEquals(1, wakes)
                assertOriginalCreateProvenance()
            }
            assertEquals(unknown, journal.read(target, prepared.id)); assertNull(journal.readCompletion(target, prepared.id))
            assertEquals(later, values()); assertEquals(0, writes.get()); assertEquals(0, inspections.get())
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId))
            assertEquals(base, store.projectedBaseline(target, base.eventId)); assertNull(receipt())
        }

        suspend fun productionFreshCapsuleUsesOnlyGuardedOverload(revoke: Boolean) {
            toggleTitles(true)
            assertEquals(1, store.writableDatabase.delete("local_edit_preparations", null, null))
            reserveProductionIdentity()
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort(); val before = values()
            if (revoke) capsule.beforeReturn = { toggleTitles(false); toggleTitles(true) }
            val outcome = prepareProduction(capsule, port, preparation.timestamp)
            assertEquals(1, capsule.guardedCalls); assertEquals(0, capsule.forbiddenCalls)
            assertEquals(0, port.submissions); assertEquals(0, port.sends); assertEquals(before, values())
            allowSql()
            if (revoke) {
                assertEquals(LocalTitleAttemptPreparation.Inactive, outcome)
                assertEquals(0, port.preparations); assertNull(journal.read(target, prepared.id))
                assertNull(store.localEditPreparation(target, base.eventId))
            } else {
                assertEquals(LocalTitleAttemptPreparation.Prepared(prepared), outcome)
                assertEquals(1, port.preparations); assertEquals(prepared, journal.read(target, prepared.id))
                assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            }
            assertEquals(intent, store.localEdit(target, base.eventId))
            assertEquals(TitleUpdatePreparationReservation.of(prepared), journal.readPreparationReservation(target, base.eventId))
        }

        suspend fun productionRevocationBeforeClaimCannotSendAfterModeRestoration() {
            toggleTitles(true); reserveProductionIdentity()
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort()
            assertEquals(LocalTitleAttemptPreparation.Prepared(prepared), prepareProduction(capsule, port))
            val before = values()
            port.beforeClaim = { toggleTitles(false); toggleTitles(true) }
            assertNull(coordinator.submitSystemTitle(prepared, journal, port) { it == target && titlesAllowed })
            assertEquals(1, port.submissions); assertEquals(0, port.sends); assertEquals(0, port.forbiddenCalls)
            assertEquals(prepared, journal.read(target, prepared.id)); assertNull(journal.readVerified(target, prepared.id))
            assertEquals(before, values()); assertEquals(0, writes.get())
        }

        suspend fun productionAllDayDateChangeBeforeClaimPreservesLaterNativeEdit() {
            toggleTitles(true); reserveProductionIdentity()
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort()
            assertEquals(LocalTitleAttemptPreparation.Prepared(prepared), prepareProduction(capsule, port))
            val dates = base.content.time as EventTime.AllDay
            val laterEnd = dates.endExclusive.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli().toString()
            port.beforeClaim = {
                assertEquals(1, context.contentResolver.update(uri, ContentValues().apply {
                    put(Events.DTEND, laterEnd.toLong()); put(Events.DIRTY, 1)
                }, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())))
            }
            assertNull(coordinator.submitSystemTitle(prepared, journal, port) { it == target && titlesAllowed })
            assertEquals(1, port.submissions); assertEquals(0, port.sends); assertEquals(0, writes.get())
            assertEquals(prepared, journal.read(target, prepared.id)); assertNull(journal.readVerified(target, prepared.id))
            assertEquals(laterEnd, values()[Events.DTEND]); assertEquals("1", values()[Events.DIRTY])
            assertSingleOriginalRow(); allowSql()
            assertEquals(intent, store.localEdit(target, base.eventId)); assertEquals(base, store.projectedBaseline(target, base.eventId))
        }

        suspend fun productionAllDayRemoteDateChangeBeforeClaimRemainsArchivedWithoutSend() {
            toggleTitles(true); reserveProductionIdentity()
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort()
            assertEquals(LocalTitleAttemptPreparation.Prepared(prepared), prepareProduction(capsule, port))
            val dates = base.content.time as EventTime.AllDay
            val remote = base.copy(revision = Revision("synthetic-competing-all-day-revision"),
                content = base.content.copy(time = dates.copy(endExclusive = dates.endExclusive.plusDays(1))))
            val before = values()
            port.beforeClaim = { allowSql(); store.transaction(target) { putRemote(remote) } }
            assertNull(coordinator.submitSystemTitle(prepared, journal, port) { it == target && titlesAllowed })
            assertEquals(1, port.submissions); assertEquals(0, port.sends); assertEquals(0, writes.get())
            assertEquals(prepared, journal.read(target, prepared.id)); assertNull(journal.readVerified(target, prepared.id))
            assertEquals(before, values()); assertSingleOriginalRow(); allowSql()
            assertEquals(intent.copy(state = LocalEditState.CONFLICT), store.localEdit(target, base.eventId))
            val ref = store.conflictArchives(ConflictArchiveQuery(target)).entries.single()
            assertEquals(base, store.conflictArchive(ref)!!.base); assertEquals(local, store.conflictArchive(ref)!!.local)
            assertEquals(remote, store.conflictObservations(ConflictObservationQuery(ref)).entries.last().change)
        }

        suspend fun productionUnknownCannotBePreparedOrSubmittedAgain() {
            toggleTitles(true); reserveProductionIdentity()
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort()
            assertEquals(LocalTitleAttemptPreparation.Prepared(prepared), prepareProduction(capsule, port))
            assertEquals(result, coordinator.submitSystemTitle(prepared, journal, port) { it == target && titlesAllowed })
            val original = journal.read(target, prepared.id)
            assertEquals(unknown.copy(protectedEvidence = "synthetic-composed-title-response"), original)
            val before = values()
            assertEquals(LocalTitleAttemptPreparation.Unresolved, prepareProduction(capsule, port))
            assertNull(coordinator.submitSystemTitle(prepared, journal, port) { it == target && titlesAllowed })
            assertEquals(original, journal.read(target, prepared.id)); assertEquals(result, journal.readVerified(target, prepared.id))
            port.assertOneExchange(); assertEquals(0, capsule.guardedCalls); assertEquals(0, capsule.forbiddenCalls)
            assertEquals(before, values()); assertEquals("1", before[Events.DIRTY]); assertEquals(0, writes.get())
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId))
        }

        suspend fun productionCancelledNullCapsuleCannotReturnNormally() {
            toggleTitles(true)
            assertEquals(1, store.writableDatabase.delete("local_edit_preparations", null, null))
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort()
            capsule.returnNull = true
            capsule.beforeReturn = { currentCoroutineContext().cancel() }
            var normalReturn = false
            supervisorScope {
                val child = async {
                    prepareProduction(capsule, port, preparation.timestamp)
                    normalReturn = true
                }
                try { child.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertFalse(normalReturn); assertEquals(1, capsule.guardedCalls); assertEquals(0, capsule.forbiddenCalls)
            assertEquals(0, port.preparations); assertEquals(0, port.submissions)
            assertNull(journal.read(target, prepared.id)); assertNull(journal.readVerified(target, prepared.id))
            allowSql(); assertNull(store.localEditPreparation(target, base.eventId)); assertEquals(intent, store.localEdit(target, base.eventId))
        }

        suspend fun productionFrozenRunLeaseCannotSurviveRevocationBetweenPreparationAndSubmission(authentication: Boolean) {
            toggleTitles(true); reserveProductionIdentity()
            val capsule = ControlledCapsulePort(); val port = ControlledTitlePort()
            val lease = coordinator.systemTitleRunAdmission(target)
            assertTrue(lease(target)); assertFalse(lease(target.copy(generation = 1)))
            assertEquals(LocalTitleAttemptPreparation.Prepared(prepared), prepareProduction(capsule, port, admission = lease))
            if (authentication) {
                val prior = schedules.get()
                coordinator.authenticationChanged(false); coordinator.authenticationChanged(true)
                withTimeout(10_000) { while (schedules.get() == prior) delay(10) }
            } else { toggleTitles(false); toggleTitles(true) }
            assertFalse("The original run lease must stay closed", lease(target))
            assertTrue("A later independent run can obtain current access", coordinator.systemTitleRunAdmission(target)(target))
            val before = values()
            assertNull(coordinator.submitSystemTitle(prepared, journal, port, lease))
            assertEquals(0, port.submissions); assertEquals(0, port.sends)
            assertEquals(prepared, journal.read(target, prepared.id)); assertNull(journal.readVerified(target, prepared.id))
            assertEquals(before, values()); assertEquals(0, writes.get())
        }

        private suspend fun prepareGuarded(port: ControlledTitlePort): TitleUpdateAttemptRecord {
            check(preparedOnly)
            assertNull(journal.read(target, prepared.id))
            val before = values()
            val canonical = coordinator.prepareLocalTitleAttempt(TitleUpdatePreparationReservation.of(prepared),
                journal, port, allowNetwork = true) as LocalTitleAttemptPreparation.Prepared
            assertEquals(prepared, canonical.record)
            assertEquals(before, values()); assertEquals(0, writes.get()); assertEquals(0, inspections.get())
            return canonical.record
        }

        /** Reconstruct every SQL owner/journal and coroutine runtime, keeping only the native row. */
        private suspend fun reopenOwner() {
            coordinator.authenticationChanged(false)
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); journal.close(); captures.close(); createAttempts.close()
            store = ImportStore(context, importName); journal = TitleUpdateAttemptStore(context, attemptName)
            captures = LocalCreateStore(context, captureName); createAttempts = CreateAttemptStore(context, createAttemptName)
            jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = newCoordinator()
            val prior = schedules.get()
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == prior) delay(10) }
        }

        suspend fun guardedSubmissionCompletesOfflineAfterReopening(restoreGap: Boolean = false,
            completionGap: Boolean = false) {
            val port = ControlledTitlePort()
            val canonical = prepareGuarded(port)
            val before = values()
            val retained = coordinator.submitAndRetainLocalTitleAttempt(canonical, journal, journal, port, allowNetwork = true)
            assertEquals(result, retained); port.assertOneExchange()
            assertEquals(before, values()); assertEquals("1", before[Events.DIRTY])
            assertEquals(0, writes.get()); assertEquals(0, inspections.get()); assertSingleOriginalRow()
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId)); assertNull(receipt())
            assertOriginalCreateProvenance()
            val evidenced = unknown.copy(protectedEvidence = "synthetic-composed-title-response")
            reopenOwner()
            assertEquals(evidenced, journal.read(target, canonical.id)); assertEquals(result, journal.readVerified(target, canonical.id))
            assertEquals(before, values()); assertNull(journal.readCompletion(target, canonical.id))
            // Neither offline wire reuse nor replay of PREPARED may re-enter the port.
            assertEquals(LocalTitleAttemptPreparation.Unresolved, coordinator.prepareLocalTitleAttempt(
                TitleUpdatePreparationReservation.of(canonical), journal, port))
            assertNull(coordinator.submitAndRetainLocalTitleAttempt(canonical, journal, journal, port, allowNetwork = true))
            port.assertOneExchange()
            if (restoreGap) store.writableDatabase.execSQL("CREATE TRIGGER composed_restore_gap BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'synthetic restoration gap'); END")
            if (completionGap) journal.writableDatabase.execSQL("CREATE TRIGGER composed_completion_gap BEFORE UPDATE OF completion ON title_update_attempts BEGIN SELECT RAISE(ABORT,'synthetic completion gap'); END")
            val first = coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal)
            if (restoreGap || completionGap) {
                assertEquals(TitleUpdateOfflineRecoveryProgress(held = 1), first)
                assertEquals(1, writes.get()); assertEquals(0, inspections.get())
                assertNull(journal.readCompletion(target, canonical.id))
                assertEquals(if (restoreGap) TitleUpdateAcknowledgementState.PENDING else TitleUpdateAcknowledgementState.RESTORED,
                    receipt()!!.state)
                mutateTitle("Synthetic later edit across composed recovery gap")
                val later = values()
                if (restoreGap) store.writableDatabase.execSQL("DROP TRIGGER composed_restore_gap")
                if (completionGap) journal.writableDatabase.execSQL("DROP TRIGGER composed_completion_gap")
                reopenOwner()
                assertEquals(TitleUpdateOfflineRecoveryProgress(completed = 1),
                    coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal))
                assertEquals(later, values()); assertEquals("1", values()[Events.DIRTY])
                assertEquals(if (restoreGap) 1 else 0, inspections.get())
            } else {
                assertEquals(TitleUpdateOfflineRecoveryProgress(completed = 1), first)
                val after = values()
                assertEquals("0", after[Events.DIRTY])
                val metadata = setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3)
                assertEquals(before.filterKeys { it !in metadata }, after.filterKeys { it !in metadata })
                assertEquals(digest("revision-v1", verified.revision.value), after[Events.SYNC_DATA2])
                assertEquals("calendeer-projection-receipt-v1:${receipt()!!.receipt.projection.nonce}", after[Events.SYNC_DATA3])
                mutateTitle("Synthetic later edit after composed completion")
            }
            assertSqlRestored(evidenced); assertOriginalCreateProvenance(); assertSingleOriginalRow()
            assertEquals(TitleUpdateCompletion.of(result, receipt()!!), journal.readCompletion(target, canonical.id))
            val later = values()
            val nextId = MutationId("synthetic-next-composed-owner")
            if (transferred) captureTransferred(nextId) else
                assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, nextId))
            allowSql()
            val next = store.localEdit(target, base.eventId)!!
            assertEquals(nextId, next.id); assertEquals(verified, next.base); assertEquals(later[Events.TITLE], next.content.title)
            assertEquals(TitleUpdateOfflineRecoveryProgress(), coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal))
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            allowSql(); assertEquals(next, store.localEdit(target, base.eventId))
            assertEquals(later, values()); assertEquals(1, writes.get()); port.assertOneExchange()
        }

        suspend fun guardedSubmissionPreservesChangedNativeSource(afterSend: Boolean) {
            val port = ControlledTitlePort()
            val canonical = prepareGuarded(port)
            var changeCalls = 0
            val laterTitle = "Synthetic newer edit during guarded dispatch"
            val change: suspend () -> Unit = { changeCalls++; mutateTitle(laterTitle) }
            if (afterSend) port.afterSend = change else port.beforeClaim = change
            assertNull(coordinator.submitAndRetainLocalTitleAttempt(canonical, journal, journal, port, allowNetwork = true))
            val later = values()
            assertEquals(1, changeCalls); assertEquals(laterTitle, later[Events.TITLE])
            val historical = if (afterSend) unknown.copy(protectedEvidence = "synthetic-composed-title-response") else canonical
            assertEquals(historical, journal.read(target, canonical.id)); assertNull(journal.readVerified(target, canonical.id))
            assertEquals(0, writes.get()); assertEquals(0, inspections.get()); assertNull(receipt())
            reopenOwner()
            assertEquals(TitleUpdateOfflineRecoveryProgress(held = 1), coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal))
            assertEquals(later, values()); assertEquals(laterTitle, values()[Events.TITLE])
            assertEquals("1", values()[Events.DIRTY]); assertSingleOriginalRow()
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId)); assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(1, port.preparations); assertEquals(1, port.submissions); assertEquals(if (afterSend) 1 else 0, port.sends)
            assertEquals(0, port.forbiddenCalls); assertEquals(0, forbiddenAccountReentries)
            assertOriginalCreateProvenance()
        }

        private fun syncUri(value: Uri): Uri = native.accountFor(target.account).let { account -> value.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build() }

        suspend fun seed() {
            val summary = CalendarSummary(target.calendar, "Calendeer synthetic title ACK integration")
            store.remember(target.account, listOf(summary)); store.setEnabled(target, true)
            calendar = native.ensureCalendarState(target, summary, true).id
            store.transaction(target) {
                putRemote(base); putProjection(ProjectionIntent(target, base))
                checkpoint = PageCheckpoint(target, format, traversalComplete = true)
            }
            if (transferred) seedTransferred() else {
                native.apply(ProjectionIntent(target, base))
                assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            }
            context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); row = it.getLong(0)
            }
            if (pendingProjection != null) {
                check(!transferred && !preparedOnly)
                val pending = when (pendingProjection) {
                    "redundant", "representation" -> base
                    "revision" -> base.copy(revision = Revision("synthetic-pending-projection-revision"))
                    "content" -> base.copy(content = base.content.copy(description = "Synthetic different pending description"))
                    else -> error("Unknown pending projection profile")
                }
                store.transaction(target) { putRemote(pending); putProjection(ProjectionIntent(target, pending)) }
                ordinaryReceipt = store.prepareProjection(ProjectionIntent(target, pending), format,
                    if (pendingProjection == "representation") ProjectedEventReference.direct(pending) else null)
                assertEquals(base, ordinaryReceipt!!.previousBase)
                assertEquals(ordinaryReceipt, store.projectionReceipt(target, base.eventId))
                // Prepare without applying: the native row and previous baseline remain original.
                store.transaction(target) { putRemote(base) }
                assertEquals(base, store.projectedBaseline(target, base.eventId))
            }
            mutateTitle(local.title)
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
            if (pendingProjection in setOf("content", "representation")) {
                // The coordinator conservatively refuses ambiguous projection recovery. Stage
                // its already captured SQL owner to test the independent ACK admission boundary.
                allowSql(); native.admitOperation = { true }
                assertEquals(local, native.captureOwnedSimpleEdit(target, base)!!.content)
                assertEquals(intent, store.captureLocalEdit(intent))
            } else if (transferred) captureTransferred(firstId) else
                assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, firstId))
            allowSql()
            assertEquals(intent, store.localEdit(target, base.eventId))
            assertTrue(store.retainLocalEditPreparation(preparation))
            if (preparedOnly) {
                journal.admitDispatch = { error("Retaining wire must never claim dispatch") }
                return
            }
            journal.remember(prepared); journal.admitDispatch = { it == target }; assertTrue(journal.claim(prepared))
            journal.admitDispatch = { error("Native acknowledgement must never admit another send") }
            journal.retainVerified(result)
        }

        /** A real ordinary preparation is not an applied projection. Only exact redundant
         * work may be retired, atomically with SQL restoration after the native ACK proof.
         */
        suspend fun pendingOrdinaryProjectionAcknowledgement(reject: String? = null) {
            val old = checkNotNull(ordinaryReceipt)
            allowSql()
            journal.retainEvidence(unknown, "synthetic-redundant-projection-response-evidence")
            val durableUnknown = checkNotNull(journal.read(target, prepared.id))
            store.transaction(target) { putRemote(verified) }
            assertEquals(intent.copy(state = LocalEditState.CONFLICT), store.localEdit(target, base.eventId))
            when (reject) {
                null, "native", "profile" -> Unit
                "deferred" -> store.transaction(target) {
                    (this as StagedSyncTransaction).defer(DeferredCalendarEvent(base.eventId, verified.revision,
                        format, DeferredEventReason.UNVERIFIED_CONTENT, "synthetic-deferred-projection"))
                }
                "owner" -> assertEquals(1, store.writableDatabase.update("local_edits", ContentValues().apply {
                    put("mutation", "synthetic-different-local-owner")
                }, null, null))
                "capsule" -> assertEquals(1, store.writableDatabase.update("local_edit_preparations", ContentValues().apply {
                    put("payload", "synthetic-different-sealed-capsule")
                }, null, null))
                "proof" -> journal.writableDatabase.execSQL("UPDATE title_update_attempts SET result=NULL")
                else -> error("Unknown redundant projection rejection")
            }
            reopenOwner(); allowSql()
            journal.admitDispatch = { error("Pending projection recovery must never claim another send") }
            val owner = store.localEdit(target, base.eventId)
            val invalidSource = reject == "owner" || reject == "capsule"
            val rawCapsule = if (invalidSource) rawLocalPreparationSnapshot() else null
            val capsule = if (invalidSource) null else store.localEditPreparation(target, base.eventId)
            if (reject == "owner") {
                var decodingRefused = false
                try { store.localEditPreparation(target, base.eventId) }
                catch (_: IllegalStateException) { decodingRefused = true }
                assertTrue("A capsule with a different owner must fail closed during decoding", decodingRefused)
            }
            val retainedResult = journal.readVerified(target, prepared.id)
            val before = values()
            assertEquals(old, store.projectionReceipt(target, base.eventId))
            assertNull(receipt())
            var pendingObserved = false
            beforeFreshAcknowledgement = {
                allowSql()
                assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
                assertEquals(old, store.projectionReceipt(target, base.eventId))
                assertEquals(before, values())
                pendingObserved = true
            }
            rejectFreshAcknowledgement = reject == "native"
            if (reject == null) {
                assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
                assertTrue("The ordinary receipt must survive reservation until the native write", pendingObserved)
                allowSql(); assertNull(store.projectionReceipt(target, base.eventId))
                assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
                val clean = values()
                assertEquals(before - setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3),
                    clean - setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3))
                assertEquals("0", clean[Events.DIRTY]); assertSqlRestored(durableUnknown)
                val completion = checkNotNull(journal.readCompletion(target, prepared.id))
                assertEquals(result, completion.result)
                reopenOwner()
                assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
                assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
                allowSql(); assertNull(store.projectionReceipt(target, base.eventId))
                assertEquals(completion, journal.readCompletion(target, prepared.id))
                assertEquals(clean, values()); assertEquals(1, writes.get())
            } else {
                assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
                assertEquals(TitleUpdateCompletionResult.UNRESOLVED, complete())
                allowSql()
                assertEquals(old, store.projectionReceipt(target, base.eventId))
                assertEquals(owner, store.localEdit(target, base.eventId))
                if (invalidSource) assertEquals(rawCapsule, rawLocalPreparationSnapshot())
                else assertEquals(capsule, store.localEditPreparation(target, base.eventId))
                assertEquals(base, store.projectedBaseline(target, base.eventId))
                assertNull(journal.readCompletion(target, prepared.id)); assertEquals(before, values())
                if (reject == "native") {
                    assertTrue(pendingObserved)
                    assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
                    val pendingAck = receipt()
                    reopenOwner()
                    assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
                    allowSql(); assertEquals(old, store.projectionReceipt(target, base.eventId))
                    assertEquals(pendingAck, receipt()); assertEquals(before, values())
                    assertEquals(1, writes.get()); assertEquals(1, inspections.get())
                } else { assertFalse(pendingObserved); assertNull(receipt()); assertEquals(0, writes.get()) }
            }
            assertSingleOriginalRow()
            assertEquals(durableUnknown, journal.read(target, prepared.id))
            assertEquals(retainedResult, journal.readVerified(target, prepared.id))
        }

        /** Deliberately mismatched source rows cannot always pass the domain decoder.
         * Compare every bounded stored field instead of treating decoding as fixture success.
         */
        private fun rawLocalPreparationSnapshot(): List<Pair<String, String>> {
            val columns = listOf("scope", "mutation", "event", "adapter", "version", "base", "local", "state",
                "timestamp", "prep_adapter", "prep_version", "payload")
            val selected = columns.joinToString(",") { "CASE WHEN length(CAST($it AS BLOB))<=16384 THEN CAST($it AS TEXT) END" }
            return store.readableDatabase.rawQuery("SELECT $selected FROM local_edit_preparations WHERE " +
                "scope=(SELECT scope FROM calendars WHERE account=? AND calendar=? AND generation=?) AND event=? LIMIT 2",
                arrayOf(target.account.value, target.calendar.value, target.generation.toString(), base.eventId.value)).use { row ->
                assertTrue("Require exactly one retained synthetic capsule", row.count == 1 && row.moveToFirst())
                columns.mapIndexed { index, column ->
                    assertFalse("Require a complete bounded capsule snapshot", row.isNull(index))
                    column to row.getString(index)
                }
            }
        }

        suspend fun redundantProjectionDeletionRollsBackWithBaselineAndRecoversByInspection() {
            val old = checkNotNull(ordinaryReceipt)
            check(pendingProjection == "redundant")
            allowSql()
            journal.retainEvidence(unknown, "synthetic-redundant-projection-rollback-evidence")
            val durableUnknown = checkNotNull(journal.read(target, prepared.id))
            store.transaction(target) { putRemote(verified) }
            val owner = checkNotNull(store.localEdit(target, base.eventId))
            assertEquals(intent.copy(state = LocalEditState.CONFLICT), owner)
            val before = values()
            var reserved = false
            beforeFreshAcknowledgement = {
                allowSql()
                assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
                assertEquals(old, store.projectionReceipt(target, base.eventId))
                // Persistent trigger, isolated to this fixture database. Abort the same SQL
                // transaction after it retired the redundant receipt but before base advance.
                store.writableDatabase.execSQL("CREATE TRIGGER fixture_redundant_projection_restore_loss " +
                    "BEFORE UPDATE ON projection_bases BEGIN SELECT RAISE(ABORT,'synthetic baseline restore loss'); END")
                reserved = true
            }
            assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
            assertTrue(reserved)
            allowSql()
            val pendingAck = checkNotNull(receipt())
            val clean = values()
            assertEquals(TitleUpdateAcknowledgementState.PENDING, pendingAck.state)
            assertEquals(before + mapOf(Events.DIRTY to "0",
                Events.SYNC_DATA2 to digest("revision-v1", verified.revision.value),
                Events.SYNC_DATA3 to "calendeer-projection-receipt-v1:${pendingAck.receipt.projection.nonce}"), clean)
            assertNotEquals(before[Events.SYNC_DATA3], clean[Events.SYNC_DATA3])
            assertEquals(old, store.projectionReceipt(target, base.eventId))
            assertEquals(owner, store.localEdit(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(base, store.projectedBaseline(target, base.eventId))
            assertNull(journal.readCompletion(target, prepared.id))
            assertEquals(1, writes.get()); assertEquals(0, inspections.get()); assertSingleOriginalRow()

            reopenOwner(); allowSql()
            journal.admitDispatch = { error("SQL rollback recovery must never claim another send") }
            assertEquals(old, store.projectionReceipt(target, base.eventId))
            assertEquals(pendingAck, receipt()); assertEquals(clean, values())
            // Absence of IF EXISTS also verifies that the failure survived database reopening.
            store.writableDatabase.execSQL("DROP TRIGGER fixture_redundant_projection_restore_loss")
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            allowSql(); assertNull(store.projectionReceipt(target, base.eventId))
            assertSqlRestored(durableUnknown)
            val completed = checkNotNull(journal.readCompletion(target, prepared.id))
            assertEquals(result, completed.result)
            assertEquals(clean, values()); assertSingleOriginalRow()
            assertEquals(1, writes.get()); assertEquals(1, inspections.get())
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            allowSql(); assertNull(store.projectionReceipt(target, base.eventId))
            assertEquals(completed, journal.readCompletion(target, prepared.id))
            assertEquals(durableUnknown, journal.read(target, prepared.id))
            assertEquals(result, journal.readVerified(target, prepared.id))
            assertEquals(clean, values()); assertEquals(1, writes.get()); assertEquals(1, inspections.get())
        }

        private suspend fun seedTransferred() {
            val localId = LocalEventId(UUID.randomUUID().toString())
            val captured = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-origin-capture"),
                localId, target, format, Instant.parse("2030-10-01T12:00:00Z")), base.content)
            val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(captured,
                MutationId(UUID(0, 9).toString()), AdapterFormat("synthetic-create-wire", 1)), "synthetic-create-opaque")
            // The transferred drain checks these original journals independently of the mutable
            // import frame. Persist the same synthetic source as a real completed CREATE path.
            captures.admitCapture = { it == target }
            assertTrue(captures.reserve(captured.reservation))
            assertEquals(captured, captures.retain(captured))
            val permit = captures.reserveSubmission(submission.reservation)!!
            assertEquals(submission, captures.retainSubmission(permit, submission))
            createAttempts.admitDispatch = { it == target }
            createAttempts.admitAssociation = { it == target }
            val identity = createAttempts.rememberCaptured(submission)
            val originalAttempt = CreateAttemptRecord(identity.attempt.id, target, identity.attempt.format, submission.protectedPayload)
            assertTrue(createAttempts.claimCaptured(originalAttempt, identity))
            val binding = createAttempts.bindCaptured(originalAttempt.copy(state = CreateAttemptState.OUTCOME_UNKNOWN),
                identity, CreateSubmissionObservation.Verified(identity.attempt, base))
            val attachment = createAttempts.prepareCapturedAttachment(binding)
            originalSubmission = submission
            createAttempts.admitDispatch = { error("Transferred drain must never dispatch another CREATE") }
            captures.admitCapture = { false }
            val origin = CapturedCreateBaseline(attachment, base)
            val candidate = ContentValues().apply {
                put(Events.CALENDAR_ID, calendar); put(Events.TITLE, base.content.title)
                put(Events.DESCRIPTION, base.content.description); put(Events.EVENT_LOCATION, base.content.location)
                put(Events.DIRTY, 1); put(Events.DELETED, 0); put(Events.STATUS, Events.STATUS_CONFIRMED)
                put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
                putNull(Events.ORGANIZER); putNull(Events.EVENT_COLOR); putNull(Events.EVENT_COLOR_KEY)
                for (flag in listOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
                    Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM,
                    Events.ACCESS_LEVEL)) put(flag, 0)
                when (val time = base.content.time) {
                    is EventTime.AllDay -> {
                        put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC")
                        put(Events.EVENT_END_TIMEZONE, "UTC")
                        put(Events.DTSTART, time.start.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
                        put(Events.DTEND, time.endExclusive.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
                    }
                    is EventTime.Timed -> {
                        put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id)
                        put(Events.EVENT_END_TIMEZONE, time.zone.id)
                        put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
                    }
                }
            }
            row = android.content.ContentUris.parseId(context.contentResolver.insert(uri, candidate)!!)
            // Supplying SELF_ATTENDEE_STATUS on insertion creates a self-attendee,
            // even for zero. Leave Android's default and qualify the actual child table.
            context.contentResolver.query(syncUri(CalendarContract.Attendees.CONTENT_URI), arrayOf("_id"),
                "${CalendarContract.Attendees.EVENT_ID}=?", arrayOf(row.toString()), null)!!.use { assertEquals(0, it.count) }
            val snapshot = native.captureAndStampSimpleCreate(target, row, localId)
            assertNotNull("Unmarked synthetic CREATE must be capturable", snapshot)
            assertEquals(base.content, snapshot!!.content)
            val attached = native.attachCapturedCreate(attachment)!!
            assertTrue(store.retainCapturedBaseline(origin, attached))
            val acknowledgement = CapturedCreateAcknowledgementReceipt(origin, UUID.randomUUID().toString())
            store.reserveCapturedAcknowledgement(acknowledgement)
            assertEquals(CapturedCreateAcknowledgementState.PROVEN,
                store.retainCapturedAcknowledgement(native.acknowledgeCapturedCreate(acknowledgement)!!).state)
            val transfer = store.reserveCapturedHandoff(CapturedCreateHandoffReceipt(acknowledgement, UUID.randomUUID().toString()))
            frame = store.retainCapturedHandoff(transfer.frame.receipt,
                native.inspectAcknowledgedCreateClean(acknowledgement)!!).frame
            assertOriginalCreateProvenance()
        }

        private suspend fun assertOriginalCreateProvenance() {
            if (!transferred) return
            val submitted = requireNotNull(originalSubmission)
            val identity = CapturedCreateDispatchIdentity.of(submitted)
            val attachment = requireNotNull(frame).receipt.acknowledgement.baseline.receipt
            LocalCreateStore(context, captureName).use { reopened ->
                reopened.admitCapture = { it == target }
                assertEquals(submitted, reopened.readSubmission(target, identity.localId))
            }
            CreateAttemptStore(context, createAttemptName).use { reopened ->
                assertEquals(identity, reopened.capturedIdentity(target, identity.attempt.id))
                assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, reopened.read(target, identity.attempt.id)!!.state)
                assertEquals(attachment.binding, reopened.capturedBinding(identity))
                assertEquals(attachment, reopened.capturedAttachment(attachment.binding))
            }
        }

        suspend fun captureTransferred(id: MutationId) {
            allowSql(); native.admitOperation = { true }
            val current = store.capturedHandoff(target, base.eventId)!!.frame
            val observed = native.captureCapturedHandoffCurrent(current) as CapturedHandoffCurrentObservation.Supported
            assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(observed.snapshot, id))
        }

        fun allowSql() { store.admitTransaction = { it == target } }
        fun mutateTitle(title: String) {
            assertEquals(1, context.contentResolver.update(uri, ContentValues().apply {
                put(Events.TITLE, title); put(Events.DIRTY, 1)
            }, "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())))
        }
        fun values(): Map<String, String?> = context.contentResolver.query(uri, null,
            "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst())
            it.columnNames.indices.associate { column -> it.getColumnName(column) to if (it.isNull(column)) null else it.getString(column) }
        }
        fun assertSingleOriginalRow() {
            context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(row, it.getLong(0))
            }
        }
        suspend fun acknowledge() = coordinator.acknowledgeLocalTitle(target, base.eventId, prepared.id, journal)
        suspend fun complete(attempt: MutationId = prepared.id) =
            coordinator.completeLocalTitle(target, base.eventId, attempt, journal)

        suspend fun secondCycle(): Pair<TitleUpdateAttemptRecord, TitleUpdateVerifiedResult> {
            val id = MutationId("synthetic-second-intent")
            mutateTitle("Synthetic second submitted system title")
            if (transferred) captureTransferred(id) else
                assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, id))
            allowSql()
            val next = store.localEdit(target, base.eventId)!!
            assertEquals(verified, next.base)
            val nextPreparation = preparation.copy(intent = next, timestamp = preparation.timestamp.plusSeconds(1))
            assertTrue(store.retainLocalEditPreparation(nextPreparation))
            val nextAttempt = prepared.copy(id = MutationId("synthetic-second-attempt"), preparation = nextPreparation)
            journal.remember(nextAttempt); journal.admitDispatch = { it == target }; assertTrue(journal.claim(nextAttempt))
            journal.admitDispatch = { error("Acknowledgement and completion cannot dispatch") }
            val unknown = nextAttempt.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
            val result = TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
                TitleUpdateAttemptIdentity.of(unknown), verified.copy(revision = Revision("synthetic-second-revision"), content = next.content)))
            journal.retainVerified(result)
            return nextAttempt to result
        }

        suspend fun twoCycles(recoverSecond: Boolean = false) {
            assertEquals(TitleUpdateCompletionResult.UNRESOLVED, complete())
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            val firstReceipt = receipt()!!
            val firstNative = values()
            journal.writableDatabase.execSQL("CREATE TRIGGER fixture_completion_loss BEFORE UPDATE OF completion ON title_update_attempts BEGIN SELECT RAISE(ABORT,'synthetic completion loss'); END")
            assertEquals(TitleUpdateCompletionResult.UNRESOLVED, complete())
            assertNull(journal.readCompletion(target, prepared.id))
            assertEquals(firstNative, values())
            journal.writableDatabase.execSQL("DROP TRIGGER fixture_completion_loss")
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            assertEquals(TitleUpdateCompletion.of(result, firstReceipt), journal.readCompletion(target, prepared.id))
            assertEquals(firstNative, values())
            val (second, secondResult) = secondCycle()
            val nextNative = values()
            allowSql()
            val nextIntent = store.localEdit(target, base.eventId)
            val secondFrame = store.capturedHandoff(target, base.eventId)?.frame
            val pending = if (recoverSecond) store.prepareTitleUpdateAcknowledgement(secondResult, secondFrame)!! else null
            pending?.let { assertTrue(it.fresh) }
            // A late replay belongs to the first attempt, even with a new owner or PENDING receipt.
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            allowSql(); assertEquals(nextIntent, store.localEdit(target, base.eventId))
            assertEquals(pending?.record, store.titleUpdateAcknowledgement(target, base.eventId, second.id))
            assertEquals(nextNative, values()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
            if (pending != null) {
                // Simulate a process loss after the directly reserved fresh native write.
                native.admitOperation = { true }
                assertNotNull(native.acknowledgeTitleUpdate(pending.record.receipt))
            }
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED,
                coordinator.acknowledgeLocalTitle(target, base.eventId, second.id, journal))
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete(second.id))
            allowSql()
            assertEquals(secondResult.event, store.projectedBaseline(target, base.eventId))
            assertNull(store.localEdit(target, base.eventId)); assertNull(store.localEditPreparation(target, base.eventId))
            assertEquals(firstReceipt, store.titleUpdateAcknowledgement(target, base.eventId, prepared.id))
            assertEquals(unknown, journal.read(target, prepared.id))
            assertEquals(second.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), journal.read(target, second.id))
            TitleUpdateAttemptStore(context, attemptName).use { reopened ->
                assertEquals(journal.readCompletion(target, prepared.id), reopened.readCompletion(target, prepared.id))
                assertEquals(journal.readCompletion(target, second.id), reopened.readCompletion(target, second.id))
                reopened.admitDispatch = { true }; assertFalse(reopened.claim(prepared)); assertFalse(reopened.claim(second))
            }
            val finalNative = values()
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            assertEquals(finalNative, values()); assertSingleOriginalRow()
            assertEquals(if (recoverSecond) 1 else 2, writes.get())
            assertEquals(if (recoverSecond) 1 else 0, inspections.get())
            if (transferred) {
                allowSql()
                val final = store.capturedHandoff(target, base.eventId)!!
                assertEquals(frame!!.receipt, final.frame.receipt)
                assertEquals(secondResult.event, final.frame.base)
                assertEquals(secondFrame, journal.readCompletion(target, second.id)!!.receipt.handoff)
            }
        }
        suspend fun receipt(): TitleUpdateAcknowledgementRecord? { allowSql(); return store.titleUpdateAcknowledgement(target, base.eventId) }

        suspend fun retainPreparedWireWithoutConsumingNativeOwner() {
            val port = object : CalendarTitleUpdateSubmissionPort {
                override val titleUpdateFormat = prepared.format
                override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
                    error("Retention cannot prepare remote wire")
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
                    error("Retention cannot submit")
                override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation =
                    error("Retention cannot inspect service state")
                override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation =
                    error("Retention cannot inspect service state")
            }
            val before = values()
            assertEquals("1", before[Events.DIRTY])
            assertEquals(LocalTitleAttemptRetentionResult.RETAINED, coordinator.retainLocalTitleAttempt(prepared, journal, port))
            assertEquals(prepared, journal.read(target, prepared.id)); assertNull(journal.readVerified(target, prepared.id))
            assertNull(journal.readCompletion(target, prepared.id)); assertEquals(before, values())
            assertEquals(0, writes.get()); assertEquals(0, inspections.get()); assertSingleOriginalRow()
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(base, store.projectedBaseline(target, base.eventId)); assertNull(receipt())
            assertOriginalCreateProvenance()
            if (transferred) assertEquals(frame, store.capturedHandoff(target, base.eventId)!!.frame)
            TitleUpdateAttemptStore(context, attemptName).use { reopened ->
                reopened.admitDispatch = { error("Reopened retention cannot claim dispatch") }
                assertEquals(LocalTitleAttemptRetentionResult.HISTORICAL, coordinator.retainLocalTitleAttempt(prepared, reopened, port))
                assertEquals(prepared, reopened.read(target, prepared.id))
            }
            assertEquals(before, values()); assertSingleOriginalRow()
            mutateTitle("Synthetic later title before any submission")
            val later = values()
            assertEquals(LocalTitleAttemptRetentionResult.UNRESOLVED, coordinator.retainLocalTitleAttempt(prepared, journal, port))
            assertEquals(prepared, journal.read(target, prepared.id)); assertEquals(later, values())
            assertEquals(0, writes.get()); assertEquals(0, inspections.get())
        }

        suspend fun missingCreateSourceCannotFallBackToOrdinaryWireRetention(dropFrame: Boolean) {
            val before = values()
            if (dropFrame) assertEquals(1, store.writableDatabase.delete("captured_create_handoffs", null, null))
            else assertEquals(1, createAttempts.writableDatabase.delete("captured_create_attachments", null, null))
            val port = object : CalendarTitleUpdateSubmissionPort {
                override val titleUpdateFormat = prepared.format
                override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = error("No preparation")
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation = error("No send")
                override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("No inspection")
            }
            assertEquals(LocalTitleAttemptRetentionResult.UNRESOLVED, coordinator.retainLocalTitleAttempt(prepared, journal, port))
            assertNull(journal.read(target, prepared.id)); assertEquals(before, values()); assertSingleOriginalRow()
            allowSql(); assertEquals(intent, store.localEdit(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(0, writes.get()); assertEquals(0, inspections.get())
        }

        suspend fun offlineBatchClosesStoredProofWithoutAnotherSend() {
            val progress = coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal)
            assertEquals(1, progress.completed); assertEquals(0, progress.held); assertEquals(0, progress.failed)
            assertFalse(progress.inactive); assertNull(progress.next)
            assertSqlRestored(); assertNotNull(journal.readCompletion(target, prepared.id))
            assertEquals(1, writes.get()); assertEquals(0, inspections.get()); assertSingleOriginalRow()
            val before = values()
            assertEquals(TitleUpdateOfflineRecoveryProgress(), coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal))
            assertEquals(before, values()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
        }

        suspend fun scheduledOfflineRecoveryPreservesLaterEdit(completionGap: Boolean) {
            val cursorName = "title-recovery-integration-$token"
            fun recovery() = SystemTitleRecoveryController(AndroidTitleRecoveryCursorStore(context, cursorName),
                { query -> coordinator.recoverLocalTitles(query, journal) })
            try {
                journal.admitDispatch = { error("Offline scheduling cannot claim an attempt") }
                val before = values()
                if (completionGap) journal.writableDatabase.execSQL("CREATE TRIGGER scheduled_completion_gap BEFORE UPDATE OF completion ON title_update_attempts BEGIN SELECT RAISE(ABORT,'synthetic completion gap'); END")
                assertEquals(SystemTitleRecoveryProgress(), recovery().run(target) { true })
                assertEquals(1, writes.get()); assertEquals(0, inspections.get())
                assertEquals("0", values()[Events.DIRTY])
                val metadata = setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3)
                assertEquals(before.filterKeys { it !in metadata }, values().filterKeys { it !in metadata })
                assertEquals(TitleUpdateAcknowledgementState.RESTORED, receipt()!!.state)
                allowSql(); assertNull(store.localEdit(target, base.eventId))
                if (completionGap) {
                    assertNull(journal.readCompletion(target, prepared.id))
                    journal.writableDatabase.execSQL("DROP TRIGGER scheduled_completion_gap")
                } else assertNotNull(journal.readCompletion(target, prepared.id))
                mutateTitle("Synthetic later edit across scheduled recovery")
                val later = values()
                reopenOwner()
                journal.admitDispatch = { error("Reopened offline scheduling cannot claim an attempt") }
                assertEquals(SystemTitleRecoveryProgress(), recovery().run(target) { true })
                assertEquals(later, values()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
                assertSqlRestored(); assertOriginalCreateProvenance(); assertSingleOriginalRow()
                assertEquals(TitleUpdateCompletion.of(result, receipt()!!), journal.readCompletion(target, prepared.id))
                val nextId = MutationId("synthetic-next-scheduled-owner")
                if (transferred) captureTransferred(nextId) else
                    assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, nextId))
                allowSql(); val nextOwner = store.localEdit(target, base.eventId)
                assertEquals(SystemTitleRecoveryProgress(), recovery().run(target) { true })
                allowSql(); assertEquals(nextOwner, store.localEdit(target, base.eventId))
                assertEquals(later, values()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
                assertNull(AndroidTitleRecoveryCursorStore(context, cursorName).read(target))
            } finally { context.deleteSharedPreferences(cursorName) }
        }

        suspend fun inspectionBatchRecoversMissingProof(restoreGap: Boolean = false) {
            journal.writableDatabase.execSQL("UPDATE title_update_attempts SET result=NULL")
            assertNull(journal.readVerified(target, prepared.id))
            var serviceInspections = 0
            val port = object : CalendarTitleUpdateSubmissionPort {
                override val titleUpdateFormat = prepared.format
                override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
                    error("Recovery cannot prepare a service request")
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
                    error("Recovery cannot send a service request")
                override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation =
                    error("Recovery cannot use unrestricted inspection")
                override suspend fun inspect(record: TitleUpdateAttemptRecord,
                    admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                    assertEquals(unknown, record); assertTrue(admit(target)); assertFalse(admit(target.copy(generation = 8)))
                    serviceInspections++
                    return TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), verified)
                }
            }
            if (restoreGap) store.writableDatabase.execSQL("CREATE TRIGGER fixture_inspection_restore_gap BEFORE UPDATE OF state ON title_update_acknowledgements WHEN NEW.state='RESTORED' BEGIN SELECT RAISE(ABORT,'synthetic restoration gap'); END")
            val first = coordinator.inspectAndRecoverLocalTitles(TitleUpdateWorkQuery(target), journal, port, allowNetwork = true)
            assertEquals(1, serviceInspections); assertEquals(result, journal.readVerified(target, prepared.id))
            assertEquals(unknown, journal.read(target, prepared.id)); assertEquals(1, writes.get())
            if (restoreGap) {
                assertEquals(TitleUpdateRecoveryProgress(held = 1), first)
                assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
                assertNull(journal.readCompletion(target, prepared.id))
                mutateTitle("Synthetic later edit during inspection restoration gap")
                val later = values()
                store.writableDatabase.execSQL("DROP TRIGGER fixture_inspection_restore_gap")
                TitleUpdateAttemptStore(context, attemptName).use { reopened ->
                    assertEquals(TitleUpdateRecoveryProgress(completed = 1), coordinator.inspectAndRecoverLocalTitles(
                        TitleUpdateWorkQuery(target), reopened, port, allowNetwork = true))
                    assertEquals(result, reopened.readVerified(target, prepared.id))
                    assertNotNull(reopened.readCompletion(target, prepared.id))
                }
                assertEquals(later, values()); assertEquals("1", values()[Events.DIRTY])
                assertEquals(1, inspections.get())
            } else {
                assertEquals(TitleUpdateRecoveryProgress(completed = 1), first)
                assertNotNull(journal.readCompletion(target, prepared.id))
                assertEquals(0, inspections.get())
            }
            assertSqlRestored(); assertSingleOriginalRow()
            assertEquals(1, writes.get()); assertEquals(1, serviceInspections)
            val before = values()
            assertEquals(TitleUpdateRecoveryProgress(), coordinator.inspectAndRecoverLocalTitles(
                TitleUpdateWorkQuery(target), journal, port, allowNetwork = true))
            assertEquals(before, values()); assertEquals(1, serviceInspections); assertEquals(1, writes.get())
        }

        suspend fun offlineBatchRecoversCompletionGapAfterReopeningAndKeepsLaterDirtyEdit() {
            journal.writableDatabase.execSQL("CREATE TRIGGER fixture_completion_gap BEFORE UPDATE OF completion ON title_update_attempts BEGIN SELECT RAISE(ABORT,'synthetic completion gap'); END")
            val held = coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), journal)
            assertEquals(0, held.completed); assertEquals(1, held.held); assertEquals(0, held.failed)
            assertSqlRestored(); assertNull(journal.readCompletion(target, prepared.id))
            assertEquals(1, writes.get()); assertEquals(0, inspections.get())
            mutateTitle("Synthetic later edit during completion gap")
            val later = values()
            journal.writableDatabase.execSQL("DROP TRIGGER fixture_completion_gap")
            TitleUpdateAttemptStore(context, attemptName).use { reopened ->
                val completed = coordinator.recoverLocalTitles(TitleUpdateWorkQuery(target), reopened)
                assertEquals(1, completed.completed); assertEquals(0, completed.held); assertEquals(0, completed.failed)
                assertNotNull(reopened.readCompletion(target, prepared.id))
                assertEquals(unknown, reopened.read(target, prepared.id))
            }
            assertEquals(later, values()); assertEquals("1", values()[Events.DIRTY])
            assertEquals(1, writes.get()); assertEquals(0, inspections.get()); assertSingleOriginalRow()
        }

        private fun pendingFlag(source: ImportStore = store): Int = source.readableDatabase.rawQuery(
            "SELECT pending FROM records WHERE event=?", arrayOf(base.eventId.value)).use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getInt(0)
        }

        private suspend fun cacheDistinctObservation(): RemoteChange.Upsert {
            val observed = verified.copy(revision = Revision("synthetic-distinct-cached-revision"),
                content = verified.content.copy(title = "Synthetic distinct remote title"))
            allowSql()
            store.transaction(target) { putRemote(observed); putProjection(ProjectionIntent(target, observed)) }
            // The local owner is still held: receipt restoration must explicitly rearm this work.
            assertEquals(0, pendingFlag())
            return observed
        }

        private suspend fun assertReopenedPending(observed: RemoteChange) {
            ImportStore(context, importName).use { reopened ->
                reopened.admitTransaction = { it == target }
                assertEquals(1, pendingFlag(reopened))
                assertEquals(observed, reopened.transaction(target) { remote(base.eventId) })
                assertEquals(verified, reopened.projectedBaseline(target, base.eventId))
                assertEquals(TitleUpdateAcknowledgementState.RESTORED,
                    reopened.titleUpdateAcknowledgement(target, base.eventId, prepared.id)!!.state)
            }
        }

        suspend fun cachedObservationDrainsOfflineOnOriginalRow() {
            var observed: RemoteChange.Upsert? = null
            afterFreshAcknowledgement = { observed = cacheDistinctObservation() }
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            val cached = requireNotNull(observed)
            assertEquals(1, writes.get()); assertEquals(0, inspections.get())
            assertEquals(local.title, values()[Events.TITLE]); assertEquals("0", values()[Events.DIRTY])
            assertReopenedPending(cached)
            assertOriginalCreateProvenance()
            val afterAck = values()
            coordinator.run(network = false)
            val drained = values()
            assertEquals(cached.content.title, drained[Events.TITLE]); assertEquals("0", drained[Events.DIRTY])
            assertEquals(digest("revision-v1", cached.revision.value), drained[Events.SYNC_DATA2])
            for (field in listOf(Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND,
                Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7))
                assertEquals(afterAck[field], drained[field])
            assertSingleOriginalRow(); assertEquals(0, pendingFlag())
            allowSql()
            assertEquals(cached, store.projectedBaseline(target, base.eventId))
            assertEquals(cached, store.transaction(target) { remote(base.eventId) })
            assertNull(store.localEdit(target, base.eventId))
            if (transferred) {
                val advanced = store.capturedHandoff(target, base.eventId)!!
                assertEquals(frame!!.receipt, advanced.frame.receipt)
                assertEquals(cached, advanced.frame.base); assertFalse(advanced.waiting)
            }
            // Replaying the first ACK is historical; it cannot rearm the already settled cache.
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(0, pendingFlag()); assertEquals(drained, values())
            assertEquals(1, writes.get()); assertEquals(0, inspections.get())
            coordinator.run(network = false)
            assertEquals(drained, values()); assertEquals(0, pendingFlag()); assertSingleOriginalRow()
        }

        suspend fun ordinaryCachedDeletionDrainsOfflineWithoutRecreatingTheRow() {
            val deleted = RemoteChange.Deleted(base.eventId, Revision("synthetic-later-cached-deletion"))
            afterFreshAcknowledgement = {
                allowSql()
                store.transaction(target) { putRemote(deleted); putProjection(ProjectionIntent(target, deleted)) }
                assertEquals(0, pendingFlag())
            }
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertSingleOriginalRow(); assertReopenedPending(deleted)
            coordinator.run(network = false)
            context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use { assertEquals(0, it.count) }
            assertEquals(0, pendingFlag()); allowSql()
            assertEquals(deleted, store.transaction(target) { remote(base.eventId) })
            assertNull(store.projectedBaseline(target, base.eventId))
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(0, pendingFlag()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
            coordinator.run(network = false)
            context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use { assertEquals(0, it.count) }
        }

        suspend fun cachedObservationSurvivesRollbackAndNeverOverwritesLaterDirtyEdit() {
            var observed: RemoteChange.Upsert? = null
            afterFreshAcknowledgement = { observed = cacheDistinctObservation() }
            // This failure is after the queue update, so queue/base/owner/receipt must roll back together.
            store.writableDatabase.execSQL("CREATE TRIGGER fixture_title_queue_restore_loss BEFORE UPDATE OF state ON title_update_acknowledgements WHEN NEW.state='RESTORED' BEGIN SELECT RAISE(ABORT,'synthetic restore loss'); END")
            assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
            val cached = requireNotNull(observed)
            assertEquals(0, pendingFlag())
            assertEquals("0", values()[Events.DIRTY])
            assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
            allowSql()
            assertEquals(base, store.projectedBaseline(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertEquals(cached, store.transaction(target) { remote(base.eventId) })
            val laterTitle = "Synthetic later dirty title retained offline"
            mutateTitle(laterTitle)
            val laterNative = values()
            store.writableDatabase.execSQL("DROP TRIGGER fixture_title_queue_restore_loss")
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(1, writes.get()); assertEquals(1, inspections.get())
            assertEquals(laterNative, values()); assertReopenedPending(cached)
            assertOriginalCreateProvenance()
            if (transferred) {
                allowSql(); assertFalse(store.capturedHandoff(target, base.eventId)!!.waiting)
            }
            coordinator.run(network = false)
            assertEquals(laterNative, values()); assertEquals("1", values()[Events.DIRTY]); assertSingleOriginalRow()
            allowSql()
            // Prove the transferred drain reached native qualification and explicitly held DIRTY.
            if (transferred) assertTrue(store.capturedHandoff(target, base.eventId)!!.waiting)
            assertEquals(verified, store.projectedBaseline(target, base.eventId))
            val nextId = MutationId("synthetic-later-conflicting-title")
            if (transferred) {
                native.admitOperation = { true }
                val current = store.capturedHandoff(target, base.eventId)!!.frame
                val snapshot = native.captureCapturedHandoffCurrent(current) as CapturedHandoffCurrentObservation.Supported
                assertEquals(LocalEditState.CONFLICT, store.retainCapturedHandoffLocalChange(snapshot.snapshot, nextId))
            } else assertEquals(LocalEditCaptureResult.CONFLICT,
                coordinator.captureLocalEdit(target, base.eventId, nextId))
            allowSql()
            val captured = store.localEdit(target, base.eventId)!!
            assertEquals(nextId, captured.id); assertEquals(verified, captured.base)
            assertEquals(verified.content.copy(title = laterTitle), captured.content)
            assertEquals(LocalEditState.CONFLICT, captured.state)
            assertTrue(store.pending(target).isEmpty())
            assertEquals(cached, store.transaction(target) { remote(base.eventId) })
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            allowSql(); assertEquals(captured, store.localEdit(target, base.eventId))
            coordinator.run(network = false)
            assertEquals(laterNative, values()); assertSingleOriginalRow()
            assertEquals(1, writes.get()); assertEquals(1, inspections.get())
        }

        /** Incoming self-echo can arrive before a receipt exists. Its observation must never
         * re-send the UNKNOWN request or silently reset an arbitrary conflict to PAUSED.
         */
        suspend fun verifiedSelfEchoBeforeFreshAcknowledgement(rejectedObservation: String? = null) {
            check(!preparedOnly)
            allowSql()
            assertEquals(intent, store.localEdit(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            assertNull(receipt())
            journal.retainEvidence(unknown, "synthetic-retained-response-evidence")
            val durableUnknown = checkNotNull(journal.read(target, prepared.id))
            val durableResult = checkNotNull(journal.readVerified(target, prepared.id))
            val dirty = values()
            assertEquals("1", dirty[Events.DIRTY]); assertEquals(local.title, dirty[Events.TITLE])
            allowSql()
            store.transaction(target) { putRemote(result.event) }
            assertEquals(intent.copy(state = LocalEditState.CONFLICT), store.localEdit(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            when (rejectedObservation) {
                null -> Unit
                "revision" -> store.transaction(target) {
                    putRemote(verified.copy(revision = Revision("synthetic-unrelated-revision")))
                }
                "content" -> store.transaction(target) {
                    putRemote(verified.copy(content = local.copy(description = "Synthetic unrelated remote content")))
                }
                "tombstone" -> store.transaction(target) {
                    putRemote(RemoteChange.Deleted(base.eventId, Revision("synthetic-remote-tombstone")))
                }
                "deferred" -> store.transaction(target) {
                    (this as StagedSyncTransaction).defer(DeferredCalendarEvent(base.eventId, verified.revision,
                        format, DeferredEventReason.UNVERIFIED_CONTENT, "synthetic-sealed-unverified-observation"))
                }
                "absent" -> assertEquals(1, store.writableDatabase.delete("records",
                    "scope=(SELECT scope FROM calendars WHERE account=? AND calendar=? AND generation=?) AND event=?",
                    arrayOf(target.account.value, target.calendar.value, target.generation.toString(), base.eventId.value)))
                "owner" -> assertEquals(1, store.writableDatabase.update("local_edits", ContentValues().apply {
                    put("mutation", "synthetic-distinct-echo-owner")
                }, null, null))
                "capsule" -> assertEquals(1, store.writableDatabase.delete("local_edit_preparations", null, null))
                "frame" -> assertEquals(1, store.writableDatabase.delete("captured_create_handoffs", null, null))
                "waiting" -> assertEquals(1, store.writableDatabase.update("captured_create_handoffs", ContentValues().apply {
                    put("waiting", 0)
                }, null, null))
                "release" -> assertEquals(1, store.writableDatabase.update("captured_create_handoffs", ContentValues().apply {
                    put("state", "PENDING")
                }, null, null))
                "frame_nonce" -> assertEquals(1, store.writableDatabase.update("captured_create_handoffs", ContentValues().apply {
                    put("nonce", "synthetic-altered-handoff-nonce")
                }, null, null))
                "origin" -> assertEquals(1, store.writableDatabase.delete("captured_create_bases", null, null))
                "create_ack" -> assertEquals(1, store.writableDatabase.update("captured_create_acknowledgements", ContentValues().apply {
                    put("state", "UNKNOWN")
                }, null, null))
                "create_fingerprint" -> assertEquals(1, store.writableDatabase.update("captured_create_acknowledgements", ContentValues().apply {
                    put("fingerprint", "synthetic-altered-origin-fingerprint")
                }, null, null))
                "frame_base" -> store.writableDatabase.execSQL("UPDATE projection_bases SET payload=replace(payload,?,?)",
                    arrayOf("synthetic-original-revision", "synthetic-distinct-frame-revision"))
                "projection" -> store.writableDatabase.execSQL("INSERT INTO projection_receipts " +
                    "(scope,event,nonce,payload,adapter,version,previous_base,protocol) " +
                    "SELECT scope,event,'synthetic-competing-projection',payload,?,?,payload,1 FROM projection_bases",
                    arrayOf<Any?>(format.id, format.version))
                else -> error("Unknown synthetic observation case")
            }
            val expectedOwner = intent.copy(state = LocalEditState.CONFLICT).let {
                if (rejectedObservation == "owner") it.copy(id = MutationId("synthetic-distinct-echo-owner")) else it
            }
            val expectedPreparation = if (rejectedObservation == "capsule") null else preparation
            val archive = store.conflictArchives(ConflictArchiveQuery(target)).entries.single()
            val archived = archiveSnapshot(archive)
            // The acknowledgement must survive reopening and qualify the conflict from durable
            // exact proof, capsule and cached observation, not from in-memory submission state.
            reopenOwner()
            allowSql()
            val conflicted = store.localEdit(target, base.eventId)
            assertEquals(expectedOwner, conflicted)
            assertNull(receipt()); assertEquals(dirty, values()); assertEquals(0, writes.get())
            if (rejectedObservation != null) {
                assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
                assertEquals(TitleUpdateCompletionResult.UNRESOLVED, complete())
                allowSql()
                assertEquals(conflicted, store.localEdit(target, base.eventId))
                if (rejectedObservation == "owner") {
                    try {
                        store.localEditPreparation(target, base.eventId)
                        fail("A capsule cannot be decoded for a different owner")
                    } catch (_: IllegalStateException) { }
                    store.readableDatabase.rawQuery("SELECT mutation,payload FROM local_edit_preparations", null).use {
                        assertEquals(1, it.count); assertTrue(it.moveToFirst())
                        assertEquals(intent.id.value, it.getString(0))
                        assertEquals(preparation.protectedPayload, it.getString(1))
                    }
                } else assertEquals(expectedPreparation, store.localEditPreparation(target, base.eventId))
                assertNull(receipt()); assertNull(journal.readCompletion(target, prepared.id))
                assertEquals(dirty, values()); assertEquals(0, writes.get())
            } else {
                assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
                assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
                val clean = values()
                val metadata = setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3)
                assertEquals(dirty - metadata, clean - metadata)
                assertEquals("0", clean[Events.DIRTY]); assertEquals(1, writes.get())
                assertSingleOriginalRow(); assertSqlRestored(durableUnknown)
                val completed = checkNotNull(journal.readCompletion(target, prepared.id))
                assertEquals(durableResult, completed.result)
                reopenOwner()
                assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
                assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
                assertEquals(completed, journal.readCompletion(target, prepared.id))
                assertEquals(clean, values()); assertEquals(1, writes.get())
                assertSqlRestored(durableUnknown)
                assertOriginalCreateProvenance()
                if (transferred) {
                    mutateTitle("Synthetic later transferred title after exact echo recovery")
                    val later = values()
                    captureTransferred(MutationId("synthetic-later-echo-owner"))
                    val next = store.localEdit(target, base.eventId)!!
                    assertEquals(verified, next.base)
                    assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
                    assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
                    allowSql(); assertEquals(next, store.localEdit(target, base.eventId))
                    assertEquals(later, values()); assertEquals(1, writes.get())
                    assertEquals(completed, journal.readCompletion(target, prepared.id))
                    assertOriginalCreateProvenance()
                }
            }
            assertEquals(durableUnknown, journal.read(target, prepared.id))
            assertEquals(durableResult, journal.readVerified(target, prepared.id))
            assertEquals(archived, archiveSnapshot(archive))
        }

        suspend fun transferredSelfEchoSqlLossPreservesLaterNativeEdit() {
            check(transferred && !preparedOnly)
            journal.retainEvidence(unknown, "synthetic-retained-echo-response")
            val durableUnknown = checkNotNull(journal.read(target, prepared.id))
            allowSql(); store.transaction(target) { putRemote(result.event) }
            val conflicted = intent.copy(state = LocalEditState.CONFLICT)
            assertEquals(conflicted, store.localEdit(target, base.eventId))
            val archive = store.conflictArchives(ConflictArchiveQuery(target)).entries.single()
            val archived = archiveSnapshot(archive)
            reopenOwner()
            store.writableDatabase.execSQL("CREATE TRIGGER fixture_echo_restore_loss BEFORE DELETE ON local_edits " +
                "BEGIN SELECT RAISE(ABORT,'synthetic echo restore loss'); END")
            assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
            val retained = receipt()!!
            assertEquals(TitleUpdateAcknowledgementState.PENDING, retained.state)
            assertEquals("0", values()[Events.DIRTY]); assertEquals(1, writes.get())
            allowSql(); assertEquals(base, store.projectedBaseline(target, base.eventId))
            assertEquals(frame, store.capturedHandoff(target, base.eventId)!!.frame)
            assertTrue(store.capturedHandoff(target, base.eventId)!!.waiting)
            assertEquals(conflicted, store.localEdit(target, base.eventId))
            assertEquals(preparation, store.localEditPreparation(target, base.eventId))
            mutateTitle("Synthetic later native edit during exact-echo restoration gap")
            val later = values()
            store.writableDatabase.execSQL("DROP TRIGGER fixture_echo_restore_loss")
            reopenOwner()
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(later, values()); assertEquals(1, writes.get()); assertEquals(1, inspections.get())
            assertSqlRestored(durableUnknown)
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            val completed = journal.readCompletion(target, prepared.id)!!
            captureTransferred(MutationId("synthetic-after-echo-restoration-gap"))
            val next = store.localEdit(target, base.eventId)!!
            assertEquals(verified, next.base)
            assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
            assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
            allowSql(); assertEquals(next, store.localEdit(target, base.eventId))
            assertEquals(later, values()); assertEquals(1, writes.get()); assertEquals(1, inspections.get())
            assertEquals(retained.receipt, receipt()!!.receipt)
            assertEquals(completed, journal.readCompletion(target, prepared.id))
            assertEquals(durableUnknown, journal.read(target, prepared.id))
            assertEquals(result, journal.readVerified(target, prepared.id))
            assertEquals(archived, archiveSnapshot(archive))
            assertOriginalCreateProvenance(); assertSingleOriginalRow()
        }

        /** Exact stored fields, including opaque deferred evidence and collection timestamps.
         * Presentation models deliberately have identity equality and redact their contents.
         */
        private fun archiveSnapshot(ref: ConflictArchiveRef): List<List<List<String?>>> =
            listOf("SELECT * FROM conflict_archives WHERE id=? ORDER BY id",
                "SELECT * FROM conflict_observations WHERE archive_id=? ORDER BY id").map { query ->
                store.readableDatabase.rawQuery(query, arrayOf(ref.id.toString())).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.columnNames.indices.map {
                            if (cursor.isNull(it)) null else cursor.getString(it)
                        })
                    }
                }
            }

        suspend fun assertSqlRestored(expectedUnknown: TitleUpdateAttemptRecord = unknown) {
            allowSql()
            assertEquals(verified, store.projectedBaseline(target, base.eventId))
            assertEquals(verified, store.transaction(target) { remote(base.eventId) })
            assertNull(store.localEdit(target, base.eventId)); assertNull(store.localEditPreparation(target, base.eventId))
            assertEquals(TitleUpdateAcknowledgementState.RESTORED, receipt()!!.state)
            assertEquals(expectedUnknown, journal.read(target, prepared.id)); assertEquals(result, journal.readVerified(target, prepared.id))
            if (transferred) {
                val current = store.capturedHandoff(target, base.eventId)!!
                assertEquals(CapturedCreateHandoffState.RELEASED, current.state); assertFalse(current.waiting)
                assertEquals(frame!!.receipt, current.frame.receipt)
                assertEquals(verified, current.frame.base)
                assertEquals(receipt()!!.receipt.projection, current.frame.lastProjection)
                assertEquals(frame, receipt()!!.receipt.handoff)
            }
        }
        suspend fun close() {
            try { coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin() }
            finally {
                try {
                    native.admitOperation = { true }; native.removeOwnedCalendar(target)
                    val account = native.accountFor(target.account)
                    val manager = AccountManager.get(context)
                    if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == account })
                        assertTrue(manager.removeAccountExplicitly(account))
                } finally {
                    journal.close(); store.close(); captures.close(); createAttempts.close()
                    context.deleteDatabase(importName); context.deleteDatabase(attemptName)
                    context.deleteDatabase(captureName); context.deleteDatabase(createAttemptName)
                }
            }
        }
    }

    private fun fixture(allDay: Boolean, transferred: Boolean = false, preparedOnly: Boolean = false, pendingProjection: String? = null,
        allDayDays: Long = 2, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(AndroidCalendarProjection(context).hasPermissions())
        val fixture = Fixture(context, allDay, transferred, preparedOnly, pendingProjection, allDayDays)
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }

    @Test fun existingOnlyRejectsNewTitleCapsulesButKeepsPreparedHistory() = fixture(allDay = false, preparedOnly = true) {
        existingOnlyRejectsNewCapsulesAndKeepsTheOriginalPreparation()
    }
    @Test fun ordinaryTimedGuardedSubmissionCompletesOfflineAfterOwnerReopening() = fixture(allDay = false, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening()
    }
    @Test fun exactVerifiedSelfEchoBeforeReceiptRecoversConflictedOwnerWithoutAnotherSend() = fixture(allDay = false) {
        verifiedSelfEchoBeforeFreshAcknowledgement()
    }
    @Test fun exactTransferredTimedSelfEchoRestoresSameRowAndPreservesOriginalCreateAndLaterOwner() = fixture(allDay = false, transferred = true) {
        verifiedSelfEchoBeforeFreshAcknowledgement()
    }
    @Test fun exactTransferredAllDaySelfEchoRestoresSameRowAndPreservesExclusiveDatesAndLaterOwner() = fixture(allDay = true, transferred = true) {
        verifiedSelfEchoBeforeFreshAcknowledgement()
    }
    @Test fun distinctOrUnverifiedTransferredObservationsCannotQualifyAnExactEcho() {
        for (allDay in listOf(false, true)) for (reason in listOf("revision", "content", "tombstone", "deferred", "absent"))
            fixture(allDay = allDay, transferred = true) { verifiedSelfEchoBeforeFreshAcknowledgement(reason) }
    }
    @Test fun alteredTransferredOriginFrameOrAcknowledgementNeverFallsBackToOrdinaryEchoRecovery() {
        for (allDay in listOf(false, true))
            for (reason in listOf("frame", "waiting", "release", "frame_nonce", "origin", "create_ack", "create_fingerprint", "frame_base", "projection"))
                fixture(allDay = allDay, transferred = true) { verifiedSelfEchoBeforeFreshAcknowledgement(reason) }
    }
    @Test fun differentTransferredOwnerOrMissingCapsuleCannotConsumeExactEchoProof() {
        for (allDay in listOf(false, true)) for (reason in listOf("owner", "capsule"))
            fixture(allDay = allDay, transferred = true) { verifiedSelfEchoBeforeFreshAcknowledgement(reason) }
    }
    @Test fun transferredTimedSelfEchoSqlRollbackRecoversByInspectionAndPreservesLaterDirtyEdit() = fixture(allDay = false, transferred = true) {
        transferredSelfEchoSqlLossPreservesLaterNativeEdit()
    }
    @Test fun transferredAllDaySelfEchoSqlRollbackPreservesExclusiveDatesDuringLaterDirtyEdit() = fixture(allDay = true, transferred = true) {
        transferredSelfEchoSqlLossPreservesLaterNativeEdit()
    }
    @Test fun redundantOrdinaryProjectionSurvivesReservationAndRetiresOnlyAfterNativeTitleProof() =
        fixture(allDay = false, pendingProjection = "redundant") { pendingOrdinaryProjectionAcknowledgement() }
    @Test fun redundantOrdinaryProjectionAndTitleReceiptBothSurviveFailedNativeAcknowledgement() =
        fixture(allDay = false, pendingProjection = "redundant") { pendingOrdinaryProjectionAcknowledgement("native") }
    @Test fun redundantOrdinaryProjectionRetirementRollsBackAfterNativeProofAndRecoversWithoutAnotherWrite() =
        fixture(allDay = false, pendingProjection = "redundant") { redundantProjectionDeletionRollsBackWithBaselineAndRecoversByInspection() }
    @Test fun nonredundantOrdinaryProjectionProfilesCannotBeRetiredByTitleAcknowledgement() {
        for (profile in listOf("revision", "content", "representation"))
            fixture(allDay = false, pendingProjection = profile) { pendingOrdinaryProjectionAcknowledgement("profile") }
    }
    @Test fun redundantOrdinaryProjectionCannotBypassDifferentOwnerCapsuleDeferredOrMissingProof() {
        for (rejected in listOf("owner", "capsule", "deferred", "proof"))
            fixture(allDay = false, pendingProjection = "redundant") { pendingOrdinaryProjectionAcknowledgement(rejected) }
    }
    @Test fun selfEchoWithDifferentRevisionCannotAcknowledgeConflictedOwner() = fixture(allDay = false) {
        verifiedSelfEchoBeforeFreshAcknowledgement("revision")
    }
    @Test fun selfEchoWithDifferentContentCannotAcknowledgeConflictedOwner() = fixture(allDay = false) {
        verifiedSelfEchoBeforeFreshAcknowledgement("content")
    }
    @Test fun tombstoneCannotQualifyAsVerifiedSelfEcho() = fixture(allDay = false) {
        verifiedSelfEchoBeforeFreshAcknowledgement("tombstone")
    }
    @Test fun deferredObservationBlocksOtherwiseExactVerifiedSelfEcho() = fixture(allDay = false) {
        verifiedSelfEchoBeforeFreshAcknowledgement("deferred")
    }
    @Test fun absentCachedObservationCannotQualifyConflictedSelfEcho() = fixture(allDay = false) {
        verifiedSelfEchoBeforeFreshAcknowledgement("absent")
    }
    @Test fun productionTitleOptionDefaultsOffWithoutAnyAdapterCalls() {
        for (allDay in listOf(false, true)) for (transferred in listOf(false, true)) fixture(allDay = allDay, transferred = transferred, preparedOnly = true) {
            productionTitleOptionOffNeverCallsPorts()
        }
    }
    @Test fun optedInTimedTitleControllerRetainsCanonicalProofAndAcknowledgesSameProviderRow() = fixture(allDay = false, preparedOnly = true) {
        productionTitleControllerUsesCanonicalWorkAndCompletesTheRealNativeOwner()
    }
    @Test fun optedInAllDayTitleControllerPreservesExclusiveDatesAcrossDstAndAcknowledgesSameRow() = fixture(allDay = true, preparedOnly = true) {
        productionTitleControllerUsesCanonicalWorkAndCompletesTheRealNativeOwner()
    }
    @Test fun optedInSingleDayTitleControllerKeepsItsExclusiveNextDayBoundary() = fixture(allDay = true, preparedOnly = true, allDayDays = 1) {
        productionTitleControllerUsesCanonicalWorkAndCompletesTheRealNativeOwner()
    }
    @Test fun optedInTransferredTimedTitleControllerCompletesSameRowWithImmutableCreateOrigin() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        productionTitleControllerUsesCanonicalWorkAndCompletesTheRealNativeOwner()
    }
    @Test fun optedInTransferredAllDayTitleControllerPreservesExclusiveDatesAndCreateOrigin() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        productionTitleControllerUsesCanonicalWorkAndCompletesTheRealNativeOwner()
    }
    @Test fun completedTransferredTimedTitleClosesOriginalCreateFollowupWithoutNativeWrite() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        completedTransferredTitleClosesCreateFollowupWithoutNativeWrite()
    }
    @Test fun completedTransferredAllDayTitleClosesOriginalCreateFollowupWithoutNativeWrite() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        completedTransferredTitleClosesCreateFollowupWithoutNativeWrite()
    }
    @Test fun completedTransferredTimedTitleSchedulesOneOfflineEchoContinuation() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        completedTransferredTitleClosesCreateFollowupWithoutNativeWrite(pendingEcho = true)
    }
    @Test fun completedTransferredAllDayTitleSchedulesOneOfflineEchoContinuation() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        completedTransferredTitleClosesCreateFollowupWithoutNativeWrite(pendingEcho = true)
    }
    @Test fun completedTransferredTitleDoesNotScheduleForDistinctRemoteRevision() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        completedTransferredTitleClosesCreateFollowupWithoutNativeWrite(distinctRevision = true)
    }
    @Test fun settledTransferredTimedOwnerKeepsCreateFollowupWaiting() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("owner")
    }
    @Test fun settledTransferredTimedProjectionKeepsCreateFollowupWaiting() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("projection")
    }
    @Test fun settledTransferredTimedLaterTitleKeepsCreateFollowupWaiting() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("late_title")
    }
    @Test fun settledTransferredTimedLaterDeletionKeepsCreateFollowupWaiting() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("late_delete")
    }
    @Test fun settledTransferredTimedLostOriginKeepsCreateFollowupWaiting() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("origin")
    }
    @Test fun settledTransferredTimedRevocationKeepsCreateFollowupWaiting() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("revoked")
    }
    @Test fun settledTransferredTimedCancellationKeepsCreateFollowupWaiting() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("cancelled_read")
    }
    @Test fun settledTransferredAllDayOwnerKeepsCreateFollowupWaiting() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("owner")
    }
    @Test fun settledTransferredAllDayProjectionKeepsCreateFollowupWaiting() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("projection")
    }
    @Test fun settledTransferredAllDayLaterTitleKeepsCreateFollowupWaiting() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("late_title")
    }
    @Test fun settledTransferredAllDayLaterDeletionKeepsCreateFollowupWaiting() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("late_delete")
    }
    @Test fun settledTransferredAllDayLostOriginKeepsCreateFollowupWaiting() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("origin")
    }
    @Test fun settledTransferredAllDayRevocationKeepsCreateFollowupWaiting() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("revoked")
    }
    @Test fun settledTransferredAllDayCancellationKeepsCreateFollowupWaiting() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        changedSettledHandoffCannotCloseCreateFollowup("cancelled_read")
    }

    @Test fun optedInAllDayLaterEndDateCannotBeOverwrittenOrSubmittedByTitleWork() = fixture(allDay = true, preparedOnly = true) {
        productionAllDayDateChangeBeforeClaimPreservesLaterNativeEdit()
    }
    @Test fun optedInAllDayCompetingRemoteDatesAreArchivedWithoutTitleSend() = fixture(allDay = true, preparedOnly = true) {
        productionAllDayRemoteDateChangeBeforeClaimRemainsArchivedWithoutSend()
    }
    @Test fun productionAllDayFirstCapsuleUsesGuardedPreparationOnly() = fixture(allDay = true, preparedOnly = true) {
        productionFreshCapsuleUsesOnlyGuardedOverload(revoke = false)
    }
    @Test fun productionInvalidTransferredCreateCannotFallBackToOrdinaryTitleWork() {
        for (allDay in listOf(false, true)) for (case in listOf("frame", "origin", "attachment", "binding", "submission",
            "submission_payload", "waiting", "release", "nonce", "projection")) {
            fixture(allDay = allDay, transferred = true, preparedOnly = true) {
                productionInvalidTransferredSourceNeverCallsPorts(case)
            }
        }
    }
    @Test fun productionTransferredSourceChangesBeforeClaimCannotSendOrConsumeProjection() {
        for (allDay in listOf(false, true)) for (case in listOf("attachment", "submission_payload", "waiting", "nonce", "projection", "scope_hold")) {
            fixture(allDay = allDay, transferred = true, preparedOnly = true) {
                productionTransferredSourceChangingBeforeClaimCannotSend(case)
            }
        }
    }
    @Test fun productionTransferredUnknownInspectionKeepsProofAndLaterNativeEdits() {
        for (allDay in listOf(false, true)) for (case in listOf("later_title", "later_date", "deleted", "attachment", "revoke",
            "inspection_attachment", "inspection_role", "projection")) {
            fixture(allDay = allDay, transferred = true) {
                productionHistoricalInspectionPreservesLaterNativeChange(case)
            }
        }
    }
    @Test fun productionTransferredScopeHoldAfterNativeReadCannotBeIgnored() {
        for (allDay in listOf(false, true)) fixture(allDay = allDay, transferred = true, preparedOnly = true) {
            productionTransferredScopeHoldAppearingAfterNativeReadCannotPrepare()
        }
    }
    @Test fun productionTransferredAllDayLaterNativeDatesAreHeldWithoutSend() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        productionAllDayDateChangeBeforeClaimPreservesLaterNativeEdit()
    }
    @Test fun productionTransferredAllDayRemoteDateConflictRemainsArchivedWithoutSend() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        productionAllDayRemoteDateChangeBeforeClaimRemainsArchivedWithoutSend()
    }
    @Test fun productionTransferredFreshCapsuleUsesGuardedPreparationOnly() {
        for (allDay in listOf(false, true)) fixture(allDay = allDay, transferred = true, preparedOnly = true) {
            productionFreshCapsuleUsesOnlyGuardedOverload(revoke = false)
        }
    }
    @Test fun productionTitleFirstCapsuleUsesGuardedPreparationOnly() = fixture(allDay = false, preparedOnly = true) {
        productionFreshCapsuleUsesOnlyGuardedOverload(revoke = false)
    }
    @Test fun productionTitleRevokedAndRestoredDuringFirstCapsuleCannotRetainOrPrepareWire() {
        for (allDay in listOf(false, true)) for (transferred in listOf(false, true)) fixture(allDay = allDay, transferred = transferred, preparedOnly = true) {
            productionFreshCapsuleUsesOnlyGuardedOverload(revoke = true)
        }
    }
    @Test fun productionTitleRevokedAndRestoredBeforeClaimCannotSend() {
        for (allDay in listOf(false, true)) for (transferred in listOf(false, true)) fixture(allDay = allDay, transferred = transferred, preparedOnly = true) {
            productionRevocationBeforeClaimCannotSendAfterModeRestoration()
        }
    }
    @Test fun productionTitleUnknownRemainsInspectionOnlyWithoutAnotherPreparationOrSend() {
        for (allDay in listOf(false, true)) for (transferred in listOf(false, true)) fixture(allDay = allDay, transferred = transferred, preparedOnly = true) {
            productionUnknownCannotBePreparedOrSubmittedAgain()
        }
    }
    @Test fun productionTitleCancelledCapsuleReturningNullCannotReturnNormally() {
        for (allDay in listOf(false, true)) for (transferred in listOf(false, true)) fixture(allDay = allDay, transferred = transferred, preparedOnly = true) {
            productionCancelledNullCapsuleCannotReturnNormally()
        }
    }
    @Test fun productionTitleRunLeaseRejectsRestoredPolicyAndAuthenticationAfterPreparation() {
        for (allDay in listOf(false, true)) for (transferred in listOf(false, true)) for (authentication in listOf(false, true)) fixture(allDay = allDay, transferred = transferred, preparedOnly = true) {
            productionFrozenRunLeaseCannotSurviveRevocationBetweenPreparationAndSubmission(authentication)
        }
    }
    @Test fun ordinaryAllDayGuardedSubmissionCompletesOfflineAfterOwnerReopening() = fixture(allDay = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening()
    }
    @Test fun transferredTimedGuardedSubmissionCompletesOfflineWithOriginalCreateProvenance() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening()
    }
    @Test fun transferredAllDayGuardedSubmissionCompletesOfflineWithOriginalCreateProvenance() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening()
    }
    @Test fun ordinaryGuardedSubmissionRecoversNativeAcknowledgementGapAndKeepsLaterEdit() = fixture(allDay = false, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening(restoreGap = true)
    }
    @Test fun transferredGuardedSubmissionRecoversNativeAcknowledgementGapAndKeepsLaterEdit() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening(restoreGap = true)
    }
    @Test fun ordinaryGuardedSubmissionRecoversCompletionGapWithoutAnotherNativeWrite() = fixture(allDay = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening(completionGap = true)
    }
    @Test fun transferredGuardedSubmissionRecoversCompletionGapWithoutAnotherNativeWrite() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        guardedSubmissionCompletesOfflineAfterReopening(completionGap = true)
    }
    @Test fun ordinaryGuardedSubmissionRefusesNativeChangeBeforeClaim() = fixture(allDay = false, preparedOnly = true) {
        guardedSubmissionPreservesChangedNativeSource(afterSend = false)
    }
    @Test fun transferredGuardedSubmissionRefusesNativeChangeBeforeClaim() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        guardedSubmissionPreservesChangedNativeSource(afterSend = false)
    }
    @Test fun ordinaryGuardedSubmissionKeepsEvidenceAndLaterEditAfterSend() = fixture(allDay = true, preparedOnly = true) {
        guardedSubmissionPreservesChangedNativeSource(afterSend = true)
    }
    @Test fun transferredGuardedSubmissionKeepsEvidenceAndLaterEditAfterSend() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        guardedSubmissionPreservesChangedNativeSource(afterSend = true)
    }

    @Test fun ordinaryPreparedTitleWireRetainsExactNativeOwnerWithoutClaimOrAcknowledgement() = fixture(allDay = false, preparedOnly = true) {
        retainPreparedWireWithoutConsumingNativeOwner()
    }
    @Test fun transferredPreparedTitleWireRetainsExactNativeOwnerAndOriginalCreateProvenance() = fixture(allDay = true, transferred = true, preparedOnly = true) {
        retainPreparedWireWithoutConsumingNativeOwner()
    }
    @Test fun transferredPreparedTitleWireWithoutOriginalAttachmentNeverUsesOrdinaryRetention() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        missingCreateSourceCannotFallBackToOrdinaryWireRetention(dropFrame = false)
    }
    @Test fun transferredPreparedTitleWireWithoutHandoffFrameNeverUsesOrdinaryRetention() = fixture(allDay = false, transferred = true, preparedOnly = true) {
        missingCreateSourceCannotFallBackToOrdinaryWireRetention(dropFrame = true)
    }

    @Test fun composedAcknowledgementKeepsSameAllDayRowAndContentWhileRestoringSql() = fixture(allDay = true) {
        val before = values()
        assertEquals("1", before[Events.DIRTY]); assertEquals(local.title, before[Events.TITLE])
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        val retained = receipt()!!
        val expected = before + mapOf(Events.DIRTY to "0",
            Events.SYNC_DATA2 to digest("revision-v1", verified.revision.value),
            Events.SYNC_DATA3 to "calendeer-projection-receipt-v1:${retained.receipt.projection.nonce}")
        assertEquals(expected, values()); assertSingleOriginalRow()
        assertEquals(1, writes.get()); assertEquals(0, inspections.get())
        assertSqlRestored()
    }

    @Test fun twoOrdinaryAllDayTitleCyclesRetainHistoryAndRecoverCompletionLoss() = fixture(allDay = true) { twoCycles(recoverSecond = true) }
    @Test fun twoOrdinaryTimedTitleCyclesRetainHistoryAndRecoverCompletionLoss() = fixture(allDay = false) { twoCycles() }
    @Test fun twoTransferredAllDayTitleCyclesPreserveOriginAndRecoverCompletionLoss() = fixture(allDay = true, transferred = true) { twoCycles(recoverSecond = true) }
    @Test fun twoTransferredTimedTitleCyclesPreserveOriginAndRecoverCompletionLoss() = fixture(allDay = false, transferred = true) { twoCycles() }

    @Test fun ordinaryMissingTitleProofCompletesThroughInspectionBatchOnSameRow() = fixture(allDay = false) {
        inspectionBatchRecoversMissingProof()
    }
    @Test fun transferredMissingTitleProofCompletesThroughInspectionBatchOnSameRow() = fixture(allDay = true, transferred = true) {
        inspectionBatchRecoversMissingProof()
    }
    @Test fun ordinaryInspectionBatchReopensProofToAcknowledgementGapAndKeepsLaterDirtyEdit() = fixture(allDay = true) {
        inspectionBatchRecoversMissingProof(restoreGap = true)
    }
    @Test fun transferredInspectionBatchReopensProofToAcknowledgementGapAndKeepsLaterDirtyEdit() = fixture(allDay = false, transferred = true) {
        inspectionBatchRecoversMissingProof(restoreGap = true)
    }

    @Test fun scheduledOfflineTitleProofKeepsSameRowAndNextOwnerAfterReopening() = fixture(allDay = false) {
        scheduledOfflineRecoveryPreservesLaterEdit(completionGap = false)
    }
    @Test fun scheduledTransferredTitleRecoveryClosesCompletionGapWithoutConsumingLaterOwner() = fixture(allDay = true, transferred = true) {
        scheduledOfflineRecoveryPreservesLaterEdit(completionGap = true)
    }

    @Test fun ordinaryStoredTitleProofCompletesThroughOfflineBatchOnSameRow() = fixture(allDay = false) {
        offlineBatchClosesStoredProofWithoutAnotherSend()
    }
    @Test fun transferredStoredTitleProofCompletesThroughOfflineBatchOnSameRow() = fixture(allDay = true, transferred = true) {
        offlineBatchClosesStoredProofWithoutAnotherSend()
    }
    @Test fun ordinaryOfflineBatchRecoversCompletionGapAndPreservesLaterDirtyEdit() = fixture(allDay = false) {
        offlineBatchRecoversCompletionGapAfterReopeningAndKeepsLaterDirtyEdit()
    }
    @Test fun transferredOfflineBatchRecoversCompletionGapAndPreservesLaterDirtyEdit() = fixture(allDay = false, transferred = true) {
        offlineBatchRecoversCompletionGapAfterReopeningAndKeepsLaterDirtyEdit()
    }

    @Test fun ordinaryTimedCachedObservationAfterNativeAckDrainsOfflineOnSameRow() = fixture(allDay = false) {
        cachedObservationDrainsOfflineOnOriginalRow()
    }
    @Test fun ordinaryAllDayCachedObservationAfterNativeAckDrainsOfflineOnSameRow() = fixture(allDay = true) {
        cachedObservationDrainsOfflineOnOriginalRow()
    }
    @Test fun ordinaryCachedDeletionAfterNativeAckDrainsOfflineWithoutRecreatingTheRow() = fixture(allDay = false) {
        ordinaryCachedDeletionDrainsOfflineWithoutRecreatingTheRow()
    }
    @Test fun transferredTimedCachedObservationAfterNativeAckDrainsOfflineOnSameRow() = fixture(allDay = false, transferred = true) {
        cachedObservationDrainsOfflineOnOriginalRow()
    }
    @Test fun transferredAllDayCachedObservationAfterNativeAckDrainsOfflineOnSameRow() = fixture(allDay = true, transferred = true) {
        cachedObservationDrainsOfflineOnOriginalRow()
    }
    @Test fun ordinaryCachedQueueRollsBackThenRecoversWithoutOverwritingLaterDirtyEdit() = fixture(allDay = false) {
        cachedObservationSurvivesRollbackAndNeverOverwritesLaterDirtyEdit()
    }
    @Test fun transferredCachedQueueRollsBackThenRecoversWithoutOverwritingLaterDirtyEdit() = fixture(allDay = false, transferred = true) {
        cachedObservationSurvivesRollbackAndNeverOverwritesLaterDirtyEdit()
    }

    @Test fun restoredNativeSqlProofCanCloseUnknownWithoutNativeOrNetwork() = fixture(allDay = false) {
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        val before = values()
        assertEquals(TitleUpdateCompletionResult.COMPLETED, complete())
        assertNotNull(journal.readCompletion(target, prepared.id))
        assertEquals(before, values()); assertEquals(1, writes.get()); assertEquals(0, inspections.get())
    }

    @Test fun transferredAcknowledgementKeepsAllDayOriginAndAdvancesTheSameFrame() = fixture(allDay = true, transferred = true) {
        val before = values()
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        val retained = receipt()!!
        assertEquals(before + mapOf(Events.DIRTY to "0",
            Events.SYNC_DATA2 to digest("revision-v1", verified.revision.value),
            Events.SYNC_DATA3 to "calendeer-projection-receipt-v1:${retained.receipt.projection.nonce}"), values())
        assertSingleOriginalRow(); assertSqlRestored()
        assertEquals(1, writes.get()); assertEquals(0, inspections.get())
    }

    @Test fun transferredSqlLossPreservesLaterTimedEditAndReplayNeverConsumesItsOwner() = fixture(allDay = false, transferred = true) {
        store.writableDatabase.execSQL("CREATE TRIGGER fixture_handoff_ack_loss BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'synthetic restore loss'); END")
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        assertEquals("0", values()[Events.DIRTY]); assertEquals(TitleUpdateAcknowledgementState.PENDING, receipt()!!.state)
        allowSql(); assertEquals(frame, store.capturedHandoff(target, base.eventId)!!.frame)
        val laterTitle = "Synthetic later transferred title"
        mutateTitle(laterTitle); val laterNative = values()
        store.writableDatabase.execSQL("DROP TRIGGER fixture_handoff_ack_loss")
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals(laterNative, values()); assertSqlRestored()
        val nextId = MutationId("synthetic-next-transferred-intent")
        captureTransferred(nextId)
        val next = store.localEdit(target, base.eventId)!!
        assertEquals(nextId, next.id); assertEquals(verified, next.base); assertEquals(laterTitle, next.content.title)
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        allowSql(); assertEquals(next, store.localEdit(target, base.eventId)); assertEquals(laterNative, values())
        assertEquals(1, writes.get()); assertEquals(1, inspections.get()); assertSingleOriginalRow()
    }

    @Test fun realSqlFailureAfterNativeCommitRecoversByInspectionAndPreservesLaterDirtyTimedEdit() = fixture(allDay = false) {
        store.writableDatabase.execSQL("CREATE TRIGGER fixture_title_ack_restore_loss BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'synthetic restore loss'); END")
        assertEquals(TitleUpdateAcknowledgementResult.UNRESOLVED, acknowledge())
        val retained = receipt()!!
        assertEquals(TitleUpdateAcknowledgementState.PENDING, retained.state)
        val committedNative = values()
        assertEquals("0", committedNative[Events.DIRTY]); assertEquals(local.title, committedNative[Events.TITLE])
        assertEquals(digest("revision-v1", verified.revision.value), committedNative[Events.SYNC_DATA2])
        assertEquals("calendeer-projection-receipt-v1:${retained.receipt.projection.nonce}", committedNative[Events.SYNC_DATA3])
        allowSql()
        assertEquals(base, store.projectedBaseline(target, base.eventId))
        assertEquals(preparation, store.localEditPreparation(target, base.eventId))
        val latest = local.copy(title = "Synthetic later unsent system edit")
        mutateTitle(latest.title)
        val laterNative = values()
        store.writableDatabase.execSQL("DROP TRIGGER fixture_title_ack_restore_loss")
        assertEquals(TitleUpdateAcknowledgementResult.ACKNOWLEDGED, acknowledge())
        assertEquals(laterNative, values()); assertEquals("1", values()[Events.DIRTY]); assertSingleOriginalRow()
        assertEquals(1, writes.get()); assertEquals(1, inspections.get())
        assertSqlRestored()
        val nextId = MutationId(UUID.randomUUID().toString())
        assertEquals(LocalEditCaptureResult.RETAINED, coordinator.captureLocalEdit(target, base.eventId, nextId))
        allowSql()
        val next = store.localEdit(target, base.eventId)!!
        assertEquals(nextId, next.id); assertEquals(verified, next.base); assertEquals(latest, next.content)
        assertEquals(LocalEditState.PAUSED, next.state); assertEquals(laterNative, values())
    }

    private companion object {
        fun digest(vararg parts: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
            parts.forEach { part -> val bytes = part.toByteArray(Charsets.UTF_8)
                hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes) }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
