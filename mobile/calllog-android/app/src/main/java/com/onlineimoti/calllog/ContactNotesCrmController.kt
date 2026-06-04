package com.onlineimoti.calllog

import android.app.Activity

class ContactNotesCrmController(
    private val activity: Activity,
    private val getPhone: () -> String,
    private val getTitle: () -> String,
    private val setBusy: (Boolean) -> Unit,
    private val rerender: () -> Unit,
) {
    fun reconcileCurrentPhone() {
        val phone = getPhone()
        if (phone.isBlank()) return
        setBusy(true)
        rerender()
        val appContext = activity.applicationContext
        val displayName = cleanDisplayName(getTitle(), phone)
        Thread {
            RmContactReconciler.reconcileOne(appContext, phone, displayName)
            activity.runOnUiThread {
                setBusy(false)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    rerender()
                }
            }
        }.start()
    }

    private fun cleanDisplayName(title: String, phone: String): String {
        var value = title.trim()
        if (value.isBlank() || value == phone) return ""
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (PhoneNormalizer.normalize(value) == normalizedPhone) return ""
        val leadingPhone = Regex("^[+\\d][\\d\\s().-]{5,}").find(value)?.value.orEmpty()
        if (leadingPhone.isNotBlank() && PhoneNormalizer.normalize(leadingPhone) == normalizedPhone) {
            value = value.removePrefix(leadingPhone).trim()
        }
        return value
    }
}
