package com.patmanak.proton.andcalendarbridge.data.calendar
import com.patmanak.proton.andcalendarbridge.sync.supportsCapturedHandoff
import com.patmanak.proton.andcalendarbridge.sync.LocalCreateIntent

import com.patmanak.proton.andcalendarbridge.data.calendar.NativeCalendarSelection as Selection

import com.patmanak.proton.andcalendarbridge.sync.DeleteAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedDeleteAcknowledgement
import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.content.pm.PackageManager
import android.net.Uri
import android.os.RemoteException
import com.patmanak.proton.andcalendarbridge.data.transientStorageFailure
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.ExtendedProperties
import androidx.core.content.ContextCompat
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.Revision
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventTime
import com.patmanak.proton.andcalendarbridge.sync.RecurringLocalTimeRisk
import com.patmanak.proton.andcalendarbridge.sync.ProjectionIntent
import com.patmanak.proton.andcalendarbridge.sync.ProjectionReceipt
import com.patmanak.proton.andcalendarbridge.sync.ProjectionRepresentation
import com.patmanak.proton.andcalendarbridge.sync.AppliedProjectionWitness
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateAttachmentReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedCapturedCreateAttachment
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateBaseline
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateCurrent
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateCurrentObservation
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedCapturedCreateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateCleanWitness
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateHandoffFrame
import com.patmanak.proton.andcalendarbridge.sync.CapturedHandoffCleanWitness
import com.patmanak.proton.andcalendarbridge.sync.CapturedHandoffCurrent
import com.patmanak.proton.andcalendarbridge.sync.CapturedHandoffCurrentObservation
import com.patmanak.proton.andcalendarbridge.sync.CapturedHandoffProjection
import com.patmanak.proton.andcalendarbridge.sync.AppliedCapturedHandoffProjection
import com.patmanak.proton.andcalendarbridge.sync.TitleUpdateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedTitleUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.TextUpdateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedTextUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.PersonalUpdateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedPersonalUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.PersonalUpdateContentPolicy
import com.patmanak.proton.andcalendarbridge.sync.PersonalEditCandidate
import com.patmanak.proton.andcalendarbridge.sync.MutationId
import com.patmanak.proton.andcalendarbridge.sync.AdapterFormat
import com.patmanak.proton.andcalendarbridge.sync.TimeUpdateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedTimeUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.LocalEditIntent
import com.patmanak.proton.andcalendarbridge.sync.TextUpdateContentPolicy
import com.patmanak.proton.andcalendarbridge.sync.TimeUpdateContentPolicy
import com.patmanak.proton.andcalendarbridge.sync.RecurrencePoint
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventDetails
import com.patmanak.proton.andcalendarbridge.sync.LocalEventId
import com.patmanak.proton.andcalendarbridge.sync.EventPerson
import com.patmanak.proton.andcalendarbridge.sync.AttendanceRole
import com.patmanak.proton.andcalendarbridge.sync.AttendanceStatus
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import com.patmanak.proton.andcalendarbridge.sync.EventColorPalette
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.Instant
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CalendarProjectionFailure {
    PERMISSION_REQUIRED, ACCOUNT_UNAVAILABLE, CALENDAR_MISSING, IDENTITY_CONFLICT, PROVIDER_UNAVAILABLE, PROVIDER_REJECTED,
    LOCAL_CHANGE_PENDING,
}

/** Contains no provider exception, account identity or event content. Failed intents remain queued. */
class CalendarProjectionException(val category: CalendarProjectionFailure) :
    IllegalStateException("Calendar projection failed: ${category.name}")

/** Creation is explicit because Provider row IDs may be reused after a mirror is removed. */
data class EnsuredCalendar(val id: Long, val created: Boolean)

/**
 * Read-only Calendar Provider projection. All instances serialize locally; atomic provider batches
 * assert the observed identity before writing. Crash replay recovers mappings from stable sync IDs.
 * Callers must enforce active scope/generation and ordered durable queue draining. This class cannot
 * order opaque revisions and never infers deletion from an absent event in a server page.
 */
