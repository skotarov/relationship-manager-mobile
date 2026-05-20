package com.onlineimoti.calllog

fun normalizePhoneDigits(value: String): String {
    return value.filter { it.isDigit() }
}

fun phoneLastDigits(value: String, length: Int = 9): String {
    val digits = normalizePhoneDigits(value)
    if (digits.isBlank()) {
        return ""
    }
    return if (digits.length <= length) digits else digits.takeLast(length)
}
