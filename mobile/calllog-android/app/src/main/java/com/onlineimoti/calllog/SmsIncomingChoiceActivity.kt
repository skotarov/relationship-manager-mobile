package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the tap from a new-SMS notification while Call Report is the default SMS app.
 * The old decision screen is intentionally skipped: the sender's CRM history opens at once.
 */
class SmsIncomingChoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openSenderHistory()
    }

    private fun openSenderHistory() {
        val rawPhone = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val phone = PhoneNormalizer.normalize(rawPhone).ifBlank { rawPhone.trim() }
        if (phone.isBlank()) {
            finish()
            return
        }
        val title = RmRealContactLookup.resolveDisplayName(this, phone).orEmpty().ifBlank { phone }
        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, title),
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
