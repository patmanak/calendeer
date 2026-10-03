// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Container checks only. Construction here does not establish signature verification. */
class ProtonSharedTitleBasisTest {
    private fun text(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n" +
        "UID:fixture@calendeer.invalid\r\nDTSTAMP:20261003T100000Z\r\n$body" +
        "END:VEVENT\r\nEND:VCALENDAR\r\n"
    private val plaintext = ProtonEventTextParts(text("DTSTART:20261004T100000Z\r\n"),
        text("X-OPAQUE:unchanged\r\nSUMMARY:Original\r\nLOCATION:Unchanged\r\n"))
    private val row = buildJsonObject {
        put("ID", "fixture-id")
        putJsonObject("Unknown") { putJsonArray("Data") { add("opaque") } }
        putJsonArray("SharedEvents") { add(buildJsonObject { put("Signature", "original-signature") }) }
    }
    private fun basis() = ProtonSharedTitleBasis(CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 1),
        EventId("fixture-id"), Revision("fixture-revision"), "fixture@calendeer.invalid", "Original", row.toString(), plaintext)

    @Test fun patchKeepsTheOriginalSignedRowSeparateFromChangedPlaintext() {
        val basis = basis()
        val draft = basis.patchTitle("New title")
        assertEquals(row, basis.originalRow)
        assertEquals(row, draft.originalRow)
        assertSame(plaintext, basis.originalPlaintext)
        assertEquals(plaintext.clearShared, draft.patchedPlaintext.clearShared)
        assertEquals(plaintext.encryptedShared.replace("SUMMARY:Original\r\n", "SUMMARY:New title\r\n"),
            draft.patchedPlaintext.encryptedShared)
        assertEquals("original-signature", (draft.originalRow.array("SharedEvents").single() as JsonObject).string("Signature"))
    }

    @Test fun everyOriginalRowAccessReturnsAnIndependentDeepSnapshot() {
        val basis = basis()
        val draft = basis.patchTitle("Original")
        assertNotSame(basis.originalRow, basis.originalRow)
        assertNotSame(basis.originalRow.obj("Unknown"), basis.originalRow.obj("Unknown"))
        assertNotSame(draft.originalRow.obj("Unknown").array("Data"), draft.originalRow.obj("Unknown").array("Data"))
        assertSame(plaintext, draft.patchedPlaintext)
    }

    @Test fun containerDiagnosticsExposeNoBindingsPlaintextOrSignatures() {
        val basis = basis()
        assertEquals("ProtonSharedTitleBasis(redacted)", basis.toString())
        assertEquals("ProtonSharedTitleDraft(redacted)", basis.patchTitle("Private new title").toString())
        try { basis.patchTitle("\u0000private input"); fail("Control must be refused") }
        catch (failure: ProtonTitlePatchException) {
            assertEquals("Shared title patch unavailable: UNSUPPORTED", failure.message)
            assertNull(failure.cause)
        }
    }
}
