package com.onlineimoti.calllog

object PhoneNormalizer {
    private const val DEFAULT_COUNTRY_CODE = "359"
    private const val BULGARIAN_NATIONAL_PREFIX = "0"
    private const val PHONE_KEY_LENGTH = 9

    /**
     * Canonical display-neutral phone value. Bulgarian numbers are normalized to +359…
     * while the lookup key below remains the single source for comparisons.
     */
    fun normalize(value: String): String {
        val decoded = cleanInput(value)
        if (decoded.isBlank()) return ""

        val startsWithPlus = decoded.trimStart().startsWith("+")
        var digits = decoded.filter { it.isDigit() }
        if (digits.isBlank()) return ""

        if (digits.startsWith("00") && digits.length > 4) {
            digits = digits.drop(2)
        }

        return when {
            digits.startsWith(DEFAULT_COUNTRY_CODE) -> "+$digits"
            digits.startsWith(BULGARIAN_NATIONAL_PREFIX) && digits.length in 9..10 -> "+$DEFAULT_COUNTRY_CODE${digits.drop(1)}"
            digits.length == PHONE_KEY_LENGTH && digits.startsWith("8") -> "+$DEFAULT_COUNTRY_CODE$digits"
            startsWithPlus -> "+$digits"
            else -> digits
        }
    }

    /** The internal matching/storage key: +359888… and 0888… both become 888…. */
    fun key(value: String): String {
        val digits = cleanInput(value).filter { it.isDigit() }
            .let { raw -> if (raw.startsWith("00") && raw.length > 4) raw.drop(2) else raw }
        return if (digits.length > PHONE_KEY_LENGTH) digits.takeLast(PHONE_KEY_LENGTH) else digits
    }

    fun samePhone(left: String, right: String): Boolean {
        val a = key(left)
        val b = key(right)
        return a.isNotBlank() && a == b
    }

    /** Candidate forms used when Android providers store either 0…, 359… or +359…. */
    fun candidates(value: String): List<String> {
        val decoded = cleanInput(value)
        val digits = decoded.filter { it.isDigit() }
            .let { raw -> if (raw.startsWith("00") && raw.length > 4) raw.drop(2) else raw }
        val phoneKey = key(value)
        return linkedSetOf<String>().apply {
            add(decoded.trim())
            add(digits)
            add(normalize(value))
            if (phoneKey.length == PHONE_KEY_LENGTH) {
                add(phoneKey)
                add("$BULGARIAN_NATIONAL_PREFIX$phoneKey")
                add("$DEFAULT_COUNTRY_CODE$phoneKey")
                add("+$DEFAULT_COUNTRY_CODE$phoneKey")
            }
        }.filter { it.isNotBlank() }
    }

    /** Friendly Bulgarian display format for mobile numbers; storage/search still use [key]. */
    fun display(value: String): String {
        val phoneKey = key(value)
        if (phoneKey.length == PHONE_KEY_LENGTH && phoneKey.startsWith("8")) {
            return "0${phoneKey.take(3)} ${phoneKey.drop(3).take(3)} ${phoneKey.drop(6)}"
        }
        return normalize(value).ifBlank { value.trim() }
    }

    private fun cleanInput(value: String): String {
        return android.net.Uri.decode(value.trim())
            .removePrefix("tel:")
            .removePrefix("phone:")
            .removePrefix("web_search:")
            .substringBefore('?')
            .substringBefore(';')
            .trim()
    }
}
