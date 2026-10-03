package com.patmanak.proton.andcalendarbridge.data.calendar

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
import com.patmanak.proton.andcalendarbridge.sync.ProjectionIntent
import com.patmanak.proton.andcalendarbridge.sync.ProjectionReceipt
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
import com.patmanak.proton.andcalendarbridge.sync.RecurrencePoint
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventDetails
import com.patmanak.proton.andcalendarbridge.sync.LocalEventId
import com.patmanak.proton.andcalendarbridge.sync.EventPerson
import com.patmanak.proton.andcalendarbridge.sync.AttendanceRole
import com.patmanak.proton.andcalendarbridge.sync.AttendanceStatus
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CalendarProjectionFailure {
    PERMISSION_REQUIRED, ACCOUNT_UNAVAILABLE, CALENDAR_MISSING, IDENTITY_CONFLICT, PROVIDER_UNAVAILABLE, PROVIDER_REJECTED,
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
class AndroidCalendarProjection(context: Context) : CalendarProjection {
    @Volatile override var admitOperation: () -> Boolean = { true }
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val accounts = AccountManager.get(this.context)

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
        if (scope.calendar != summary.id) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
        val account = ensureAccountInternal(scope.account)
        val uri = syncUri(Calendars.CONTENT_URI, account)
        val identity = calendarSelection(scope, account)
        val existing = calendarRow(scope, account)
        val values = ContentValues().apply {
            put(Calendars.CALENDAR_DISPLAY_NAME, summary.title)
            put(Calendars.CALENDAR_COLOR, (summary.colorRgb ?: 0x167D8D) or 0xFF000000.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
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
            operations += ContentProviderOperation.newUpdate(uri).withSelection(identity.sql, identity.args)
                .withValues(values).withExpectedCount(1).build()
        }
        requirePermissions()
        val result = checkedBatch(operations)
        if (result[0].count != (if (existing == null) 0 else 1) ||
            (existing != null && result[1].count != 1)) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        val id = existing ?: result[1].uri?.let(ContentUris::parseId)
            ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        EnsuredCalendar(id, created = existing == null)
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

    private fun applyInternal(intent: ProjectionIntent, seriesUid: String?, receipt: ProjectionReceipt?) {
        val account = accountFor(intent.scope.account)
        val calendar = calendarRow(intent.scope, account) ?: fail(CalendarProjectionFailure.CALENDAR_MISSING)
        val uri = syncUri(Events.CONTENT_URI, account)
        val remoteId = digest("event-v1", calendarSyncId(intent.scope), intent.change.eventId.value)
        val identity = Selection("${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA4}=? OR " +
            "(${Events.SYNC_DATA4} IS NULL AND ${Events._SYNC_ID}=?))", arrayOf(calendar.toString(), remoteId, remoteId))
        val existing = ownedRow(uri, identity, Events.SYNC_DATA1)
        if (existing != null) refuseCapturedMarkers(uri, identity)
        val previousMaster = existing?.let { masterSyncId(uri, it) }
        val operations = arrayListOf<ContentProviderOperation>()
        // Recheck calendar ownership inside the same provider transaction as the event mutation.
        operations += assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(intent.scope, account), calendar)
        operations += assertion(uri, identity, existing)
        if (existing != null) operations += ContentProviderOperation.newAssertQuery(uri)
            .withSelection(identity.sql, identity.args).withExpectedCount(1)
            .withValue(Events.DIRTY, 0).withValue(Events.DELETED, 0)
            .withValue(Events.SYNC_DATA5, null).withValue(Events.SYNC_DATA6, null).withValue(Events.SYNC_DATA7, null).build()
        when (val change = intent.change) {
            is RemoteChange.Upsert -> {
                val recurrence = change.content.recurrence
                val seriesId = recurrence?.let { digest("series-v1", calendarSyncId(intent.scope), it.uid) }
                val ownSyncId = if (recurrence?.isMaster == true) seriesId!! else remoteId
                val syncIdentity = Selection("${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
                    arrayOf(calendar.toString(), ownSyncId))
                val sameSync = ownedRow(uri, syncIdentity, Events.SYNC_DATA1)
                if (sameSync != null && sameSync != existing) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                operations += assertion(uri, syncIdentity, sameSync)
                val parent = if (recurrence != null && !recurrence.isMaster) {
                    val parentIdentity = Selection("${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
                        arrayOf(calendar.toString(), seriesId!!))
                    val parentId = ownedRow(uri, parentIdentity, Events.SYNC_DATA1)
                        ?: fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                    refuseCapturedMarkers(uri, parentIdentity)
                    if (masterSyncId(uri, parentId) != seriesId) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                    val originalAllDay = if (recurrence.original is RecurrencePoint.AllDay) 1 else 0
                    val parentAllDay = resolver.query(uri, arrayOf(Events.ALL_DAY),
                        "${Events._ID}=?", arrayOf(parentId.toString()), null)?.use {
                        if (!it.moveToFirst()) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                        it.getInt(0)
                    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
                    if (parentAllDay != originalAllDay) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                    operations += assertion(uri, parentIdentity, parentId)
                    // Recheck the slot's type in the same transaction as the exception write.
                    operations += ContentProviderOperation.newAssertQuery(uri)
                        .withSelection(parentIdentity.sql, parentIdentity.args).withExpectedCount(1)
                        .withValue(Events.ALL_DAY, originalAllDay)
                        .withValue(Events.SYNC_DATA5, null).withValue(Events.SYNC_DATA6, null).withValue(Events.SYNC_DATA7, null).build()
                    parentId
                } else null
                if (previousMaster != null && previousMaster != ownSyncId) {
                    removeChildren(operations, uri, calendar, previousMaster)
                }
                val values = eventValues(change).apply {
                    put(Events.CALENDAR_ID, calendar)
                    put(Events._SYNC_ID, ownSyncId)
                    put(Events.SYNC_DATA1, OWNER_MARKER)
                    put(Events.SYNC_DATA2, digest("revision-v1", change.revision.value))
                    put(Events.SYNC_DATA4, remoteId)
                    if (receipt != null) put(Events.SYNC_DATA3, receiptMarker(receipt))
                    if (parent != null) {
                        put(Events.ORIGINAL_ID, parent)
                        put(Events.ORIGINAL_SYNC_ID, seriesId)
                    }
                }
                val eventOperation = operations.size
                operations += if (existing == null) ContentProviderOperation.newInsert(uri).withValues(values).build()
                else ContentProviderOperation.newUpdate(uri).withSelection(identity.sql, identity.args)
                    .withValues(values).withExpectedCount(1).build()
                replaceDetails(operations, account, existing, eventOperation, change.content)
            }
            is RemoteChange.Deleted -> {
                // The store retains the master association after its Provider row disappears.
                val deletedSeries = seriesUid?.let { digest("series-v1", calendarSyncId(intent.scope), it) }
                    ?: previousMaster
                if (deletedSeries != null) removeChildren(operations, uri, calendar, deletedSeries)
                if (existing != null) operations += ContentProviderOperation.newDelete(uri)
                    .withSelection(identity.sql, identity.args).withExpectedCount(1).build()
            }
        }
        requirePermissions()
        checkedBatch(operations)
        Unit
    }

    override suspend fun inspectProjectionReceipt(receipt: ProjectionReceipt): AppliedProjectionWitness? {
        val frozen = snapshotReceipt(receipt)
        return operation {
            val scope = frozen.scope
            val account = accountFor(scope.account)
            val calendar = calendarRow(scope, account) ?: return@operation null
            val remoteId = digest("event-v1", calendarSyncId(scope), frozen.change.eventId.value)
            val uri = syncUri(Events.CONTENT_URI, account)
            val identity = Selection("${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA4}=? OR " +
                "(${Events.SYNC_DATA4} IS NULL AND ${Events._SYNC_ID}=?))", arrayOf(calendar.toString(), remoteId, remoteId))
            val row = ownedRow(uri, identity, Events.SYNC_DATA1) ?: return@operation null
            val metadata = resolver.query(uri, arrayOf(Events._ID, Events.CALENDAR_ID, Events._SYNC_ID,
                Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4),
                identity.sql, identity.args, null)?.use {
                if (it.count != 1 || !it.moveToFirst()) return@operation null
                ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
            } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            if (metadata.getAsLong(Events._ID) != row || metadata.getAsString(Events._SYNC_ID) != remoteId ||
                metadata.getAsString(Events.SYNC_DATA1) != OWNER_MARKER || metadata.getAsString(Events.SYNC_DATA4) != remoteId ||
                metadata.getAsString(Events.SYNC_DATA2) != digest("revision-v1", frozen.change.revision.value) ||
                metadata.getAsString(Events.SYNC_DATA3) != receiptMarker(frozen)) return@operation null
            requirePermissions()
            capturedBatch(arrayListOf(
                assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
                ContentProviderOperation.newAssertQuery(uri).withSelection(identity.sql, identity.args)
                    .withValues(metadata).withExpectedCount(1).build(),
            ), intArrayOf(1, 1))
            requirePermissions()
            AppliedProjectionWitness(frozen)
        }
    }

    private fun snapshotReceipt(receipt: ProjectionReceipt): ProjectionReceipt = try { receipt.snapshot() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(CalendarProjectionFailure.PROVIDER_REJECTED) }

    private fun receiptMarker(receipt: ProjectionReceipt) = "calendeer-projection-receipt-v${receipt.protocol}:${receipt.nonce}"

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

    override suspend fun captureOwnedSimpleDelete(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = operation {
        captureSimpleSnapshot(scope, base, deleted = true)
    }

    private fun captureSimpleSnapshot(scope: CalendarScope, base: RemoteChange.Upsert, deleted: Boolean): OwnedLocalEditSnapshot? {
        if (base.content.recurrence != null ||
            (base.content.details != null && base.content.details != EventDetails())) return null
        val account = accountFor(scope.account)
        val calendar = calendarRow(scope, account) ?: return null
        val remoteId = digest("event-v1", calendarSyncId(scope), base.eventId.value)
        val uri = syncUri(Events.CONTENT_URI, account)
        val identity = Selection("${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA4}=? OR " +
            "(${Events.SYNC_DATA4} IS NULL AND ${Events._SYNC_ID}=?))", arrayOf(calendar.toString(), remoteId, remoteId))
        val row = ownedRow(uri, identity, Events.SYNC_DATA1) ?: return null
        val columns = arrayOf(Events._ID, Events.CALENDAR_ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2,
            Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.DIRTY, Events.DELETED, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
            Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE,
            Events.EVENT_COLOR, Events.EVENT_COLOR_KEY, Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE,
            Events.DURATION, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME,
            Events.ORIGINAL_ALL_DAY, Events.STATUS, Events.HAS_ATTENDEE_DATA, Events.ORGANIZER, Events.IS_ORGANIZER,
            Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS,
            Events.HAS_ALARM, Events.SELF_ATTENDEE_STATUS, Events.AVAILABILITY, Events.ACCESS_LEVEL,
            Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
        val values = resolver.query(uri, columns, identity.sql, identity.args, null)?.use {
            if (it.count != 1 || !it.moveToFirst()) return null
            ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (values.getAsLong(Events._ID) != row || values.getAsString(Events._SYNC_ID) != remoteId ||
            values.getAsString(Events.SYNC_DATA1) != OWNER_MARKER ||
            values.getAsString(Events.SYNC_DATA2) != digest("revision-v1", base.revision.value) ||
            CAPTURED_ORIGIN_COLUMNS.any { values[it] != null } ||
            values.getAsInteger(Events.DIRTY) != 1 || values.getAsInteger(Events.DELETED) != (if (deleted) 1 else 0))
            return null
        val unsupported = arrayOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
            Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
            Events.ORGANIZER, Events.EVENT_COLOR_KEY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
        if (unsupported.any { values[it] != null }) return null
        val emptyFlags = arrayOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
            Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM,
            Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
        if (emptyFlags.any { values.getAsInteger(it) != 0 } ||
            values.getAsInteger(Events.STATUS) != Events.STATUS_CONFIRMED ||
            values.getAsInteger(Events.AVAILABILITY) != Events.AVAILABILITY_BUSY) return null
        val expectedColor = base.content.colorRgb?.or(0xFF000000.toInt())
        if (values.getAsInteger(Events.EVENT_COLOR) != expectedColor) return null
        val time = readSimpleTime(values) ?: return null
        val content = EventContent(values.getAsString(Events.TITLE) ?: "", time,
            values.getAsString(Events.DESCRIPTION) ?: "", values.getAsString(Events.EVENT_LOCATION) ?: "",
            base.content.colorRgb, details = base.content.details)
        if (!deleted && content == base.content) return null
        // Assertion-only batch: qualify the complete snapshot and absent child data together.
        // It does not make the later SQLite capture atomic with the Provider or clear DIRTY.
        val checks = arrayListOf(assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            assertion(uri, identity, row),
            ContentProviderOperation.newAssertQuery(uri).withSelection(identity.sql, identity.args)
                .withValues(values).withExpectedCount(1).build())
        for ((child, column) in listOf(Reminders.CONTENT_URI to Reminders.EVENT_ID,
            Attendees.CONTENT_URI to Attendees.EVENT_ID, ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)) {
            val childrenAbsent = resolver.query(syncUri(child, account), arrayOf("_id"),
                "$column=?", arrayOf(row.toString()), null)?.use { it.count == 0 }
                ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            if (!childrenAbsent) return null
            checks += ContentProviderOperation.newAssertQuery(syncUri(child, account))
                .withSelection("$column=?", arrayOf(row.toString())).withExpectedCount(0).build()
        }
        requirePermissions()
        capturedBatch(checks, intArrayOf(1, 1, 1, 0, 0, 0))
        requirePermissions()
        return OwnedLocalEditSnapshot(calendar, row, content)
    }

    override suspend fun captureAndStampSimpleCreate(scope: CalendarScope, eventRow: Long, localId: LocalEventId): OwnedLocalEditSnapshot? = operation {
        readLocalCreate(scope, localId, eventRow)
    }

    override suspend fun inspectStampedSimpleCreate(scope: CalendarScope, localId: LocalEventId): OwnedLocalEditSnapshot? = operation {
        readLocalCreate(scope, localId, null)
    }

    /** A row locator can select a candidate only for the durable claim's first invocation. */
    private fun readLocalCreate(scope: CalendarScope, localId: LocalEventId, firstRow: Long?): OwnedLocalEditSnapshot? {
        if (firstRow != null && firstRow <= 0) return null
        val account = accountFor(scope.account)
        val calendar = calendarRow(scope, account) ?: return null
        val uri = syncUri(Events.CONTENT_URI, account)
        val marker = digest("local-create-v1", calendarSyncId(scope), localId.value)
        val markerSelection = Selection("${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA5}=?", arrayOf(calendar.toString(), marker))
        val selection = if (firstRow == null) markerSelection else Selection(
            "${Events.CALENDAR_ID}=? AND ${Events._ID}=?", arrayOf(calendar.toString(), firstRow.toString()))
        val syncColumns = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
            Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8,
            Events.SYNC_DATA9, Events.SYNC_DATA10)
        val unsupported = arrayOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
            Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
            Events.ORGANIZER, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
        val emptyFlags = arrayOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
            Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM,
            Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
        val columns = arrayOf(Events._ID, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED, Events.TITLE,
            Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.ALL_DAY,
            Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.STATUS, Events.AVAILABILITY) +
            syncColumns + unsupported + emptyFlags
        val values = resolver.query(uri, columns, selection.sql, selection.args, null)?.use {
            if (it.count != 1 || !it.moveToFirst()) return null
            ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        val row = values.getAsLong(Events._ID) ?: return null
        if (row <= 0 || values.getAsLong(Events.CALENDAR_ID) != calendar ||
            values.getAsInteger(Events.DIRTY) != 1 || values.getAsInteger(Events.DELETED) != 0) return null
        if (syncColumns.any { column ->
                if (firstRow == null && column == Events.SYNC_DATA5) values.getAsString(column) != marker
                else values[column] != null
            } || unsupported.any { values[it] != null } || emptyFlags.any { values.getAsInteger(it) != 0 } ||
            values.getAsInteger(Events.STATUS) != Events.STATUS_CONFIRMED ||
            values.getAsInteger(Events.AVAILABILITY) != Events.AVAILABILITY_BUSY) return null
        val time = readSimpleTime(values) ?: return null
        val content = EventContent(values.getAsString(Events.TITLE) ?: "", time,
            values.getAsString(Events.DESCRIPTION) ?: "", values.getAsString(Events.EVENT_LOCATION) ?: "",
            details = EventDetails())
        val checks = arrayListOf(assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            ContentProviderOperation.newAssertQuery(uri).withSelection(selection.sql, selection.args)
                .withValues(values).withExpectedCount(1).build(),
            ContentProviderOperation.newAssertQuery(uri).withSelection(markerSelection.sql, markerSelection.args)
                .withExpectedCount(if (firstRow == null) 1 else 0).build())
        for ((child, column) in listOf(Reminders.CONTENT_URI to Reminders.EVENT_ID,
            Attendees.CONTENT_URI to Attendees.EVENT_ID, ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)) {
            val absent = resolver.query(syncUri(child, account), arrayOf("_id"), "$column=?", arrayOf(row.toString()), null)
                ?.use { it.count == 0 } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            if (!absent) return null
            checks += ContentProviderOperation.newAssertQuery(syncUri(child, account))
                .withSelection("$column=?", arrayOf(row.toString())).withExpectedCount(0).build()
        }
        if (firstRow != null) checks += ContentProviderOperation.newUpdate(uri)
            .withSelection(selection.sql, selection.args).withValue(Events.SYNC_DATA5, marker).withExpectedCount(1).build()
        requirePermissions()
        capturedBatch(checks, if (firstRow == null) intArrayOf(1, 1, 1, 0, 0, 0)
            else intArrayOf(1, 1, 0, 0, 0, 0, 1))
        requirePermissions()
        return OwnedLocalEditSnapshot(calendar, row, content)
    }

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
        captureCapturedCurrent(baseline)
    }

    override val capturedAcknowledgementProtocol: Int get() = 1

    override suspend fun acknowledgeCapturedCreate(receipt: CapturedCreateAcknowledgementReceipt): AppliedCapturedCreateAcknowledgement? = operation {
        if (captureCapturedCurrent(receipt.baseline, writeAck = receipt) is CapturedCreateCurrentObservation.Supported)
            AppliedCapturedCreateAcknowledgement(receipt) else null
    }

    override suspend fun captureAcknowledgedCreateCurrent(receipt: CapturedCreateAcknowledgementReceipt): CapturedCreateCurrentObservation = operation {
        captureCapturedCurrent(receipt.baseline, expectedAck = receipt)
    }

    override suspend fun inspectAcknowledgedCreateClean(receipt: CapturedCreateAcknowledgementReceipt): CapturedCreateCleanWitness? = operation {
        if (captureCapturedCurrent(receipt.baseline, expectedAck = receipt, clean = true) is CapturedCreateCurrentObservation.Supported)
            CapturedCreateCleanWitness(receipt) else null
    }

    override val capturedHandoffProtocol: Int get() = 1

    override suspend fun inspectCapturedHandoffBase(frame: CapturedCreateHandoffFrame): CapturedHandoffCleanWitness? = operation {
        val ack = frame.receipt.acknowledgement
        if (captureCapturedCurrent(ack.baseline, expectedAck = ack, clean = true, handoff = frame) is CapturedCreateCurrentObservation.Supported)
            CapturedHandoffCleanWitness(frame) else null
    }

    override suspend fun captureCapturedHandoffCurrent(frame: CapturedCreateHandoffFrame): CapturedHandoffCurrentObservation = operation {
        val ack = frame.receipt.acknowledgement
        when (val observed = captureCapturedCurrent(ack.baseline, expectedAck = ack, handoff = frame)) {
            is CapturedCreateCurrentObservation.Supported -> CapturedHandoffCurrentObservation.Supported(
                CapturedHandoffCurrent(frame, observed.snapshot.content, observed.snapshot.deleted))
            CapturedCreateCurrentObservation.Unsupported -> CapturedHandoffCurrentObservation.Unsupported
            CapturedCreateCurrentObservation.Unresolved -> CapturedHandoffCurrentObservation.Unresolved
        }
    }

    override suspend fun applyCapturedHandoffProjection(plan: CapturedHandoffProjection): AppliedCapturedHandoffProjection? = operation {
        val ack = plan.frame.receipt.acknowledgement
        if (captureCapturedCurrent(ack.baseline, expectedAck = ack, clean = true, handoff = plan.frame,
                handoffProjection = plan) is CapturedCreateCurrentObservation.Supported)
            AppliedCapturedHandoffProjection(plan) else null
    }

    override suspend fun inspectCapturedHandoffProjection(plan: CapturedHandoffProjection): AppliedCapturedHandoffProjection? = operation {
        if (inspectCapturedHistory(plan.frame.receipt.acknowledgement, plan.receipt))
            AppliedCapturedHandoffProjection(plan) else null
    }

    private fun capturedAcknowledgementMarker(receipt: CapturedCreateAcknowledgementReceipt) =
        "calendeer-captured-ack-v1:" + receipt.fingerprint()

    private fun captureCapturedCurrent(baseline: CapturedCreateBaseline,
        expectedAck: CapturedCreateAcknowledgementReceipt? = null,
        writeAck: CapturedCreateAcknowledgementReceipt? = null,
        clean: Boolean = false,
        handoff: CapturedCreateHandoffFrame? = null,
        handoffProjection: CapturedHandoffProjection? = null): CapturedCreateCurrentObservation {
        val ackMarker = (expectedAck ?: writeAck)?.let(::capturedAcknowledgementMarker)
        val receipt = baseline.receipt
        val expected = capturedAttachmentValues(receipt) // Strict canonical identity before Provider IO.
        if (expectedAck != null) expected.put(Events.SYNC_DATA7, ackMarker)
        if (handoff != null) {
            expected.put(Events.SYNC_DATA2, digest("revision-v1", handoff.base.revision.value))
            expected.put(Events.SYNC_DATA3, handoff.lastProjection?.let(::receiptMarker))
        }
        val scope = baseline.scope
        val account = accountFor(scope.account)
        val calendar = try { calendarRow(scope, account) } catch (failure: CalendarProjectionException) {
            if (failure.category == CalendarProjectionFailure.IDENTITY_CONFLICT)
                return CapturedCreateCurrentObservation.Unresolved
            throw failure
        } ?: return CapturedCreateCurrentObservation.Unresolved
        val uri = syncUri(Events.CONTENT_URI, account)
        val local = capturedLocalSelection(receipt, calendar)
        val unsupported = arrayOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
            Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
            Events.ORGANIZER, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
        val emptyFlags = arrayOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
            Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM,
            Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
        val columns = arrayOf(Events._ID, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED, Events.TITLE,
            Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.ALL_DAY,
            Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.STATUS, Events.AVAILABILITY) +
            ATTACHMENT_SYNC_COLUMNS + unsupported + emptyFlags
        val values = resolver.query(uri, columns, local.sql, local.args, null)?.use {
            if (it.count != 1 || !it.moveToFirst()) return CapturedCreateCurrentObservation.Unresolved
            ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        val row = values.getAsLong(Events._ID)
        val deleted = values.getAsInteger(Events.DELETED)
        if (row == null || row <= 0 || values.getAsLong(Events.CALENDAR_ID) != calendar ||
            values.getAsInteger(Events.DIRTY) != (if (clean) 0 else 1) ||
            (if (clean) deleted != 0 else deleted != 0 && deleted != 1) ||
            values.getAsString(Events.SYNC_DATA5) != local.args[1] ||
            expected.keySet().any { values.getAsString(it) != expected.getAsString(it) } ||
            ATTACHMENT_SYNC_COLUMNS.any { it !in expected.keySet() && it != Events.SYNC_DATA5 && values[it] != null })
            return CapturedCreateCurrentObservation.Unresolved
        val remote = capturedRemoteSelection(receipt, calendar)
        val remoteMatches = resolver.query(uri, arrayOf(Events._ID), remote.sql, remote.args, null)?.use {
            it.count == 1 && it.moveToFirst() && it.getLong(0) == row
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (!remoteMatches) return CapturedCreateCurrentObservation.Unresolved
        if (unsupported.any { values[it] != null } || emptyFlags.any { values.getAsInteger(it) != 0 } ||
            values.getAsInteger(Events.STATUS) != Events.STATUS_CONFIRMED ||
            values.getAsInteger(Events.AVAILABILITY) != Events.AVAILABILITY_BUSY)
            return CapturedCreateCurrentObservation.Unsupported
        val time = readSimpleTime(values) ?: return CapturedCreateCurrentObservation.Unsupported
        val snapshot = try {
            CapturedCreateCurrent(baseline, EventContent(values.getAsString(Events.TITLE) ?: "", time,
                values.getAsString(Events.DESCRIPTION) ?: "", values.getAsString(Events.EVENT_LOCATION) ?: "",
                details = EventDetails()), deleted == 1, acknowledgement = expectedAck)
        } catch (_: IllegalArgumentException) { return CapturedCreateCurrentObservation.Unsupported }
        val unchanged = if (handoff == null) snapshot.unchanged else !snapshot.deleted && snapshot.content == handoff.base.content
        if ((writeAck != null || clean) && !unchanged) return CapturedCreateCurrentObservation.Unresolved
        val checks = arrayListOf(assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            ContentProviderOperation.newAssertQuery(uri).withSelection(local.sql, local.args)
                .withValues(values).withExpectedCount(1).build(),
            ContentProviderOperation.newAssertQuery(uri).withSelection(remote.sql, remote.args)
                .withValue(Events._ID, row).withExpectedCount(1).build())
        for ((child, column) in listOf(Reminders.CONTENT_URI to Reminders.EVENT_ID,
            Attendees.CONTENT_URI to Attendees.EVENT_ID, ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)) {
            val absent = resolver.query(syncUri(child, account), arrayOf("_id"), "$column=?", arrayOf(row.toString()), null)
                ?.use { it.count == 0 } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            if (!absent) return CapturedCreateCurrentObservation.Unsupported
            checks += ContentProviderOperation.newAssertQuery(syncUri(child, account))
                .withSelection("$column=?", arrayOf(row.toString())).withExpectedCount(0).build()
        }
        if (writeAck != null) checks += ContentProviderOperation.newUpdate(uri)
            .withSelection(local.sql, local.args).withValue(Events.DIRTY, 0)
            .withValue(Events.SYNC_DATA7, ackMarker).withExpectedCount(1).build()
        if (handoffProjection != null) checks += ContentProviderOperation.newUpdate(uri)
            .withSelection(local.sql, local.args).withValues(eventValues(handoffProjection.receipt.change).apply {
                put(Events.SYNC_DATA2, digest("revision-v1", handoffProjection.receipt.change.revision.value))
                put(Events.SYNC_DATA3, receiptMarker(handoffProjection.receipt))
            }).withExpectedCount(1).build()
        // ACK and incoming handoff writes admit from current assertions, never a prior snapshot.
        // Every identity, content/flag value and child absence is qualified in this one batch.
        requirePermissions()
        capturedBatch(checks, if (writeAck != null || handoffProjection != null)
            intArrayOf(1, 1, 1, 0, 0, 0, 1) else intArrayOf(1, 1, 1, 0, 0, 0))
        requirePermissions()
        return CapturedCreateCurrentObservation.Supported(snapshot)
    }

    override suspend fun inspectCapturedAcknowledgement(receipt: CapturedCreateAcknowledgementReceipt): AppliedCapturedCreateAcknowledgement? = operation {
        if (inspectCapturedHistory(receipt)) AppliedCapturedCreateAcknowledgement(receipt) else null
    }

    private fun inspectCapturedHistory(receipt: CapturedCreateAcknowledgementReceipt, projectionReceipt: ProjectionReceipt? = null): Boolean {
        val baseline = receipt.baseline
        val expected = capturedAttachmentValues(baseline.receipt)
        expected.put(Events.SYNC_DATA7, capturedAcknowledgementMarker(receipt))
        if (projectionReceipt != null) {
            expected.put(Events.SYNC_DATA2, digest("revision-v1", projectionReceipt.change.revision.value))
            expected.put(Events.SYNC_DATA3, receiptMarker(projectionReceipt))
        }
        val scope = baseline.scope
        val account = accountFor(scope.account)
        val calendar = try { calendarRow(scope, account) } catch (failure: CalendarProjectionException) {
            if (failure.category == CalendarProjectionFailure.IDENTITY_CONFLICT) return false
            throw failure
        } ?: return false
        val uri = syncUri(Events.CONTENT_URI, account)
        val local = capturedLocalSelection(baseline.receipt, calendar)
        val values = attachmentMetadata(uri, local) ?: return false
        if (expected.keySet().any { values.getAsString(it) != expected.getAsString(it) } ||
            values.getAsString(Events.SYNC_DATA5) != local.args[1] ||
            ATTACHMENT_SYNC_COLUMNS.any { it !in expected.keySet() && it != Events.SYNC_DATA5 && values[it] != null })
            return false
        val remote = capturedRemoteSelection(baseline.receipt, calendar)
        val remoteMatches = resolver.query(uri, arrayOf(Events._ID), remote.sql, remote.args, null)?.use {
            it.count == 1 && it.moveToFirst() && it.getLong(0) == values.getAsLong(Events._ID)
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (!remoteMatches) return false
        // Historical proof ignores later user data, children and dirty/deleted flags.
        val metadata = ContentValues(values).apply { remove(Events.DIRTY); remove(Events.DELETED) }
        requirePermissions()
        capturedBatch(arrayListOf(
            assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            ContentProviderOperation.newAssertQuery(uri).withSelection(local.sql, local.args)
                .withValues(metadata).withExpectedCount(1).build(),
            ContentProviderOperation.newAssertQuery(uri).withSelection(remote.sql, remote.args)
                .withValue(Events._ID, values.getAsLong(Events._ID)).withExpectedCount(1).build(),
        ), intArrayOf(1, 1, 1))
        requirePermissions()
        return true
    }

    /** A lost or malformed response cannot establish a write or assertion witness. */
    private fun capturedBatch(operations: ArrayList<ContentProviderOperation>, expected: IntArray) {
        val results = checkedBatch(operations)
        if (results.size != expected.size) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        results.forEachIndexed { index, result ->
            if (result.count != expected[index] || result.uri != null) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        }
    }

    /** Validate the response before returning success. Expected counts remain enforced by the
     * Provider's operations; fixed proof batches additionally check the exact returned counts. */
    private fun checkedBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        val results: Array<ContentProviderResult?>? = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        if (results == null || results.size != operations.size) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        return Array(results.size) { index ->
            val result = results[index] ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            if (result.extras != null || result.exception != null) fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            val operation = operations[index]
            val count = result.count
            if (operation.isInsert) {
                val uri = result.uri ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
                val segments = uri.pathSegments
                if (count != null || uri.scheme != "content" || uri.authority != operation.uri.authority ||
                    segments.dropLast(1) != operation.uri.pathSegments ||
                    segments.lastOrNull()?.toLongOrNull()?.let { it > 0 } != true)
                    fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            } else if (result.uri != null || count == null || count < 0) {
                fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            }
            result
        }
    }

    private fun inspectCapturedAttachmentInternal(receipt: CapturedCreateAttachmentReceipt): AppliedCapturedCreateAttachment? {
        val expected = capturedAttachmentValues(receipt)
        val scope = receipt.binding.identity.attempt.scope
        val account = accountFor(scope.account)
        val calendar = calendarRow(scope, account) ?: return null
        val uri = syncUri(Events.CONTENT_URI, account)
        val local = capturedLocalSelection(receipt, calendar)
        val values = attachmentMetadata(uri, local) ?: return null
        if (expected.keySet().any { values.getAsString(it) != expected.getAsString(it) } ||
            values.getAsString(Events.SYNC_DATA5) != local.args[1]) return null
        val remote = capturedRemoteSelection(receipt, calendar)
        val remoteValues = resolver.query(uri, arrayOf(Events._ID), remote.sql, remote.args, null)?.use {
            it.count == 1 && it.moveToFirst() && it.getLong(0) == values.getAsLong(Events._ID)
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (!remoteValues) return null
        val checks = ContentValues(values).apply { remove(Events.DIRTY); remove(Events.DELETED) }
        requirePermissions()
        capturedBatch(arrayListOf(
            assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            ContentProviderOperation.newAssertQuery(uri).withSelection(local.sql, local.args)
                .withValues(checks).withExpectedCount(1).build(),
            ContentProviderOperation.newAssertQuery(uri).withSelection(remote.sql, remote.args)
                .withValue(Events._ID, values.getAsLong(Events._ID)).withExpectedCount(1).build(),
        ), intArrayOf(1, 1, 1))
        requirePermissions()
        return AppliedCapturedCreateAttachment(receipt)
    }

    private fun attachmentMetadata(uri: Uri, selection: Selection): ContentValues? {
        val cursor = resolver.query(uri, arrayOf(Events._ID, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED) + ATTACHMENT_SYNC_COLUMNS,
            selection.sql, selection.args, null) ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        return cursor.use {
            if (it.count != 1 || !it.moveToFirst()) null
            else ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
        }
    }

    private fun capturedLocalSelection(receipt: CapturedCreateAttachmentReceipt, calendar: Long): Selection {
        val identity = receipt.binding.identity
        return Selection("${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA5}=?", arrayOf(calendar.toString(),
            digest("local-create-v1", calendarSyncId(identity.attempt.scope), identity.localId.value)))
    }

    private fun capturedRemoteSelection(receipt: CapturedCreateAttachmentReceipt, calendar: Long): Selection {
        val remote = digest("event-v1", calendarSyncId(receipt.binding.identity.attempt.scope), receipt.binding.eventId.value)
        return Selection("${Events.CALENDAR_ID}=? AND (${Events._SYNC_ID}=? OR ${Events.SYNC_DATA4}=?)",
            arrayOf(calendar.toString(), remote, remote))
    }

    private fun capturedAttachmentValues(receipt: CapturedCreateAttachmentReceipt) = ContentValues().apply {
        val identity = receipt.binding.identity
        val attempt = identity.attempt
        val remote = digest("event-v1", calendarSyncId(attempt.scope), receipt.binding.eventId.value)
        put(Events._SYNC_ID, remote); put(Events.SYNC_DATA1, OWNER_MARKER)
        put(Events.SYNC_DATA2, digest("revision-v1", receipt.binding.verifiedRevision.value)); put(Events.SYNC_DATA4, remote)
        // Bind the full immutable receipt, not just a nonce or historical row locator.
        put(Events.SYNC_DATA6, "calendeer-captured-attachment-v1:" + strictAttachmentDigest("captured-attachment-v1",
            attempt.scope.account.value, attempt.scope.calendar.value, attempt.scope.generation.toString(),
            attempt.id.value, attempt.format.id, attempt.format.version.toString(),
            attempt.payloadFingerprint, identity.localId.value, identity.captureId.value, identity.captureFormat.id,
            identity.captureFormat.version.toString(), identity.timestamp.toString(), identity.contentFingerprint,
            identity.fingerprintVersion.toString(), receipt.binding.eventId.value, receipt.binding.verifiedRevision.value,
            receipt.protocol.toString(), receipt.nonce))
    }

    private fun readSimpleTime(values: ContentValues): EventTime? {
        val start = values.getAsLong(Events.DTSTART) ?: return null
        val end = values.getAsLong(Events.DTEND) ?: return null
        if (end <= start || start % 1000L != 0L || end % 1000L != 0L) return null
        val zone = values.getAsString(Events.EVENT_TIMEZONE) ?: return null
        if (values.getAsString(Events.EVENT_END_TIMEZONE) != zone) return null
        return try {
            when (values.getAsInteger(Events.ALL_DAY)) {
                0 -> EventTime.Timed(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), ZoneId.of(zone))
                1 -> {
                    if (zone != "UTC" || start % 86_400_000L != 0L || end % 86_400_000L != 0L) return null
                    EventTime.AllDay(Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC).toLocalDate(),
                        Instant.ofEpochMilli(end).atOffset(ZoneOffset.UTC).toLocalDate())
                }
                else -> null
            }
        } catch (_: IllegalArgumentException) { null }
          catch (_: java.time.DateTimeException) { null }
    }

    /** Child rows have no ownership marker; the parent assertions above guard this whole batch. */
    private fun replaceDetails(operations: ArrayList<ContentProviderOperation>, account: Account,
        eventId: Long?, eventOperation: Int, content: EventContent) {
        val remindersUri = syncUri(Reminders.CONTENT_URI, account)
        val attendeesUri = syncUri(Attendees.CONTENT_URI, account)
        if (eventId != null) {
            operations += ContentProviderOperation.newDelete(remindersUri)
                .withSelection("${Reminders.EVENT_ID}=?", arrayOf(eventId.toString())).build()
            operations += ContentProviderOperation.newDelete(attendeesUri)
                .withSelection("${Attendees.EVENT_ID}=?", arrayOf(eventId.toString())).build()
        }
        val details = content.details ?: return
        fun ContentProviderOperation.Builder.forEvent(column: String): ContentProviderOperation.Builder =
            if (eventId != null) withValue(column, eventId) else withValueBackReference(column, eventOperation)
        // Cancelled exceptions must suppress the original instance without scheduling an alert.
        if (content.recurrence?.cancelled != true) details.reminders.forEach { reminder ->
            operations += ContentProviderOperation.newInsert(remindersUri).forEvent(Reminders.EVENT_ID)
                .withValue(Reminders.MINUTES, reminder.minutesBefore)
                .withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
        }
        fun addPerson(person: EventPerson, role: Int, status: Int, relationship: Int) {
            operations += ContentProviderOperation.newInsert(attendeesUri).forEvent(Attendees.EVENT_ID)
                .withValue(Attendees.ATTENDEE_NAME, person.name)
                .withValue(Attendees.ATTENDEE_EMAIL, person.email)
                .withValue(Attendees.ATTENDEE_TYPE, role)
                .withValue(Attendees.ATTENDEE_STATUS, status)
                .withValue(Attendees.ATTENDEE_RELATIONSHIP, relationship).build()
        }
        details.attendees.forEach { attendee ->
            addPerson(attendee.person, when (attendee.role) {
                AttendanceRole.REQUIRED -> Attendees.TYPE_REQUIRED
                AttendanceRole.OPTIONAL -> Attendees.TYPE_OPTIONAL
                AttendanceRole.RESOURCE -> Attendees.TYPE_RESOURCE
                AttendanceRole.NONE -> Attendees.TYPE_NONE
            }, when (attendee.status) {
                AttendanceStatus.NEEDS_ACTION -> Attendees.ATTENDEE_STATUS_INVITED
                AttendanceStatus.ACCEPTED -> Attendees.ATTENDEE_STATUS_ACCEPTED
                AttendanceStatus.DECLINED -> Attendees.ATTENDEE_STATUS_DECLINED
                AttendanceStatus.TENTATIVE -> Attendees.ATTENDEE_STATUS_TENTATIVE
                AttendanceStatus.UNKNOWN -> Attendees.ATTENDEE_STATUS_NONE
            }, if (attendee.person.email.equals(details.organizer?.email, ignoreCase = true))
                Attendees.RELATIONSHIP_ORGANIZER else Attendees.RELATIONSHIP_ATTENDEE)
        }
        details.organizer?.takeIf { organizer -> details.attendees.none {
            it.person.email.equals(organizer.email, ignoreCase = true)
        } }?.let { addPerson(it, Attendees.TYPE_NONE, Attendees.ATTENDEE_STATUS_NONE, Attendees.RELATIONSHIP_ORGANIZER) }
    }

    private fun masterSyncId(uri: Uri, event: Long): String? {
        requirePermissions()
        val cursor = resolver.query(uri, arrayOf(Events._SYNC_ID, Events.RRULE, Events.RDATE),
            "${Events._ID}=?", arrayOf(event.toString()), null)
            ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        return cursor.use {
            if (!it.moveToFirst()) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
            if (!it.getString(1).isNullOrEmpty() || !it.getString(2).isNullOrEmpty()) it.getString(0) else null
        }
    }

    private fun removeChildren(operations: ArrayList<ContentProviderOperation>, uri: Uri, calendar: Long, series: String) {
        val children = Selection("${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_SYNC_ID}=?",
            arrayOf(calendar.toString(), series))
        requirePermissions()
        val count = resolver.query(uri, arrayOf(Events.SYNC_DATA1) + CAPTURED_ORIGIN_COLUMNS, children.sql, children.args, null)?.use {
            while (it.moveToNext()) {
                if (it.getString(0) != OWNER_MARKER || (1..CAPTURED_ORIGIN_COLUMNS.size).any { column -> !it.isNull(column) })
                    fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
            }
            it.count
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        operations += ContentProviderOperation.newAssertQuery(uri).withSelection(children.sql, children.args)
            .withExpectedCount(count).apply { if (count > 0) {
                withValue(Events.SYNC_DATA1, OWNER_MARKER)
                withValue(Events.DIRTY, 0); withValue(Events.DELETED, 0)
                CAPTURED_ORIGIN_COLUMNS.forEach { withValue(it, null) }
            } }.build()
        if (count > 0) operations += ContentProviderOperation.newDelete(uri).withSelection(children.sql, children.args)
            .withExpectedCount(count).build()
    }

    /** Explicit local removal only. Never call this merely because a calendar disappears remotely. */
    suspend fun removeOwnedCalendar(scope: CalendarScope) = operation {
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

    private fun ensureAccountInternal(accountId: AccountId): Account {
        val account = accountFor(accountId)
        if (accounts.getAccountsByType(ACCOUNT_TYPE).none { it == account } &&
            !accounts.addAccountExplicitly(account, null, null) &&
            accounts.getAccountsByType(ACCOUNT_TYPE).none { it == account }) {
            fail(CalendarProjectionFailure.ACCOUNT_UNAVAILABLE)
        }
        return account
    }

    private fun calendarRow(scope: CalendarScope, account: Account): Long? =
        ownedRow(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), Calendars.CAL_SYNC1)

    /** Captured rows require their specialized source/protocol, even when currently clean. */
    private fun refuseCapturedMarkers(uri: Uri, selection: Selection) {
        val captured = resolver.query(uri, CAPTURED_ORIGIN_COLUMNS, selection.sql, selection.args, null)?.use {
            if (it.count != 1 || !it.moveToFirst()) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
            CAPTURED_ORIGIN_COLUMNS.indices.any { column -> !it.isNull(column) }
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (captured) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
    }

    private fun ownedRow(uri: Uri, selection: Selection, ownerColumn: String): Long? {
        requirePermissions()
        val cursor = resolver.query(uri, arrayOf("_id", ownerColumn), selection.sql, selection.args, null)
            ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        return cursor.use {
            if (!it.moveToFirst()) null
            else {
                if (it.count != 1 || it.getString(1) != OWNER_MARKER) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                it.getLong(0)
            }
        }
    }

    private fun assertion(uri: Uri, selection: Selection, existing: Long?): ContentProviderOperation {
        val builder = ContentProviderOperation.newAssertQuery(uri).withSelection(selection.sql, selection.args)
            .withExpectedCount(if (existing == null) 0 else 1)
        if (existing != null) {
            builder.withValue("_id", existing)
            builder.withValue(if (uri.pathSegments.firstOrNull() == "calendars") Calendars.CAL_SYNC1 else Events.SYNC_DATA1,
                OWNER_MARKER)
        }
        return builder.build()
    }

    private fun eventValues(change: RemoteChange.Upsert): ContentValues = ContentValues().apply {
        put(Events.TITLE, change.content.title)
        put(Events.DESCRIPTION, change.content.description)
        put(Events.EVENT_LOCATION, change.content.location)
        putNull(Events.EVENT_COLOR_KEY)
        val color = change.content.colorRgb
        if (color == null) putNull(Events.EVENT_COLOR) else put(Events.EVENT_COLOR, color or 0xFF000000.toInt())
        put(Events.DIRTY, 0)
        put(Events.DELETED, 0)
        // A later untracked write must not leave an old receipt as proof of new content.
        putNull(Events.SYNC_DATA3)
        put(Events.STATUS, Events.STATUS_CONFIRMED)
        val details = change.content.details
        put(Events.HAS_ATTENDEE_DATA, if (details?.organizer != null || !details?.attendees.isNullOrEmpty()) 1 else 0)
        val organizer = details?.organizer
        if (organizer == null) putNull(Events.ORGANIZER) else put(Events.ORGANIZER, organizer.email)
        // Provider defaults SELF_ATTENDEE_STATUS to NONE. Supplying it would create a
        // synthetic self-attendee on insert and is forbidden on event updates. Our owned
        // calendar's opaque owner name cannot match an attendee email, so no RSVP is inferred.
        put(Events.IS_ORGANIZER, 0)
        put(Events.GUESTS_CAN_MODIFY, 0)
        put(Events.GUESTS_CAN_INVITE_OTHERS, 0)
        put(Events.GUESTS_CAN_SEE_GUESTS, 0)
        putNull(Events.RRULE)
        putNull(Events.RDATE)
        putNull(Events.EXRULE)
        putNull(Events.EXDATE)
        putNull(Events.DURATION)
        putNull(Events.ORIGINAL_ID)
        putNull(Events.ORIGINAL_SYNC_ID)
        putNull(Events.ORIGINAL_INSTANCE_TIME)
        putNull(Events.ORIGINAL_ALL_DAY)
        when (val time = change.content.time) {
            is EventTime.Timed -> {
                put(Events.DTSTART, time.start.toEpochMilli())
                put(Events.DTEND, time.end.toEpochMilli())
                put(Events.ALL_DAY, 0)
                put(Events.EVENT_TIMEZONE, time.zone.id)
                put(Events.EVENT_END_TIMEZONE, time.zone.id)
            }
            is EventTime.AllDay -> {
                put(Events.DTSTART, time.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.DTEND, time.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.ALL_DAY, 1)
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.EVENT_END_TIMEZONE, "UTC")
            }
        }
        change.content.recurrence?.let { recurrence ->
            if (recurrence.isMaster) {
                putNull(Events.DTEND)
                put(Events.DURATION, when (val time = change.content.time) {
                    is EventTime.AllDay -> "P${ChronoUnit.DAYS.between(time.start, time.endExclusive)}D"
                    is EventTime.Timed -> "PT${Duration.between(time.start, time.end).seconds}S"
                })
                recurrence.rule?.let { put(Events.RRULE, it) }
                val additionalDates = if (recurrence.rule == null) {
                    // Android expands an RDATE-only set literally; RFC 5545 also includes DTSTART.
                    val first = when (val time = change.content.time) {
                        is EventTime.AllDay -> RecurrencePoint.AllDay(time.start)
                        is EventTime.Timed -> RecurrencePoint.Timed(time.start)
                    }
                    (listOf(first) + recurrence.additionalDates).distinct()
                } else recurrence.additionalDates
                if (additionalDates.isNotEmpty()) put(Events.RDATE, recurrenceDates(additionalDates))
                if (recurrence.excludedDates.isNotEmpty()) put(Events.EXDATE, recurrenceDates(recurrence.excludedDates))
            } else {
                val original = recurrence.original!!
                put(Events.ORIGINAL_INSTANCE_TIME, recurrenceInstant(original))
                put(Events.ORIGINAL_ALL_DAY, if (original is RecurrencePoint.AllDay) 1 else 0)
                if (recurrence.cancelled) put(Events.STATUS, Events.STATUS_CANCELED)
            }
        }
    }

    private fun recurrenceInstant(point: RecurrencePoint): Long = when (point) {
        is RecurrencePoint.AllDay -> point.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        is RecurrencePoint.Timed -> point.instant.toEpochMilli()
    }

    private fun recurrenceDates(points: List<RecurrencePoint>): String = points.joinToString(",") {
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(recurrenceInstant(it)))
    }

    private fun calendarSyncId(scope: CalendarScope): String = digest("calendar-v1", scope.account.value,
        scope.calendar.value, scope.generation.toString())

    private fun calendarSelection(scope: CalendarScope, account: Account) = Selection(
        "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars._SYNC_ID}=?",
        arrayOf(account.name, account.type, calendarSyncId(scope)))

    private fun syncUri(uri: Uri, account: Account): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build()

    private fun requirePermissions() {
        if (!admitOperation()) throw CancellationException("Inactive import lease")
        if (!hasPermissions()) fail(CalendarProjectionFailure.PERMISSION_REQUIRED)
    }

    private suspend fun <T> operation(block: () -> T): T = withContext(Dispatchers.IO) {
        projectionLock.withLock {
            requirePermissions()
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (known: CalendarProjectionException) { throw known }
            catch (_: SecurityException) {
                fail(if (hasPermissions()) CalendarProjectionFailure.ACCOUNT_UNAVAILABLE else CalendarProjectionFailure.PERMISSION_REQUIRED)
            }
            catch (failure: Exception) { fail(providerFailureCategory(failure)) }
        }
    }

    private data class Selection(val sql: String, val args: Array<String>)

    companion object {
        const val ACCOUNT_TYPE = "com.patmanak.proton.andcalendarbridge.account"
        private const val OWNER_MARKER = "calendar-bridge-projection-v1"
        private val projectionLock = Mutex()
        private val ATTACHMENT_SYNC_COLUMNS = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2,
            Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
        private val CAPTURED_ORIGIN_COLUMNS = arrayOf(Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7)
        private fun strictAttachmentDigest(vararg parts: String): String {
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
        private fun fail(category: CalendarProjectionFailure): Nothing = throw CalendarProjectionException(category)
        private fun digest(vararg parts: String): String {
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
