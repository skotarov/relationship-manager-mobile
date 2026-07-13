package com.onlineimoti.calllog

import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Required SMS handler activity for Android's default-SMS role. */
class SmsSendToActivity : FontScaledActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSmsTargetAndFinish()
    }

    private fun handleSmsTargetAndFinish() {
        val phone = phoneFromUri(intent.data).ifBlank {
            intent.getStringExtra("address").orEmpty()
        }
        if (ConfigStore.load(this).openSmsIconToHistory && phone.isNotBlank()) {
            openHistoryAndFinish(phone)
            return
        }
        openComposerAndFinish(phone)
    }

    private fun openComposerAndFinish(phone: String) {
        val body = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra("sms_body")
            ?: intent.getStringExtra("android.intent.extra.TEXT")
            ?: ""
        val title = PhoneNormalizer.display(phone).ifBlank { phone }
        SmsComposeDialog(this, ::dp).show(
            phone = phone,
            title = title.ifBlank { getString(R.string.dynamic_sms_new) },
            initialBody = body,
            onDismiss = {
                finish()
                overridePendingTransition(0, 0)
            },
        )
    }

    private fun openHistoryAndFinish(rawPhone: String) {
        val phone = PhoneNormalizer.normalize(rawPhone).ifBlank { rawPhone.trim() }
        val title = ContactGroupFilter.resolveDisplayName(this, phone).orEmpty()
            .ifBlank { PhoneNormalizer.display(phone) }
            .ifBlank { phone }
        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, title),
        )
        finish()
        overridePendingTransition(0, 0)
    }

    private fun phoneFromUri(uri: Uri?): String {
        uri ?: return ""
        return Uri.decode(uri.schemeSpecificPart.orEmpty().substringBefore('?').substringBefore(';')).trim()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
