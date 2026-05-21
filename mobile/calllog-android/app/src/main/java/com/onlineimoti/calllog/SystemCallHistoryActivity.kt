package com.onlineimoti.calllog

import android.app.Activity
import android.app.SearchManager
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
        val opened = if (mode == MODE_NUMBER) {
            openNumberHistoryInGooglePhone(phone)
        } else {
            openSystemCallHistory(phone)
        }
        if (!opened) {
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

        return startFirstWorking(intents)
    }

    private fun openNumberHistoryInGooglePhone(phone: String): Boolean {
        if (phone.isBlank()) {
            return openSystemCallHistory(phone)
        }

        val googlePhoneIntents = numberHistoryIntents(phone, GOOGLE_PHONE_PACKAGE) +
            googleDialerActivityIntents(phone)
        if (startFirstWorking(googlePhoneIntents)) {
            return true
        }

        Toast.makeText(this, "Google Phone не отвори история за номера — пробвам резервен вариант", Toast.LENGTH_SHORT).show()

        val fallbackPackages = buildList {
            add("com.google.android.contacts")
            add("com.android.dialer")
            add("com.android.contacts")
            getDefaultDialerPackageName()?.let { add(it) }
        }.distinct().filterNot { it == GOOGLE_PHONE_PACKAGE }

        val fallbackIntents = fallbackPackages.flatMap { packageName ->
            numberHistoryIntents(phone, packageName)
        } + numberHistoryIntents(phone, packageName = null)

        return startFirstWorking(fallbackIntents)
    }

    private fun googleDialerActivityIntents(phone: String): List<Intent> {
        return listOf(
            Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(CallLog.Calls.CONTENT_FILTER_URI, phone)).apply {
                setClassName(GOOGLE_PHONE_PACKAGE, "com.google.android.dialer.extensions.GoogleDialtactsActivity")
                putPhoneHints(phone)
            },
            Intent(Intent.ACTION_SEARCH).apply {
                setClassName(GOOGLE_PHONE_PACKAGE, "com.google.android.dialer.extensions.GoogleDialtactsActivity")
                putExtra(SearchManager.QUERY, phone)
                putPhoneHints(phone)
            },
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)).apply {
                setClassName(GOOGLE_PHONE_PACKAGE, "com.google.android.dialer.extensions.GoogleDialtactsActivity")
                putPhoneHints(phone)
            },
        )
    }

    private fun numberHistoryIntents(phone: String, packageName: String?): List<Intent> {
        val encodedPhone = Uri.encode(phone)
        return buildList {
            add(
                Intent(Intent.ACTION_VIEW, Uri.withAppendedPath(CallLog.Calls.CONTENT_FILTER_URI, phone)).apply {
                    packageName?.let { setPackage(it) }
                    putPhoneHints(phone)
                }
            )
            add(
                Intent(Intent.ACTION_VIEW, Uri.parse("content://call_log/calls/filter/$encodedPhone")).apply {
                    packageName?.let { setPackage(it) }
                    putPhoneHints(phone)
                }
            )
            add(
                Intent(Intent.ACTION_SEARCH).apply {
                    packageName?.let { setPackage(it) }
                    putExtra(SearchManager.QUERY, phone)
                    putPhoneHints(phone)
                }
            )
            add(
                Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI.buildUpon()
                    .appendQueryParameter("number", phone)
                    .appendQueryParameter("phone", phone)
                    .appendQueryParameter("query", phone)
                    .build()
                ).apply {
                    packageName?.let { setPackage(it) }
                    putPhoneHints(phone)
                }
            )
            add(
                Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT, Uri.fromParts("tel", phone, null)).apply {
                    packageName?.let { setPackage(it) }
                    putPhoneHints(phone)
                }
            )
            add(dialIntent(phone, packageName))
        }
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
        putExtra("EXTRA_PHONE_NUMBER", phone)
        putExtra("EXTRA_CALL_LOG_FILTER", phone)
    }

    companion object {
        const val EXTRA_PHONE = "phone"
        const val EXTRA_MODE = "mode"
        const val MODE_GENERAL = "general"
        const val MODE_NUMBER = "number"
        private const val GOOGLE_PHONE_PACKAGE = "com.google.android.dialer"
    }
}
