// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant

/** Comparison of imported SQL history, never a current server/Provider observation or upload gate. */
enum class LocalEditImportRelation { FORMAT_UNKNOWN, FORMAT_MISMATCH, DEFERRED, UNOBSERVED, UNCHANGED, CHANGED, DELETED }

/** Shared history policy for UPDATE and DELETE; opaque revisions are compared, never ordered. */
internal fun localImportRelation(format: AdapterFormat, base: RemoteChange.Upsert, imported: RemoteChange?,
    observationFormat: AdapterFormat?, deferred: Boolean): LocalEditImportRelation = when {
    observationFormat == null -> LocalEditImportRelation.FORMAT_UNKNOWN
    observationFormat != format -> LocalEditImportRelation.FORMAT_MISMATCH
    deferred -> LocalEditImportRelation.DEFERRED
    imported == null -> LocalEditImportRelation.UNOBSERVED
    imported is RemoteChange.Deleted -> LocalEditImportRelation.DELETED
    imported == base -> LocalEditImportRelation.UNCHANGED
    else -> LocalEditImportRelation.CHANGED
}

/** Historical metadata only. No protected payload, signature qualification or dispatch authority. */
data class RetainedLocalTitleInfo(val format: AdapterFormat, val timestamp: Instant) {
    override fun toString() = "RetainedLocalTitleInfo(REDACTED)"
}

/** One local journal observation. The captured base/content and recorded conflict are preserved.
 * The imported record can be stale, deferred, absent or from another observation format.
 * This does not read Android contents or renew the validity of a retained preparation.
 */
data class LocalEditReview(
    val owner: LocalEditIntent,
    val projectedBase: RemoteChange.Upsert?,
    val imported: RemoteChange?,
    val observationFormat: AdapterFormat?,
    val deferred: Boolean,
    val retainedTitle: RetainedLocalTitleInfo?,
) {
    init {
        require(projectedBase == null || projectedBase.eventId == owner.base.eventId)
        require(imported == null || imported.eventId == owner.base.eventId)
    }
    val importRelation: LocalEditImportRelation get() = localImportRelation(owner.format, owner.base, imported, observationFormat, deferred)
    val recordedConflict: Boolean get() = owner.state == LocalEditState.CONFLICT
    val capturedBaseStillProjected: Boolean get() = projectedBase == owner.base
    val titleOnly: Boolean get() = owner.content == owner.base.content.copy(title = owner.content.title)
    override fun toString() = "LocalEditReview(REDACTED)"
}
