// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.content.ContentValues
import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.digest

internal class NativeEditAcknowledgement private constructor(val intent: LocalEditIntent,
    val projection: ProjectionReceipt, val handoff: CapturedCreateHandoffFrame?,
    private val canonicalText: Boolean, val checkActive: () -> Unit, val personal: Boolean = false) {
    fun values(marker: String): ContentValues = ContentValues().apply {
        put(Events.DIRTY, 0)
        put(Events.SYNC_DATA2, digest("revision-v1", projection.change.revision.value))
        put(Events.SYNC_DATA3, marker)
        if (canonicalText) {
            val raw = intent.content
            val actual = projection.change.content
            if (raw.title != actual.title) put(Events.TITLE, actual.title)
            if (raw.description != actual.description) put(Events.DESCRIPTION, actual.description)
            if (raw.location != actual.location) put(Events.EVENT_LOCATION, actual.location)
        }
    }
    companion object {
        fun title(receipt: TitleUpdateAcknowledgementReceipt): NativeEditAcknowledgement {
            require(receipt.intent.content == receipt.projection.change.content)
            return NativeEditAcknowledgement(receipt.intent, receipt.projection, receipt.handoff, false, {})
        }
        fun text(receipt: TextUpdateAcknowledgementReceipt, checkActive: () -> Unit): NativeEditAcknowledgement {
            require(TextUpdateContentPolicy.sameSubmittedContent(receipt.intent.content, receipt.projection.change.content))
            return NativeEditAcknowledgement(receipt.intent, receipt.projection, receipt.handoff, true, checkActive)
        }
        fun personal(receipt: PersonalUpdateAcknowledgementReceipt, checkActive: () -> Unit): NativeEditAcknowledgement {
            require(PersonalUpdateContentPolicy.sameSubmittedContent(receipt.intent.content, receipt.projection.change.content))
            return NativeEditAcknowledgement(receipt.intent, receipt.projection, receipt.handoff, true, checkActive, personal = true)
        }
        fun time(receipt: TimeUpdateAcknowledgementReceipt, checkActive: () -> Unit): NativeEditAcknowledgement {
            require(TimeUpdateContentPolicy.sameSubmittedContent(receipt.intent.content, receipt.projection.change.content))
            return NativeEditAcknowledgement(receipt.intent, receipt.projection, receipt.handoff, true, checkActive)
        }
    }
}
