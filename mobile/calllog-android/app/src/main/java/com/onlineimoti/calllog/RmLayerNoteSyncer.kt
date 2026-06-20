package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object RmLayerNoteSyncer {
    private const val LEGACY_CALL_NOTES_MIME_TYPE = "vnd.android.cursor.item/vnd.com.onlineimoti.calllog.call_notes"
    private const val MAX_CALL_NOTES = 3

    fun formatForCurrentRules(context: Context, phone: String, note: String): String {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone).ifBlank { phone.trim() }
        val cleanGeneralNote = stripLayerPrefix(note, normalizedPhone)
        val generalBlock = when {
            cleanGeneralNote.isBlank() -> ""
            CrmContactSyncStore.isEnabled(appContext, normalizedPhone) -> "☁ $normalizedPhone | $cleanGeneralNote"
            else -> "$normalizedPhone | $cleanGeneralNote"
        }
        val recentCallNotesBlock = formatLatestCallNotes(appContext, normalizedPhone)
        return listOf(generalBlock, recentCallNotesBlock)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    fun syncIfLayerExists(context: Context, phone: String, note: String): Boolean {
        val appContext = context.applicationContext
        if (!RmContactPermissions.canReadAndWriteContacts(appContext)) return false
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return false
        val rawId = CrmContactAccountStore.findCallReportRawContactId(appContext, normalizedPhone)
        if (rawId <= 0L) return true
        return upsertNoteAndClearLegacyCallNotes(
            context = appContext,
            rawId = rawId,
            formattedNote = formatForCurrentRules(appContext, normalizedPhone, note),
        )
    }

    fun syncCurrentGeneralNoteIfLayerExists(context: Context, phone: String): Boolean {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return false
        return syncIfLayerExists(
            context = appContext,
            phone = normalizedPhone,
            note = ContactNoteReader.generalNoteForPhone(appContext, normalizedPhone),
        )
    }

    fun reformatExistingLayerNote(context: Context, phone: String): Boolean {
        return syncCurrentGeneralNoteIfLayerExists(context, phone)
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

    private fun stripLayerPrefix(note: String, phone: String): String {
        val trimmed = note.trim()
        if (trimmed.isBlank()) return ""

        val withoutCloud = if (trimmed.startsWith("☁")) {
            trimmed.removePrefix("☁").trim()
        } else {
            trimmed
        }
        val separatorIndex = withoutCloud.indexOf('|')
        if (separatorIndex < 0) return withoutCloud.trim()

        val beforeSeparator = withoutCloud.substring(0, separatorIndex).trim()
        return if (PhoneNormalizer.samePhone(beforeSeparator, phone)) {
            withoutCloud.substring(separatorIndex + 1).trim()
        } else {
            withoutCloud.trim()
        }
    }

    private data class NoteRow(val id: Long)

    private fun readNote(context: Context, rawId: Long): NoteRow? {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) NoteRow(cursor.getLong(0)) else null
            }
        }.getOrNull()
    }

    private fun upsertNoteAndClearLegacyCallNotes(context: Context, rawId: Long, formattedNote: String): Boolean {
        val existing = readNote(context, rawId)
        val operations = arrayListOf<ContentProviderOperation>()

        when {
            formattedNote.isBlank() && existing != null -> {
                operations.add(
                    ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existing.id.toString()))
                        .build()
                )
            }
            formattedNote.isNotBlank() && existing != null -> {
                operations.add(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existing.id.toString()))
                        .withValue(ContactsContract.CommonDataKinds.Note.NOTE, formattedNote)
                        .build()
                )
            }
            formattedNote.isNotBlank() -> {
                operations.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Note.NOTE, formattedNote)
                        .build()
                )
            }
        }

        operations.add(
            ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(rawId.toString(), LEGACY_CALL_NOTES_MIME_TYPE),
                )
                .build()
        )

        return runCatching {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        }.isSuccess
    }
}
