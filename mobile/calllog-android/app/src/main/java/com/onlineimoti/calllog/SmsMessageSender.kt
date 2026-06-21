package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * Sends an SMS from Call Report while it is the default SMS app.
 * A failed history write must never turn a successfully handed-off SMS into an app crash.
 */
internal object SmsMessageSender {
    data class Outcome(
        val historySaved: Boolean,
    )

    fun send(context: Context, rawPhone: String, rawBody: String): Result<Outcome> = runCatching {
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
            "Липсва разрешение за изпращане на SMS. Отвори Settings → Permissions и разреши SMS."
        }

        val manager = SmsManager.getDefault()
        val parts = manager.divideMessage(body)
        if (parts.size > 1) {
            manager.sendMultipartTextMessage(phone, null, parts, null, null)
        } else {
            manager.sendTextMessage(phone, null, body, null, null)
        }

        Outcome(historySaved = saveToSystemSentMessages(context.applicationContext, phone, body))
    }

    private fun saveToSystemSentMessages(context: Context, phone: String, body: String): Boolean {
        return runCatching {
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
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values) != null
        }.getOrDefault(false)
    }
}
