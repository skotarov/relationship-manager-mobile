package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context

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
            if (displayName.isBlank()) {
                removeOrphanRmRecordIfRealContactIsMissing(appContext, phone)
            } else {
                saveWithStablePath(appContext, phone, displayName)
            }
            activity.runOnUiThread {
                setBusy(false)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    rerender()
                }
            }
        }.start()
    }

    private fun saveWithStablePath(context: Context, phone: String, displayName: String): Boolean {
        return CrmContactLinkSaver.save(
            context = context,
            fields = CallReportStableCrmContactWriter.Fields(
                originalPhone = phone,
                displayName = displayName,
                organization = "Relation Management",
                jobTitle = "RM auto",
                groupName = "Relation Management",
                customText = "RM auto link",
            ),
            mode = ConfigStore.load(context).contactLinkMode,
            phone = phone,
            title = displayName,
        )
    }

    private fun removeOrphanRmRecordIfRealContactIsMissing(context: Context, phone: String) {
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return
        val realRawId = RmRealContactLookup.findRawContactId(context, normalizedPhone)
        if (realRawId > 0L) return
        val rm = RmContactReader.findRmRecord(context, normalizedPhone) ?: return
        RmContactWriter.delete(context, rm.rawContactId)
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
