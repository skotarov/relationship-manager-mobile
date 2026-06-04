package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

internal object RmContactDebugReader {
    fun debugText(context: Context, phone: String, title: String): String {
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return "RM debug: empty phone"
        val real = RmContactReader.findRealContact(context, normalizedPhone, title)
        val rm = RmContactReader.findRmRecord(context, normalizedPhone)
        val expectedTitle = real?.let { RmContactNameResolver.titleFor(it) }.orEmpty()
        val expectedParts = if (expectedTitle.isNotBlank()) RmContactNameResolver.structuredParts(expectedTitle) else RmContactNameParts()
        val current = real != null && rm != null && isCurrent(rm, real, expectedTitle, expectedParts)
        val realContactId = real?.existingRawContactId?.let { contactIdForRaw(context, it) } ?: 0L
        val rmContactId = rm?.rawContactId?.let { contactIdForRaw(context, it) } ?: 0L
        val aggregation = if (real != null && rm != null) aggregationInfo(context, real.existingRawContactId, rm.rawContactId) else "none"
        return buildString {
            appendLine("RM decision: ${decisionText(real, rm, current)}")
            appendLine("expectedName: $expectedTitle")
            appendLine("RM debug")
            appendLine("phone: $phone")
            appendLine("normalized: $normalizedPhone")
            appendLine("realRawId: ${real?.existingRawContactId ?: 0}")
            appendLine("realContactId: $realContactId")
            appendLine("rmRawId: ${rm?.rawContactId ?: 0}")
            appendLine("rmContactId: $rmContactId")
            appendLine("sameContactId: ${realContactId > 0 && realContactId == rmContactId}")
            appendLine("aggregationException: $aggregation")
            appendLine("rmName: ${rm?.displayName.orEmpty()}")
            appendLine("expectedGiven: ${expectedParts.givenName}")
            appendLine("rmGiven: ${rm?.givenName.orEmpty()}")
            appendLine("expectedMiddle: ${expectedParts.middleName}")
            appendLine("rmMiddle: ${rm?.middleName.orEmpty()}")
            appendLine("expectedFamily: ${expectedParts.familyName}")
            appendLine("rmFamily: ${rm?.familyName.orEmpty()}")
            appendLine("rmPhoneRows: ${rm?.normalizedPhones?.joinToString().orEmpty()}")
            appendLine("nameRowId: ${rm?.nameRowId ?: 0}")
            appendLine("phoneRowId: ${rm?.phoneRowId ?: 0}")
            appendLine("historyRowId: ${rm?.historyRowId ?: 0}")
        }.trim()
    }

    private fun decisionText(real: BulkContactCandidate?, rm: RmRecord?, current: Boolean): String {
        return when {
            real != null && rm == null -> "ADD: real contact exists, RM record missing"
            real != null && rm != null && !current -> "UPDATE: RM data differs"
            real == null && rm != null -> "REMOVE_ORPHAN: real contact missing"
            real != null && rm != null -> "OK: no action"
            else -> "SKIP: no real contact and no RM record"
        }
    }

    private fun isCurrent(
        rm: RmRecord,
        real: BulkContactCandidate,
        expectedTitle: String,
        expectedParts: RmContactNameParts,
    ): Boolean {
        return rm.syncPhone == real.phone &&
            rm.normalizedPhones.contains(real.phone) &&
            rm.displayName == expectedTitle &&
            rm.givenName == expectedParts.givenName &&
            rm.middleName == expectedParts.middleName &&
            rm.familyName == expectedParts.familyName &&
            rm.nameRowId > 0L &&
            rm.phoneRowId > 0L &&
            rm.historyRowId > 0L
    }

    private fun contactIdForRaw(context: Context, rawContactId: Long): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts._ID}=?",
                arrayOf(rawContactId.toString()),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLongOrZero(ContactsContract.RawContacts.CONTACT_ID) else 0L }.orZero()
        }.getOrDefault(0L)
    }

    private fun aggregationInfo(context: Context, raw1: Long, raw2: Long): String {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.AggregationExceptions.CONTENT_URI,
                arrayOf(
                    ContactsContract.AggregationExceptions.TYPE,
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID1,
                    ContactsContract.AggregationExceptions.RAW_CONTACT_ID2,
                ),
                "(${ContactsContract.AggregationExceptions.RAW_CONTACT_ID1}=? AND ${ContactsContract.AggregationExceptions.RAW_CONTACT_ID2}=?) OR (${ContactsContract.AggregationExceptions.RAW_CONTACT_ID1}=? AND ${ContactsContract.AggregationExceptions.RAW_CONTACT_ID2}=?)",
                arrayOf(raw1.toString(), raw2.toString(), raw2.toString(), raw1.toString()),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) "none"
                else "type=${cursor.getLongOrZero(ContactsContract.AggregationExceptions.TYPE)}, raw1=${cursor.getLongOrZero(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1)}, raw2=${cursor.getLongOrZero(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2)}"
            }.orEmpty()
        }.getOrDefault("error")
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
