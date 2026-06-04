package com.onlineimoti.calllog

internal object RmContactNameResolver {
    fun titleFor(real: BulkContactCandidate): String {
        return real.displayName.ifBlank { real.displayPhone.ifBlank { real.phone } }
    }

    fun cleanFallbackDisplayName(candidate: String, phone: String): String {
        var value = candidate.trim()
        if (value.isBlank()) return ""
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (PhoneNormalizer.normalize(value) == normalizedPhone) return ""
        val leadingPhone = Regex("^[+\\d][\\d\\s().-]{5,}").find(value)?.value.orEmpty()
        if (leadingPhone.isNotBlank() && PhoneNormalizer.normalize(leadingPhone) == normalizedPhone) {
            value = value.removePrefix(leadingPhone).trim()
        }
        return value
    }
}
