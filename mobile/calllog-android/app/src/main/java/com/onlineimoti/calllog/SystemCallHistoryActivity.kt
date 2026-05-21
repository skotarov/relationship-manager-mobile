package com.onlineimoti.calllog

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import android.widget.Toast

class SystemCallHistoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        if (!openSystemCallHistory(phone)) {
            Toast.makeText(this, "Не успях да отворя телефонната история", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun openSystemCallHistory(phone: String): Boolean {
        val defaultDialerPackage = getDefaultDialerPackageName()
        val intents = buildList {
            defaultDialerPackage?.let { packageName ->
                add(callLogByTypeIntent(phone, packageName))
                add(callLogByUriIntent(phone, packageName))
                add(callButtonIntent(phone, packageName))
                if (phone.isNotBlank()) {
                    add(dialIntent(phone, packageName))
                }
            }

            add(callLogByTypeIntent(phone, packageName = null))
            add(callLogByUriIntent(phone, packageName = null))
            add(callButtonIntent(phone, packageName = null))
            if (phone.isNotBlank()) {
                add(dialIntent(phone, packageName = null))
            }
        }

        return intents.any { candidate ->
            runCatching {
                startActivity(candidate)
                true
            }.getOrElse { error ->
                error !is ActivityNotFoundException && error !is SecurityException && false
            }
        }
    }

    private fun getDefaultDialerPackageName(): String? {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        return telecomManager?.defaultDialerPackage?.takeIf { it.isNotBlank() }
    }

    private fun callLogByTypeIntent(phone: String, packageName: String?): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            type = CallLog.Calls.CONTENT_TYPE
            packageName?.let { setPackage(it) }
            putPhoneHints(phone)
        }
    }

    private fun callLogByUriIntent(phone: String, packageName: String?): Intent {
        return Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI).apply {
            packageName?.let { setPackage(it) }
            putPhoneHints(phone)
        }
    }

    private fun callButtonIntent(phone: String, packageName: String?): Intent {
        return Intent(Intent.ACTION_CALL_BUTTON).apply {
            packageName?.let { setPackage(it) }
            putPhoneHints(phone)
        }
    }

    private fun dialIntent(phone: String, packageName: String?): Intent {
        return Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)).apply {
            packageName?.let { setPackage(it) }
            putPhoneHints(phone)
        }
    }

    private fun Intent.putPhoneHints(phone: String) {
        addCategory(Intent.CATEGORY_DEFAULT)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (phone.isBlank()) {
            return
        }
        putExtra(Intent.EXTRA_PHONE_NUMBER, phone)
        putExtra("phone", phone)
        putExtra("number", phone)
        putExtra("query", phone)
        putExtra("search_query", phone)
    }

    companion object {
        const val EXTRA_PHONE = "phone"
    }
}
