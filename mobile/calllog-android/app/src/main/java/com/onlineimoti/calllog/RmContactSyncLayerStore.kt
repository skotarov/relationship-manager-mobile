package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract

internal object RmContactSyncLayerStore {
    private const val CLOUD_SYNC_GROUP_NAME = "Cloud Sync"
    private const val CLOUD_NOTE_PREFIX = "☁"

    fun setEnabled(context: Context, phone: String, title: String, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return false

        if (!enabled) {
            CrmContactSyncStore.setEnabled(appContext, normalizedPhone, false)
            val visibleTargets = findVisibleRawContacts(appContext, normalizedPhone)
            return if (visibleTargets.isEmpty()) {
                deleteRmLayer(appContext, normalizedPhone)
            } else {
                val cloudMarkerRemoved = removeCloudMarkerFromRmNote(appContext, normalizedPhone)
                val rmCloudLabelRemoved = removeRmCloudSyncMembership(appContext, normalizedPhone)
                val labelCleared = clearVisibleCloudSyncLabels(appContext, visibleTargets)
                cloudMarkerRemoved && rmCloudLabelRemoved && labelCleared
            }
        }

        CrmContactSyncStore.setEnabled(appContext, normalizedPhone, true)
        val updated = ensureLayerWithCurrentNote(appContext, normalizedPhone, title)
        if (!updated) CrmContactSyncStore.setEnabled(appContext, normalizedPhone, false)
        return updated
    }

    fun refreshNoteIfEnabled(context: Context, phone: String, title: String = "") {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return
        if (!CrmContactSyncStore.isEnabled(appContext, normalizedPhone)) return
        ensureLayerWithCurrentNote(appContext, normalizedPhone, title)
    }

