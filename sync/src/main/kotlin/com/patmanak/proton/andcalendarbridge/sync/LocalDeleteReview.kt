// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Historical DELETE observation only; no acknowledgement, undo image or upload authority. */
data class LocalDeleteReview(
    val owner: LocalDeleteIntent,
    val projectedBase: RemoteChange.Upsert?,
    val imported: RemoteChange?,
    val observationFormat: AdapterFormat?,
    val deferred: Boolean,
) {
    init {
        require(projectedBase == null || projectedBase.eventId == owner.base.eventId)
        require(imported == null || imported.eventId == owner.base.eventId)
    }
    val importRelation: LocalEditImportRelation get() = localImportRelation(owner.format, owner.base, imported, observationFormat, deferred)
    val recordedConflict: Boolean get() = owner.state == LocalEditState.CONFLICT
    val capturedBaseStillProjected: Boolean get() = projectedBase == owner.base
    override fun toString() = "LocalDeleteReview(REDACTED)"
}
