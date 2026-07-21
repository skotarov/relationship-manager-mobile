package com.onlineimoti.calllog

internal enum class ContactNotesHeaderAction {
    CRM,
    CALENDAR,
    CONTACT,
    ADD_CONTACT,
    CALL,
    SMS,
}

/** Keeps History actions in the same predictable order as the phone Contacts page. */
internal object ContactNotesHeaderActionPolicy {
    fun ordered(contactExists: Boolean): List<ContactNotesHeaderAction> = listOf(
        ContactNotesHeaderAction.CRM,
        ContactNotesHeaderAction.CALENDAR,
        if (contactExists) ContactNotesHeaderAction.CONTACT else ContactNotesHeaderAction.ADD_CONTACT,
        ContactNotesHeaderAction.CALL,
        ContactNotesHeaderAction.SMS,
    )
}
