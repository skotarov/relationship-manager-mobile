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

    fun openDefaultContact(phone: String) {
        if (phone.isBlank()) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, activity.getString(R.string.contact_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        val lookupUri = runCatching {
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

        if (lookupUri == null) {
            Toast.makeText(activity, activity.getString(R.string.contact_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, lookupUri)) }
            .onFailure { Toast.makeText(activity, activity.getString(R.string.contacts_app_not_found), Toast.LENGTH_SHORT).show() }
    }

    fun openCalendarEvent(phone: String, titleText: String) {
        val safeName = titleText.ifBlank { phone.ifBlank { "контакт" } }
        val begin = System.currentTimeMillis() + 60 * 60 * 1000L
        val end = begin + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, "Среща с $safeName")
            putExtra(CalendarContract.Events.DESCRIPTION, buildString {
                appendLine("Име: $safeName")
                if (phone.isNotBlank()) appendLine("Телефон: $phone")
            }.trim())
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, "Няма намерено приложение Календар", Toast.LENGTH_SHORT).show()
        }
    }

    fun openGeneralNotePopup(phone: String, titleText: String) {
        activity.startActivity(
            Intent(activity, ContactNoteEditActivity::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_GENERAL_NOTE)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, titleText)
        )
    }

    fun openEditPopup(phone: String, titleText: String, note: ContactCallNote) {
        activity.startActivity(
            Intent(activity, ContactNoteEditActivity::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_NOTE)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, note.direction)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, titleText)
                .putExtra(PostCallOverlayService.EXTRA_CALL_AT, note.callAt)
                .putExtra(PostCallOverlayService.EXTRA_DURATION, note.durationSeconds)
        )
    }
}
