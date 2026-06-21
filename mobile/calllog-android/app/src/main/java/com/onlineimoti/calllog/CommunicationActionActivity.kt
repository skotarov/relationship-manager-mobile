package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Default-SMS compose handler. There is intentionally no routing menu: opening an SMS target
 * goes straight to the CRM history for that phone number.
 */
class CommunicationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openSmsHistory(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSmsHistory(intent)
    }

    private fun openSmsHistory(sourceIntent: Intent?) {
        val uri = sourceIntent?.data
        val rawPhone = Uri.decode(uri?.schemeSpecificPart.orEmpty().substringBefore('?')).trim()
        val phone = PhoneNormalizer.normalize(rawPhone).ifBlank { rawPhone }
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
}
