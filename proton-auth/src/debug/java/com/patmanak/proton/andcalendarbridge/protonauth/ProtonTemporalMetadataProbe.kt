// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext

/** Closed observations relative to an explicitly enrolled synthetic time, never raw API data. */
enum class TemporalApiZoneShape { UTC, Z, EXPECTED, EMPTY, OTHER }
data class TemporalMetadataShape(val fullDay: Long, val startDeltaSeconds: Long?, val endDeltaSeconds: Long?,
    val startZone: TemporalApiZoneShape, val endZone: TemporalApiZoneShape) {
    override fun toString() = "TemporalMetadataShape(REDACTED)"
}

/** Only bounded metadata classes/booleans leave the authenticated inspection. */
data class SeriesMetadataShape(val temporal: TemporalMetadataShape, val timed: Boolean,
    val rawRuleString: Boolean, val ruleMatchesSigned: Boolean, val recurrenceIdNull: Boolean,
    val exdatesEmpty: Boolean, val apiZoneSpellingMatches: Boolean, val completeFamilyQualified: Boolean,
    val wireProfileQualified: Boolean) {
    override fun toString() = "SeriesMetadataShape(REDACTED)"
}

internal fun temporalMetadataShape(row: JsonObject, expected: EventTime): TemporalMetadataShape {
    val bounds = when (expected) {
        is EventTime.AllDay -> expected.start.atStartOfDay(ZoneOffset.UTC).toEpochSecond() to
            expected.endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        is EventTime.Timed -> expected.start.epochSecond to expected.end.epochSecond
    }
    val zone = (expected as? EventTime.Timed)?.zone?.id ?: "UTC"
    fun shape(name: String): TemporalApiZoneShape = when (val value = row.string(name)) {
        "UTC" -> TemporalApiZoneShape.UTC
        "Z" -> TemporalApiZoneShape.Z
        zone -> TemporalApiZoneShape.EXPECTED
        "" -> TemporalApiZoneShape.EMPTY
        else -> TemporalApiZoneShape.OTHER
    }
    fun delta(name: String, wanted: Long): Long? = Math.subtractExact(row.number(name), wanted).takeIf { it in -86_400..86_400 }
    return TemporalMetadataShape(row.number("FullDay").also { require(it in 0..1) },
        delta("StartTime", bounds.first), delta("EndTime", bounds.second), shape("StartTimezone"), shape("EndTimezone"))
}

/** Debug-only authenticated GET. Full identity/session checks and the shared logout mutex apply.
 * Reflection drift fails closed; no readback claim, dispatch or raw payload leaves this helper.
 */
object ProtonTemporalMetadataProbe {
    /** Cached Core entitlement only; no plan/account mutation or fallback network read. */
    suspend fun cachedPaidMail(context: Context, scope: CalendarScope, identityHash: String,
        admit: () -> Boolean): String = withContext(Dispatchers.IO) {
        try {
            require(identityHash.matches(Regex("[a-f0-9]{64}")))
            val port = ProtonAuthentication.personalPreparations(context)
            (field(port, "lock") as Mutex).withLock {
                val graph = field(port, "graph") as ProtonAuthenticationGraph
                val sessions = field(graph.personals, "sessions") as CatalogSessionSource
                val session = requireNotNull(sessions.current()).also { require(it.userId.id == scope.account.value) }
                suspend fun checkpoint() {
                    currentCoroutineContext().ensureActive()
                    require(admit() && field(port, "authenticated") == true && sessions.current() == session)
                    require(graph.matchesIdentityHash(scope.account, identityHash))
                    require(admit() && field(port, "authenticated") == true && sessions.current() == session)
                }
                checkpoint()
                val file = context.getDatabasePath("proton-auth-v1.db")
                require(file.isFile)
                val subscribed = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT subscribed FROM UserEntity WHERE userId=? LIMIT 2", arrayOf(scope.account.value)).use { row ->
                        require(row.count == 1 && row.moveToFirst() && row.getType(0) == android.database.Cursor.FIELD_TYPE_INTEGER)
                        row.getLong(0).also { require(it >= 0) }
                    }
                }
                checkpoint()
                if (subscribed and 1L == 1L) "CACHED_PAID_MAIL" else "CACHED_NO_PAID_MAIL"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { "UNAVAILABLE" }
    }

