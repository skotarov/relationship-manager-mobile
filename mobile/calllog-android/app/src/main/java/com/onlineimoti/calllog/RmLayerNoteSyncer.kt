package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

internal object RmLayerNoteSyncer {
    fun formatForCurrentRules(context: Context, phone: String, note: String): String {
        val normalizedPhone = PhoneNormalizer.normalize(phone).ifBlank { phone.trim() }
        val cleanNote = stripLayerPrefix(note, normalizedPhone)
        if (cleanNote.isBlank()) return ""
        return if (CrmContactSyncStore.isEnabled(context.applicationContext, normalizedPhone)) {
            "☁ $normalizedPhone | $cleanNote"
        } else {
            "$normalizedPhone | $cleanNote"
        }
    }

    fun syncIfLayerExists(context: Context, phone: String, note: String): Boolean {
        val appContext = context.applicationContext
        if (!RmContactPermissions.canReadAndWriteContacts(appContext)) return false
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return false
        val rawId = CrmContactAccountStore.findCallReportRawContactId(appContext, normalizedPhone)
        if (rawId <= 0L) return true
        return upsertOrDeleteNote(appContext, rawId, formatForCurrentRules(appContext, normalizedPhone, note))
    }

    fun reformatExistingLayerNote(context: Context, phone: String): Boolean {
        val appContext = context.applicationContext
        if (!RmContactPermissions.canReadAndWriteContacts(appContext)) return false
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return false
        val rawId = CrmContactAccountStore.findCallReportRawContactId(appContext, normalizedPhone)
        if (rawId <= 0L) return true
        val existing = readNote(appContext, rawId)
        return if (existing == null) {
            true
        } else {
            upsertOrDeleteNote(appContext, rawId, formatForCurrentRules(appContext, normalizedPhone, existing.note))
        }
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

    private data class NoteRow(val id: Long, val note: String)

    private fun readNote(context: Context, rawId: Long): NoteRow? {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.CommonDataKinds.Note.NOTE),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) NoteRow(cursor.getLong(0), cursor.getString(1).orEmpty()) else null
            }
        }.getOrNull()
    }

    private fun upsertOrDeleteNote(context: Context, rawId: Long, formattedNote: String): Boolean {
        val existing = readNote(context, rawId)
        if (formattedNote.isBlank() && existing == null) return true

        val operation = when {
            formattedNote.isBlank() -> ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existing!!.id.toString()))
                .build()

            existing != null -> ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existing.id.toString()))
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, formattedNote)
                .build()

            else -> ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, formattedNote)
                .build()
        }
        return runCatching {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(operation))
        }.isSuccess
    }
}
