package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog

class SystemCallHistoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        openSystemCallHistory(phone)
        finish()
    }

    private fun openSystemCallHistory(phone: String) {
        val intents = buildList {
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    type = CallLog.Calls.CONTENT_TYPE
                    putPhoneHints(phone)
                }
            )
            add(
                Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI).apply {
                    putPhoneHints(phone)
                }
            )
            if (phone.isNotBlank()) {
                add(
                    Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)).apply {
                        putPhoneHints(phone)
                    }
                )
            }
        }

        val targetIntent = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: return
        startActivity(targetIntent)
    }

    private fun Intent.putPhoneHints(phone: String) {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