    /** Inspect the exact retained CREATE UID without accepting, claiming or resending it. */
    suspend fun retainedSeries(context: Context, record: CreateAttemptRecord, draft: CalendarCreateDraft,
        identityHash: String, admit: () -> Boolean): Pair<String, RemoteChange.Upsert?> = withContext(Dispatchers.IO) {
        try {
            require(record.state == CreateAttemptState.OUTCOME_UNKNOWN && record.format == SERIES_CREATE_SUBMISSION_FORMAT)
            val frozen = draft.frozen()
            require(frozen.scope == record.scope && frozen.correlation == record.id)
            val port = ProtonAuthentication.personalPreparations(context)
            val lock = field(port, "lock") as Mutex
            lock.withLock {
                val graph = field(port, "graph") as ProtonAuthenticationGraph
                val adapter = graph.personals as ProtonPersonalPreparationAdapter
                val sessions = field(adapter, "sessions") as CatalogSessionSource
                val session = requireNotNull(sessions.current()).also { require(it.userId.id == record.scope.account.value) }
                suspend fun check() {
                    currentCoroutineContext().ensureActive()
                    require(admit() && field(port, "authenticated") == true && sessions.current() == session)
                    require(graph.matchesIdentityHash(record.scope.account, identityHash))
                    require(admit() && field(port, "authenticated") == true && sessions.current() == session)
                }
                check()
                val capsule = ProtonCreateSubmissionCodec.open(record,
                    ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
                require(capsule.draft == frozen)
                val remote = field(requireNotNull(graph.submissions), "remote") as CreateReadbackRemote
                val rows = remote.byUid(session, record.scope.calendar.value, capsule.uid, 0)
                check()
                if (rows.isEmpty()) return@withLock "UID_ABSENT" to null
                if (rows.size != 1) return@withLock "UID_AMBIGUOUS" to null
                val found = rows.single()
                require(found.string("CalendarID") == record.scope.calendar.value && found.string("UID") == capsule.uid)
                val row = remote.event(session, record.scope.calendar.value, found.string("ID"))
                check()
                require(row.string("ID") == found.string("ID") && row.string("CalendarID") == record.scope.calendar.value &&
                    row.string("UID") == capsule.uid)
                require(row.array("SharedEvents").map { (it as JsonObject).number("Type") }.sorted() == listOf(2L, 3L))
                val events = field(adapter, "events") as EventImportRemote
                val bootstrap = events.bootstrap(session, record.scope.calendar.value)
                check()
                val addresses = (field(adapter, "addresses") as LocalEventAddresses).get(session.userId)
                    .map { it.copy(keys = it.keys.toList()) }
                check()
                val decoder = ProtonEventDecryptor(AndroidCryptoContext()) { user ->
                    check(); require(user == session.userId); addresses
                }
                val decoded = decoder.decodePage(session.userId, record.scope.calendar.value, bootstrap, listOf(row), capsule.uid)
                check()
                if (decoded.deferred.isNotEmpty()) return@withLock "SIGNATURE_OR_CONTENT_UNRESOLVED" to null
                val master = decoded.changes.singleOrNull() ?: return@withLock "DECODE_UNRESOLVED" to null
                if (master.content != frozen.canonicalContent(capsule.uid)) return@withLock "CONTENT_DRIFT" to null
                "SIGNED_CONTENT_MATCH" to master
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { "READ_${failure.importFailure().reason.name}" to null }
    }

    /** GET-only inspection of an already known synthetic master. The optional lease is
     * checked around every suspension, including the complete-family verifier path.
     * Fresh signed content supplies the comparison time; cached content grants no write.
     */
    suspend fun inspectSeries(context: Context, scope: CalendarScope, expectedBase: RemoteChange.Upsert,
        expectedIdentityHash: String, admit: () -> Boolean = { true }): SeriesMetadataShape? = withContext(Dispatchers.IO) {
        try {
            if (!expectedIdentityHash.matches(Regex("[a-f0-9]{64}"))) return@withContext null
            val expected = expectedBase.copy(content = SeriesEditProfile.freezeMaster(expectedBase.content))
            if (!syntheticSeries(expected.content)) return@withContext null
            val port = ProtonAuthentication.personalPreparations(context)
            val lock = field(port, "lock") as Mutex
            lock.withLock {
                if (field(port, "authenticated") != true || !admit()) return@withLock null
                val graph = field(port, "graph") as ProtonAuthenticationGraph
                val adapter = graph.personals as? ProtonPersonalPreparationAdapter ?: return@withLock null
                val sessions = field(adapter, "sessions") as CatalogSessionSource
                val session = sessions.current()?.takeIf { it.userId.id == scope.account.value } ?: return@withLock null
                suspend fun checkpoint() {
                    currentCoroutineContext().ensureActive()
                    check(field(port, "authenticated") == true && admit())
                    check(sessions.current() == session)
                    check(field(port, "authenticated") == true && admit())
                    check(graph.matchesIdentityHash(scope.account, expectedIdentityHash))
                    check(field(port, "authenticated") == true && admit())
                    check(sessions.current() == session)
                    check(field(port, "authenticated") == true && admit())
                    currentCoroutineContext().ensureActive()
                }
                checkpoint()
                val originals = field(adapter, "originals") as LocalTitleEventRemote
                val events = field(adapter, "events") as EventImportRemote
                val addresses = field(adapter, "addresses") as LocalEventAddresses
                val submission = graph.submissions as? ProtonCreateSubmissionAdapter ?: return@withLock null
                val remote = field(submission, "remote") as CreateReadbackRemote
                val uid = expected.content.recurrence!!.uid
                val row = originals.event(session, scope, expected.eventId)
                checkpoint()
                if (row.string("ID") != expected.eventId.value || row.string("CalendarID") != scope.calendar.value ||
                    row.string("UID") != uid) return@withLock null
                val bootstrap = events.bootstrap(session, scope.calendar.value)
                checkpoint()
                if (bootstrap.obj("CalendarSettings").string("CalendarID") != scope.calendar.value) return@withLock null
                val local = addresses.get(session.userId).map { it.copy(keys = it.keys.toList()) }
                checkpoint()
                val decoder = ProtonEventDecryptor(AndroidCryptoContext()) { user ->
                    checkpoint()
                    check(user == session.userId)
                    local
                }
                suspend fun decode(calendar: String, bootstrapRow: JsonObject, eventRow: JsonObject,
                    expectedUid: String): RemoteChange.Upsert? {
                    checkpoint()
                    if (calendar != scope.calendar.value || expectedUid != uid ||
                        eventRow.string("ID") != expected.eventId.value || eventRow.string("CalendarID") != calendar ||
                        eventRow.string("UID") != uid) return null
                    val result = decoder.decodePage(session.userId, calendar, bootstrapRow, listOf(eventRow), expectedUid)
                    checkpoint()
                    if (result.deferred.isNotEmpty()) return null
                    return result.changes.singleOrNull()?.takeIf { it.eventId == expected.eventId }?.let {
                        it.copy(content = SeriesEditProfile.freezeMaster(it.content))
                    }
                }
                val fresh = decode(scope.calendar.value, bootstrap, row, uid) ?: return@withLock null
                if (!syntheticSeries(fresh.content)) return@withLock null
                val signedRule = CreateSeriesRule.parse(requireNotNull(fresh.content.recurrence?.rule))
                val rawRule = row["RRule"] as? JsonPrimitive
                val rawRuleString = rawRule?.isString == true
                val ruleMatches = rawRuleString && try { CreateSeriesRule.parse(rawRule!!.content) == signedRule }
                    catch (_: IllegalArgumentException) { false }
                val recurrenceIdNull = row["RecurrenceID"] == JsonNull
                val exdatesEmpty = (row["Exdates"] as? JsonArray)?.isEmpty() == true
                val productionVerifier = seriesFamilyVerifier(decoder)
                val readback = ProtonCreatedEventReadback(sessions, remote, events,
                    CreateEventVerifier { _, _, _, _, _, _ -> null },
                    SeriesFamilyEventVerifier { observedSession, calendar, bootstrapRow, eventRow, expectedUid ->
                        checkpoint()
                        check(observedSession == session)
                        productionVerifier.verify(observedSession, calendar, bootstrapRow, eventRow, expectedUid).also { checkpoint() }
                    })
                val qualified = readback.qualifySeries(session, scope, fresh, ::checkpoint)
                checkpoint()
                val wireProfile = try { ProtonSeriesEventUpdate.qualify(row, fresh.content); true }
                    catch (_: Exception) { false }
                val time = fresh.content.time
                val expectedZone = if (time is EventTime.Timed && time.zone != ZoneOffset.UTC) time.zone.id else "UTC"
                val zonesMatch = row.string("StartTimezone") == expectedZone && row.string("EndTimezone") == expectedZone
                SeriesMetadataShape(temporalMetadataShape(row, fresh.content.time), fresh.content.time is EventTime.Timed,
                    rawRuleString, ruleMatches, recurrenceIdNull, exdatesEmpty, zonesMatch, qualified == fresh, wireProfile)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
    }

    private fun syntheticSeries(content: EventContent) = content.title.startsWith("BridgeQA-") || content.title.startsWith("Calendeer QA")

    suspend fun inspect(context: Context, scope: CalendarScope, event: EventId, expected: EventTime,
        expectedIdentityHash: String): TemporalMetadataShape? = withContext(Dispatchers.IO) {
        try {
            if (!expectedIdentityHash.matches(Regex("[a-f0-9]{64}"))) return@withContext null
            val port = ProtonAuthentication.localEditPreparations(context)
            val lock = field(port, "lock") as Mutex
            lock.withLock {
                if (field(port, "authenticated") != true) return@withLock null
                val graph = field(port, "graph") as ProtonAuthenticationGraph
                val adapter = graph.localEdits as? ProtonLocalTitlePreparationAdapter ?: return@withLock null
                val sessions = field(adapter, "sessions") as CatalogSessionSource
                val session = sessions.current()?.takeIf { it.userId.id == scope.account.value } ?: return@withLock null
                if (!graph.matchesIdentityHash(scope.account, expectedIdentityHash) || sessions.current() != session) return@withLock null
                val originals = field(adapter, "originals") as LocalTitleEventRemote
                val row = originals.event(session, scope, event)
                if (sessions.current() != session || row.string("ID") != event.value ||
                    row.string("CalendarID") != scope.calendar.value) return@withLock null
                temporalMetadataShape(row, expected)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
    }
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
}
