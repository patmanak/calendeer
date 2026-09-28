// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.RemoteImportException
import com.patmanak.proton.andcalendarbridge.sync.RemoteImportFailure

/** Unsupported records do not consume the aggregate budget of accepted mapped contents. */
internal class EventDecodeBudget {
    private var acceptedBytes = 0
    fun accept(event: EventTextBudget) {
        if (event.bytes > MAX_ACCEPTED_BYTES - acceptedBytes) throw RemoteImportException(RemoteImportFailure.CAPACITY)
        acceptedBytes += event.bytes
    }
    companion object { const val MAX_ACCEPTED_BYTES = 8 * 1024 * 1024 }
}

/** One event's independently bounded UTF-8 plaintext. Reset for every record, including retries. */
internal class EventTextBudget {
    var bytes: Int = 0
        private set
    fun consume(text: String) {
        if (text.length > MAX_EVENT_BYTES - bytes) unsupportedEvents()
        val encoded = text.toByteArray(Charsets.UTF_8)
        try {
            if (encoded.size > MAX_EVENT_BYTES - bytes) unsupportedEvents()
            bytes += encoded.size
        } finally { encoded.fill(0) }
    }
    companion object { const val MAX_EVENT_BYTES = 1024 * 1024 }
}
