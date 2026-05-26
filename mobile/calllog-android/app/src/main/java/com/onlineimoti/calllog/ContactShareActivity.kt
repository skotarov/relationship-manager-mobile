package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader

class ContactShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSharedContact(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleSharedContact(intent)
    }

    private fun handleSharedContact(sourceIntent: Intent?) {
        val sharedText = readSharedText(sourceIntent)
        val phone = extractPhone(sharedText)
        val title = extractName(sharedText).ifBlank { phone }

        if (phone.isBlank()) {
            Toast.makeText(this, "Не намерих телефон в споделения контакт", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, "Отварям историята в Call Report", Toast.LENGTH_SHORT).show()

        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, title)
        )
        finish()
    }

    private fun readSharedText(sourceIntent: Intent?): String {
        if (sourceIntent == null) return ""
        val parts = mutableListOf<String>()
        sourceIntent.getStringExtra(Intent.EXTRA_TEXT)?.let { parts.add(it) }
        sourceIntent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { parts.add(it) }

        val singleStream = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            sourceIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            sourceIntent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        singleStream?.let { uri -> readUriText(uri).takeIf { it.isNotBlank() }?.let { parts.add(it) } }

        if (sourceIntent.action == Intent.ACTION_SEND_MULTIPLE) {
            val streams = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                sourceIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                sourceIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            streams.orEmpty().forEach { uri -> readUriText(uri).takeIf { it.isNotBlank() }?.let { parts.add(it) } }
        }

        return parts.joinToString("\n")
    }

    private fun readUriText(uri: Uri): String {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).use { it.readText() }
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun extractPhone(text: String): String {
        if (text.isBlank()) return ""
        val telLinePhone = text.lineSequence()
            .firstNotNullOfOrNull { line ->
                val normalizedLine = line.trim()
                if (!normalizedLine.contains("TEL", ignoreCase = true)) return@firstNotNullOfOrNull null
                normalizedLine.substringAfter(':', normalizedLine).takeIf { it.any(Char::isDigit) }
            }
        val candidate = telLinePhone ?: Regex("(?:\\+?\\d[\\d\\s()./-]{6,}\\d)").find(text)?.value.orEmpty()
        return PhoneNormalizer.normalize(candidate)
    }

    private fun extractName(text: String): String {
        if (text.isBlank()) return ""
        val fullName = text.lineSequence()
            .firstOrNull { it.trim().startsWith("FN", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
        if (fullName.isNotBlank()) return fullName

        return text.lineSequence()
            .firstOrNull { it.trim().startsWith("N", ignoreCase = true) }
            ?.substringAfter(':')
            ?.split(';')
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { it.trim() }
            .orEmpty()
    }
}
