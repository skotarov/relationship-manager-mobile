package com.onlineimoti.calllog

import android.content.Context
import android.provider.CallLog

/**
 * Contacts-mode source: every saved phone contact explicitly marked CRM, plus
 * unknown CRM-marked numbers that appeared in the phone call log during the
 * latest two weeks. Unknown numbers are kept only once, at their newest call.
 */
internal object HomeCrmContactCandidates {
    private const val UNKNOWN_CRM_LOOKBACK_MS = 14L * 24L * 60L * 60L * 1_000L

    fun load(context: Context, nowMs: Long = System.currentTimeMillis()): List<PhoneCallRecord> {
        val appContext = context.applicationContext
        val knownContacts = ContactSearchProvider.crmEnabledContacts(appContext)
            .map { contact -> PhoneCallRecord(contact.phone, contact.name, "", 0L, 0L) }
        val knownKeys = knownContacts.mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it.number) }
        val unknownRecent = recentUnknownCrmCalls(appContext, nowMs)
            .filter { call -> HomeCallPageLoader.noteKey(call.number) !in knownKeys }
        return knownContacts + unknownRecent
    }

    private fun recentUnknownCrmCalls(context: Context, nowMs: Long): List<PhoneCallRecord> {
        if (!PhoneCallReader.hasCallLogPermission(context)) return emptyList()
        val enabledKeys = CrmContactSyncStore.enabledPhoneKeys(context)
        if (enabledKeys.isEmpty()) return emptyList()
        val cutoffMs = nowMs - UNKNOWN_CRM_LOOKBACK_MS
        val newestByPhoneKey = linkedMapOf<String, PhoneCallRecord>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
        )
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.DATE}>=?",
                arrayOf(cutoffMs.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                while (cursor.moveToNext()) {
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                    val phoneKey = HomeCallPageLoader.noteKey(number)
                    if (phoneKey.isBlank() || phoneKey !in enabledKeys || phoneKey in newestByPhoneKey) continue
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    val startedAt = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
                    newestByPhoneKey[phoneKey] = PhoneCallRecord(
                        number = number,
                        name = name,
                        direction = "",
                        startedAt = startedAt,
                        durationSeconds = 0L,
                    )
                }
            }
        }
        if (newestByPhoneKey.isEmpty()) return emptyList()

        val kinds = HomeCallPageLoader.crmContactKinds(
            context,
            newestByPhoneKey.values.map { it.number },
        )
        return newestByPhoneKey.values
            .filter { call ->
                kinds[HomeCallPageLoader.noteKey(call.number)] == HomeCrmContactKind.UNKNOWN
            }
            .sortedByDescending { it.startedAt }
    }
}
