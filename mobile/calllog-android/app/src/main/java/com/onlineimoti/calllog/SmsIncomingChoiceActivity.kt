package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives the tap from a new-SMS notification while Call Report is the default SMS app.
 * Opens Home with the sender filter, where calls and SMS are displayed in one chronological list.
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
            Intent(this, HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_PHONE_FILTER, phone)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
