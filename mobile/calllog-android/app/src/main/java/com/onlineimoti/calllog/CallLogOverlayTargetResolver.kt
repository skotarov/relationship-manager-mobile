package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object CallLogOverlayTargetResolver {
    fun detect(context: Context, titleTexts: List<String>, screenTexts: List<String>): CallLogOverlayTarget {
        val titleTarget = targetFromTexts(context, titleTexts, allowNameLookup = true)
        if (titleTarget.phone.isNotBlank()) return titleTarget

        val screenKind = classifyScreen(titleTexts, screenTexts)
        if (screenKind == ScreenKind.GENERAL_LOG) return CallLogOverlayTarget()

        val headerTarget = targetFromTexts(context, titleTexts + screenTexts.take(16), allowNameLookup = screenKind != ScreenKind.UNKNOWN)
        if (headerTarget.phone.isNotBlank()) return headerTarget

        val allTexts = (titleTexts + screenTexts)
            .map { it.trim() }
            .filter { isUsableCandidateText(it) }
            .distinct()

        if (screenKind != ScreenKind.UNKNOWN) {
            val wholeTarget = targetFromTexts(context, allTexts, allowNameLookup = true)
            if (wholeTarget.phone.isNotBlank()) return wholeTarget
        }

        val phones = allTexts.mapNotNull(::extractPhoneCandidate).distinct()
        return if (phones.size == 1) {
            CallLogOverlayTarget(phone = phones.first(), title = firstLikelyTitle(allTexts))
        } else {
            CallLogOverlayTarget()
        }
    }

    fun keepText(text: String): Boolean {
        return isUsableCandidateText(text) || isScreenHintText(text)
    }

    private fun targetFromTexts(context: Context, texts: List<String>, allowNameLookup: Boolean): CallLogOverlayTarget {
        val cleanedTexts = texts.map { it.trim() }.filter { isUsableCandidateText(it) }.distinct()
        val phones = cleanedTexts.mapNotNull(::extractPhoneCandidate).distinct()
        val title = firstLikelyTitle(cleanedTexts)
        if (phones.size == 1) return CallLogOverlayTarget(phone = phones.first(), title = title)
        if (allowNameLookup) {
            val phoneFromName = resolvePhoneFromVisibleContactName(context, cleanedTexts)
            if (phoneFromName.isNotBlank()) {
                return CallLogOverlayTarget(phone = phoneFromName, title = title.ifBlank { firstMatchingContactTitle(context, cleanedTexts) })
            }
        }
        return CallLogOverlayTarget()
    }

    private fun classifyScreen(titleTexts: List<String>, screenTexts: List<String>): ScreenKind {
        val titleBlob = titleTexts.joinToString(" ").lowercase()
        val screenBlob = screenTexts.joinToString(" ").lowercase()
        val allBlob = "$titleBlob $screenBlob"
        val phoneCount = screenTexts.mapNotNull(::extractPhoneCandidate).distinct().size
        if (hasContactHistoryHints(allBlob)) return ScreenKind.CONTACT_HISTORY
        if (hasContactDetailHints(allBlob)) return ScreenKind.CONTACT_DETAIL
        if (hasGeneralLogHints(allBlob)) return ScreenKind.GENERAL_LOG
        if (phoneCount >= 2 && titleTexts.none { extractPhoneCandidate(it).isNotBlank() }) return ScreenKind.GENERAL_LOG
        return ScreenKind.UNKNOWN
    }

    private fun hasGeneralLogHints(blob: String): Boolean {
        return listOf("search contacts", "search calls", "favorites", "view contacts", "recents", "recent calls", "missed", "търси контакти", "любими", "последни", "пропуснати").any { blob.contains(it) }
    }

    private fun hasContactDetailHints(blob: String): Boolean {
        val actionCount = listOf("call", "message", "video", "email", "обаждане", "съобщение", "имейл").count { blob.contains(it) }
        val fieldHint = listOf("other", "default", "address", "recent activity", "source: contact", "адрес", "скорошна активност").any { blob.contains(it) }
        return actionCount >= 2 || fieldHint
    }

    private fun hasContactHistoryHints(blob: String): Boolean {
        val callRow = listOf("outgoing call", "incoming call", "missed call", "изходящ", "входящ", "пропуснат").any { blob.contains(it) }
        val section = listOf("older", "recent activity", "по-стари", "история").any { blob.contains(it) }
        return callRow && section
    }

    private fun resolvePhoneFromVisibleContactName(context: Context, texts: List<String>): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        texts
            .map { it.trim() }
            .filter { isUsableCandidateText(it) }
            .filter { it.length in 2..80 }
            .filter { extractPhoneCandidate(it).isNullOrBlank() }
            .distinct()
            .forEach { candidate ->
                val phones = phonesForExactContactName(context, candidate)
                if (phones.size == 1) return phones.first()
            }
        return ""
    }

    private fun firstMatchingContactTitle(context: Context, texts: List<String>): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        return texts.firstOrNull { text ->
            val candidate = text.trim()
            candidate.length in 2..80 && isUsableCandidateText(candidate) && extractPhoneCandidate(candidate).isNullOrBlank() && phonesForExactContactName(context, candidate).size == 1
        }.orEmpty()
    }

    private fun phonesForExactContactName(context: Context, name: String): List<String> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} = ? OR ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        val args = arrayOf(name, name)
        return runCatching {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val result = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val normalized = normalizePhone(cursor.getString(numberIndex).orEmpty())
                    if (normalized.isNotBlank()) result.add(normalized)
                }
                result.distinctBy { normalizeForCompare(it) }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun firstLikelyTitle(texts: List<String>): String {
        return texts.firstOrNull { text ->
            val trimmed = text.trim()
            trimmed.isNotBlank() && isUsableCandidateText(trimmed) && extractPhoneCandidate(trimmed).isNullOrBlank()
        }.orEmpty()
    }

    private fun extractPhoneCandidate(text: String): String? {
        val matches = PHONE_PATTERN.findAll(text).map { it.value }.toList()
        if (matches.size != 1) return null
        return normalizePhone(matches.first()).takeIf { it.isNotBlank() }
    }

    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 7) return ""
        return when {
            raw.trim().startsWith("+") -> "+$digits"
            digits.startsWith("359") -> "+$digits"
            else -> digits
        }
    }

    private fun normalizeForCompare(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private fun isUsableCandidateText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (isUiText(trimmed)) return false
        if (looksLikeMeta(trimmed)) return false
        if (looksLikeClassName(trimmed)) return false
        return true
    }

    private fun isScreenHintText(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isBlank()) return false
        if (lower == "navigation bar" || lower == "status bar") return false
        return hasGeneralLogHints(lower) || hasContactDetailHints(lower) || hasContactHistoryHints(lower)
    }

    private fun isUiText(text: String): Boolean {
        val lower = text.lowercase()
        return lower in setOf("call log", "calls", "recents", "contacts", "phone", "history", "search", "edit", "add", "delete", "share", "mobile", "home", "work", "message", "video call", "details", "more options", "navigation bar", "status bar", "system navigation", "back", "recent apps", "overview")
    }

    private fun looksLikeMeta(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.contains("ago") || lower.contains("today") || lower.contains("yesterday")) return true
        if (lower.contains("преди") || lower.contains("днес") || lower.contains("вчера")) return true
        if (lower.contains("incoming") || lower.contains("outgoing") || lower.contains("missed")) return true
        if (lower.contains("входящ") || lower.contains("изходящ") || lower.contains("пропуснат")) return true
        return TIME_PATTERN.matches(lower)
    }

    private fun looksLikeClassName(text: String): Boolean {
        return text.contains('.') || text.contains('$') || text.startsWith("android.") || text.startsWith("com.")
    }

    private enum class ScreenKind { GENERAL_LOG, CONTACT_DETAIL, CONTACT_HISTORY, UNKNOWN }

    private val PHONE_PATTERN = Regex("(?<!\\d)(?:\\+?\\d[\\d\\s().-]{5,}\\d)(?!\\d)")
    private val TIME_PATTERN = Regex(".*\\b\\d{1,2}:\\d{2}\\b.*")
}
