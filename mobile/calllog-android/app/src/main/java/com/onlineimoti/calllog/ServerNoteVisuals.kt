package com.onlineimoti.calllog

/** UI-only markers for text that came from Relationship Manager server history. */
internal object ServerNoteVisuals {
    private const val CLOUD_PREFIX = "☁ "

    fun prefixed(text: String): String {
        val value = text.trim()
        if (value.isBlank()) return ""
        return if (isPrefixed(value)) value else CLOUD_PREFIX + value
    }

    fun prefixedIfServer(text: String, fromServer: Boolean): String {
        return if (fromServer) prefixed(text) else text.trim()
    }

    fun isPrefixed(text: String): Boolean {
        val value = text.trimStart()
        return value.startsWith("☁") || value.startsWith("☁️")
    }

    fun withoutPrefix(text: String): String {
        val value = text.trimStart()
        return when {
            value.startsWith("☁️") -> value.removePrefix("☁️").trimStart()
            value.startsWith("☁") -> value.removePrefix("☁").trimStart()
            else -> text.trim()
        }
    }
}
