package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class ContactShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        handleSharedContact(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedContact(intent)
    }

    private fun handleSharedContact(sourceIntent: Intent?) {
        if (!ConfigStore.load(this).useContactShareIntegration) {
            finish()
            return
        }

        val sharedText = unfoldVCardLines(readSharedText(sourceIntent))
        val phone = extractPhone(sharedText)
        val title = extractName(sharedText).ifBlank { phone }

        // A phone number takes priority: Call Report is useful for its call history and notes.
        if (phone.isNotBlank()) {
            Toast.makeText(this, getString(R.string.external_opening_history), Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, ContactNotesActivity::class.java)
                    .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                    .putExtra(ContactNotesActivity.EXTRA_TITLE, title)
            )
            finish()
            return
        }

        // A vCard/contact without a phone can still be useful as an e-mail draft.
        val email = extractEmail(sharedText)
        if (email.isNotBlank()) {
            openEmailClient(email)
            return
        }

        Toast.makeText(this, getString(R.string.external_contact_without_phone_or_email), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun openEmailClient(email: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:${Uri.encode(email)}")
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.external_choose_email_app)))
            finish()
        }.onFailure {
            Toast.makeText(this, getString(R.string.external_email_app_not_found), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun readSharedText(sourceIntent: Intent?): String {
        if (sourceIntent == null) return ""
        val parts = mutableListOf<String>()
        sourceIntent.getStringExtra(Intent.EXTRA_TEXT)?.let { parts.add(it) }
        sourceIntent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { parts.add(it) }
        sourceIntent.data?.let { uri -> readUriText(uri).takeIf { it.isNotBlank() }?.let { parts.add(it) } }

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

    private fun unfoldVCardLines(text: String): String {
        if (text.isBlank()) return text

        val unfoldedLines = mutableListOf<String>()
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .forEach { rawLine ->
                val line = rawLine
                val previous = unfoldedLines.lastOrNull()
                when {
                    previous != null && (line.startsWith(" ") || line.startsWith("\t")) -> {
                        unfoldedLines[unfoldedLines.lastIndex] = previous + line.drop(1)
                    }
                    previous != null && isQuotedPrintableSoftBreak(previous) -> {
                        unfoldedLines[unfoldedLines.lastIndex] = previous.dropLast(1) + line.trimStart()
                    }
                    else -> {
                        unfoldedLines.add(line)
                    }
                }
            }

        return unfoldedLines.joinToString("\n")
    }

    private fun isQuotedPrintableSoftBreak(line: String): Boolean {
        val header = line.substringBefore(':', missingDelimiterValue = "")
        if (!header.contains("ENCODING=QUOTED-PRINTABLE", ignoreCase = true)) return false
        return line.endsWith("=")
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

    private fun extractEmail(text: String): String {
        if (text.isBlank()) return ""
        val vCardEmail = text.lineSequence()
            .firstNotNullOfOrNull { line ->
                if (!isVCardProperty(line, "EMAIL")) return@firstNotNullOfOrNull null
                decodeVCardValue(line).takeIf { it.contains('@') }
            }
            .orEmpty()
            .removePrefix("mailto:")
            .trim()
        if (vCardEmail.isNotBlank()) return emailRegex.find(vCardEmail)?.value.orEmpty()
        return emailRegex.find(text)?.value.orEmpty()
    }

    private fun extractName(text: String): String {
        if (text.isBlank()) return ""
        val fullName = text.lineSequence()
            .firstOrNull { isVCardProperty(it, "FN") }
            ?.let { decodeVCardValue(it) }
            ?.trim()
            .orEmpty()
        if (fullName.isNotBlank()) return fullName

        return text.lineSequence()
            .firstOrNull { isVCardProperty(it, "N") }
            ?.let { decodeVCardValue(it) }
            ?.split(';')
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { it.trim() }
            .orEmpty()
    }

    private fun isVCardProperty(line: String, propertyName: String): Boolean {
        val header = line.trimStart().substringBefore(':', missingDelimiterValue = "")
        val name = header.substringBefore(';').trim()
        return name.equals(propertyName, ignoreCase = true)
    }

    private fun decodeVCardValue(line: String): String {
        val header = line.substringBefore(':', missingDelimiterValue = "")
        val value = line.substringAfter(':', missingDelimiterValue = "").trim()
        if (value.isBlank()) return value

        val shouldDecode = header.contains("ENCODING=QUOTED-PRINTABLE", ignoreCase = true) ||
            quotedPrintableHexRegex.findAll(value).take(2).count() >= 2
        if (!shouldDecode) return value

        val decoded = decodeQuotedPrintableUtf8(value)
        if (decoded != value && BuildConfig.DEBUG) {
            Log.d(TAG, "Decoded quoted-printable vCard value")
        }
        return decoded
    }

    private fun decodeQuotedPrintableUtf8(value: String): String {
        return runCatching {
            val bytes = ByteArrayOutputStream()
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '=') {
                    val next = value.getOrNull(index + 1)
                    if (next == '\r' || next == '\n') {
                        index += 1
                        if (value.getOrNull(index) == '\r') index += 1
                        if (value.getOrNull(index) == '\n') index += 1
                        continue
                    }

                    val hex = value.substring(index + 1, (index + 3).coerceAtMost(value.length))
                    if (hex.length == 2 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                        bytes.write(hex.toInt(16))
                        index += 3
                        continue
                    }
                }

                val rawBytes = char.toString().toByteArray(Charsets.UTF_8)
                bytes.write(rawBytes, 0, rawBytes.size)
                index += 1
            }

            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString().trim()
        }.getOrElse {
            value
        }
    }

    private companion object {
        private const val TAG = "ContactShareActivity"
        private val quotedPrintableHexRegex = Regex("=([0-9A-Fa-f]{2})")
        private val emailRegex = Regex("[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    }
}
