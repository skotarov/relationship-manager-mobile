package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract

/** Handles the visible Contacts labels used by Cloud Sync. */
internal object RmCloudSyncLabelStore {
    const val GROUP_NAME = "Cloud Sync"

    fun applyToVisibleContacts(context: Context, phone: String): Boolean {
        val targets = findVisibleRawContacts(context, phone)
        return targets.all { target ->
            val groupId = ensureGroup(context, target.accountName, target.accountType, GROUP_NAME)
            groupId > 0L && upsertGroupMembership(context, target.rawId, groupId)
        }
    }

    fun removeFromRmAndVisibleContacts(context: Context, phone: String): Boolean {
        val rmRemoved = removeFromRmLayer(context, phone)
        val visibleRemoved = findVisibleRawContacts(context, phone).all { target ->
            val groupId = findGroupId(context, target.accountName, target.accountType, GROUP_NAME)
            groupId <= 0L || deleteGroupMembership(context, target.rawId, groupId)
        }
        return rmRemoved && visibleRemoved
    }

    private fun removeFromRmLayer(context: Context, phone: String): Boolean {
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, phone)
        if (rawId <= 0L) return true
        val groupId = findGroupId(
            context = context,
            accountName = CrmContactAccountStore.ACCOUNT_NAME,
            accountType = CallReportContactIntegration.ACCOUNT_TYPE,
            title = GROUP_NAME,
        )
        return groupId <= 0L || deleteGroupMembership(context, rawId, groupId)
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
            buildList {
                context.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.RawContacts._ID,
                        ContactsContract.RawContacts.ACCOUNT_NAME,
                        ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ),
                    "${ContactsContract.RawContacts.CONTACT_ID}=? AND " +
                        "${ContactsContract.RawContacts.DELETED}=0 AND " +
                        "(${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR " +
                        "${ContactsContract.RawContacts.ACCOUNT_TYPE}!=?)",
                    arrayOf(contactId.toString(), CallReportContactIntegration.ACCOUNT_TYPE),
                    null,
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
                    val nameIndex = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
                    val typeIndex = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    while (cursor.moveToNext()) {
                        add(
                            VisibleRawContact(
                                rawId = cursor.getLong(idIndex),
                                accountName = cursor.getString(nameIndex),
                                accountType = cursor.getString(typeIndex),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun findGroupId(context: Context, accountName: String?, accountType: String?, title: String): Long {
        val (selection, args) = groupSelection(accountName, accountType, title)
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

    private fun ensureGroup(context: Context, accountName: String?, accountType: String?, title: String): Long {
        val existingId = findGroupId(context, accountName, accountType, title)
        if (existingId > 0L) return existingId

        return runCatching {
            val values = ContentValues().apply {
                if (accountName != null) put(ContactsContract.Groups.ACCOUNT_NAME, accountName)
                if (accountType != null) put(ContactsContract.Groups.ACCOUNT_TYPE, accountType)
                put(ContactsContract.Groups.TITLE, title)
                put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                put(ContactsContract.Groups.SHOULD_SYNC, 1)
            }
            context.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, values)
                ?.lastPathSegment
                ?.toLongOrNull()
                ?: 0L
        }.getOrDefault(0L)
    }

    private fun groupSelection(accountName: String?, accountType: String?, title: String): Pair<String, Array<String>> {
        val titleSelection = "${ContactsContract.Groups.TITLE}=? AND ${ContactsContract.Groups.DELETED}=0"
        return if (accountName == null || accountType == null) {
            "${ContactsContract.Groups.ACCOUNT_NAME} IS NULL AND " +
                "${ContactsContract.Groups.ACCOUNT_TYPE} IS NULL AND $titleSelection" to arrayOf(title)
        } else {
            "${ContactsContract.Groups.ACCOUNT_NAME}=? AND " +
                "${ContactsContract.Groups.ACCOUNT_TYPE}=? AND $titleSelection" to arrayOf(accountName, accountType, title)
        }
    }

    private fun upsertGroupMembership(context: Context, rawId: Long, groupId: Long): Boolean {
        val existingId = findGroupMembershipRowId(context, rawId, groupId)
        val operation = if (existingId > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existingId.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
        }
            .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
            .build()
        return runCatching {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(operation))
        }.isSuccess
    }

    private fun deleteGroupMembership(context: Context, rawId: Long, groupId: Long): Boolean {
        val existingId = findGroupMembershipRowId(context, rawId, groupId)
        if (existingId <= 0L) return true
        val operation = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data._ID}=?", arrayOf(existingId.toString()))
            .build()
        return runCatching {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(operation))
        }.isSuccess
    }

    private fun findGroupMembershipRowId(context: Context, rawId: Long, groupId: Long): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND " +
                    "${ContactsContract.Data.MIMETYPE}=? AND " +
                    "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID}=?",
                arrayOf(
                    rawId.toString(),
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
                    groupId.toString(),
                ),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }
}
