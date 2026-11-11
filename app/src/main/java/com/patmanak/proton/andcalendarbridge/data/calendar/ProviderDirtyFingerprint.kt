// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import java.nio.ByteBuffer
import java.security.MessageDigest

/** A scheduling hint only. Raw Provider contents never leave the reader. */
internal class ProviderDirtyFingerprint(val hash: String?, val dirtyRows: Int) {
    override fun toString() = "ProviderDirtyFingerprint(REDACTED)"
}

/** Bounded, length-framed hashing. Refuse overflow instead of caching a truncated snapshot. */
internal class ProviderWakeSnapshotLimit : IllegalStateException("Provider wake snapshot limit")

internal class ProviderWakeDigest(private val maxRows: Int = 4096, private val maxBytes: Int = 16 * 1024 * 1024) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var rows = 0
    private var bytes = 0L
    fun row() { if (++rows > maxRows) throw ProviderWakeSnapshotLimit() }
    fun text(value: String) = value(value.toByteArray(Charsets.UTF_8))
    fun value(value: ByteArray) {
        bytes += value.size.toLong() + 4
        if (bytes > maxBytes) throw ProviderWakeSnapshotLimit()
        digest.update(ByteBuffer.allocate(4).putInt(value.size).array())
        digest.update(value)
    }
    fun finish(): String = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
