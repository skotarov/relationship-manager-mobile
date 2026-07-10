package com.onlineimoti.calllog

import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Required SMS handler activity for Android's default-SMS role. */
class SmsSendToActivity : FontScaledActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openComposerAndFinish()
    }

    private fun openComposerAndFinish() {
        val phone = phoneFromUri(intent.data).ifBlank {
            intent.getStringExtra("address").orEmpty()
        }
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

    private fun phoneFromUri(uri: Uri?): String {
        uri ?: return ""
        return uri.schemeSpecificPart
            .substringBefore('?')
            .substringBefore(';')
            .trim()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
