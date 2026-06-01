package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object CallLogOverlayTargetResolver {
    fun detect(context: Context, titleTexts: List<String>, screenTexts: List<String>): CallLogOverlayTarget {
        val screenOnlyKind = classifyScreen(emptyList(), screenTexts)
        if (screenOnlyKind == ScreenKind.GENERAL_LOG) return CallLogOverlayTarget()

        val titlePhoneTarget = targetFromTexts(context, titleTexts, allowNameLookup = false)
        if (titlePhoneTarget.phone.isNotBlank()) return titlePhoneTarget

        val screenKind = classifyScreen(titleTexts, screenTexts)
        if (screenKind == ScreenKind.GENERAL_LOG) return CallLogOverlayTarget()

        val allowContactNameLookup = screenKind == ScreenKind.CONTACT_DETAIL || screenKind == ScreenKind.CONTACT_HISTORY

        val titleTarget = targetFromTexts(context, titleTexts, allowNameLookup = allowContactNameLookup)
        if (titleTarget.phone.isNotBlank()) return titleTarget

        val headerTarget = targetFromTexts(context, titleTexts + screenTexts.take(16), allowNameLookup = allowContactNameLookup)
        if (headerTarget.phone.isNotBlank()) return headerTarget

        val allTexts = (titleTexts + screenTexts)
            .map { it.trim() }
            .filter { isValidPhoneText(it) || isValidContactNameCandidateShape(it) }
            .distinct()

        if (allowContactNameLookup) {
            val wholeTarget = targetFromTexts(context, allTexts, allowNameLookup = true)
            if (wholeTarget.phone.isNotBlank()) return wholeTarget
        }

        val phones = allTexts.mapNotNull(::extractPhoneCandidate).distinct()
        return if (phones.size == 1 && screenKind != ScreenKind.UNKNOWN) {
            CallLogOverlayTarget(phone = phones.first(), title = firstLikelyTitle(allTexts))
        } else {
            CallLogOverlayTarget()
        }
    }

    fun keepText(text: String): Boolean {
        return isValidPhoneText(text) || isValidContactNameCandidateShape(text) || isScreenHintText(text)
    }

    private fun targetFromTexts(context: Context, texts: List<String>, allowNameLookup: Boolean): CallLogOverlayTarget {
        val cleanedTexts = texts
            .map { it.trim() }
            .filter { isValidPhoneText(it) || isValidContactNameCandidateShape(it) }
            .distinct()
        val phones = cleanedTexts.mapNotNull(::extractPhoneCandidate).distinct()
        val title = firstLikelyTitle(cleanedTexts)
        if (phones.size == 1) return CallLogOverlayTarget(phone = phones.first(), title = title)
        if (allowNameLookup) {
            val resolved = resolvePhoneAndTitleFromVisibleContactName(context, cleanedTexts)
            if (resolved.phone.isNotBlank()) {
                return CallLogOverlayTarget(phone = resolved.phone, title = title.ifBlank { resolved.title })
            }
        }
        return CallLogOverlayTarget()
    }

    private fun classifyScreen(titleTexts: List<String>, screenTexts: List<String>): ScreenKind {
        val titleBlob = titleTexts.joinToString(" ").lowercase()
        val screenBlob = screenTexts.joinToString(" ").lowercase()
        val allBlob = "$titleBlob $screenBlob"
        val phoneCount = screenTexts.mapNotNull(::extractPhoneCandidate).distinct().size
        if (hasGeneralSurfaceHints(allBlob)) return ScreenKind.GENERAL_LOG
        if (hasContactHistoryHints(allBlob)) return ScreenKind.CONTACT_HISTORY
        if (hasContactDetailHints(allBlob)) return ScreenKind.CONTACT_DETAIL
        if (phoneCount >= 2 && titleTexts.none { extractPhoneCandidate(it).orEmpty().isNotBlank() }) return ScreenKind.GENERAL_LOG
        return ScreenKind.UNKNOWN
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
        val callRow = listOf("outgoing call", "incoming call", "missed call", "изходящ", "входящ", "пропуснат").any { blob.contains(it) }
        val section = listOf("older", "recent activity", "по-стари", "история").any { blob.contains(it) }
        return callRow && section
    }

    private fun resolvePhoneAndTitleFromVisibleContactName(context: Context, texts: List<String>): ResolvedContactTarget {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ResolvedContactTarget()
        texts
            .map { it.trim() }
            .filter { isValidContactNameCandidateShape(it) }
            .filterNot { isScreenHintText(it) }
            .filter { extractPhoneCandidate(it).isNullOrBlank() }
            .distinct()
            .forEach { candidate ->
                val match = contactMatchForVisibleName(context, candidate)
                if (match.phone.isNotBlank()) return match
            }
        return ResolvedContactTarget()
    }

    private fun contactMatchForVisibleName(context: Context, visibleName: String): ResolvedContactTarget {
        val exactRows = contactRows(context, exactName = visibleName)
        val exactMatch = uniquePhoneFromRows(exactRows)
        if (exactMatch.phone.isNotBlank() || exactRows.isNotEmpty()) return exactMatch

        val normalizedVisibleName = normalizeNameForCompare(visibleName)
        if (normalizedVisibleName.length < 3) return ResolvedContactTarget()
        val visibleTokens = normalizedVisibleName.split(' ').filter { it.length >= 2 }
        val fuzzyRows = contactRows(context, exactName = null).filter { row ->
            val contactName = normalizeNameForCompare(row.displayName)
            contactName.isNotBlank() && (
                contactName == normalizedVisibleName ||
                    contactName.contains(normalizedVisibleName) ||
                    normalizedVisibleName.contains(contactName) ||
                    (visibleTokens.size >= 2 && visibleTokens.all { token -> contactName.contains(token) }) ||
                    (visibleTokens.size == 1 && visibleTokens.first().length >= 4 && contactName.split(' ').any { it.startsWith(visibleTokens.first()) })
                )
        }
        return uniquePhoneFromRows(fuzzyRows)
    }

    private fun contactRows(context: Context, exactName: String?): List<ContactPhoneRow> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        val selection = exactName?.let {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} = ? OR ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        }
        val args = exactName?.let { arrayOf(it, it) }
        return runCatching {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val primaryNameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val displayNameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val displayName = cursor.getString(primaryNameIndex).orEmpty().ifBlank { cursor.getString(displayNameIndex).orEmpty() }.trim()
                        val phone = normalizePhone(cursor.getString(numberIndex).orEmpty())
                        if (displayName.isNotBlank() && phone.isNotBlank()) add(ContactPhoneRow(id, displayName, phone))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun uniquePhoneFromRows(rows: List<ContactPhoneRow>): ResolvedContactTarget {
        val byContact = rows.groupBy { it.contactId }
        if (byContact.size != 1) return ResolvedContactTarget()
        val contactRows = byContact.values.first()
        val phones = contactRows.map { it.phone }.distinctBy { normalizeForCompare(it) }
        if (phones.size != 1) return ResolvedContactTarget()
        return ResolvedContactTarget(phone = phones.first(), title = contactRows.firstOrNull()?.displayName.orEmpty())
    }

    private fun firstLikelyTitle(texts: List<String>): String {
        return texts.firstOrNull { text ->
            val trimmed = text.trim()
            isValidContactNameCandidateShape(trimmed) && !isScreenHintText(trimmed) && extractPhoneCandidate(trimmed).isNullOrBlank()
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

    private fun normalizeNameForCompare(name: String): String {
        return name
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun isValidPhoneText(text: String): Boolean {
        return extractPhoneCandidate(text).orEmpty().isNotBlank()
    }

    private fun isValidContactNameCandidateShape(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 2..80) return false
        if (!trimmed.any { it.isLetter() }) return false
        if (looksLikeClassName(trimmed)) return false
        if (looksLikeSystemChrome(trimmed)) return false
        if (looksLikeCallMeta(trimmed)) return false
        return true
    }

    private fun isScreenHintText(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isBlank()) return false
        if (looksLikeSystemChrome(lower)) return false
        return hasGeneralSurfaceHints(lower) || hasContactDetailHints(lower) || hasContactHistoryHints(lower)
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

    private data class ContactPhoneRow(
        val contactId: Long,
        val displayName: String,
        val phone: String,
    )

    private data class ResolvedContactTarget(
        val phone: String = "",
        val title: String = "",
    )

    private enum class ScreenKind { GENERAL_LOG, CONTACT_DETAIL, CONTACT_HISTORY, UNKNOWN }

    private val PHONE_PATTERN = Regex("(?<!\\d)(?:\\+?\\d[\\d\\s().-]{5,}\\d)(?!\\d)")
    private val TIME_PATTERN = Regex(".*\\b\\d{1,2}:\\d{2}\\b.*")
}
