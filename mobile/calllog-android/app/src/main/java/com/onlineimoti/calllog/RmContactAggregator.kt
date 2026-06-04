package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

internal object RmContactAggregator {
    fun keepTogetherByPhone(context: Context, phone: String): Boolean {
        val realRawId = RmRealContactLookup.findRawContactId(context, phone)
        val rmRawId = CrmContactAccountStore.findCallReportRawContactId(context, PhoneNormalizer.normalize(phone))
        return keepTogether(context, realRawId, rmRawId)
    }

    fun keepTogether(context: Context, realRawId: Long, rmRawId: Long): Boolean {
        if (realRawId <= 0L || rmRawId <= 0L || realRawId == rmRawId) return false
        val op = ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
            .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
            .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, realRawId)
            .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rmRawId)
            .build()
        return runCatching {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, arrayListOf(op))
            true
        }.getOrDefault(false)
    }
}
