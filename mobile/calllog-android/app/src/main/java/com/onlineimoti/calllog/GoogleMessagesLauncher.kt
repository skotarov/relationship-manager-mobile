package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri

/** Opens Google Messages for one recipient without routing back to this app's default-SMS handler. */
internal object GoogleMessagesLauncher {
    private const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"

    fun openConversation(activity: Activity, phone: String): Boolean {
        val normalizedPhone = PhoneNormalizer.normalize(phone).ifBlank { phone.trim() }
        if (normalizedPhone.isBlank() || activity.isFinishing || activity.isDestroyed) return false

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.fromParts("smsto", normalizedPhone, null)
            setPackage(GOOGLE_MESSAGES_PACKAGE)
        }
        return runCatching { activity.startActivity(intent) }.isSuccess
    }
}
