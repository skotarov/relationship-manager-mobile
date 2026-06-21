package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Default-SMS compose handler. Opening an SMS target goes straight to Home filtered by that
 * number, where calls and SMS are shown together in chronological order.
 */
class CommunicationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openSmsTimeline(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSmsTimeline(intent)
    }

    private fun openSmsTimeline(sourceIntent: Intent?) {
        val uri = sourceIntent?.data
        val rawPhone = Uri.decode(uri?.schemeSpecificPart.orEmpty().substringBefore('?')).trim()
        val phone = PhoneNormalizer.normalize(rawPhone).ifBlank { rawPhone }
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
}
