package com.onlineimoti.calllog

internal enum class CallLogOverlayScreenKind { GENERAL_LOG, CONTACT_DETAIL, CONTACT_HISTORY, UNKNOWN }

internal object CallLogOverlayTextRules {
    fun classifyScreen(titleTexts: List<String>, screenTexts: List<String>): CallLogOverlayScreenKind {
        val titleBlob = titleTexts.joinToString(" ").lowercase()
        val screenBlob = screenTexts.joinToString(" ").lowercase()
        val allBlob = "$titleBlob $screenBlob"
        val phoneCount = screenTexts.mapNotNull(::extractPhoneCandidate).distinct().size
        if (hasGeneralSurfaceHints(allBlob)) return CallLogOverlayScreenKind.GENERAL_LOG
        if (hasContactHistoryHints(allBlob)) return CallLogOverlayScreenKind.CONTACT_HISTORY
        if (hasContactDetailHints(allBlob)) return CallLogOverlayScreenKind.CONTACT_DETAIL
        if (phoneCount >= 2 && titleTexts.none { extractPhoneCandidate(it).orEmpty().isNotBlank() }) {
            return CallLogOverlayScreenKind.GENERAL_LOG
        }
        return CallLogOverlayScreenKind.UNKNOWN
    }

    fun keepText(text: String): Boolean {
        return isValidPhoneText(text) || isValidContactNameCandidateShape(text) || isScreenHintText(text)
    }

    fun isValidPhoneText(text: String): Boolean {
        return extractPhoneCandidate(text).orEmpty().isNotBlank()
    }

    fun isValidContactNameCandidateShape(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 2..80) return false
        if (!trimmed.any { it.isLetter() }) return false
        if (looksLikeClassName(trimmed)) return false
        if (looksLikeSystemChrome(trimmed)) return false
        if (looksLikeCallMeta(trimmed)) return false
        return true
    }

    fun isScreenHintText(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isBlank()) return false
        if (looksLikeSystemChrome(lower)) return false
        return hasGeneralSurfaceHints(lower) || hasContactDetailHints(lower) || hasContactHistoryHints(lower)
    }

    fun firstLikelyTitle(texts: List<String>): String {
        return texts.firstOrNull { text ->
            val trimmed = text.trim()
            isValidContactNameCandidateShape(trimmed) && !isScreenHintText(trimmed) && extractPhoneCandidate(trimmed).isNullOrBlank()
        }.orEmpty()
    }

    fun extractPhoneCandidate(text: String): String? {
        val matches = PHONE_PATTERN.findAll(text).map { it.value }.toList()
        if (matches.size != 1) return null
        return normalizePhone(matches.first()).takeIf { it.isNotBlank() }
    }

    fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 7) return ""
        return when {
            raw.trim().startsWith("+") -> "+$digits"
            digits.startsWith("359") -> "+$digits"
            else -> digits
        }
    }

    fun normalizeForCompare(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    fun normalizeNameForCompare(name: String): String {
        return name
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun hasGeneralSurfaceHints(blob: String): Boolean {
        return listOf(
            "keypad",
            "dial pad",
            "dialpad",
            "showing default suggestions",
            "default suggestions",
            "suggestions",
            "search contacts",
            "search calls",
            "view contacts",
            "recents",
            "recent calls",
            "missed",
            "non-spam",
            "spam",
            "цифри",
            "клавиатура",
            "търси контакти",
            "последни",
            "пропуснати",
        ).any { blob.contains(it) }
    }

    private fun hasContactDetailHints(blob: String): Boolean {
        val actionCount = listOf(
            "call",
            "message",
            "video",
            "email",
            "location sharing",
            "обаждане",
            "съобщение",
            "имейл",
            "местоположение",
        ).count { blob.contains(it) }
        val fieldHint = listOf(
            "mobile",
            "mobile default",
            "mobile · default",
            "other",
            "other default",
            "other · default",
            "default",
            "address",
            "recent activity",
            "source: contact",
            "emergency contact",
            "family group",
            "add to favorites",
            "remove from favorites",
            "starred",
            "unstarred",
            "near ",
            "can see your location",
            "адрес",
            "скорошна активност",
            "спешен контакт",
            "семейна група",
        ).any { blob.contains(it) }
        return actionCount >= 2 || fieldHint
    }

    private fun hasContactHistoryHints(blob: String): Boolean {
        val callRow = listOf("outgoing call", "incoming call", "missed call", "изходящ", "входящ", "пропуснат")
            .any { blob.contains(it) }
        val section = listOf("older", "recent activity", "по-стари", "история")
            .any { blob.contains(it) }
        return callRow && section
    }

    private fun looksLikeCallMeta(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.contains("ago") || lower.contains("today") || lower.contains("yesterday")) return true
        if (lower.contains("преди") || lower.contains("днес") || lower.contains("вчера")) return true
        if (lower.contains("incoming") || lower.contains("outgoing") || lower.contains("missed")) return true
        if (lower.contains("входящ") || lower.contains("изходящ") || lower.contains("пропуснат")) return true
        return TIME_PATTERN.matches(lower)
    }

    private fun looksLikeSystemChrome(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "navigation bar" ||
            lower == "status bar" ||
            lower == "system navigation" ||
            lower == "back" ||
            lower == "recent apps" ||
            lower == "overview"
    }

    private fun looksLikeClassName(text: String): Boolean {
        return text.contains('.') || text.contains('$') || text.startsWith("android.") || text.startsWith("com.")
    }

    private val PHONE_PATTERN = Regex("(?<!\\d)(?:\\+?\\d[\\d\\s().-]{5,}\\d)(?!\\d)")
    private val TIME_PATTERN = Regex(".*\\b\\d{1,2}:\\d{2}\\b.*")
}
