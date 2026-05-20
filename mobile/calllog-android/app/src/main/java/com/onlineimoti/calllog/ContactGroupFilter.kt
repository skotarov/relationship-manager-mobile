package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactGroupFilter {
    fun shouldNotify(context: Context, phoneNumber: String, config: AppConfig): Boolean {
        val contact = findContact(context, phoneNumber) ?: return true
        val allowedGroups = parseAllowedGroups(config.contactGroups)
        if (allowedGroups.isEmpty()) {
            return false
        }
        return contact.groups.any { normalizeGroupName(it) in allowedGroups }
    }

    fun resolveDisplayName(context: Context, phoneNumber: String): String? {
        return findContact(context, phoneNumber)?.displayName?.takeIf { it.isNotBlank() }
    }

    private fun findContact(context: Context, phoneNumber: String): ContactRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val resolver = context.contentResolver
        val contact = resolver.query(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(phoneNumber)
                .build(),
            arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContactRecord(
                    displayName = cursor.getString(1).orEmpty(),
                    groups = emptyList(),
                    contactId = cursor.getLong(0),
                )
            } else {
                null
            }
        } ?: return null

        val groupIds = mutableListOf<Long>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(
                contact.contactId.toString(),
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
            ),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                groupIds += cursor.getLong(0)
            }
        }

        if (groupIds.isEmpty()) {
            return contact
        }

        val placeholders = groupIds.joinToString(",") { "?" }
        val groups = mutableListOf<String>()
        resolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups._ID} IN ($placeholders)",
            groupIds.map { it.toString() }.toTypedArray(),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                groups += cursor.getString(0).orEmpty()
            }
        }

        return contact.copy(groups = groups)
    }

    private fun parseAllowedGroups(rawGroups: String): Set<String> {
        return rawGroups.split(',', '\n', ';')
            .map { normalizeGroupName(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun normalizeGroupName(value: String): String {
        return value.trim().lowercase()
    }

    private data class ContactRecord(
        val displayName: String,
        val groups: List<String>,
        val contactId: Long,
    )
}
