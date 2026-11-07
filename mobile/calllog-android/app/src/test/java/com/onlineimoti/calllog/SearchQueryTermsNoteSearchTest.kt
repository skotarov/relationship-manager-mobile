package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTermsNoteSearchTest {
    @Test
    fun `finds every term when it occurs in a yellow general note`() {
        assertTrue(SearchQueryTerms.from("оглед петък").matches("Жълта бележка: Оглед в петък"))
    }

    @Test
    fun `finds every term when it occurs in a blue call note`() {
        assertTrue(SearchQueryTerms.from("изпрати оферта").matches("Синя бележка: Изпрати оферта по имейл"))
    }

    @Test
    fun `finds server note words even when their order differs from the query`() {
        assertTrue(SearchQueryTerms.from("оферта изпрати").matches("Синя сървърна бележка: Изпрати оферта по имейл"))
    }

    @Test
    fun `does not return a note unless every entered word matches`() {
        assertFalse(SearchQueryTerms.from("оглед кредит").matches("Жълта бележка: Оглед в петък"))
    }
}
