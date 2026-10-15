// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated metadata fixtures. No Provider, sessions, protected-payload codec or network. */
@RunWith(AndroidJUnit4::class)
class TextUpdateWorkPageDeviceTest {
    private val scope = CalendarScope(AccountId("work-page-account"), CalendarId("work-page-calendar"), 4)

    private suspend fun TitleUpdateAttemptStore.page(query: TextUpdateWorkQuery): TextUpdateWorkPage =
        requireNotNull(textJournal.page(query) { it == query.scope })

    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "text-work-page-fixture-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }

    /** Deliberately undecodable opaque columns prove discovery never interprets work contents. */
    private fun seed(store: TitleUpdateAttemptStore, event: String, target: CalendarScope = scope,
        state: TextUpdateAttemptState = TextUpdateAttemptState.PREPARED, completion: String? = null) {
        store.writableDatabase.insertOrThrow("text_update_attempts", null, ContentValues().apply {
            put("account", target.account.value); put("calendar", target.calendar.value); put("generation", target.generation)
            put("mutation", "attempt-$event"); put("event", event)
            put("state", state.name); put("capsule", byteArrayOf(0, 1))
            put("result", byteArrayOf(2, 3))
            if (completion != null) put("completion", completion.toByteArray())
        })
    }

    @Test fun preparedAndUnknownWorkArePagedWithoutOpeningOpaquePayloads() = fixture { _, _, store ->
        seed(store, "a"); seed(store, "b", state = TextUpdateAttemptState.OUTCOME_UNKNOWN); seed(store, "c")
        val first = store.page(TextUpdateWorkQuery(scope, 2))
        assertEquals(listOf("a", "b"), first.entries.map { it.eventId.value })
        assertEquals(listOf(TextUpdateAttemptState.PREPARED, TextUpdateAttemptState.OUTCOME_UNKNOWN), first.entries.map { it.state })
        assertEquals(listOf("attempt-a", "attempt-b"), first.entries.map { it.id.value })
        val next = requireNotNull(first.next)
        assertEquals(EventId("b"), next.afterEvent)
        val second = store.page(TextUpdateWorkQuery(scope, 2, next))
        assertEquals(listOf("c"), second.entries.map { it.eventId.value }); assertNull(second.next)
    }

