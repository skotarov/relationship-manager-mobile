package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

class PhoneNumberActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePhoneAction(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handlePhoneAction(intent)
    }

    private fun handlePhoneAction(sourceIntent: Intent?) {
        val phone = extractPhone(sourceIntent)
        if (phone.isBlank()) {
            Toast.makeText(this, "Не намерих телефонен номер", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val title = ContactGroupFilter.resolveDisplayName(this, phone).orEmpty().ifBlank { phone }
        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, title)
        )
        finish()
    }

    private fun extractPhone(sourceIntent: Intent?): String {
        if (sourceIntent == null) return ""
        val customDataPhone = CallReportContactIntegration.phoneFromDataUri(this, sourceIntent.data)
        if (customDataPhone.isNotBlank()) return PhoneNormalizer.normalize(customDataPhone)
        searchQueryFromUri(sourceIntent.data)?.let { query ->
            PhoneNormalizer.normalize(query).takeIf { it.isNotBlank() }?.let { return it }
        }
        val candidates = listOfNotNull(
            sourceIntent.data?.schemeSpecificPart,
            sourceIntent.dataString,
            sourceIntent.getStringExtra(Intent.EXTRA_PHONE_NUMBER),
            sourceIntent.getStringExtra("query"),
            sourceIntent.getStringExtra("android.intent.extra.SEARCH_QUERY"),
            sourceIntent.getStringExtra("android.app.SearchManager.query"),
            sourceIntent.getStringExtra(Intent.EXTRA_TEXT),
            sourceIntent.getStringExtra(Intent.EXTRA_SUBJECT),
        )
        val raw = candidates.firstOrNull { it.any(Char::isDigit) }.orEmpty()
        return PhoneNormalizer.normalize(raw)
    }

    private fun searchQueryFromUri(uri: Uri?): String? {
        if (uri == null) return null
        val host = uri.host.orEmpty().lowercase()
        if (!host.endsWith("google.com") && !host.endsWith("google.bg")) return null
        if (!uri.path.orEmpty().startsWith("/search")) return null
        return uri.getQueryParameter("q")
            ?: uri.getQueryParameter("query")
            ?: uri.getQueryParameter("search_query")
    }
}