    fun noteForCurrentRules(context: Context, phone: String): String {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone).ifBlank { phone }
        val note = existingRmNote(appContext, normalizedPhone)
            ?: ContactNoteReader.generalNoteForPhone(appContext, normalizedPhone)
        return if (CrmContactSyncStore.isEnabled(appContext, normalizedPhone)) {
            cloudMarkedNote(note, normalizedPhone)
        } else {
            removeExistingCloudMarker(note)
        }
    }

    fun groupNameForCurrentRules(context: Context, phone: String, fallback: String = CrmContactAccountStore.ACCOUNT_NAME): String {
        val normalizedPhone = PhoneNormalizer.normalize(phone).ifBlank { phone }
        return if (CrmContactSyncStore.isEnabled(context.applicationContext, normalizedPhone)) CLOUD_SYNC_GROUP_NAME else fallback
    }

    fun applyCloudSyncLabelsIfEnabled(context: Context, phone: String): Boolean {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return true
        if (!CrmContactSyncStore.isEnabled(appContext, normalizedPhone)) return true
        return applyVisibleCloudSyncLabels(appContext, normalizedPhone)
    }

    private fun ensureLayerWithCurrentNote(context: Context, phone: String, title: String): Boolean {
        if (!RmContactPermissions.canReadAndWriteContacts(context)) return false
        val displayName = title.trim()
            .ifBlank { ContactGroupFilter.resolveDisplayName(context, phone).orEmpty() }
            .ifBlank { RmContactReader.findRmRecord(context, phone)?.displayName.orEmpty() }
            .ifBlank { phone }
        val fields = CallReportStableCrmContactWriter.Fields(
            originalPhone = phone,
            displayName = displayName,
            note = noteForCurrentRules(context, phone),
            groupName = groupNameForCurrentRules(context, phone),
        )
        val saved = CrmContactLinkSaver.save(
            context = context,
            fields = fields,
            mode = ConfigStore.load(context).contactLinkMode,
            phone = phone,
            title = displayName,
        )
        if (!saved) return false
        return applyCloudSyncLabelsIfEnabled(context, phone)
    }

    private fun cloudMarkedNote(note: String, phone: String): String {
        val cleaned = removeExistingCloudMarker(note)
        return if (cleaned.isBlank()) {
            "$CLOUD_NOTE_PREFIX $phone |"
        } else {
            "$CLOUD_NOTE_PREFIX $phone | $cleaned"
        }
    }

    private fun removeExistingCloudMarker(note: String): String {
        val trimmed = note.trim()
        if (!trimmed.startsWith(CLOUD_NOTE_PREFIX)) return trimmed
        val withoutCloud = trimmed.removePrefix(CLOUD_NOTE_PREFIX).trim()
        return if (withoutCloud.contains("|")) {
            withoutCloud.substringAfter("|").trim()
        } else {
            withoutCloud
        }
    }

    private fun existingRmNote(context: Context, phone: String): String? {
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, phone)
        if (rawId <= 0L) return null
        return findRmNoteRow(context, rawId)?.note
    }

    private fun removeCloudMarkerFromRmNote(context: Context, phone: String): Boolean {
        if (!RmContactPermissions.canReadAndWriteContacts(context)) return false
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, phone)
        if (rawId <= 0L) return true
        val noteRow = findRmNoteRow(context, rawId) ?: return true
        val cleanedNote = removeExistingCloudMarker(noteRow.note)
        val op = if (cleanedNote.isBlank()) {
            ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(noteRow.id.toString()))
                .build()
        } else {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(noteRow.id.toString()))
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, cleanedNote)
                .build()
        }
        return runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op)) }.isSuccess
    }

    private data class NoteRow(val id: Long, val note: String)

    private fun findRmNoteRow(context: Context, rawId: Long): NoteRow? {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.CommonDataKinds.Note.NOTE),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    NoteRow(cursor.getLong(0), cursor.getString(1).orEmpty())
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun removeRmCloudSyncMembership(context: Context, phone: String): Boolean {
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, phone)
        if (rawId <= 0L) return true
        val groupId = findVisibleGroup(context, CrmContactAccountStore.ACCOUNT_NAME, CallReportContactIntegration.ACCOUNT_TYPE, CLOUD_SYNC_GROUP_NAME)
        if (groupId <= 0L) return true
        return deleteGroupMembership(context, rawId, groupId)
    }

    private fun deleteRmLayer(context: Context, phone: String): Boolean {
        if (!RmContactPermissions.canReadAndWriteContacts(context)) return false
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, phone)
        if (rawId <= 0L) return true
        val deleted = runCatching {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID}=?",
                arrayOf(rawId.toString()),
            )
        }.getOrDefault(0)
        return deleted > 0 || CrmContactAccountStore.findCallReportRawContactId(context, phone) <= 0L
    }

    private fun applyVisibleCloudSyncLabels(context: Context, phone: String): Boolean {
        val targets = findVisibleRawContacts(context, phone)
        if (targets.isEmpty()) return true
        return targets.all { target ->
            val groupId = ensureVisibleGroup(context, target.accountName, target.accountType, CLOUD_SYNC_GROUP_NAME)
            if (groupId <= 0L) return@all false
            upsertGroupMembership(context, target.rawId, groupId)
        }
    }

    private fun clearVisibleCloudSyncLabels(context: Context, targets: List<VisibleRawContact>): Boolean {
        if (targets.isEmpty()) return true
        return targets.all { target ->
            val groupId = findVisibleGroup(context, target.accountName, target.accountType, CLOUD_SYNC_GROUP_NAME)
            if (groupId <= 0L) return@all true
            deleteGroupMembership(context, target.rawId, groupId)
        }
    }

    private data class VisibleRawContact(
        val rawId: Long,
        val accountName: String?,
        val accountType: String?,
    )

    private fun findVisibleRawContacts(context: Context, phone: String): List<VisibleRawContact> {
        val contactId = runCatching {
            context.contentResolver.query(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phone).build(),
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        if (contactId <= 0L) return emptyList()

        return runCatching {
            val rows = mutableListOf<VisibleRawContact>()
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.ACCOUNT_NAME,
                    ContactsContract.RawContacts.ACCOUNT_TYPE,
                ),
                "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0 AND (${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_TYPE}!=?)",
                arrayOf(contactId.toString(), CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
                val typeIndex = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
                while (cursor.moveToNext()) {
                    rows.add(
                        VisibleRawContact(
                            rawId = cursor.getLong(idIndex),
                            accountName = cursor.getString(nameIndex),
                            accountType = cursor.getString(typeIndex),
                        )
                    )
                }
            }
            rows
        }.getOrDefault(emptyList())
    }

    private fun findVisibleGroup(context: Context, accountName: String?, accountType: String?, title: String): Long {
        val (selection, args) = accountGroupSelection(accountName, accountType, title)
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID),
                selection,
                args,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    private fun ensureVisibleGroup(context: Context, accountName: String?, accountType: String?, title: String): Long {
        val existing = findVisibleGroup(context, accountName, accountType, title)
        if (existing > 0L) return existing
        return runCatching {
            val values = ContentValues().apply {
                if (accountName != null) put(ContactsContract.Groups.ACCOUNT_NAME, accountName)
                if (accountType != null) put(ContactsContract.Groups.ACCOUNT_TYPE, accountType)
                put(ContactsContract.Groups.TITLE, title)
                put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                put(ContactsContract.Groups.SHOULD_SYNC, 1)
            }
            val uri = context.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, values)
            uri?.lastPathSegment?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
    }

    private fun accountGroupSelection(accountName: String?, accountType: String?, title: String): Pair<String, Array<String>> {
        val deleted = "${ContactsContract.Groups.DELETED}=0"
        val titleSelection = "${ContactsContract.Groups.TITLE}=?"
        return if (accountName == null || accountType == null) {
            "${ContactsContract.Groups.ACCOUNT_NAME} IS NULL AND ${ContactsContract.Groups.ACCOUNT_TYPE} IS NULL AND $titleSelection AND $deleted" to arrayOf(title)
        } else {
            "${ContactsContract.Groups.ACCOUNT_NAME}=? AND ${ContactsContract.Groups.ACCOUNT_TYPE}=? AND $titleSelection AND $deleted" to arrayOf(accountName, accountType, title)
        }
    }

    private fun upsertGroupMembership(context: Context, rawId: Long, groupId: Long): Boolean {
        val existing = findGroupMembershipRowId(context, rawId, groupId)
        val op = if (existing > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existing.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
        }
            .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
            .build()
        return runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op)) }.isSuccess
    }

    private fun deleteGroupMembership(context: Context, rawId: Long, groupId: Long): Boolean {
        val existing = findGroupMembershipRowId(context, rawId, groupId)
        if (existing <= 0L) return true
        val op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existing.toString()))
            .build()
        return runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op)) }.isSuccess
    }

    private fun findGroupMembershipRowId(context: Context, rawId: Long, groupId: Long): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }
}
