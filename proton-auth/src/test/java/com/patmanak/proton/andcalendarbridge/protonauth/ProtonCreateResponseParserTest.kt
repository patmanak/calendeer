// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import org.junit.Assert.*
import org.junit.Test

class ProtonCreateResponseParserTest {
    private val success = """{"Code":1001,"Responses":[{"Index":0,"Response":{"Code":1000,
      "Event":{"ID":"synthetic-event","CalendarID":"synthetic-calendar","UID":"synthetic-uid","ModifyTime":10,"Unknown":"preserved"}}}]}"""
    private fun parse(value: String) = ProtonCreateResponseParser.parse(value, "synthetic-calendar", "synthetic-uid")

    @Test fun successRetainsCandidateEvidenceWithoutAcknowledgingOrDroppingUnknownFields() {
        val candidate = parse(success) as ProtonCreateResponse.Candidate
        assertEquals("preserved", candidate.event.string("Unknown"))
        assertFalse(candidate.toString().contains("synthetic"))
        assertTrue(parse(success.replace("1001", "1000")) is ProtonCreateResponse.Candidate)
    }
    @Test fun missingForeignDuplicateTruncatedOrMalformedResponsesStayUnknown() {
        for (value in listOf("", "[]", "{}", success.dropLast(4), success.replace("synthetic-calendar", "foreign"),
            success.replace("synthetic-uid", "foreign"), success.replace("\"Index\":0", "\"Index\":1"),
            success.replace("\"Index\":0", "\"Index\":\"0\""), success.replace("\"Code\":1000", "\"Code\":\"1000\""),
            success.replace("\"ModifyTime\":10", "\"ModifyTime\":-1"), success.replace("\"ID\":\"synthetic-event\"", "\"ID\":\"\""),
            """{"Code":1001,"Responses":[]}""", """{"Code":1001,"Responses":[{"Index":0,"Response":{"Code":1000}}]}""",
            """{"Code":1001,"Responses":[{"Index":0},{"Index":0}]}""", "x".repeat(1_048_577))) {
            assertEquals(ProtonCreateResponse.Unknown, parse(value))
        }
    }
    @Test fun reportedErrorsAreNeverProofThatResendingIsSafeAndRawMessagesNeverEscape() {
        for (code in listOf(2000, 2011, 2061, 2500, 2501, 9999)) {
            val failure = parse("""{"Code":1001,"Responses":[{"Index":0,"Response":{"Code":$code,"Error":"private server detail"}}]}""")
                as ProtonCreateResponse.ReportedFailure
            assertEquals(code.toLong(), failure.code)
            assertFalse(failure.toString().contains("private"))
        }
        assertEquals(ProtonCreateResponse.Unknown, parse(success.replace("\"Code\":1000", "\"Code\":2500")))
    }
    @Test fun duplicateObjectKeysAndExcessiveNestingStayUnknown() {
        for (value in listOf(
            success.replace("\"Code\":1001", "\"Code\":2000,\"Code\":1001"),
            success.replace("\"Index\":0", "\"Index\":1,\"Index\":0"),
            success.replace("\"Code\":1000", "\"Code\":2500,\"Code\":1000"),
            success.replace("\"CalendarID\":", "\"CalendarID\":\"foreign\",\"CalendarID\":"),
            success.replace("\"UID\":", "\"U\\u0049D\":\"foreign\",\"UID\":"),
            success.replace("\"Unknown\":\"preserved\"", "\"Unknown\":" + "[".repeat(80) + "0" + "]".repeat(80))
        )) assertEquals(ProtonCreateResponse.Unknown, parse(value))
    }
}
