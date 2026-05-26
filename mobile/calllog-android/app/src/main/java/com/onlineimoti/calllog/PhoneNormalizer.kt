package com.onlineimoti.calllog

object PhoneNormalizer {
    fun normalize(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""

        val decoded = android.net.Uri.decode(trimmed)
            .removePrefix("tel:")
            .removePrefix("phone:")
            .removePrefix("web_search:")
            .substringBefore('?')
            .substringBefore(';')
            .trim()

        val startsWithPlus = decoded.trimStart().startsWith("+")
        var digits = decoded.filter { it.isDigit() }
        if (digits.isBlank()) return ""

        if (digits.startsWith("00") && digits.length > 4) {
            digits = digits.drop(2)
        }

        return when {
            digits.startsWith("359") -> "+$digits"
            digits.startsWith("0") && digits.length in 9..10 -> "+359${digits.drop(1)}"
            digits.length == 9 && digits.startsWith("8") -> "+359$digits"
            startsWithPlus -> "+$digits"
            else -> digits
        }
    }

    fun samePhone(left: String, right: String): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        return a.isNotBlank() && a == b
    }
}
