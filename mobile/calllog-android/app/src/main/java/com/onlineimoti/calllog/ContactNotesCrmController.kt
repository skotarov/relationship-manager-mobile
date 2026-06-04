package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

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
        rememberStatus(phone, "START", "title=${getTitle()}")
        Thread {
            if (displayName.isBlank()) {
                handleUnknownNumber(appContext, phone)
            } else {
                val saved = saveWithStablePath(appContext, phone, displayName)
                rememberStatus(phone, if (saved) "SAVE_DONE" else "SAVE_FAILED", "name=$displayName")
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

    private fun handleUnknownNumber(context: Context, phone: String) {
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) {
            rememberStatus(phone, "SKIP_UNKNOWN", "empty phone")
            return
        }
        val realRawId = RmRealContactLookup.findRawContactId(context, normalizedPhone)
        if (realRawId > 0L) {
            rememberStatus(phone, "SKIP_UNKNOWN", "realRaw=$realRawId")
            return
        }
        val rm = RmContactReader.findRmRecord(context, normalizedPhone)
        if (rm == null) {
            rememberStatus(phone, "SKIP_UNKNOWN", "no rm")
            return
        }
        val affected = RmContactWriter.delete(context, rm.rawContactId)
        rememberStatus(phone, "CLEAN_RM_DONE", "rmRaw=${rm.rawContactId}, affected=$affected")
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

    companion object {
        private val lastStatusByPhone = ConcurrentHashMap<String, String>()

        fun lastStatusFor(phone: String): String {
            val key = PhoneNormalizer.normalize(phone)
            return if (key.isBlank()) "" else lastStatusByPhone[key].orEmpty()
        }

        private fun rememberStatus(phone: String, action: String, detail: String) {
            val key = PhoneNormalizer.normalize(phone)
            if (key.isBlank()) return
            val tick = (System.currentTimeMillis() / 1000L) % 86400L
            lastStatusByPhone[key] = "$tick | $action | $detail"
        }
    }
}
