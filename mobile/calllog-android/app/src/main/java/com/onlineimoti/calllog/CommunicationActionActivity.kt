package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Default-SMS conversation handler. Opening an SMS target goes straight to the
 * contact history screen for that number instead of the filtered Call Log home screen.
 */
class CommunicationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        openSmsContactHistory(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSmsContactHistory(intent)
    }

    private fun openSmsContactHistory(sourceIntent: Intent?) {
        val uri = sourceIntent?.data
        val rawPhone = Uri.decode(uri?.schemeSpecificPart.orEmpty().substringBefore('?')).trim()
        val phone = PhoneNormalizer.normalize(rawPhone).ifBlank { rawPhone }
        if (phone.isBlank()) {
            finish()
            return
        }

        val title = ContactGroupFilter.resolveDisplayName(this, phone).orEmpty().ifBlank { phone }
        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, title),
        )
        finish()
    }
}
