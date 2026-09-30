package com.patmanak.proton.andcalendarbridge.data.calendar

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
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
import com.patmanak.proton.andcalendarbridge.sync.RecurrencePoint
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventDetails
import com.patmanak.proton.andcalendarbridge.sync.EventPerson
import com.patmanak.proton.andcalendarbridge.sync.AttendanceRole
import com.patmanak.proton.andcalendarbridge.sync.AttendanceStatus
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.nio.ByteBuffer
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
        val result = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        val id = existing ?: result.last().uri?.let(ContentUris::parseId)
            ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        EnsuredCalendar(id, created = existing == null)
    }

    override val projectionReceiptProtocol: Int = 1

    override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = operation {
        applyInternal(intent, seriesUid, null)
    }

    override suspend fun applyTracked(receipt: ProjectionReceipt): AppliedProjectionWitness = operation {
        applyInternal(ProjectionIntent(receipt.scope, receipt.change), null, receipt)
        requirePermissions()
        AppliedProjectionWitness(receipt)
    }

    private fun applyInternal(intent: ProjectionIntent, seriesUid: String?, receipt: ProjectionReceipt?) {
        val account = accountFor(intent.scope.account)
        val calendar = calendarRow(intent.scope, account) ?: fail(CalendarProjectionFailure.CALENDAR_MISSING)
        val uri = syncUri(Events.CONTENT_URI, account)
        val remoteId = digest("event-v1", calendarSyncId(intent.scope), intent.change.eventId.value)
        val identity = Selection("${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA4}=? OR " +
            "(${Events.SYNC_DATA4} IS NULL AND ${Events._SYNC_ID}=?))", arrayOf(calendar.toString(), remoteId, remoteId))
        val existing = ownedRow(uri, identity, Events.SYNC_DATA1)
        val previousMaster = existing?.let { masterSyncId(uri, it) }
        val operations = arrayListOf<ContentProviderOperation>()
        // Recheck calendar ownership inside the same provider transaction as the event mutation.
        operations += assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(intent.scope, account), calendar)
        operations += assertion(uri, identity, existing)
        if (existing != null) operations += ContentProviderOperation.newAssertQuery(uri)
            .withSelection(identity.sql, identity.args).withExpectedCount(1)
            .withValue(Events.DIRTY, 0).withValue(Events.DELETED, 0).build()
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
                        .withValue(Events.ALL_DAY, originalAllDay).build()
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
        resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        Unit
    }

    override suspend fun inspectProjectionReceipt(receipt: ProjectionReceipt): AppliedProjectionWitness? = operation {
        val scope = receipt.scope
        val account = accountFor(scope.account)
        val calendar = calendarRow(scope, account) ?: return@operation null
        val remoteId = digest("event-v1", calendarSyncId(scope), receipt.change.eventId.value)
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
            metadata.getAsString(Events.SYNC_DATA2) != digest("revision-v1", receipt.change.revision.value) ||
            metadata.getAsString(Events.SYNC_DATA3) != receiptMarker(receipt)) return@operation null
        requirePermissions()
        resolver.applyBatch(CalendarContract.AUTHORITY, arrayListOf(
            assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            ContentProviderOperation.newAssertQuery(uri).withSelection(identity.sql, identity.args)
                .withValues(metadata).withExpectedCount(1).build(),
        ))
        requirePermissions()
        AppliedProjectionWitness(receipt)
    }

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
            Events.SYNC_DATA4, Events.DIRTY, Events.DELETED, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
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
        resolver.applyBatch(CalendarContract.AUTHORITY, checks)
        requirePermissions()
        return OwnedLocalEditSnapshot(calendar, row, content)
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
        val count = resolver.query(uri, arrayOf(Events.SYNC_DATA1), children.sql, children.args, null)?.use {
            while (it.moveToNext()) if (it.getString(0) != OWNER_MARKER) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
            it.count
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        operations += ContentProviderOperation.newAssertQuery(uri).withSelection(children.sql, children.args)
            .withExpectedCount(count).apply { if (count > 0) {
                withValue(Events.SYNC_DATA1, OWNER_MARKER)
                withValue(Events.DIRTY, 0); withValue(Events.DELETED, 0)
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
        resolver.applyBatch(CalendarContract.AUTHORITY, arrayListOf(
            assertion(uri, selection, existing),
            ContentProviderOperation.newDelete(uri).withSelection(selection.sql, selection.args).withExpectedCount(1).build(),
        ))
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
