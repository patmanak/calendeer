// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

/** Names only for retained instrumentation fixtures. Never applies to production journals,
 * cursors, modes, accounts or Provider rows. No label preserves every historical fixture name.
 */
internal class TitleJourneyNamespace private constructor(
    val label: String?, automaticSource: Boolean, systemJourney: Boolean,
) {
    private val labelSuffix = label?.let { "-journey-$it" }.orEmpty()
    val sourceSuffix = when {
        automaticSource -> "-system-worker-v1"
        systemJourney -> "-system-v1"
        else -> ""
    } + labelSuffix
    val sourcePreferences = "calendar-title-guarded-journey-v1" + sourceSuffix
    val sourceImportDatabase = "calendar-title-guarded-journey-import-v1.db" + sourceSuffix
    val sourceCreateDatabase = "calendar-title-guarded-journey-create-v1.db" + sourceSuffix
    val sourceTitleDatabase = "calendar-title-guarded-journey-attempt-v1.db" + sourceSuffix
    val workerPreferences = "calendar-system-title-worker-v1" + labelSuffix
    // Historical worker invocations did not require the systemTitleJourney argument.
    val workerSourcePreferences = if (automaticSource) sourcePreferences else "calendar-title-guarded-journey-v1-system-v1"

    override fun toString() = "TitleJourneyNamespace(REDACTED)"

    companion object {
        fun create(automaticSource: Boolean, systemJourney: Boolean, label: String?): TitleJourneyNamespace {
            require(label == null || (automaticSource && systemJourney &&
                label.matches(Regex("[a-z][a-z0-9-]{0,23}")))) { "Invalid explicit title journey namespace" }
            return TitleJourneyNamespace(label, automaticSource, systemJourney)
        }
    }
}
