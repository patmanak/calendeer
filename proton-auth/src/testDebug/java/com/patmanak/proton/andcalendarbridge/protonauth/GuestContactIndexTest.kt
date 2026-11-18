package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class GuestContactIndexTest {
    private val target = "canonical@example.test"
    private val unavailable = ProtonGuestDirectoryProbe.Status.CONTACT_PREFERENCES_UNAVAILABLE
    private fun row(id: Int, canonical: String = "other@example.test", defaults: Int = 1) =
        """{"ID":"$id","Email":"an-alias@example.test","CanonicalEmail":"$canonical","Defaults":$defaults}"""
    private fun page(total: Int, rows: List<String>) =
        """{"Code":1000,"Total":$total,"ContactEmails":[${rows.joinToString(",")}]}"""
    private fun inspect(vararg pages: String) = runBlocking {
        inspectGuestContactIndex(target) { index, _ -> pages[index].toResponseBody() }
    }

    @Test fun authenticCanonicalSelectsAliasInCompleteIndex() {
        assertEquals(ProtonGuestDirectoryProbe.Status.INTERNAL_DEFAULT_CONTACT, inspect(page(1, listOf(row(1, target)))))
        assertEquals(ProtonGuestDirectoryProbe.Status.INTERNAL_CUSTOM_CONTACT, inspect(page(1, listOf(row(1, target, 0)))))
        assertEquals(ProtonGuestDirectoryProbe.Status.INTERNAL_NO_MATCHING_CONTACT, inspect(page(0, emptyList())))
        val others = (1..32).map { row(it) }
        assertEquals(ProtonGuestDirectoryProbe.Status.INTERNAL_CUSTOM_CONTACT,
            inspect(page(33, others), page(33, listOf(row(33, target, 0)))))
    }

    @Test fun ambiguousIncompleteUnstableOrOversizeIndexCannotProveDefault() {
        val others = (1..32).map { row(it) }
        assertEquals(unavailable, inspect(page(2, listOf(row(1, target), row(2, target)))))
        assertEquals(unavailable, inspect(page(2, listOf(row(1, target)))))
        assertEquals(unavailable, inspect(page(33, others), page(32, emptyList())))
        assertEquals(unavailable, inspect(page(33, others), page(33, listOf(row(1)))))
        assertEquals(unavailable, inspect(page(1_000_000, others)))
        assertEquals(unavailable, inspect(page(1, listOf(row(1, target.uppercase(java.util.Locale.ROOT))))))
        assertEquals(unavailable, inspect(page(1, listOf(row(1, target, 2)))))
        assertEquals(unavailable, inspect(page(1, listOf(row(1, target).replace("\"Defaults\":1", "\"Defaults\":0,\"Defaults\":1")))))
    }
}
