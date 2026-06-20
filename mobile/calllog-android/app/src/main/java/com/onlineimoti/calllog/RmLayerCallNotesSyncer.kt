package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object RmLayerCallNotesSyncer {
    private const val CALL_NOTES_MIME_TYPE = "vnd.android.cursor.item/vnd.com.onlineimoti.calllog.call_notes"
    private const val FIELD_TITLE = "Call notes"
    private const val CLOUD_FIELD_TITLE = "☁ Call notes"
    private const val FIELD_DESCRIPTION = "Последни 3 бележки от разговори"
    private const val MAX_CALL_NOTES = 3

    fun syncIfLayerExists(context: Context, phone: String): Boolean {
        val appContext = context.applicationContext
        if (!RmContactPermissions.canReadAndWriteContacts(appContext)) return false
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return false

        val rawId = CrmContactAccountStore.findCallReportRawContactId(appContext, normalizedPhone)
        if (rawId <= 0L) return true

        val text = formatLatestCallNotes(appContext, normalizedPhone)
        val title = if (CrmContactSyncStore.isEnabled(appContext, normalizedPhone)) CLOUD_FIELD_TITLE else FIELD_TITLE
        return upsertOrDeleteField(appContext, rawId, title, text)
    }

    private fun formatLatestCallNotes(context: Context, phone: String): String {
        return ContactNoteReader.callNotesForPhone(context, phone)
            .take(MAX_CALL_NOTES)
            .mapNotNull { callNote ->
                val text = callNote.note
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .trim()
                if (text.isBlank()) return@mapNotNull null

                val timestamp = callNote.callAt.takeIf { it > 0L } ?: callNote.savedAt
                "${formatTimestamp(timestamp)}\n$text"
            }
            .joinToString("\n\n")
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "Без дата"
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private data class FieldRow(val id: Long)

    private fun findFieldRow(context: Context, rawId: Long): FieldRow? {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), CALL_NOTES_MIME_TYPE),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) FieldRow(cursor.getLong(0)) else null
            }
        }.getOrNull()
    }

    private fun upsertOrDeleteField(context: Context, rawId: Long, title: String, text: String): Boolean {
        val existing = findFieldRow(context, rawId)
        if (text.isBlank() && existing == null) return true

        val operation = when {
            text.isBlank() -> ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existing!!.id.toString()))
                .build()

            existing != null -> ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existing.id.toString()))
                .withValue(ContactsContract.Data.DATA1, text)
                .withValue(ContactsContract.Data.DATA2, title)
                .withValue(ContactsContract.Data.DATA3, FIELD_DESCRIPTION)
                .build()

            else -> ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, CALL_NOTES_MIME_TYPE)
                .withValue(ContactsContract.Data.DATA1, text)
                .withValue(ContactsContract.Data.DATA2, title)
                .withValue(ContactsContract.Data.DATA3, FIELD_DESCRIPTION)
                .build()
        }

        return runCatching {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(operation))
        }.isSuccess
    }
}
