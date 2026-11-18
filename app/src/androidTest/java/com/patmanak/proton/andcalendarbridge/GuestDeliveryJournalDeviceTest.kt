package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuestDeliveryJournalDeviceTest {
    @Test fun lostResponseOrCancellationCannotRepeatMailOrCalendar() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-guest-delivery-" + UUID.randomUUID() + ".db"
        val scope = CalendarScope(AccountId("fixture"), CalendarId("calendar"), 1)
        try {
            TitleUpdateAttemptStore(context, name).use { store ->
                val journal = store.guestJournal
                for (case in listOf("lost-mail", "cancel-mail", "lost-calendar", "confirmed")) {
                    val record = GuestJournalRecord.prepared(scope, MutationId(case), MutationId("local-" + case),
                        EventId("event-" + case), Revision("source"), "sealed-ctx", "sealed-mail", "sealed-cal")
                    assertTrue(journal.remember(record) { true })
                    var mail = 0
                    var calendar = 0
                    var inspections = 0
                    val port = object : GuestDeliveryTransport {
                        override suspend fun prepareMail(row: GuestJournalRecord) = GuestMailTransmission { active ->
                            assertTrue(active()); mail++
                            assertEquals(GuestDeliveryPhase.MAIL_UNKNOWN, journal.read(scope, row.owner.id) { true }?.progress?.phase)
                            if (case == "cancel-mail") throw kotlinx.coroutines.CancellationException("fixture")
                            if (case == "lost-mail") null else 4321L
                        }
                        override suspend fun prepareCalendar(row: GuestJournalRecord) = GuestCalendarTransmission { active ->
                            assertTrue(active()); calendar++
                            assertEquals(GuestDeliveryPhase.CALENDAR_UNKNOWN, journal.read(scope, row.owner.id) { true }?.progress?.phase)
                            if (case == "lost-calendar") throw java.io.IOException("fixture")
                        }
                        override suspend fun inspectCalendar(row: GuestJournalRecord): String? {
                            inspections++
                            return if (case == "confirmed") "sealed-readback" else null
                        }
                    }
                    val worker = GuestDeliveryWorker(journal, port)
                    repeat(4) {
                        try { worker.step(scope, record.owner.id, { true }, { true }) }
                        catch (_: kotlinx.coroutines.CancellationException) { assertEquals("cancel-mail", case) }
                    }
                    assertEquals(1, mail)
                    if (case.endsWith("mail")) { assertEquals(0, calendar); assertEquals(0, inspections) }
                    else { assertEquals(1, calendar); assertTrue(inspections > 0) }
                    val final = journal.read(scope, record.owner.id) { true }!!.progress.phase
                    assertEquals(when (case) {
                        "confirmed" -> GuestDeliveryPhase.BOTH_PROVEN
                        "lost-calendar" -> GuestDeliveryPhase.CALENDAR_UNKNOWN
                        else -> GuestDeliveryPhase.MAIL_UNKNOWN
                    }, final)
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migratesVersionElevenWithoutRewritingOpaquePersonalHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-guest-migration-" + UUID.randomUUID() + ".db"
        val scope = CalendarScope(AccountId("a"), CalendarId("c"), 1)
        val day = java.time.LocalDate.of(2030, 1, 1)
        val content = EventContent("Before", EventTime.AllDay(day, day.plusDays(1)), details = EventDetails())
        val local = LocalEditIntent(MutationId("local"), scope, AdapterFormat("observation", 1),
            RemoteChange.Upsert(EventId("e"), Revision("r"), content), content.copy(title = "After"))
        val record = PersonalUpdateAttemptRecord(MutationId("m"),
            PersonalEditPreparation(local, java.time.Instant.parse("2026-01-01T00:00:00Z"), AdapterFormat("prep", 1), "sealed-context"),
            AdapterFormat("wire", 1), "sealed-request")
        val unknown = record.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        try {
            lateinit var before: ByteArray
            TitleUpdateAttemptStore(context, name).use { store ->
                val db = store.writableDatabase
                assertTrue(store.personalJournal.remember(record) { true })
                assertTrue(store.personalJournal.claim(record) { true })
                restorePreGuestUpdateTable(db)
                before = db.rawQuery("SELECT capsule FROM personal_update_attempts", null).use { it.moveToFirst(); it.getBlob(0) }
                db.version = 11
            }
            TitleUpdateAttemptStore(context, name).use { store ->
                val db = store.readableDatabase
                assertEquals(12, db.version)
                db.rawQuery("SELECT guest,state,capsule,result,completion FROM personal_update_attempts", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0)); assertEquals("OUTCOME_UNKNOWN", it.getString(1))
                    assertArrayEquals(before, it.getBlob(2)); assertTrue(it.isNull(3)); assertTrue(it.isNull(4))
                }
                assertEquals(unknown, store.personalJournal.read(scope, record.id) { true })
                assertFalse(store.personalJournal.claim(record) { true })
                assertNull(store.guestJournal.read(scope, record.id) { true })
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun freezesBothRequestsAndSurvivesReopenWithoutRepeatingEitherClaim() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-guest-journal-" + UUID.randomUUID() + ".db"
        val scope = CalendarScope(AccountId("synthetic"), CalendarId("calendar"), 1)
        val prepared = GuestJournalRecord.prepared(scope, MutationId("transaction"), MutationId("local"), EventId("event"),
            Revision("source"), "sealed-context", "sealed-mail", "sealed-calendar")
        try {
            TitleUpdateAttemptStore(context, name).use { store ->
                assertTrue(store.guestJournal.remember(prepared) { true })
                assertFalse(store.guestJournal.claimMail(prepared) { false })
                assertTrue(store.guestJournal.claimMail(prepared) { true })
                assertFalse(store.guestJournal.claimMail(prepared) { true })
            }
            TitleUpdateAttemptStore(context, name).use { store ->
                val journal = store.guestJournal
                val unknown = requireNotNull(journal.read(scope, prepared.owner.id) { true })
                assertEquals(listOf(unknown.owner.id), requireNotNull(journal.page(PersonalUpdateWorkQuery(scope)) { true }).entries.map { it.id })
                assertTrue(requireNotNull(store.personalJournal.page(PersonalUpdateWorkQuery(scope)) { true }).entries.isEmpty())
                assertEquals(GuestDeliveryPhase.MAIL_UNKNOWN, unknown.progress.phase)
                assertFalse(journal.claimCalendar(unknown) { true })
                assertTrue(journal.mailAccepted(unknown, 1234) { true })
                val accepted = requireNotNull(journal.read(scope, prepared.owner.id) { true })
                assertEquals("sealed-mail", accepted.protectedMail)
                assertEquals("sealed-calendar", accepted.protectedCalendar)
                assertFalse(journal.claimMail(accepted) { true })
                assertTrue(journal.claimCalendar(accepted) { true })
                assertFalse(journal.claimCalendar(accepted) { true })
                val calendar = requireNotNull(journal.read(scope, prepared.owner.id) { true })
                assertFalse(journal.claimCalendar(calendar) { true })
                assertTrue(journal.calendarVerified(calendar, "sealed-readback") { true })
                val proven = requireNotNull(journal.read(scope, prepared.owner.id) { true })
                assertEquals("sealed-readback", proven.protectedProof)
                assertEquals(GuestDeliveryPhase.BOTH_PROVEN, proven.progress.phase)
                assertNull(store.personalJournal.read(scope, prepared.owner.id) { true })
                val conflict = GuestJournalRecord.prepared(scope.copy(generation = 2), MutationId("other"),
                    MutationId("new-local"), EventId("event"), Revision("new"), "ctx", "mail", "cal")
                assertFalse(journal.remember(conflict) { true })
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun corruptCompetingHistoryAfterReservationBlocksBothOutgoingClaims() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-guest-ownership-" + UUID.randomUUID() + ".db"
        val scope = CalendarScope(AccountId("a"), CalendarId("c"), 1)
        val record = GuestJournalRecord.prepared(scope, MutationId("m"), MutationId("local"), EventId("e"),
            Revision("source"), "ctx", "mail", "cal")
        try {
            TitleUpdateAttemptStore(context, name).use { store ->
                val journal = store.guestJournal
                val db = store.writableDatabase
                fun corrupt() = db.execSQL("""INSERT INTO personal_update_attempts
                    (account,calendar,generation,mutation,event,state,capsule,completion)
                    VALUES ('a','c',0,'older','e','OUTCOME_UNKNOWN',X'00',X'00')""")
                assertTrue(journal.remember(record) { true })
                corrupt()
                try { journal.claimMail(record) { true }; fail("Expected corrupt ownership to block Mail") }
                catch (_: IllegalStateException) { }
                assertEquals(GuestDeliveryPhase.MAIL_READY, journal.read(scope, record.owner.id) { true }?.progress?.phase)
                db.execSQL("DELETE FROM personal_update_attempts WHERE mutation='older'")
                assertTrue(journal.claimMail(record) { true })
                val mail = requireNotNull(journal.read(scope, record.owner.id) { true })
                assertTrue(journal.mailAccepted(mail, 1234) { true })
                val ready = requireNotNull(journal.read(scope, record.owner.id) { true })
                corrupt()
                try { journal.claimCalendar(ready) { true }; fail("Expected corrupt ownership to block Calendar") }
                catch (_: IllegalStateException) { }
                assertEquals(GuestDeliveryPhase.CALENDAR_READY, journal.read(scope, record.owner.id) { true }?.progress?.phase)
            }
        } finally { context.deleteDatabase(name) }
    }
}

/** Rebuild the historical table for upgrade fixtures on SQLite versions without DROP COLUMN. */
internal fun restorePreGuestUpdateTable(db: SQLiteDatabase) {
    db.execSQL("ALTER TABLE personal_update_attempts RENAME TO fixture_updates")
    db.execSQL("DROP INDEX personal_update_unresolved_event")
    db.execSQL("""CREATE TABLE personal_update_attempts (
        account TEXT NOT NULL, calendar TEXT NOT NULL,
        generation INTEGER NOT NULL CHECK(generation >= 0),
        mutation TEXT NOT NULL, event TEXT NOT NULL,
        state TEXT NOT NULL CHECK(state IN ('PREPARED','OUTCOME_UNKNOWN')),
        capsule BLOB NOT NULL, result BLOB, completion BLOB,
        PRIMARY KEY(account,calendar,generation,mutation), UNIQUE(account,calendar,mutation))""")
    db.execSQL("INSERT INTO personal_update_attempts SELECT account,calendar,generation,mutation,event,state,capsule,result,completion FROM fixture_updates")
    db.execSQL("DROP TABLE fixture_updates")
    db.execSQL("CREATE UNIQUE INDEX personal_update_unresolved_event ON personal_update_attempts(account,calendar,event) WHERE completion IS NULL")
}
