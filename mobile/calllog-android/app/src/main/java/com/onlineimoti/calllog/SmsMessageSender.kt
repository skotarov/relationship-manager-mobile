package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/** Sends an SMS and stores the sent row in the system SMS provider for the filtered CRM timeline. */
internal object SmsMessageSender {
    fun send(context: Context, rawPhone: String, rawBody: String): Result<Unit> = runCatching {
        val phone = PhoneNormalizer.normalize(rawPhone).ifBlank { rawPhone.trim() }
        val body = rawBody.trim()
        require(phone.isNotBlank()) { "Липсва телефонен номер." }
        require(body.isNotBlank()) { "Напиши съобщение." }
        require(SmsRoleController.isDefaultSmsApp(context)) {
            "Първо избери Call Report като SMS приложение."
        }
        require(
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED,
        ) {
            "Липсва разрешение за изпращане на SMS."
        }

        val manager = SmsManager.getDefault()
        val parts = manager.divideMessage(body)
        if (parts.size > 1) {
            manager.sendMultipartTextMessage(phone, null, parts, null, null)
        } else {
            manager.sendTextMessage(phone, null, body, null, null)
        }
        saveToSystemSentMessages(context, phone, body)
    }

    private fun saveToSystemSentMessages(context: Context, phone: String, body: String) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, phone)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, now)
            put(Telephony.Sms.DATE_SENT, now)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            ?: error("SMS беше подаден, но не успях да го добавя в историята.")
    }
}