    @Test fun cursorReopensWithExactAccountCalendarGenerationScope() = fixture { context, name, store ->
        seed(store, "a"); seed(store, "b")
        seed(store, "foreign-account", target = scope.copy(account = AccountId("other-account")))
        seed(store, "foreign-calendar", target = scope.copy(calendar = CalendarId("other-calendar")))
        seed(store, "foreign-generation", target = scope.copy(generation = 5))
        val cursor = requireNotNull(store.page(TextUpdateWorkQuery(scope, 1)).next)
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            val page = reopened.page(TextUpdateWorkQuery(scope, 1, cursor))
            assertEquals(listOf("b"), page.entries.map { it.eventId.value }); assertNull(page.next)
            assertEquals(6, reopened.readableDatabase.version)
        }
    }

    @Test fun closedRowsAreExcludedWithoutQualifyingTheirProof() = fixture { _, _, store ->
        seed(store, "closed", state = TextUpdateAttemptState.OUTCOME_UNKNOWN, completion = "corrupt-but-present")
        seed(store, "open")
        val page = store.page(TextUpdateWorkQuery(scope))
        assertEquals(listOf("open"), page.entries.map { it.eventId.value }); assertNull(page.next)
        // Listing has no authority to treat that malformed completion as a valid release.
        rejected { store.textJournal.readCompletion(scope, MutationId("attempt-closed")) { it == scope } }
    }

    @Test fun defaultMaximumAndExactEndAreBoundedByLookahead() = fixture { _, _, store ->
        for (index in 0..50) seed(store, "event-${index.toString().padStart(3, '0')}")
        val first = store.page(TextUpdateWorkQuery(scope))
        assertEquals(50, first.entries.size)
        val last = store.page(TextUpdateWorkQuery(scope, 1, requireNotNull(first.next)))
        assertEquals(listOf("event-050"), last.entries.map { it.eventId.value }); assertNull(last.next)
        val empty = store.page(TextUpdateWorkQuery(scope, cursor = TextUpdateWorkCursor(scope, EventId("z"))))
        assertTrue(empty.entries.isEmpty()); assertNull(empty.next)
    }

    @Test fun sqliteBinaryUnicodeOrderSurvivesContinuation() = fixture { _, _, store ->
        seed(store, "\uD800\uDC00"); seed(store, "\uE000")
        val first = store.page(TextUpdateWorkQuery(scope, 1))
        assertEquals("\uE000", first.entries.single().eventId.value)
        val second = store.page(TextUpdateWorkQuery(scope, 1, requireNotNull(first.next)))
        assertEquals("\uD800\uDC00", second.entries.single().eventId.value); assertNull(second.next)
    }

    @Test fun insertionBehindCursorRequiresRestartInsteadOfClaimingSnapshotCompleteness() = fixture { context, name, store ->
        seed(store, "b"); seed(store, "d")
        val first = store.page(TextUpdateWorkQuery(scope, 1))
        TitleUpdateAttemptStore(context, name).use { secondConnection -> seed(secondConnection, "a") }
        val next = store.page(TextUpdateWorkQuery(scope, 1, requireNotNull(first.next)))
        assertEquals(listOf("d"), next.entries.map { it.eventId.value }); assertNull(next.next)
        assertEquals(listOf("a", "b", "d"), store.page(TextUpdateWorkQuery(scope)).entries.map { it.eventId.value })
    }

    @Test fun corruptionInLookaheadCannotProduceApparentlyValidFirstPage() = fixture { _, _, store ->
        seed(store, "a"); seed(store, "b")
        store.writableDatabase.execSQL("UPDATE text_update_attempts SET mutation=? WHERE event='b'", arrayOf("é".repeat(2049)))
        rejected { store.page(TextUpdateWorkQuery(scope, 1)) }
    }

    @Test fun metadataBlobControlsAndMalformedUtf8AreRejectedWithoutContentsInErrors() = fixture { _, _, store ->
        seed(store, "a")
        for (expression in listOf("X'6162'", "'line' || char(10)", "CAST(X'80' AS TEXT)", "''",
            "zeroblob(3145728)", "CAST(zeroblob(3145728) AS TEXT)")) {
            store.writableDatabase.execSQL("UPDATE text_update_attempts SET mutation=$expression")
            rejected { store.page(TextUpdateWorkQuery(scope)) }
        }
    }

    @Test fun invalidStateOrOversizedEventFailsClosed() = fixture { _, _, store ->
        seed(store, "a")
        store.writableDatabase.execSQL("PRAGMA ignore_check_constraints=ON")
        store.writableDatabase.execSQL("UPDATE text_update_attempts SET state='INVALID'")
        rejected { store.page(TextUpdateWorkQuery(scope)) }
        store.writableDatabase.execSQL("UPDATE text_update_attempts SET state='PREPARED',event=?", arrayOf("x".repeat(4097)))
        rejected { store.page(TextUpdateWorkQuery(scope)) }
    }

    @Test fun opaqueMultiMegabyteBlobsNeverEnterTheMetadataCursorWindow() = fixture { _, _, store ->
        seed(store, "a"); seed(store, "b")
        store.writableDatabase.execSQL("UPDATE text_update_attempts SET capsule=zeroblob(4194305),result=zeroblob(4194305)")
        val page = store.page(TextUpdateWorkQuery(scope, 1))
        assertEquals(listOf("a"), page.entries.map { it.eventId.value })
        assertEquals(EventId("a"), page.next!!.afterEvent)
        rejected { store.textJournal.read(scope, MutationId("attempt-a")) { true } }
    }

    @Test fun deniedAndRevokedReadsReturnNullRatherThanAnApparentlyEmptyPage() = fixture { _, _, store ->
        seed(store, "a")
        var checks = 0
        assertNull(store.textJournal.page(TextUpdateWorkQuery(scope)) { actual ->
            assertEquals(scope, actual); checks++; false
        })
        assertEquals(1, checks)
        checks = 0
        assertNull(store.textJournal.page(TextUpdateWorkQuery(scope)) { ++checks < 2 })
        assertEquals(2, checks)
        assertEquals(listOf("a"), store.page(TextUpdateWorkQuery(scope)).entries.map { it.eventId.value })
    }

    @Test fun admissionCancellationCannotBecomeDeniedOrOrdinaryFailure() = fixture { _, _, store ->
        seed(store, "a")
        var cancellations = 0; var ordinaryFailures = 0; var returns = 0
        supervisorScope {
            val child = async {
                val job = currentCoroutineContext().job
                try {
                    store.textJournal.page(TextUpdateWorkQuery(scope)) {
                        job.cancel(); throw java.io.IOException("synthetic-sensitive")
                    }
                    returns++
                } catch (_: CancellationException) { cancellations++ }
                catch (_: Exception) { ordinaryFailures++ }
            }
            try { child.await() } catch (_: CancellationException) { }
        }
        assertEquals(1, cancellations); assertEquals(0, ordinaryFailures); assertEquals(0, returns)
        assertEquals(1, store.page(TextUpdateWorkQuery(scope)).entries.size)
    }

    @Test fun corruptionBeyondLookaheadIsDeferredToItsOwnBoundedPage() = fixture { _, _, store ->
        seed(store, "a"); seed(store, "b"); seed(store, "c")
        store.writableDatabase.execSQL("UPDATE text_update_attempts SET mutation=CAST(X'80' AS TEXT) WHERE event='c'")
        val first = store.page(TextUpdateWorkQuery(scope, 1))
        assertEquals(listOf("a"), first.entries.map { it.eventId.value })
        rejected { store.page(TextUpdateWorkQuery(scope, 1, requireNotNull(first.next))) }
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected invalid metadata rejection") } catch (failure: IllegalStateException) {
            assertNull(failure.cause)
            assertFalse(failure.message.orEmpty().contains("work-page"))
            assertFalse(failure.message.orEmpty().contains("undecodable"))
        }
    }
}
