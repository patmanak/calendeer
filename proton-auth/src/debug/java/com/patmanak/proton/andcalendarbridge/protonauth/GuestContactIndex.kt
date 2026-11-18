// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody

private const val PAGE_SIZE = 32
private const val MAX_ROWS = 1024
private const val MAX_PAGE_BYTES = 262144L

/** Mirrors Calendar's canonical-email comparison over Core's paged contact-email metadata index. */
internal suspend fun inspectGuestContactIndex(canonical: String, fetch: suspend (Int, Int) -> ResponseBody):
    ProtonGuestDirectoryProbe.Status {
    val probe = GuestContactIndex(canonical)
    var expectedTotal: Int? = null
    var page = 0
    return try {
        do {
            val before = probe.count
            val total = fetch(page, PAGE_SIZE).use(probe::readPage)
            require(total in 0..MAX_ROWS && (expectedTotal == null || total == expectedTotal))
            require(probe.count - before == minOf(PAGE_SIZE, total - before))
            expectedTotal = total
            page++
        } while (probe.count < requireNotNull(expectedTotal))
        probe.status()
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { ProtonGuestDirectoryProbe.Status.CONTACT_PREFERENCES_UNAVAILABLE }
}

private class GuestContactIndex(private val canonical: String) {
    private val seen = HashSet<String>()
    private val digest = MessageDigest.getInstance("SHA-256")
    private val factory = JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private var match: Int? = null
    val count: Int get() = seen.size

    fun status() = when (match) {
        0 -> ProtonGuestDirectoryProbe.Status.INTERNAL_CUSTOM_CONTACT
        1 -> ProtonGuestDirectoryProbe.Status.INTERNAL_DEFAULT_CONTACT
        else -> ProtonGuestDirectoryProbe.Status.INTERNAL_NO_MATCHING_CONTACT
    }

    fun readPage(body: ResponseBody): Int {
        require(body.contentLength() <= MAX_PAGE_BYTES && !body.source().request(MAX_PAGE_BYTES + 1))
        return factory.createParser(body.byteStream()).use { parser ->
            var total: Int? = null
            var code: Int? = null
            var hasRows = false
            parser.fields { name ->
                when (name) {
                    "Code" -> code = parser.strictInt()
                    "Total" -> total = parser.strictInt()
                    "ContactEmails" -> {
                        require(parser.currentToken == JsonToken.START_ARRAY)
                        hasRows = true
                        val before = count
                        while (parser.nextToken() == JsonToken.START_OBJECT) {
                            require(count - before < PAGE_SIZE)
                            readRow(parser)
                        }
                        require(parser.currentToken == JsonToken.END_ARRAY)
                    }
                    else -> parser.skipChildren()
                }
            }
            require(parser.nextToken() == null && code == 1000 && hasRows)
            requireNotNull(total)
        }
    }

    private fun readRow(parser: JsonParser) {
        var idHash: String? = null
        var address: String? = null
        var defaults: Int? = null
        parser.fields { key ->
            when (key) {
                "ID" -> {
                    require(parser.currentToken == JsonToken.VALUE_STRING && parser.text.length in 1..512)
                    idHash = Base64.getEncoder().encodeToString(digest.digest(parser.text.toByteArray(Charsets.UTF_8)))
                }
                "CanonicalEmail" -> if (parser.currentToken != JsonToken.VALUE_NULL) {
                    require(parser.currentToken == JsonToken.VALUE_STRING && parser.text.length in 3..254)
                    address = parser.text
                }
                "Defaults" -> defaults = parser.strictInt()
                else -> parser.skipChildren()
            }
        }
        require(seen.add(requireNotNull(idHash)) && (defaults == 0 || defaults == 1))
        require(address != null || defaults == 1)
        if (address.equals(canonical, ignoreCase = true)) {
            require(address == canonical && match == null)
            match = defaults
        }
    }
}

private inline fun JsonParser.fields(visit: (String) -> Unit) {
    if (currentToken == null) nextToken()
    require(currentToken == JsonToken.START_OBJECT)
    while (nextToken() == JsonToken.FIELD_NAME) {
        val name = currentName
        nextToken()
        visit(name)
    }
    require(currentToken == JsonToken.END_OBJECT)
}

private fun JsonParser.strictInt(): Int {
    require(currentToken == JsonToken.VALUE_NUMBER_INT)
    return intValue
}
