// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProtonColourAdmissionTest {
    @Test fun paletteAdmissionKeepsInheritanceAndUnchangedHistoryButNeverRewritesAColour() {
        assertTrue(ProtonEventColourPolicy.accepts(null))
        assertTrue(ProtonEventColourPolicy.accepts(0x8080ff))
        assertTrue(ProtonEventColourPolicy.accepts(0x258723))
        for (rgb in listOf(0x7868e6, 0x123456, 0, -1, 0x1000000))
            assertFalse(ProtonEventColourPolicy.accepts(rgb))
        assertTrue(ProtonEventColourPolicy.acceptsChange(0x123456, 0x123456))
        assertTrue(ProtonEventColourPolicy.acceptsChange(0x123456, null))
        assertFalse(ProtonEventColourPolicy.acceptsChange(null, 0x7868e6))
    }
    @Test fun offPaletteSimpleAndSeriesChangesStopBeforeReadingContext() = runTest {
        for (series in listOf(false, true)) {
            val seed = PersonalSubmissionFixture()
            val base = if (series) seed.intent.base.copy(content = seed.intent.base.content.copy(
                recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY;COUNT=3"))) else seed.intent.base
            val after = seed.intent.content.copy(colorRgb = 0x7868e6,
                recurrence = if (series) EventRecurrence("synthetic-uid", "FREQ=DAILY;COUNT=4") else null)
            val intent = seed.intent.copy(base = base, content = after,
                seriesProfile = if (series) SeriesEditProfile.of(base.content, after) else null)
            var calls = 0
            fun unexpected(): Nothing { calls++; error("Context must not be read") }
            val port = ProtonPersonalPreparationAdapter({ unexpected() }, { unexpected() },
                { _, _ -> unexpected() }, object : EventImportRemote {
                    override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = unexpected()
                    override suspend fun bootstrap(session: CatalogSession, calendar: String) = unexpected()
                }, { _, _, _ -> unexpected() }, { unexpected() },
                { _, _, _, _, _, _, _, _ -> unexpected() }, { _, _ -> unexpected() },
                { _, _, _, _, _, _, _, _ -> unexpected() }, { _, _, _, _ -> unexpected() })
            try {
                if (series) port.prepareSeries(intent, seed.timestamp) { true }
                else port.preparePersonal(intent, seed.timestamp) { true }
                fail("Unadmitted colour must not reach preparation")
            } catch (failure: LocalEditPreparationException) {
                assertEquals(LocalEditPreparationFailure.UNSUPPORTED, failure.reason)
            }
            assertEquals(0, calls)
            assertEquals(0x7868e6, intent.content.colorRgb)
        }
    }

    @Test fun historicalForeignColourRemainsReadableWithoutBeingReinterpreted() {
        val f = PersonalSubmissionFixture()
        val preparation = ProtonPersonalPreparationCodec(f.protector).openLocal(f.preparation)
        assertEquals(f.fragments.originalRow, preparation.originalRow)
        assertEquals(0xabcd12, f.preparation.frozen().intent.content.colorRgb)
    }
}
