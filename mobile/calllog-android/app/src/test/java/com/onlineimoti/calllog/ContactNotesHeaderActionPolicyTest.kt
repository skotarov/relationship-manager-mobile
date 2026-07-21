package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNotesHeaderActionPolicyTest {
    @Test
    fun crmIsAlwaysFirstForKnownContacts() {
        assertEquals(
            listOf(
                ContactNotesHeaderAction.CRM,
                ContactNotesHeaderAction.CALENDAR,
                ContactNotesHeaderAction.CONTACT,
                ContactNotesHeaderAction.CALL,
                ContactNotesHeaderAction.SMS,
            ),
            ContactNotesHeaderActionPolicy.ordered(contactExists = true),
        )
    }

    @Test
    fun unknownContactsShowAddContactInTheSameSlot() {
        assertEquals(
            listOf(
                ContactNotesHeaderAction.CRM,
                ContactNotesHeaderAction.CALENDAR,
                ContactNotesHeaderAction.ADD_CONTACT,
                ContactNotesHeaderAction.CALL,
                ContactNotesHeaderAction.SMS,
            ),
            ContactNotesHeaderActionPolicy.ordered(contactExists = false),
        )
    }
}
