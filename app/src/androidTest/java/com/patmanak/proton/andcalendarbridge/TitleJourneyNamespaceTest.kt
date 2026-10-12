// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import org.junit.Assert.*
import org.junit.Test

class TitleJourneyNamespaceTest {
    @Test fun absentLabelPreservesAllHistoricalNames() {
        for ((automatic, system, suffix) in listOf(Triple(false, false, ""), Triple(false, true, "-system-v1"),
            Triple(true, true, "-system-worker-v1"), Triple(true, false, "-system-worker-v1"))) {
            val names = TitleJourneyNamespace.create(automatic, system, null)
            assertEquals("calendar-title-guarded-journey-v1$suffix", names.sourcePreferences)
            assertEquals("calendar-title-guarded-journey-import-v1.db$suffix", names.sourceImportDatabase)
            assertEquals("calendar-title-guarded-journey-create-v1.db$suffix", names.sourceCreateDatabase)
            assertEquals("calendar-title-guarded-journey-attempt-v1.db$suffix", names.sourceTitleDatabase)
            assertEquals("calendar-system-title-worker-v1", names.workerPreferences)
        }
        assertEquals("calendar-title-guarded-journey-v1-system-v1",
            TitleJourneyNamespace.create(false, false, null).workerSourcePreferences)
    }

    @Test fun distinctLabelsIsolateBothPhasesAndAllFixtureStorage() {
        val first = TitleJourneyNamespace.create(true, true, "fresh-one")
        val second = TitleJourneyNamespace.create(true, true, "fresh-two")
        val historical = TitleJourneyNamespace.create(true, true, null)
        fun names(value: TitleJourneyNamespace) = setOf(value.sourcePreferences, value.sourceImportDatabase,
            value.sourceCreateDatabase, value.sourceTitleDatabase, value.workerPreferences)
        assertEquals(5, names(first).size)
        assertTrue(names(first).intersect(names(second)).isEmpty())
        assertTrue(names(first).intersect(names(historical)).isEmpty())
        assertEquals(first.sourcePreferences, first.workerSourcePreferences)
        assertEquals(first.sourceTitleDatabase, TitleJourneyNamespace.create(true, true, "fresh-one").sourceTitleDatabase)
        assertEquals(first.workerPreferences, TitleJourneyNamespace.create(true, true, "fresh-one").workerPreferences)
    }

    @Test fun labelRequiresBothExplicitJourneyOptIns() {
        for ((automatic, system) in listOf(false to false, false to true, true to false))
            assertThrows(IllegalArgumentException::class.java) { TitleJourneyNamespace.create(automatic, system, "fresh") }
    }

    @Test fun labelIsStrictBoundedAndNeverNormalizedOrInterpretedAsAPath() {
        for (invalid in listOf("", " Fresh", "fresh ", "Fresh", "1fresh", "fresh_one", "../fresh", "fresh/one", "fresh\\one",
            "fresh\n", "épreuve", "a".repeat(25)))
            assertThrows(IllegalArgumentException::class.java) { TitleJourneyNamespace.create(true, true, invalid) }
        for (valid in listOf("a", "fresh-2", "a".repeat(24)))
            assertEquals(valid, TitleJourneyNamespace.create(true, true, valid).label)
    }
}
