// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated owned Android fixture; no Proton session, network or retained test event is touched. */
@RunWith(AndroidJUnit4::class)
class CreateAssociationProviderDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val projection = AndroidCalendarProjection(context)
    private val event = RemoteChange.Upsert(EventId("synthetic-created"), Revision("synthetic-v1"),
        EventContent("Synthetic association", EventTime.AllDay(LocalDate.of(2030, 5, 1), LocalDate.of(2030, 5, 2))))
    private fun fixture(block: suspend (CalendarScope, Uri) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        assumeTrue(projection.hasPermissions())
        val scope = CalendarScope(AccountId("association-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val account = projection.accountFor(scope.account)
        val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter("account_name", account.name).appendQueryParameter("account_type", account.type).build()
        try { projection.ensureCalendar(scope, CalendarSummary(scope.calendar, "Association fixture")); block(scope, uri) }
        finally { projection.removeOwnedCalendar(scope); AccountManager.get(context).removeAccountExplicitly(account) }
    }

    @Test fun currentRevisionAndRecreatedMirrorAreResolvedWithoutWritingAnEvent() = fixture { scope, _ ->
        assertNull(projection.inspectOwnedEvent(scope, event.eventId, event.revision))
        projection.apply(ProjectionIntent(scope, event))
        val first = projection.inspectOwnedEvent(scope, event.eventId, event.revision)!!
        assertNull(projection.inspectOwnedEvent(scope.copy(generation = 1), event.eventId, event.revision))
        assertNull(projection.inspectOwnedEvent(scope, EventId("foreign-event"), event.revision))
        val newer = event.copy(revision = Revision("synthetic-v2"), content = event.content.copy(title = "Newer synthetic"))
        projection.apply(ProjectionIntent(scope, newer))
        assertNull(projection.inspectOwnedEvent(scope, event.eventId, event.revision))
        assertEquals(first.eventRow, projection.inspectOwnedEvent(scope, newer.eventId, newer.revision)!!.eventRow)
        projection.removeOwnedCalendar(scope)
        assertNull(projection.inspectOwnedEvent(scope, newer.eventId, newer.revision))
        projection.ensureCalendar(scope, CalendarSummary(scope.calendar, "Association fixture"))
        assertNull(projection.inspectOwnedEvent(scope, newer.eventId, newer.revision))
        projection.apply(ProjectionIntent(scope, newer))
        val restored = AndroidCalendarProjection(context).inspectOwnedEvent(scope, newer.eventId, newer.revision)!!
        context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
            arrayOf(restored.calendarRow.toString()), null)!!.use { assertEquals(1, it.count) }
    }

    @Test fun dirtyDeletedForeignAndDuplicateRowsCannotEstablishAnAssociation() = fixture { scope, uri ->
        projection.apply(ProjectionIntent(scope, event))
        val witness = projection.inspectOwnedEvent(scope, event.eventId, event.revision)!!
        fun update(values: ContentValues) = context.contentResolver.update(uri, values,
            "${Events._ID}=?", arrayOf(witness.eventRow.toString()))
        update(ContentValues().apply { put(Events.DIRTY, 1) })
        assertNull(projection.inspectOwnedEvent(scope, event.eventId, event.revision))
        update(ContentValues().apply { put(Events.DIRTY, 0); put(Events.DELETED, 1) })
        assertNull(projection.inspectOwnedEvent(scope, event.eventId, event.revision))
        update(ContentValues().apply { put(Events.DELETED, 0); put(Events.SYNC_DATA1, "foreign") })
        try { projection.inspectOwnedEvent(scope, event.eventId, event.revision); fail("Foreign ownership must fail") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category) }
        // Restore only this synthetic row's original ownership through the production projector.
        // Delete the deliberately foreign-marked fixture row by its exact generated locator first.
        context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(witness.eventRow.toString()))
        projection.apply(ProjectionIntent(scope, event))
        val current = projection.inspectOwnedEvent(scope, event.eventId, event.revision)!!
        val columns = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA4, Events.EVENT_TIMEZONE)
        val duplicate = context.contentResolver.query(uri, columns, "${Events._ID}=?", arrayOf(current.eventRow.toString()), null)!!.use {
            assertTrue(it.moveToFirst()); ContentValues().apply { columns.forEachIndexed { index, column -> put(column, it.getString(index)) } }
        }.apply {
            put(Events.CALENDAR_ID, current.calendarRow); put(Events.DTSTART, LocalDate.of(2030, 5, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
            put(Events.DTEND, LocalDate.of(2030, 5, 2).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()); put(Events.ALL_DAY, 1)
            put(Events.TITLE, "Synthetic duplicate")
        }
        context.contentResolver.insert(uri, duplicate) ?: error("Synthetic duplicate insert failed")
        try { projection.inspectOwnedEvent(scope, event.eventId, event.revision); fail("Duplicate identity must fail") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category) }
    }

    @Test fun duplicateInsertedBetweenIdentityLookupAndWitnessQueryIsRejected() = fixture { scope, uri ->
        projection.apply(ProjectionIntent(scope, event))
        val original = projection.inspectOwnedEvent(scope, event.eventId, event.revision)!!
        var injected = false
        val realResolver = context.contentResolver
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only inspection")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only inspection")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only inspection")
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
                val cursor = realResolver.query(uri, columns, selection, args, order) ?: return null
                if (!injected && uri.pathSegments.firstOrNull() == "events" && columns?.toList() == listOf(Events._ID, Events.SYNC_DATA1)) {
                    // Materialize the first read before changing the real isolated Provider fixture.
                    val frozen = MatrixCursor(cursor.columnNames)
                    cursor.use { while (it.moveToNext()) frozen.addRow(Array<Any?>(it.columnCount) { index -> it.getString(index) }) }
                    injected = true
                    val values = realResolver.query(uri, arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA4),
                        "${Events._ID}=?", arrayOf(original.eventRow.toString()), null)!!.use {
                        assertTrue(it.moveToFirst()); ContentValues().apply {
                            put(Events._SYNC_ID, it.getString(0)); put(Events.SYNC_DATA1, it.getString(1))
                            put(Events.SYNC_DATA2, it.getString(2)); put(Events.SYNC_DATA4, it.getString(3))
                        }
                    }.apply {
                        put(Events.CALENDAR_ID, original.calendarRow); put(Events.TITLE, "Synthetic concurrent duplicate")
                        put(Events.DTSTART, 1903824000000L); put(Events.DTEND, 1903910400000L)
                        put(Events.EVENT_TIMEZONE, "UTC"); put(Events.ALL_DAY, 1)
                    }
                    realResolver.insert(uri, values) ?: error("Concurrent fixture insert failed")
                    return frozen
                }
                return cursor
            }
        }
        val resolver = ContentResolver.wrap(provider)
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = resolver
        }
        assertNull("A concurrent duplicate cannot qualify a unique owned event",
            AndroidCalendarProjection(wrapped).inspectOwnedEvent(scope, event.eventId, event.revision))
        assertTrue("Race must have been injected", injected)
    }
}
