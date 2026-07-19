package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactDisplayNameChoiceTest {
    @Test
    fun currentContactsNameReplacesStalePhoneLookupName() {
        assertEquals(
            "Ново име",
            ContactDisplayNameChoice.choose(
                indexedName = "Старо име",
                currentContactName = "Ново име",
            ),
        )
    }

    @Test
    fun stalePhoneLookupNameRemainsSafeFallback() {
        assertEquals(
            "Старо име",
            ContactDisplayNameChoice.choose(
                indexedName = "Старо име",
                currentContactName = "   ",
            ),
        )
    }
}
