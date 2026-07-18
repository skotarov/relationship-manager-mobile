package com.onlineimoti.calllog

/** UI-only markers for text that came from Relationship Manager server history. */
internal object ServerNoteVisuals {
    private const val CLOUD_PREFIX = "☁ "

    fun prefixed(text: String): String {
        val value = text.trim()
        if (value.isBlank()) return ""
        return if (isServerText(value)) value else CLOUD_PREFIX + value
    }

    fun prefixedIfServer(text: String, fromServer: Boolean): String {
        return if (fromServer) prefixed(text) else text.trim()
    }

    fun isServerText(text: String): Boolean {
        val value = text.trimStart()
        return value.startsWith("☁") || value.startsWith("☁️")
    }
}
