package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Bridges Android's default-SMS delivery callback to the existing SMS inbox notification. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
        val phone = messages.firstOrNull()?.originatingAddress.orEmpty().trim()
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }.trim()
        if (phone.isNotBlank() && body.isNotBlank()) {
            SmsIncomingNotifications.show(context.applicationContext, phone, body)
        }
    }
}
