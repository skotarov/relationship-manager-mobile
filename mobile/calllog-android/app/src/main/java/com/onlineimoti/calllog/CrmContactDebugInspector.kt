package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object CrmContactDebugInspector {
    fun inspect(context: Context, inputPhone: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Няма READ_CONTACTS permission."
        }

        val normalizedPhone = PhoneNormalizer.normalize(inputPhone)
        if (normalizedPhone.isBlank()) return "Въведи телефон в Test phone."

        val real = findRealContact(context, normalizedPhone)
        val ours = findCallReportRecord(context, normalizedPhone)

        val nameMatch = real?.name?.trim().orEmpty().isNotBlank() &&
            ours?.name?.trim().orEmpty().isNotBlank() &&
            real?.name?.trim() == ours?.name?.trim()
        val phoneMatch = real?.visiblePhone?.let { PhoneNormalizer.normalize(it) } == ours?.visiblePhone?.let { PhoneNormalizer.normalize(it) }
        val exactVisiblePhoneMatch = real?.visiblePhone?.trim().orEmpty().isNotBlank() &&
            ours?.visiblePhone?.trim().orEmpty().isNotBlank() &&
            real?.visiblePhone?.trim() == ours?.visiblePhone?.trim()

        return buildString {
            appendLine("Репер телефон: $inputPhone")
            appendLine("Нормализиран: $normalizedPhone")
            appendLine()
            appendLine("Реален контакт:")
            if (real == null) {
                appendLine("- не е намерен")
            } else {
                appendLine("- rawId: ${real.rawContactId}")
                appendLine("- име: ${real.name.ifBlank { "(празно)" }}")
                appendLine("- телефон: ${real.visiblePhone.ifBlank { "(празно)" }}")
            }
            appendLine()
            appendLine("Наш Call Report запис:")
            if (ours == null) {
                appendLine("- не е намерен")
            } else {
                appendLine("- rawId: ${ours.rawContactId}")
                appendLine("- име: ${ours.name.ifBlank { "(празно)" }}")
                appendLine("- видим телефон: ${ours.visiblePhone.ifBlank { "(празно)" }}")
                appendLine("- SYNC1: ${ours.sync1.ifBlank { "(празно)" }}")
            }
            appendLine()
            appendLine("Проверка:")
            appendLine("- име 1:1: ${if (nameMatch) "ДА" else "НЕ"}")
            appendLine("- телефон логически: ${if (phoneMatch) "ДА" else "НЕ"}")
            appendLine("- телефон видимо 1:1: ${if (exactVisiblePhoneMatch) "ДА" else "НЕ"}")
        }
    }

    private fun findRealContact(context: Context, normalizedPhone: String): ContactDebugRecord? {
        val projection = arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}!=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val phone = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (PhoneNormalizer.normalize(phone) != normalizedPhone) continue
                    val rawId = cursor.getLongOrZero(ContactsContract.Data.RAW_CONTACT_ID)
                    val fallbackName = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    return@use ContactDebugRecord(
                        rawContactId = rawId,
                        name = structuredName(context, rawId, fallbackName),
                        visiblePhone = phone,
                    )
                }
                null
            }
        }.getOrNull()
    }

    private fun findCallReportRecord(context: Context, normalizedPhone: String): ContactDebugRecord? {
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, normalizedPhone)
            .takeIf { it > 0L }
            ?: findCallReportRawContactIdByVisiblePhone(context, normalizedPhone)
        if (rawId <= 0L) return null
        return ContactDebugRecord(
            rawContactId = rawId,
            name = structuredName(context, rawId, fallback = ""),
            visiblePhone = callReportVisiblePhone(context, rawId),
            sync1 = rawContactSync1(context, rawId),
        )
    }

    private fun findCallReportRawContactIdByVisiblePhone(context: Context, normalizedPhone: String): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (PhoneNormalizer.normalize(number) == normalizedPhone) return@use cursor.getLongOrZero(ContactsContract.Data.RAW_CONTACT_ID)
                }
                0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun structuredName(context: Context, rawId: Long, fallback: String): String {
        if (rawId <= 0L) return fallback
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use fallback
                listOf(
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME),
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME),
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME),
                ).filter { it.isNotBlank() }.joinToString(" ")
                    .ifBlank { cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME) }
                    .ifBlank { fallback }
            }.orEmpty().ifBlank { fallback }
        }.getOrDefault(fallback)
    }

    private fun callReportVisiblePhone(context: Context, rawId: Long): String {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.LABEL),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                var first = ""
                while (cursor.moveToNext()) {
                    val number = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (first.isBlank()) first = number
                    if (cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.LABEL) == CrmContactAccountStore.ACCOUNT_NAME) return@use number
                }
                first
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun rawContactSync1(context: Context, rawId: Long): String {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.SYNC1),
                "${ContactsContract.RawContacts._ID}=?",
                arrayOf(rawId.toString()),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }.orEmpty()
        }.getOrDefault("")
    }

    private fun android.database.Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0) getString(index).orEmpty() else ""
    }

    private fun android.database.Cursor.getLongOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0) getLong(index) else 0L
    }

    private data class ContactDebugRecord(
        val rawContactId: Long,
        val name: String,
        val visiblePhone: String,
        val sync1: String = "",
    )
}
