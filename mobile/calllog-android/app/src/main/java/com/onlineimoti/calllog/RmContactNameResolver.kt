package com.onlineimoti.calllog

internal object RmContactNameResolver {
    fun titleFor(real: BulkContactCandidate): String {
        return cleanFallbackDisplayName(real.displayName, real.phone)
            .ifBlank { cleanFallbackDisplayName(real.displayPhone, real.phone) }
            .ifBlank { real.phone }
    }

    fun structuredParts(displayName: String): RmContactNameParts {
        val parts = displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when (parts.size) {
            0 -> RmContactNameParts()
            1 -> RmContactNameParts(givenName = parts[0])
            2 -> RmContactNameParts(givenName = parts[0], familyName = parts[1])
            else -> RmContactNameParts(
                givenName = parts.first(),
                middleName = parts.drop(1).dropLast(1).joinToString(" "),
                familyName = parts.last(),
            )
        }
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

internal data class RmContactNameParts(
    val givenName: String = "",
    val middleName: String = "",
    val familyName: String = "",
)
