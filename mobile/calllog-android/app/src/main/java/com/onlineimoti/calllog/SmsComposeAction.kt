package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.widget.Toast

/** Chooses the RM composer or the phone's current default SMS app for a new message. */
internal object SmsComposeAction {
    fun open(activity: Activity, phone: String, title: String, dp: (Int) -> Int) {
        if (phone.isBlank() || activity.isFinishing || activity.isDestroyed) return

        val config = ConfigStore.load(activity)
        if (config.useInternalSmsComposer || isRmDefaultSmsApp(activity)) {
            SmsComposeDialog(activity, dp).show(phone, title)
            return
        }

        if (openDefaultSmsApp(activity, phone)) return

        Toast.makeText(
            activity,
            activity.getString(R.string.sms_default_app_not_found),
            Toast.LENGTH_LONG,
        ).show()
        SmsComposeDialog(activity, dp).show(phone, title)
    }

    private fun isRmDefaultSmsApp(activity: Activity): Boolean {
        return Telephony.Sms.getDefaultSmsPackage(activity) == activity.packageName
    }

    private fun openDefaultSmsApp(activity: Activity, phone: String): Boolean {
        val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(activity).orEmpty().trim()
        if (defaultSmsPackage.isBlank()) return false

        val normalizedPhone = PhoneNormalizer.normalize(phone).ifBlank { phone.trim() }
        if (normalizedPhone.isBlank()) return false

        val composeIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.fromParts("smsto", normalizedPhone, null)
            setPackage(defaultSmsPackage)
        }
        return runCatching { activity.startActivity(composeIntent) }.isSuccess
    }
}
