// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real SQL with controlled account/permission ports. No Provider or service access. */
@RunWith(AndroidJUnit4::class)
class RetainedLocalChangePageDeviceTest {
    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "retained-index-${UUID.randomUUID()}.db"
        val target = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0)
        val format = AdapterFormat("controlled-import", 1)
        val store = ImportStore(context, name)
        var permission = true
        var account: AccountId? = target.account
        var onAccount: suspend () -> Unit = {}
        val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { false }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("synthetic", "synthetic")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("No Provider access")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No Provider access")
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId? { onAccount(); return account }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No service reads")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No dispatch")
        }
        val schedules = AtomicInteger()
        private val networks = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() { schedules.incrementAndGet() }
            override fun requestNetwork() { schedules.incrementAndGet(); networks.incrementAndGet() }
            override fun continueWork(network: Boolean) = error("No listing scheduling")
        }
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = ImportCoordinator(context, store, remote, projection, jobs, scheduler)
        suspend fun seed() {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic index")))
            store.setEnabled(target, true)
            owner.authenticationChanged(true)
            withTimeout(10_000) { while (networks.get() == 0) delay(10) }
        }
        fun scopeKey(scope: CalendarScope) = JSONArray().put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()
        fun insert(event: String, kind: String = "UPDATE", state: String = "PAUSED", scope: CalendarScope = target) {
            // Deliberately unreadable contents prove metadata discovery never decodes the snapshots.
            store.writableDatabase.insertOrThrow("local_edits", null, ContentValues().apply {
                put("scope", scopeKey(scope)); put("mutation", "mutation-$event"); put("event", event)
                put("adapter", "controlled-import"); put("version", 1); put("base", "unreadable-base")
                put("local", "unreadable-local"); put("kind", kind); put("state", state)
            })
        }
        suspend fun list(query: RetainedLocalChangeQuery = RetainedLocalChangeQuery(target)) = owner.retainedLocalChanges(query)
        suspend fun page(limit: Int = 50, cursor: RetainedLocalChangeCursor? = null) =
            (list(RetainedLocalChangeQuery(target, limit, cursor)) as RetainedLocalChangeListing.Available).page
        fun atFinalCheck(action: suspend () -> Unit) {
            var checks = 0
            onAccount = { if (++checks == 2) action() }
        }
        fun sqlSnapshot(): List<List<String?>> {
            val db = store.writableDatabase
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { row ->
                buildList { while (row.moveToNext()) add(row.getString(0)) }
            }
            return tables.flatMap { table ->
                val columns = db.rawQuery("SELECT * FROM \"$table\" LIMIT 0", null).use { it.columnNames }
                val encoded = columns.joinToString(",") { "typeof(\"$it\"),hex(CAST(\"$it\" AS BLOB))" }
                db.rawQuery("SELECT $encoded FROM \"$table\"", null).use { row ->
                    buildList { while (row.moveToNext()) add(listOf(table) + (0 until row.columnCount).map { row.getString(it) }) }
                }
            }
        }
        suspend fun unchanged(block: suspend () -> Unit) {
            val before = sqlSnapshot(); val requests = schedules.get()
            block()
            assertTrue("Listing changed SQL", before == sqlSnapshot()); assertEquals(requests, schedules.get())
            assertFalse(store.admitTransaction(target)); assertFalse(projection.admitOperation())
        }
        suspend fun close() {
            owner.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            store.close(); context.deleteDatabase(name)
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }

    @Test fun fiftyRowBoundAndLookaheadKeepBothKindsAndConflictStates() = fixture {
        repeat(55) { insert("event-%03d".format(it), if (it % 2 == 0) "UPDATE" else "DELETE", if (it % 3 == 0) "CONFLICT" else "PAUSED") }
        unchanged {
            val first = page(); assertEquals(50, first.entries.size)
            assertEquals(EventId("event-049"), first.next!!.afterEvent)
            assertEquals(MutationKind.DELETE, first.entries[1].kind)
            assertEquals(LocalEditState.CONFLICT, first.entries[0].state)
            val rest = page(cursor = first.next)
            assertEquals((50..54).map { EventId("event-%03d".format(it)) }, rest.entries.map { it.eventId })
            assertNull(rest.next)
            assertEquals(55, (first.entries + rest.entries).map { it.eventId }.distinct().size)
        }
    }
    @Test fun emptyAndExactBoundaryPagesHaveNoNextCursor() = fixture {
        unchanged { assertTrue(page().entries.isEmpty()); assertNull(page().next) }
        insert("a"); insert("b", "DELETE")
        unchanged { assertEquals(2, page(2).entries.size); assertNull(page(2).next) }
    }
    @Test fun accountCalendarAndGenerationRemainIsolated() = fixture {
        insert("owned")
        for (other in listOf(target.copy(account = AccountId("foreign")), target.copy(calendar = CalendarId("foreign")), target.copy(generation = 1))) insert("foreign", scope = other)
        unchanged { assertEquals(listOf(EventId("owned")), page().entries.map { it.eventId }) }
    }
    @Test fun binaryUnicodeOrderingAndBoundParametersSurvivePagination() = fixture {
        val events = listOf("a' OR 1=1 --", "é", "\uE000", "\uD800\uDC00")
        events.reversed().forEach { insert(it) }
        unchanged {
            var cursor: RetainedLocalChangeCursor? = null
            val seen = mutableListOf<EventId>()
            do { val current = page(1, cursor); seen.addAll(current.entries.map { it.eventId }); cursor = current.next } while (cursor != null)
            assertEquals(events.map(::EventId), seen)
        }
    }
    @Test fun cursorReopensTheSameScopedIndexWithoutDuplicatingItsBoundary() = fixture {
        listOf("a", "b", "c").forEach { insert(it) }
        unchanged {
            val first = page(1)
            ImportStore(context, name).use { reopened ->
                val second = reopened.retainedLocalChanges(RetainedLocalChangeQuery(target, 1, first.next))
                assertEquals(listOf(EventId("b")), second.entries.map { it.eventId })
                assertEquals(EventId("b"), second.next!!.afterEvent)
            }
        }
    }
    @Test fun emptyFirstIdentityCannotBeSilentlySkipped() = fixture {
        insert(""); insert("a")
        unchanged { assertEquals(RetainedLocalChangeListing.Unavailable, list()) }
    }
    @Test fun invalidLookaheadRefusesTheEntirePage() = fixture {
        insert("a"); insert("b\u0000")
        unchanged { assertEquals(RetainedLocalChangeListing.Unavailable, list(RetainedLocalChangeQuery(target, 1))) }
    }
    @Test fun blobStorageClassesCannotBecomePagingIdentitiesOrEnums() = fixture {
        insert("a"); store.writableDatabase.execSQL("PRAGMA ignore_check_constraints=ON")
        for ((column, value) in listOf("event" to "a", "mutation" to "mutation-a", "kind" to "UPDATE", "state" to "PAUSED")) {
            store.writableDatabase.execSQL("UPDATE local_edits SET $column=CAST(? AS BLOB)", arrayOf(value))
            unchanged {
                assertEquals(RetainedLocalChangeListing.Unavailable, list())
                ImportStore(context, name).use { reader ->
                    try { reader.retainedLocalChanges(RetainedLocalChangeQuery(target)); fail("Must refuse BLOB metadata") }
                    catch (failure: Exception) {
                        assertEquals(IllegalStateException::class.java, failure.javaClass)
                        assertEquals("Invalid retained change metadata", failure.message); assertNull(failure.cause)
                    }
                }
            }
            store.writableDatabase.execSQL("UPDATE local_edits SET $column=?", arrayOf(value))
        }
        insert("b"); store.writableDatabase.execSQL("UPDATE local_edits SET event=CAST(event AS BLOB) WHERE event='b'")
        unchanged { assertEquals(RetainedLocalChangeListing.Unavailable, list(RetainedLocalChangeQuery(target, 1))) }
    }
    @Test fun oversizedUtf8MetadataAndInvalidMutationAreRefusedWithConstantDiagnostics() = fixture {
        insert("a")
        for (invalid in listOf("é".repeat(2049), "nul\u0000", " ")) {
            store.writableDatabase.execSQL("UPDATE local_edits SET mutation=?", arrayOf(invalid))
            unchanged {
                assertEquals(RetainedLocalChangeListing.Unavailable, list())
                ImportStore(context, name).use { reader ->
                    try { reader.retainedLocalChanges(RetainedLocalChangeQuery(target)); fail("Must reject malformed metadata") }
                    catch (failure: IllegalStateException) { assertEquals("Invalid retained change metadata", failure.message); assertNull(failure.cause) }
                }
            }
        }
        store.writableDatabase.execSQL("UPDATE local_edits SET mutation='synthetic-owner',event=?", arrayOf("é".repeat(2049)))
        unchanged { assertEquals(RetainedLocalChangeListing.Unavailable, list()) }
    }
    @Test fun unsupportedKindOrStateNeverReturnsPartialMetadata() = fixture {
        insert("a"); store.writableDatabase.execSQL("PRAGMA ignore_check_constraints=ON")
        store.writableDatabase.execSQL("UPDATE local_edits SET kind='CREATE'")
        unchanged { assertEquals(RetainedLocalChangeListing.Unavailable, list()) }
        store.writableDatabase.execSQL("UPDATE local_edits SET kind='UPDATE',state='UNKNOWN'")
        unchanged { assertEquals(RetainedLocalChangeListing.Unavailable, list()) }
    }
    @Test fun malformedUtf8StoredAsTextIsNeverReplacedOrUsedAsACursor() = fixture {
        insert("a"); insert("b")
        for (column in listOf("event", "mutation")) {
            store.writableDatabase.execSQL("UPDATE local_edits SET $column=CAST(X'FF' AS TEXT) WHERE mutation='mutation-b'")
            unchanged {
                assertEquals(RetainedLocalChangeListing.Unavailable, list(RetainedLocalChangeQuery(target, 1)))
            }
            // SQLite casts provide the original bytes, without a platform replacement-character decode.
            store.writableDatabase.execSQL("UPDATE local_edits SET event='b',mutation='mutation-b' WHERE CAST($column AS BLOB)=X'FF'")
        }
    }
    @Test fun discoveryDoesNotDecodeOrQualifyCorruptCapturedContents() = fixture {
        insert("a"); insert("b", "DELETE")
        unchanged {
            val values = page().entries; assertEquals(2, values.size)
            assertEquals(LocalEditInspection.Unavailable, owner.inspectLocalEdit(target, values[0].eventId, values[0].mutationId))
            assertEquals(LocalDeleteInspection.Unavailable, owner.inspectLocalDelete(target, values[1].eventId, values[1].mutationId))
        }
    }
    @Test fun insertionsBehindTheCursorRequireAFreshTraversalAndStatesCanAdvance() = fixture {
        insert("b"); insert("d")
        val cursor = page(1).next
        insert("a"); insert("z"); store.writableDatabase.execSQL("UPDATE local_edits SET state='CONFLICT' WHERE event='d'")
        unchanged {
            val continued = page(2, cursor)
            assertEquals(listOf(EventId("d"), EventId("z")), continued.entries.map { it.eventId })
            assertEquals(LocalEditState.CONFLICT, continued.entries.first().state)
            assertEquals(listOf("a", "b", "d", "z").map(::EventId), page().entries.map { it.eventId })
        }
    }
    @Test fun pausedAndUnavailableKnownCalendarsRetainDiscovery() = fixture {
        insert("a"); store.setEnabled(target, false)
        unchanged { assertEquals(1, page().entries.size) }
        store.remember(target.account, emptyList())
        unchanged { assertEquals(1, page().entries.size) }
    }
    @Test fun unknownScopeAndLostAccountOrPermissionNeverReturnPages() = fixture {
        insert("a")
        unchanged {
            for (scope in listOf(target.copy(calendar = CalendarId("unknown")), target.copy(generation = 1)))
                assertEquals(RetainedLocalChangeListing.Unqualified, list(RetainedLocalChangeQuery(scope)))
            permission = false; assertEquals(RetainedLocalChangeListing.Inactive, list())
            permission = true; account = AccountId("foreign"); assertEquals(RetainedLocalChangeListing.Inactive, list())
            account = null; assertEquals(RetainedLocalChangeListing.Inactive, list())
        }
    }
    @Test fun gatesAreRecheckedBetweenPagesAndAfterSqlReading() = fixture {
        insert("a"); insert("b"); val cursor = page(1).next
        unchanged {
            permission = false; assertEquals(RetainedLocalChangeListing.Inactive, list(RetainedLocalChangeQuery(target, 1, cursor)))
        }
        permission = true; atFinalCheck { permission = false }
        unchanged { assertEquals(RetainedLocalChangeListing.Inactive, list()) }
        permission = true; atFinalCheck { account = AccountId("foreign") }
        unchanged { assertEquals(RetainedLocalChangeListing.Inactive, list()) }
    }
    @Test fun policyAndReconnectInvalidateFrozenListing() = fixture {
        insert("a"); val selection = store.selections(target.account).single()
        atFinalCheck { onAccount = { awaitCancellation() }; owner.setEnabled(selection, false) }
        unchanged { assertEquals(RetainedLocalChangeListing.Inactive, list()) }
    }
    @Test fun reconnectInvalidatesFrozenListingAndClosesLeases() = fixture {
        insert("a")
        val before = sqlSnapshot()
        atFinalCheck { onAccount = { awaitCancellation() }; owner.authenticationChanged(false); owner.authenticationChanged(true) }
        assertEquals(RetainedLocalChangeListing.Inactive, list())
        assertTrue("Reconnection changed historical SQL", before == sqlSnapshot())
        assertFalse(store.admitTransaction(target)); assertFalse(projection.admitOperation())
        // Reconnection legitimately schedules startup recovery; listing itself does not request work.
    }
    @Test fun cancellationAtInitialAndFinalChecksPropagatesWithoutChanges() = fixture {
        insert("a")
        for (afterSql in listOf(false, true)) {
            onAccount = {}
            if (afterSql) atFinalCheck { throw CancellationException("Controlled cancellation") }
            else onAccount = { throw CancellationException("Controlled cancellation") }
            unchanged { try { list(); fail("Cancellation swallowed") } catch (_: CancellationException) { } }
        }
    }
    @Test fun sessionFailuresAreSanitizedAndKeepAllSqlTables() = fixture {
        insert("a")
        for (afterSql in listOf(false, true)) {
            onAccount = {}
            if (afterSql) atFinalCheck { error("Synthetic failure") } else onAccount = { error("Synthetic failure") }
            unchanged { assertEquals(RetainedLocalChangeListing.Unavailable, list()) }
        }
    }
    @Test fun discoveredValidOwnersRouteToTheirExactHistoricalInspection() = fixture {
        store.admitTransaction = { true }
        for (kind in listOf(MutationKind.UPDATE, MutationKind.DELETE)) {
            val base = RemoteChange.Upsert(EventId(kind.name), Revision("base"), EventContent("Synthetic original",
                EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails()))
            store.transaction(target) { putRemote(base); putProjection(ProjectionIntent(target, base)); checkpoint = PageCheckpoint(target, format) }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            val id = MutationId("owner-${kind.name}")
            if (kind == MutationKind.UPDATE) store.captureLocalEdit(LocalEditIntent(id, target, format, base, base.content.copy(title = "Synthetic edited")))
            else store.captureLocalDelete(LocalDeleteIntent(id, target, format, base, base.content))
        }
        unchanged {
            for (ref in page().entries) {
                if (ref.kind == MutationKind.UPDATE) assertTrue(owner.inspectLocalEdit(target, ref.eventId, ref.mutationId) is LocalEditInspection.Available)
                else assertTrue(owner.inspectLocalDelete(target, ref.eventId, ref.mutationId) is LocalDeleteInspection.Available)
            }
        }
    }
}
