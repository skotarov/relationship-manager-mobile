package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the tap from a new-SMS notification while Relationship Manager is the default SMS app.
 * Opens the sender's embedded Full Log inside History.
 */
class SmsIncomingChoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        openSenderTimeline()
    }

    private fun openSenderTimeline() {
        val rawPhone = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val phone = PhoneNormalizer.normalize(rawPhone).ifBlank { rawPhone.trim() }
        if (phone.isBlank()) {
            finish()
            return
        }
        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, phone)
                .putExtra(ContactNotesActivity.EXTRA_INITIAL_LIST_MODE, ContactNotesActivity.LIST_MODE_FULL_LOG),
        )
        finish()
    }

    companion object {
        const val EXTRA_ADDRESS = "sms_address"
        const val EXTRA_BODY = "sms_body"
        const val EXTRA_TIMESTAMP = "sms_timestamp"
        const val EXTRA_IS_MMS = "sms_is_mms"
    }
}
