package com.onlineimoti.calllog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import androidx.core.content.ContextCompat

class ContactNotesExternalActions(private val activity: Activity) {
    fun openAllCallsLog() {
        activity.startActivity(
            Intent(activity, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        activity.finish()
    }

    fun openDialer(phone: String) {
        if (phone.isBlank()) return
        activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
    }

    /**
     * A Call Report/RM layer record is not treated as the user's real contact.
     * The header therefore keeps the add-contact person icon until a non-RM
     * raw contact exists for the same phone number.
     */
    fun hasDefaultContact(phone: String): Boolean {
        if (phone.isBlank()) return false
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return RmRealContactLookup.findContactId(activity, phone) > 0L
    }

    fun openDefaultContact(phone: String, titleText: String = "") {
        if (phone.isBlank()) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            openCreateContact(phone, titleText)
            return
        }

        // Do not open the internal Call Report layer as if it were a normal contact.
        // When it is the only matching record, keep the user on the create-contact flow.
        if (!hasDefaultContact(phone)) {
            openCreateContact(phone, titleText)
            return
        }

        val lookupUri = lookupContactUri(phone)
        if (lookupUri == null) {
            openCreateContact(phone, titleText)
            return
        }

        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, lookupUri)) }
            .onFailure { Toast.makeText(activity, activity.getString(R.string.contacts_app_not_found), Toast.LENGTH_SHORT).show() }
    }

    private fun lookupContactUri(phone: String): Uri? {
        return runCatching {
            activity.contentResolver.query(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phone).build(),
                arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                ContactsContract.Contacts.getLookupUri(cursor.getLong(1), cursor.getString(0))
            }
        }.getOrNull()
    }

    private fun openCreateContact(phone: String, titleText: String) {
        val safeName = titleText.takeUnless {
            it.isBlank() || it == phone || it == activity.getString(R.string.dynamic_notes_default_title)
        }.orEmpty()
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            if (safeName.isNotBlank()) putExtra(ContactsContract.Intents.Insert.NAME, safeName)
            putExtra(ContactsContract.Intents.Insert.PHONE, phone)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { Toast.makeText(activity, activity.getString(R.string.contacts_app_not_found), Toast.LENGTH_SHORT).show() }
    }

    fun openCalendarEvent(phone: String, titleText: String) {
        val safeName = titleText.ifBlank { phone.ifBlank { activity.getString(R.string.dynamic_calendar_default_contact) } }
        val begin = System.currentTimeMillis() + 60 * 60 * 1000L
        val end = begin + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, activity.getString(R.string.dynamic_calendar_event_title, safeName))
            putExtra(CalendarContract.Events.DESCRIPTION, buildString {
                appendLine(activity.getString(R.string.dynamic_calendar_name_line, safeName))
                if (phone.isNotBlank()) appendLine(activity.getString(R.string.dynamic_calendar_phone_line, phone))
            }.trim())
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, activity.getString(R.string.dynamic_calendar_app_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    fun openGeneralNotePopup(phone: String, titleText: String) {
        CallNoteEditorLauncher.startEditor(
            context = activity,
            mode = PostCallOverlayService.MODE_GENERAL_NOTE,
            phone = phone,
            title = titleText,
        )
    }

    fun openEditPopup(phone: String, titleText: String, note: ContactCallNote) {
        CallNoteEditorLauncher.startEditor(
            context = activity,
            mode = PostCallOverlayService.MODE_NOTE,
            phone = phone,
            title = titleText,
            direction = note.direction,
            callAt = note.callAt,
            durationSeconds = note.durationSeconds,
        )
    }
}
