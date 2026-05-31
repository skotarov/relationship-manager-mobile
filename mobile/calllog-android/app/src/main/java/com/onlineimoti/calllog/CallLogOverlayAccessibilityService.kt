package com.onlineimoti.calllog

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat

class CallLogOverlayAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val controller by lazy { CallLogOverlayButtonController(this) }
    private val refreshRunnable = Runnable { refreshOverlay() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshOverlaySoon()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> refreshOverlaySoon()
        }
    }

    override fun onInterrupt() {
        controller.hide()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller.hide()
        super.onDestroy()
    }

    private fun refreshOverlaySoon() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 160L)
    }

    private fun refreshOverlay() {
        val settings = CallLogOverlaySettings.load(this)
        if (!settings.enabled) {
            controller.hide()
            return
        }

        val root = rootInActiveWindow
        if (!isDefaultDialerWindowInFront(root)) {
            controller.hide()
            return
        }

        controller.show(settings.position, detectTarget(root))
    }

    private fun isDefaultDialerWindowInFront(root: AccessibilityNodeInfo?): Boolean {
        val packageName = root?.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return false
        val defaultDialerPackage = (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage.orEmpty()
        if (defaultDialerPackage.isNotBlank() && packageName == defaultDialerPackage) return true
        return packageName.contains("dialer", ignoreCase = true) || packageName.contains("contacts", ignoreCase = true)
    }

    private fun detectTarget(root: AccessibilityNodeInfo?): CallLogOverlayTarget {
        if (root == null) return CallLogOverlayTarget()
        val texts = mutableListOf<String>()
        collectTexts(root, texts, limit = 80)
        val cleanedTexts = texts.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val phones = cleanedTexts.mapNotNull(::extractPhoneCandidate).distinct()
        val title = firstLikelyTitle(cleanedTexts)

        if (phones.size == 1) {
            return CallLogOverlayTarget(phone = phones.first(), title = title)
        }

        val phoneFromName = resolvePhoneFromVisibleContactName(cleanedTexts)
        if (phoneFromName.isNotBlank()) {
            return CallLogOverlayTarget(phone = phoneFromName, title = title.ifBlank { firstMatchingContactTitle(cleanedTexts) })
        }

        return CallLogOverlayTarget()
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, limit)
            child.recycle()
            if (out.size >= limit) return
        }
    }

    private fun resolvePhoneFromVisibleContactName(texts: List<String>): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        val candidates = texts
            .map { it.trim() }
            .filter { it.length in 2..80 }
            .filter { extractPhoneCandidate(it).isNullOrBlank() }
            .filterNot(::isUiChromeText)
            .filterNot(::looksLikeCallMetaText)
            .distinct()

        candidates.forEach { candidate ->
            val phones = phonesForExactContactName(candidate)
            if (phones.size == 1) return phones.first()
        }
        return ""
    }

    private fun firstMatchingContactTitle(texts: List<String>): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        return texts.firstOrNull { text ->
            val candidate = text.trim()
            candidate.length in 2..80 &&
                extractPhoneCandidate(candidate).isNullOrBlank() &&
                !isUiChromeText(candidate) &&
                !looksLikeCallMetaText(candidate) &&
                phonesForExactContactName(candidate).size == 1
        }.orEmpty()
    }

    private fun phonesForExactContactName(name: String): List<String> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} = ? OR ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        val args = arrayOf(name, name)
        return runCatching {
            contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val result = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val phone = cursor.getString(numberIndex).orEmpty().trim()
                    val normalized = normalizePhone(phone)
                    if (normalized.isNotBlank()) result.add(normalized)
                }
                result.distinctBy { normalizeForCompare(it) }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun firstLikelyTitle(texts: List<String>): String {
        return texts.firstOrNull { text ->
            val trimmed = text.trim()
            trimmed.isNotBlank() &&
                extractPhoneCandidate(trimmed).isNullOrBlank() &&
                !isUiChromeText(trimmed) &&
                !looksLikeCallMetaText(trimmed)
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

    private fun isUiChromeText(text: String): Boolean {
        val lower = text.lowercase()
        return lower in setOf(
            "call log",
            "calls",
            "recents",
            "contacts",
            "phone",
            "history",
            "search",
            "edit",
            "add",
            "block",
            "delete",
            "share",
            "mobile",
            "home",
            "work",
            "message",
            "video call",
            "details",
            "more options",
        )
    }

    private fun looksLikeCallMetaText(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.contains("ago") || lower.contains("today") || lower.contains("yesterday")) return true
        if (lower.contains("преди") || lower.contains("днес") || lower.contains("вчера")) return true
        if (lower.contains("incoming") || lower.contains("outgoing") || lower.contains("missed")) return true
        if (lower.contains("входящ") || lower.contains("изходящ") || lower.contains("пропуснат")) return true
        if (TIME_PATTERN.matches(lower)) return true
        return false
    }

    companion object {
        private val PHONE_PATTERN = Regex("(?<!\\d)(?:\\+?\\d[\\d\\s().-]{5,}\\d)(?!\\d)")
        private val TIME_PATTERN = Regex(".*\\b\\d{1,2}:\\d{2}\\b.*")
    }
}
