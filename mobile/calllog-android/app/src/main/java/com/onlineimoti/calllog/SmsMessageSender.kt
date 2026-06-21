package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * Sends an SMS from Call Report while it is the default SMS app.
 * All expected device/SIM/provider failures are returned to the composer as an error message.
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

        val manager = smsManagerForDefaultSubscription()
        val parts = manager.divideMessage(body)
        require(parts.isNotEmpty()) { "Не успях да подготвя текста за изпращане." }
        if (parts.size > 1) {
            manager.sendMultipartTextMessage(phone, null, parts, null, null)
        } else {
            manager.sendTextMessage(phone, null, body, null, null)
        }

        Outcome(historySaved = saveToSystemSentMessages(context.applicationContext, phone, body))
    }

    private fun smsManagerForDefaultSubscription(): SmsManager {
        return runCatching {
            val subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }.getOrElse {
            SmsManager.getDefault()
        }
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