class AndroidCalendarProjection(context: Context,
    internal val eventColors: EventColorPalette = EventColorPalette { emptyList() },
    internal val colorMode: () -> EventColorMode = { EventColorPreferences.read(context) }) : CalendarProjection {
    constructor(context: Context, colorMode: () -> EventColorMode) :
        this(context, EventColorPalette { emptyList() }, colorMode)
    internal var lastPaletteDiagnostic: NativePaletteDiagnostic? = null
        private set
    override suspend fun reprojectEventColors(scope: CalendarScope, afterRow: Long, limit: Int) =
        reprojectColorPage(scope, afterRow, limit)
    private val localCreateReader by lazy { NativeLocalCreateReader(resolver, ::accountFor, ::calendarRow,
        ::syncUri, ::calendarSyncId, ::calendarSelection, ::assertion,
        { values, placeholder -> readSimpleTime(values, placeholder) }, ::requirePermissions, ::capturedBatch, ::fail,
        { digest(*it) }) }
    override val deleteAcknowledgementProtocol: Int? = 1
    override suspend fun acknowledgeDelete(receipt: DeleteAcknowledgementReceipt): AppliedDeleteAcknowledgement? =
        deleteAcknowledgementOperation(receipt) { frozen, checkActive ->
            if (captureSimpleSnapshot(frozen.intent.scope, frozen.intent.base, deleted = true,
                    deleteAcknowledgement = frozen, checkDeleteActive = checkActive) != null)
                AppliedDeleteAcknowledgement(frozen) else null
        }
    override suspend fun inspectDeleteAcknowledgement(receipt: DeleteAcknowledgementReceipt): AppliedDeleteAcknowledgement? =
        deleteAcknowledgementOperation(receipt) { frozen, checkActive ->
            if (inspectOrdinaryAcknowledgement(frozen.intent.scope, frozen.intent.base.eventId,
                    frozen.intent.base.revision, deleteReceiptMarker(frozen), checkActive))
                AppliedDeleteAcknowledgement(frozen) else null
        }
    private suspend fun <T> deleteAcknowledgementOperation(receipt: DeleteAcknowledgementReceipt,
        block: (DeleteAcknowledgementReceipt, () -> Unit) -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val frozen = receipt.snapshot()
        val checkActive = { caller.ensureActive() }
        try {
            return operation {
                checkActive(); requirePermissions(); checkActive()
                val result = block(frozen, checkActive)
                checkActive(); requirePermissions(); checkActive()
                result
            }
        } catch (failure: Exception) { checkActive(); throw failure }
    }
    internal fun deleteReceiptMarker(receipt: DeleteAcknowledgementReceipt): String {
        val attempt = receipt.attempt
        return "calendeer-delete-ack-v1:" + strictAttachmentDigest(
            receipt.nonce, attempt.scope.account.value, attempt.scope.calendar.value, attempt.scope.generation.toString(),
            attempt.id.value, attempt.localIntentId.value, attempt.eventId.value, attempt.baseRevision.value,
            attempt.format.id, attempt.format.version.toString(), attempt.intentFingerprint, attempt.payloadFingerprint)
    }
    override val personalUpdateAcknowledgementProtocol: Int? = 1
    override val personalUpdateAcknowledgementRecoveryProtocol: Int? = 1
    override suspend fun resumePersonalUpdateAcknowledgement(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val frozen = receipt.snapshot()
        val recipe = NativeEditAcknowledgement.personal(frozen) { caller.ensureActive() }
        return nativeEditAcknowledgementOperation(recipe) {
            if (inspectNativeEditAcknowledgement(recipe) || acknowledgeNativeEdit(recipe))
                AppliedPersonalUpdateAcknowledgement(frozen) else null
        }
    }
    override suspend fun acknowledgePersonalUpdate(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val frozen = receipt.snapshot()
        val recipe = NativeEditAcknowledgement.personal(frozen) { caller.ensureActive() }
        return nativeEditAcknowledgementOperation(recipe) {
            if (acknowledgeNativeEdit(recipe)) AppliedPersonalUpdateAcknowledgement(frozen) else null
        }
    }
    override suspend fun inspectPersonalUpdateAcknowledgement(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val frozen = receipt.snapshot()
        val recipe = NativeEditAcknowledgement.personal(frozen) { caller.ensureActive() }
        return nativeEditAcknowledgementOperation(recipe) {
            if (inspectNativeEditAcknowledgement(recipe)) AppliedPersonalUpdateAcknowledgement(frozen) else null
        }
    }
    override val timeUpdateAcknowledgementProtocol: Int? = 1
    override suspend fun acknowledgeTimeUpdate(receipt: TimeUpdateAcknowledgementReceipt): AppliedTimeUpdateAcknowledgement? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val frozen = receipt.snapshot()
        val recipe = NativeEditAcknowledgement.time(frozen) { caller.ensureActive() }
        return nativeEditAcknowledgementOperation(recipe) {
            if (acknowledgeNativeEdit(recipe)) AppliedTimeUpdateAcknowledgement(frozen) else null
        }
    }
    override suspend fun inspectTimeUpdateAcknowledgement(receipt: TimeUpdateAcknowledgementReceipt): AppliedTimeUpdateAcknowledgement? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val frozen = receipt.snapshot()
        val recipe = NativeEditAcknowledgement.time(frozen) { caller.ensureActive() }
        return nativeEditAcknowledgementOperation(recipe) {
            if (inspectNativeEditAcknowledgement(recipe)) AppliedTimeUpdateAcknowledgement(frozen) else null
        }
    }
    override val textUpdateAcknowledgementProtocol: Int? = 1
    override suspend fun acknowledgeTextUpdate(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val frozen = receipt.snapshot()
        val recipe = NativeEditAcknowledgement.text(frozen) { caller.ensureActive() }
        return nativeEditAcknowledgementOperation(recipe) {
            if (acknowledgeNativeEdit(recipe)) AppliedTextUpdateAcknowledgement(frozen) else null
        }
    }
    override suspend fun inspectTextUpdateAcknowledgement(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val frozen = receipt.snapshot()
        val recipe = NativeEditAcknowledgement.text(frozen) { caller.ensureActive() }
        return nativeEditAcknowledgementOperation(recipe) {
            if (inspectNativeEditAcknowledgement(recipe)) AppliedTextUpdateAcknowledgement(frozen) else null
        }
    }
    override val titleUpdateAcknowledgementProtocol: Int? = 1
    override suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
        val frozen = receipt.snapshot()
        return operation {
            if (acknowledgeNativeEdit(NativeEditAcknowledgement.title(frozen))) AppliedTitleUpdateAcknowledgement(frozen) else null
        }
    }
    override suspend fun inspectTitleUpdateAcknowledgement(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
        val frozen = receipt.snapshot()
        return operation {
            if (inspectNativeEditAcknowledgement(NativeEditAcknowledgement.title(frozen))) AppliedTitleUpdateAcknowledgement(frozen) else null
        }
    }

    /** Private recipes are constructed only from their separately validated public receipt types.
     * Title stays metadata-only. Text and time allow only their verified CRLF/LF text differences;
     * the time recipe never writes dates, because the exact desired time is already in the row.
     */
    internal fun requireEditPermissions(receipt: NativeEditAcknowledgement?) {
        receipt?.checkActive?.invoke()
        requirePermissions()
        receipt?.checkActive?.invoke()
    }

    private suspend fun <T> nativeEditAcknowledgementOperation(receipt: NativeEditAcknowledgement, block: () -> T): T = try {
        operation {
            requireEditPermissions(receipt)
            val result = block()
            requireEditPermissions(receipt)
            result
        }
    } catch (failure: Exception) {
        // A callback may cancel its caller and then throw an ordinary provider/lease failure.
        receipt.checkActive()
        throw failure
    }

    private fun acknowledgeNativeEdit(receipt: NativeEditAcknowledgement): Boolean {
        receipt.handoff?.let { frame ->
            val original = frame.receipt.acknowledgement
            return captureCapturedCurrent(original.baseline, expectedAck = original, handoff = frame,
                editAcknowledgement = receipt) is CapturedNativeObservation.Supported
        }
        return captureSimpleSnapshot(receipt.intent.scope, receipt.intent.base, deleted = false,
            acknowledgement = receipt, personal = receipt.personal, series = receipt.series) != null
    }

    private fun inspectNativeEditAcknowledgement(receipt: NativeEditAcknowledgement): Boolean {
        receipt.handoff?.let { frame ->
            return inspectCapturedHistory(frame.receipt.acknowledgement, receipt.projection, receipt.checkActive)
        }
        return inspectOrdinaryAcknowledgement(receipt.intent.scope, receipt.intent.base.eventId,
            receipt.projection.change.revision, receiptMarker(receipt.projection), receipt.checkActive,
            seriesUid = if (receipt.series) receipt.projection.change.content.recurrence!!.uid else null)
    }
    @Volatile override var admitOperation: () -> Boolean = { true }
    private val context = context.applicationContext
    internal val resolver = this.context.contentResolver
    internal val personalMetadata = NativePersonalMetadata(resolver, ::syncUri, ::fail)
    internal val accounts = AccountManager.get(this.context)

    override fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun accountFor(accountId: AccountId): Account =
        Account("Proton-" + digest("account-v1", accountId.value), ACCOUNT_TYPE)

    /** No credentials or tokens are installed in AccountManager. The authenticator only owns rows. */
    suspend fun ensureAccount(accountId: AccountId): Account = operation { ensureAccountInternal(accountId) }

    suspend fun ensureCalendar(scope: CalendarScope, summary: CalendarSummary): Long =
        ensureCalendarState(scope, summary).id

    override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = operation {
        ensureCalendarInternal(scope, summary, false)
    }

    override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary, systemCreates: Boolean): EnsuredCalendar = operation {
        ensureCalendarInternal(scope, summary, systemCreates)
    }

    override suspend fun makeCalendarReadOnly(scope: CalendarScope) = operation {
        val account = accountFor(scope.account)
        val existing = calendarRow(scope, account) ?: return@operation
        val uri = syncUri(Calendars.CONTENT_URI, account)
        val identity = calendarSelection(scope, account)
        val values = ContentValues().apply { put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ) }
        val operations = arrayListOf(assertion(uri, identity, existing),
            calendarConfigurationOperation(uri, identity, existing, values))
        requirePermissions()
        val result = checkedBatch(operations)
        requirePermissions()
        if (result[0].count != 1 || result[1].count != 1)
            fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    }

    private fun ensureCalendarInternal(scope: CalendarScope, summary: CalendarSummary, systemCreates: Boolean): EnsuredCalendar {
        if (scope.calendar != summary.id) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
        val account = ensureAccountInternal(scope.account)
        lastPaletteDiagnostic = publishOptionalEventPalette(account)
        val uri = syncUri(Calendars.CONTENT_URI, account)
        val identity = calendarSelection(scope, account)
        val existing = calendarRow(scope, account)
        val values = ContentValues().apply {
            put(Calendars.CALENDAR_DISPLAY_NAME, summary.title)
            put(Calendars.CALENDAR_COLOR, (summary.colorRgb ?: 0x167D8D) or 0xFF000000.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, if (systemCreates) Calendars.CAL_ACCESS_EDITOR else Calendars.CAL_ACCESS_READ)
            put(Calendars.MAX_REMINDERS, 5)
            put(Calendars.ALLOWED_REMINDERS, Reminders.METHOD_ALERT.toString())
            put(Calendars.CAN_ORGANIZER_RESPOND, 0)
        }
        val operations = arrayListOf<ContentProviderOperation>()
        operations += assertion(uri, identity, existing)
        if (existing == null) {
            values.apply {
                put(Calendars.ACCOUNT_NAME, account.name)
                put(Calendars.ACCOUNT_TYPE, account.type)
                put(Calendars._SYNC_ID, calendarSyncId(scope))
                put(Calendars.NAME, calendarSyncId(scope))
                put(Calendars.OWNER_ACCOUNT, account.name)
                put(Calendars.CAL_SYNC1, OWNER_MARKER)
                put(Calendars.CALENDAR_TIME_ZONE, "UTC")
                put(Calendars.VISIBLE, 1)
                put(Calendars.SYNC_EVENTS, 1)
                put(Calendars.ALLOWED_ATTENDEE_TYPES, "")
                put(Calendars.ALLOWED_AVAILABILITY, Events.AVAILABILITY_BUSY.toString())
                put(Calendars.DIRTY, 0)
            }
            operations += ContentProviderOperation.newInsert(uri).withValues(values).build()
        } else {
            operations += calendarConfigurationOperation(uri, identity, existing, values)
        }
        requirePermissions()
        val result = checkedBatch(operations)
        requirePermissions()
        if (result[0].count != (if (existing == null) 0 else 1) ||
            (existing != null && result[1].count != 1)) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        val id = existing ?: result[1].uri?.let(ContentUris::parseId)
            ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        return EnsuredCalendar(id, created = existing == null)
    }

    /** CalendarProvider can notify the root URI even for an unchanged UPDATE, waking
     * the Events observer and scheduling another configuration pass. An unchanged
     * configuration is still qualified atomically with the row/owner assertion in
     * its batch; it must not be reported from a stale read or rewrite the Provider.
     */
    private fun calendarConfigurationOperation(uri: Uri, identity: Selection, row: Long,
        values: ContentValues): ContentProviderOperation {
        requirePermissions()
        val keys = values.keySet().toTypedArray()
        val columns = arrayOf(Calendars._ID, Calendars.CAL_SYNC1) + keys
        val matches = resolver.query(uri, columns, identity.sql, identity.args, null)
            ?.use { cursor ->
                if (cursor.count != 1 || !cursor.moveToFirst() || cursor.getLong(0) != row ||
                    cursor.getString(1) != OWNER_MARKER) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                keys.indices.all { index -> cursor.getString(index + 2) == values.getAsString(keys[index]) }
            } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        requirePermissions()
        val builder = if (matches) ContentProviderOperation.newAssertQuery(uri)
            else ContentProviderOperation.newUpdate(uri)
        return builder.withSelection(identity.sql, identity.args).withValues(values).withExpectedCount(1).build()
    }

    override val projectionReceiptProtocol: Int = 1

    override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = operation {
        applyInternal(intent, seriesUid, null)
    }

    override suspend fun applyTracked(receipt: ProjectionReceipt): AppliedProjectionWitness {
        val frozen = snapshotReceipt(receipt)
        return operation {
            applyInternal(ProjectionIntent(frozen.scope, frozen.change), null, frozen)
            requirePermissions()
            AppliedProjectionWitness(frozen)
        }
    }

    override suspend fun insertFiniteProjection(receipt: ProjectionReceipt): AppliedProjectionWitness {
        val frozen = snapshotFiniteInsertion(receipt)
        return operation {
            applyInternal(ProjectionIntent(frozen.scope, frozen.representation!!.nativeChange), null, frozen, firstFiniteInsertion = true)
            requirePermissions()
            AppliedProjectionWitness(frozen)
        }
    }

    override suspend fun hasOwnedLocalChanges(intent: ProjectionIntent, deletedSeriesUid: String?): Boolean = operation {
        val account = accountFor(intent.scope.account)
        val calendar = calendarRow(intent.scope, account) ?: fail(CalendarProjectionFailure.CALENDAR_MISSING)
        val uri = syncUri(Events.CONTENT_URI, account)
        val remoteId = digest("event-v1", calendarSyncId(intent.scope), intent.change.eventId.value)
        val identity = Selection("${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA4}=? OR " +
            "(${Events.SYNC_DATA4} IS NULL AND ${Events._SYNC_ID}=?))", arrayOf(calendar.toString(), remoteId, remoteId))
        val existing = ownedRow(uri, identity, Events.SYNC_DATA1)
        val series = mutableSetOf<String>()
        if (existing != null) {
            resolver.query(uri, arrayOf(Events.DIRTY, Events.DELETED, Events._SYNC_ID, Events.ORIGINAL_SYNC_ID),
                identity.sql, identity.args, null)?.use { rows ->
                if (rows.count != 1 || !rows.moveToFirst()) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                if (rows.getInt(0) != 0 || rows.getInt(1) != 0) return@operation true
                if (!rows.isNull(2)) series += rows.getString(2)
                if (!rows.isNull(3)) series += rows.getString(3)
            } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        }
        (intent.change as? RemoteChange.Upsert)?.content?.recurrence?.let {
            series += digest("series-v1", calendarSyncId(intent.scope), it.uid)
        }
        if (intent.change is RemoteChange.Deleted && deletedSeriesUid != null)
            series += digest("series-v1", calendarSyncId(intent.scope), deletedSeriesUid)
        // A dirty child can block master deletion/conversion; a dirty parent can block an
        // exception's geometry checks. Observe the whole affected owned family without editing it.
        for (syncId in series) {
            val changed = resolver.query(uri, arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=? AND " +
                    "(${Events._SYNC_ID}=? OR ${Events.ORIGINAL_SYNC_ID}=?) AND (${Events.DIRTY}<>0 OR ${Events.DELETED}<>0)",
                arrayOf(calendar.toString(), OWNER_MARKER, syncId, syncId), null)?.use { it.moveToFirst() }
                ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            if (changed) return@operation true
        }
        requirePermissions()
        false
    }


    override suspend fun inspectProjectionReceipt(receipt: ProjectionReceipt): AppliedProjectionWitness? {
        val frozen = snapshotReceipt(receipt)
        return inspectReceipt(frozen)
    }

    override suspend fun inspectFiniteInsertion(receipt: ProjectionReceipt): AppliedProjectionWitness? {
        val frozen = snapshotFiniteInsertion(receipt)
        return inspectReceipt(frozen)
    }





    override suspend fun inspectOwnedEvent(scope: CalendarScope, eventId: EventId, revision: Revision): OwnedEventWitness? = operation {
        val account = accountFor(scope.account)
        val calendar = calendarRow(scope, account) ?: return@operation null
        val remoteId = digest("event-v1", calendarSyncId(scope), eventId.value)
        val uri = syncUri(Events.CONTENT_URI, account)
        val identity = Selection("${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA4}=? OR " +
            "(${Events.SYNC_DATA4} IS NULL AND ${Events._SYNC_ID}=?))", arrayOf(calendar.toString(), remoteId, remoteId))
        val row = ownedRow(uri, identity, Events.SYNC_DATA1) ?: return@operation null
        val matching = resolver.query(uri, arrayOf(Events._ID, Events.SYNC_DATA2, Events.DIRTY, Events.DELETED, Events.SYNC_DATA1),
            identity.sql, identity.args, null)?.use {
            it.count == 1 && it.moveToFirst() && it.getLong(0) == row && it.getString(1) == digest("revision-v1", revision.value) &&
                it.getInt(2) == 0 && it.getInt(3) == 0 && it.getString(4) == OWNER_MARKER
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        requirePermissions()
        if (matching && calendarRow(scope, account) == calendar) OwnedEventWitness(calendar, row, revision) else null
    }

    override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = operation {
        captureSimpleSnapshot(scope, base, deleted = false)
    }

    override suspend fun captureOwnedPersonalEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = operation {
        captureSimpleSnapshot(scope, base, deleted = false, personal = true)
    }
    override suspend fun captureOwnedSeriesEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = operation {
        captureSimpleSnapshot(scope, base, deleted = false, personal = true, series = true)
    }
    override suspend fun captureOwnedOccurrenceEdit(scope: CalendarScope, master: RemoteChange.Upsert,
        childRow: Long): OwnedOccurrenceEditSnapshot? = readOccurrenceEdit(scope, master, childRow)
    override suspend fun captureAndStampOccurrence(intent: LocalCreateIntent, childRow: Long) = stampOccurrenceIdentity(intent, childRow)
    override suspend fun inspectStampedOccurrence(intent: LocalCreateIntent) = stampOccurrenceIdentity(intent, null)

    override suspend fun captureOwnedSimpleDelete(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = operation {
        captureSimpleSnapshot(scope, base, deleted = true)
    }


    override suspend fun localCreateProfile(scope: CalendarScope, eventRow: Long): LocalCreateProfile? =
        operation { localCreateReader.profile(scope, eventRow) }
    override suspend fun captureAndStampSimpleCreate(scope: CalendarScope, eventRow: Long, localId: LocalEventId): OwnedLocalEditSnapshot? = operation {
        readLocalCreate(scope, localId, eventRow)
    }

    override suspend fun inspectStampedSimpleCreate(scope: CalendarScope, localId: LocalEventId): OwnedLocalEditSnapshot? = operation {
        readLocalCreate(scope, localId, null)
    }
    override suspend fun captureAndStampSeriesCreate(scope: CalendarScope, eventRow: Long, localId: LocalEventId) = operation {
        readLocalCreate(scope, localId, eventRow, true)
    }
    override suspend fun inspectStampedSeriesCreate(scope: CalendarScope, localId: LocalEventId) = operation {
        readLocalCreate(scope, localId, null, true)
    }

    override fun eventIdentityKey(scope: CalendarScope, event: EventId): String =
        digest("event-v1", calendarSyncId(scope), event.value)

    override fun capturedIdentityKey(scope: CalendarScope, localId: LocalEventId): String =
        digest("local-create-v1", calendarSyncId(scope), localId.value)

    override suspend fun inspectCapturedIdentityRow(scope: CalendarScope, localId: LocalEventId, row: Long): Boolean = operation {
        val account = accountFor(scope.account)
        val calendar = calendarRow(scope, account) ?: return@operation false
        val uri = syncUri(Events.CONTENT_URI, account)
        val local = Selection("${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA5}=?",
            arrayOf(calendar.toString(), capturedIdentityKey(scope, localId)))
        val values = attachmentMetadata(uri, local) ?: return@operation false
        if (values.getAsLong(Events._ID) != row) return@operation false
        requirePermissions()
        capturedBatch(arrayListOf(
            assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            ContentProviderOperation.newAssertQuery(uri).withSelection(local.sql, local.args)
                .withValues(values).withExpectedCount(1).build()), intArrayOf(1, 1))
        requirePermissions()
        true
    }

    internal suspend fun providerDirtyFingerprint(scopes: List<CalendarScope>): ProviderDirtyFingerprint = readProviderDirtyFingerprint(scopes)

    /** Metadata-only discovery. Durable jobs and qualified capture, not this locator, authorize work. */
    override suspend fun discoverSystemChanges(scope: CalendarScope, afterRow: Long, limit: Int): SystemCalendarChangePage = operation {
        discoverNativeSystemChanges(scope, afterRow, limit)
    }

    /** A row locator can select a candidate only for the durable claim's first invocation. */
    private fun readLocalCreate(scope: CalendarScope, localId: LocalEventId, firstRow: Long?, seriesOnly: Boolean = false): OwnedLocalEditSnapshot? =
        localCreateReader.readLocalCreate(scope, localId, firstRow, seriesOnly)

    override val capturedAttachmentProtocol: Int get() = 1

    override suspend fun attachCapturedCreate(receipt: CapturedCreateAttachmentReceipt): AppliedCapturedCreateAttachment? = operation {
        val metadata = capturedAttachmentValues(receipt) // Refuse malformed identity text before Provider IO.
        inspectCapturedAttachmentInternal(receipt)?.let { return@operation it }
        val binding = receipt.binding
        val scope = binding.identity.attempt.scope
        val account = accountFor(scope.account)
        val calendar = calendarRow(scope, account) ?: return@operation null
        val uri = syncUri(Events.CONTENT_URI, account)
        val local = capturedLocalSelection(receipt, calendar)
        val values = attachmentMetadata(uri, local) ?: return@operation null
        if (values.getAsInteger(Events.DIRTY) != 1 || values.getAsInteger(Events.DELETED) != 0 ||
            ATTACHMENT_SYNC_COLUMNS.any { it != Events.SYNC_DATA5 && values[it] != null }) return@operation null
        val remote = capturedRemoteSelection(receipt, calendar)
        val count = resolver.query(uri, arrayOf(Events._ID), remote.sql, remote.args, null)
            ?.use { it.count } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (count != 0) return@operation null
        // User content and child rows are deliberately absent from the update. A concurrent edit
        // is retained; a concurrent deletion or identity change aborts this atomic batch.
        requirePermissions()
        capturedBatch(arrayListOf(
            assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            ContentProviderOperation.newAssertQuery(uri).withSelection(local.sql, local.args)
                .withValues(values).withExpectedCount(1).build(),
            ContentProviderOperation.newAssertQuery(uri).withSelection(remote.sql, remote.args).withExpectedCount(0).build(),
            ContentProviderOperation.newUpdate(uri).withSelection(local.sql, local.args)
                .withValues(metadata).withExpectedCount(1).build(),
        ), intArrayOf(1, 1, 0, 1))
        requirePermissions()
        AppliedCapturedCreateAttachment(receipt)
    }

    override suspend fun inspectCapturedAttachment(receipt: CapturedCreateAttachmentReceipt): AppliedCapturedCreateAttachment? = operation {
        inspectCapturedAttachmentInternal(receipt)
    }

    override suspend fun captureCapturedCreateCurrent(baseline: CapturedCreateBaseline): CapturedCreateCurrentObservation = operation {
        captureCapturedCurrent(baseline).forCreate(baseline)
    }

    override val capturedAcknowledgementProtocol: Int get() = 1

    override suspend fun acknowledgeCapturedCreate(receipt: CapturedCreateAcknowledgementReceipt): AppliedCapturedCreateAcknowledgement? = operation {
        if (captureCapturedCurrent(receipt.baseline, writeAck = receipt) is CapturedNativeObservation.Supported)
            AppliedCapturedCreateAcknowledgement(receipt) else null
    }

    override suspend fun captureAcknowledgedCreateCurrent(receipt: CapturedCreateAcknowledgementReceipt): CapturedCreateCurrentObservation = operation {
        captureCapturedCurrent(receipt.baseline, expectedAck = receipt).forCreate(receipt.baseline, receipt)
    }

    override suspend fun inspectAcknowledgedCreateClean(receipt: CapturedCreateAcknowledgementReceipt): CapturedCreateCleanWitness? = operation {
        if (captureCapturedCurrent(receipt.baseline, expectedAck = receipt, clean = true) is CapturedNativeObservation.Supported)
            CapturedCreateCleanWitness(receipt) else null
    }

    override val capturedHandoffProtocol: Int get() = 1

    override suspend fun inspectCapturedHandoffBase(frame: CapturedCreateHandoffFrame): CapturedHandoffCleanWitness? = operation {
        val ack = frame.receipt.acknowledgement
        if (captureCapturedCurrent(ack.baseline, expectedAck = ack, clean = true, handoff = frame) is CapturedNativeObservation.Supported)
            CapturedHandoffCleanWitness(frame) else null
    }

    override suspend fun captureCapturedHandoffCurrent(frame: CapturedCreateHandoffFrame): CapturedHandoffCurrentObservation = operation {
        val ack = frame.receipt.acknowledgement
        when (val observed = captureCapturedCurrent(ack.baseline, expectedAck = ack, handoff = frame)) {
            is CapturedNativeObservation.Supported -> CapturedHandoffCurrentObservation.Supported(
                CapturedHandoffCurrent(frame, observed.snapshot.content, observed.snapshot.deleted))
            CapturedNativeObservation.Unsupported -> CapturedHandoffCurrentObservation.Unsupported
            CapturedNativeObservation.Unresolved -> CapturedHandoffCurrentObservation.Unresolved
        }
    }

    override suspend fun applyCapturedHandoffProjection(plan: CapturedHandoffProjection): AppliedCapturedHandoffProjection? = operation {
        val ack = plan.frame.receipt.acknowledgement
        if (captureCapturedCurrent(ack.baseline, expectedAck = ack, clean = true, handoff = plan.frame,
                handoffProjection = plan) is CapturedNativeObservation.Supported)
            AppliedCapturedHandoffProjection(plan) else null
    }

    override suspend fun inspectCapturedHandoffProjection(plan: CapturedHandoffProjection): AppliedCapturedHandoffProjection? = operation {
        if (inspectCapturedHistory(plan.frame.receipt.acknowledgement, plan.receipt))
            AppliedCapturedHandoffProjection(plan) else null
    }



    override suspend fun inspectCapturedAcknowledgement(receipt: CapturedCreateAcknowledgementReceipt): AppliedCapturedCreateAcknowledgement? = operation {
        if (inspectCapturedHistory(receipt)) AppliedCapturedCreateAcknowledgement(receipt) else null
    }




















    /** Explicit local removal only. Never call this merely because a calendar disappears remotely. */
    override suspend fun removeOwnedCalendar(scope: CalendarScope) = operation {
        val account = accountFor(scope.account)
        val existing = calendarRow(scope, account) ?: return@operation
        val uri = syncUri(Calendars.CONTENT_URI, account)
        val selection = calendarSelection(scope, account)
        requirePermissions()
        capturedBatch(arrayListOf(
            assertion(uri, selection, existing),
            ContentProviderOperation.newDelete(uri).withSelection(selection.sql, selection.args).withExpectedCount(1).build(),
        ), intArrayOf(1, 1))
        Unit
    }


















    companion object {
        const val ACCOUNT_TYPE = "com.patmanak.calendeer.account"
        internal const val OWNER_MARKER = "calendar-bridge-projection-v1"
        internal const val FINITE_RECEIPT_PREFIX = "calendeer-finite-"
        internal val projectionLock = Mutex()
        internal val ATTACHMENT_SYNC_COLUMNS = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2,
            Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
        internal val CAPTURED_ORIGIN_COLUMNS = arrayOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7)
        internal val ORDINARY_RESERVED_COLUMNS = CAPTURED_ORIGIN_COLUMNS + arrayOf(Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
        internal fun ordinaryReceiptMarker(value: String?): Boolean {
            val style = NativeColorStyle.read(value) ?: return false
            return style.proof == null || style.proof.matches(
                Regex("calendeer-projection-receipt-v1:[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
        }
        internal fun strictAttachmentDigest(vararg parts: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
            for (part in parts) {
                val bytes = try {
                    val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(part))
                    ByteArray(encoded.remaining()).also { encoded.get(it) }
                } catch (_: CharacterCodingException) { throw IllegalArgumentException("Invalid attachment identity text") }
                hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
        internal fun fail(category: CalendarProjectionFailure): Nothing = throw CalendarProjectionException(category)
        internal fun digest(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { part ->
                val bytes = part.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}

/** Malformed values, rejected batches and programming errors require repair, not an endless retry. */
internal fun providerFailureCategory(failure: Exception): CalendarProjectionFailure =
    if (failure is RemoteException || transientStorageFailure(failure)) CalendarProjectionFailure.PROVIDER_UNAVAILABLE
    else CalendarProjectionFailure.PROVIDER_REJECTED
