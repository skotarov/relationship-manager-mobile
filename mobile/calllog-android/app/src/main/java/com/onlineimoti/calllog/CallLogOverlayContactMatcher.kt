package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object CallLogOverlayContactMatcher {
    fun resolvePhoneAndTitleFromVisibleName(context: Context, texts: List<String>): ResolvedContactTarget {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ResolvedContactTarget()
        }
        texts
            .map { it.trim() }
            .filter { CallLogOverlayTextRules.isValidContactNameCandidateShape(it) }
            .filterNot { CallLogOverlayTextRules.isScreenHintText(it) }
            .filter { CallLogOverlayTextRules.extractPhoneCandidate(it).isNullOrBlank() }
            .distinct()
            .forEach { candidate ->
                val match = contactMatchForVisibleName(context, candidate)
                if (match.phone.isNotBlank()) return match
            }
        return ResolvedContactTarget()
    }

    private fun contactMatchForVisibleName(context: Context, visibleName: String): ResolvedContactTarget {
        val exactRows = contactRows(context, exactName = visibleName)
        val exactMatch = uniquePhoneFromRows(exactRows)
        if (exactMatch.phone.isNotBlank() || exactRows.isNotEmpty()) return exactMatch

        val normalizedVisibleName = CallLogOverlayTextRules.normalizeNameForCompare(visibleName)
        if (normalizedVisibleName.length < 3) return ResolvedContactTarget()
        val visibleTokens = normalizedVisibleName.split(' ').filter { it.length >= 2 }
        val fuzzyRows = contactRows(context, exactName = null).filter { row ->
            val contactName = CallLogOverlayTextRules.normalizeNameForCompare(row.displayName)
            contactName.isNotBlank() && (
                contactName == normalizedVisibleName ||
                    contactName.contains(normalizedVisibleName) ||
                    normalizedVisibleName.contains(contactName) ||
                    (visibleTokens.size >= 2 && visibleTokens.all { token -> contactName.contains(token) }) ||
                    (visibleTokens.size == 1 && visibleTokens.first().length >= 4 && contactName.split(' ').any { it.startsWith(visibleTokens.first()) })
                )
        }
        return uniquePhoneFromRows(fuzzyRows)
    }

    private fun contactRows(context: Context, exactName: String?): List<ContactPhoneRow> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        val selection = exactName?.let {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} = ? OR ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        }
        val args = exactName?.let { arrayOf(it, it) }
        return runCatching {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val primaryNameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val displayNameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val displayName = cursor.getString(primaryNameIndex).orEmpty()
                            .ifBlank { cursor.getString(displayNameIndex).orEmpty() }
                            .trim()
                        val phone = CallLogOverlayTextRules.normalizePhone(cursor.getString(numberIndex).orEmpty())
                        if (displayName.isNotBlank() && phone.isNotBlank()) {
                            add(ContactPhoneRow(id, displayName, phone))
                        }
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun uniquePhoneFromRows(rows: List<ContactPhoneRow>): ResolvedContactTarget {
        val byContact = rows.groupBy { it.contactId }
        if (byContact.size != 1) return ResolvedContactTarget()
        val contactRows = byContact.values.first()
        val phones = contactRows.map { it.phone }.distinctBy { CallLogOverlayTextRules.normalizeForCompare(it) }
        if (phones.size != 1) return ResolvedContactTarget()
        return ResolvedContactTarget(phone = phones.first(), title = contactRows.firstOrNull()?.displayName.orEmpty())
    }

    private data class ContactPhoneRow(
        val contactId: Long,
        val displayName: String,
        val phone: String,
    )
}

internal data class ResolvedContactTarget(
    val phone: String = "",
    val title: String = "",
)
