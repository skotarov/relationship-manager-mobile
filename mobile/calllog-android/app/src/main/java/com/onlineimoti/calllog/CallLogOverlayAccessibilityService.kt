package com.onlineimoti.calllog

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CallLogOverlayAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val controller by lazy { CallLogOverlayButtonController(this) }
    private val refreshRunnable = Runnable { refreshOverlay() }
    private var lastEventTexts: List<String> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshOverlaySoon()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && isSupportedDialerPackage(event.packageName?.toString().orEmpty())) {
            lastEventTexts = buildList {
                event.text.forEach { value ->
                    value?.toString()?.trim()?.takeIf(CallLogOverlayTargetResolver::keepText)?.let(::add)
                }
                event.contentDescription?.toString()?.trim()?.takeIf(CallLogOverlayTargetResolver::keepText)?.let(::add)
            }.distinct()
        }
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

        controller.show(
            settings.position,
            CallLogOverlayTargetResolver.detect(
                context = this,
                titleTexts = collectTitleTexts(root),
                screenTexts = collectScreenTexts(root),
            )
        )
    }

    private fun isDefaultDialerWindowInFront(root: AccessibilityNodeInfo?): Boolean {
        return isSupportedDialerPackage(root?.packageName?.toString().orEmpty())
    }

    private fun isSupportedDialerPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val defaultDialerPackage = (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage.orEmpty()
        if (defaultDialerPackage.isNotBlank() && packageName == defaultDialerPackage) return true
        return packageName.contains("dialer", ignoreCase = true) ||
            packageName.contains("contacts", ignoreCase = true) ||
            packageName.contains("phone", ignoreCase = true)
    }

    private fun collectTitleTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        return buildList {
            addAll(lastEventTexts.filter(CallLogOverlayTargetResolver::keepText))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                root.paneTitle?.toString()?.trim()?.takeIf(CallLogOverlayTargetResolver::keepText)?.let(::add)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                windows.forEach { window ->
                    val windowRootPackage = window.root?.packageName?.toString().orEmpty()
                    if (isSupportedDialerPackage(windowRootPackage)) {
                        window.title?.toString()?.trim()?.takeIf(CallLogOverlayTargetResolver::keepText)?.let(::add)
                    }
                }
            }
        }.distinct()
    }

    private fun collectScreenTexts(root: AccessibilityNodeInfo?): List<String> {
        val texts = mutableListOf<String>()
        if (root != null) collectTexts(root, texts, limit = 100)
        return texts.distinct()
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        node.text?.toString()?.trim()?.takeIf(CallLogOverlayTargetResolver::keepText)?.let(out::add)
        node.contentDescription?.toString()?.trim()?.takeIf(CallLogOverlayTargetResolver::keepText)?.let(out::add)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.paneTitle?.toString()?.trim()?.takeIf(CallLogOverlayTargetResolver::keepText)?.let(out::add)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, limit)
            child.recycle()
            if (out.size >= limit) return
        }
    }
}
