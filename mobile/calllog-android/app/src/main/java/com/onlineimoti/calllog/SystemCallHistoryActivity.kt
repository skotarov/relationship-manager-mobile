package com.onlineimoti.calllog

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.widget.Toast

class SystemCallHistoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        val mode = intent.getStringExtra(EXTRA_MODE).orEmpty()
        val opened = when (mode) {
            MODE_NUMBER -> openContactForPhone(phone)
            MODE_SEARCH_DEFAULT -> openContactForPhone(phone)
            MODE_SEARCH_GOOGLE -> openContactForPhone(phone)
            else -> openSystemCallHistory(phone)
        }
        if (!opened) {
            Toast.makeText(this, "Не успях да отворя контакта/историята", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun openSystemCallHistory(phone: String): Boolean {
        val defaultDialerPackage = getDefaultDialerPackageName()
        val intents = buildList {
            defaultDialerPackage?.let { packageName ->
                add(callLogByTypeIntent(packageName))
                add(callLogByUriIntent(packageName))
                add(callButtonIntent(phone, packageName))
            }
            add(callLogByTypeIntent(packageName = null))
            add(callLogByUriIntent(packageName = null))
            add(callButtonIntent(phone, packageName = null))
        }

        return startFirstWorking(intents)
    }

    private fun openContactForPhone(phone: String): Boolean {
        if (phone.isBlank()) {
            return openSystemCallHistory(phone)
        }

        val contactUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
        val telUri = Uri.fromParts("tel", phone, null)

        val intents = listOf(
            Intent(Intent.ACTION_VIEW, contactUri).apply { putCommonFlags() },
            Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT, telUri).apply { putCommonFlags() },
            Intent(Intent.ACTION_VIEW, telUri).apply { putCommonFlags() },
            dialIntent(phone, packageName = null),
        )

        return startFirstWorking(intents)
    }

    private fun startFirstWorking(intents: List<Intent>): Boolean {
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

    private fun callLogByTypeIntent(packageName: String?): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            type = CallLog.Calls.CONTENT_TYPE
            packageName?.let { setPackage(it) }
            putCommonFlags()
        }
    }

    private fun callLogByUriIntent(packageName: String?): Intent {
        return Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI).apply {
            packageName?.let { setPackage(it) }
            putCommonFlags()
        }
    }

    private fun callButtonIntent(phone: String, packageName: String?): Intent {
        return Intent(Intent.ACTION_CALL_BUTTON).apply {
            packageName?.let { setPackage(it) }
            putCommonFlags()
            putPhoneHints(phone)
        }
    }

    private fun dialIntent(phone: String, packageName: String?): Intent {
        return Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)).apply {
            packageName?.let { setPackage(it) }
            putCommonFlags()
            putPhoneHints(phone)
        }
    }

    private fun Intent.putCommonFlags() {
        addCategory(Intent.CATEGORY_DEFAULT)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private fun Intent.putPhoneHints(phone: String) {
        if (phone.isBlank()) {
            return
        }
        putExtra(Intent.EXTRA_PHONE_NUMBER, phone)
        putExtra("phone", phone)
        putExtra("number", phone)
    }

    companion object {
        const val EXTRA_PHONE = "phone"
        const val EXTRA_MODE = "mode"
        const val MODE_GENERAL = "general"
        const val MODE_NUMBER = "number"
        const val MODE_SEARCH_DEFAULT = "search_default"
        const val MODE_SEARCH_GOOGLE = "search_google"
    }
}
