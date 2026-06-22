package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri

/** Opens Google Messages directly in the new-SMS composer for one recipient. */
internal object GoogleMessagesLauncher {
    private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
    private const val EXTRA_SMS_BODY = "sms_body"

    fun openConversation(activity: Activity, phone: String): Boolean {
        val normalizedPhone = PhoneNormalizer.normalize(phone).ifBlank { phone.trim() }
        if (normalizedPhone.isBlank() || activity.isFinishing || activity.isDestroyed) return false

        val composeIntent = Intent(Intent.ACTION_SEND).apply {
            // ACTION_SEND + smsto recipient makes Google Messages open its compose form,
            // rather than merely bringing its conversation list to the foreground.
            data = Uri.fromParts("smsto", normalizedPhone, null)
            type = "text/plain"
            putExtra(EXTRA_SMS_BODY, "")
            putExtra(Intent.EXTRA_TEXT, "")
            setPackage(GOOGLE_MESSAGES_PACKAGE)
        }

        return runCatching { activity.startActivity(composeIntent) }.isSuccess
    }
}
