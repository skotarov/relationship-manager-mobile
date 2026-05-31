package com.onlineimoti.calllog

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
        val phones = texts.mapNotNull(::extractPhoneCandidate).distinct()
        if (phones.size != 1) return CallLogOverlayTarget()
        val phone = phones.first()
        val title = texts.firstOrNull { text ->
            val trimmed = text.trim()
            trimmed.isNotBlank() && extractPhoneCandidate(trimmed).isNullOrBlank() && !isUiChromeText(trimmed)
        }.orEmpty()
        return CallLogOverlayTarget(phone = phone, title = title)
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

    private fun extractPhoneCandidate(text: String): String? {
        val matches = PHONE_PATTERN.findAll(text).map { it.value }.toList()
        if (matches.size != 1) return null
        val raw = matches.first()
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 7) return null
        return when {
            raw.trim().startsWith("+") -> "+$digits"
            digits.startsWith("359") -> "+$digits"
            else -> digits
        }
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
        )
    }

    companion object {
        private val PHONE_PATTERN = Regex("(?<!\\d)(?:\\+?\\d[\\d\\s().-]{5,}\\d)(?!\\d)")
    }
}
