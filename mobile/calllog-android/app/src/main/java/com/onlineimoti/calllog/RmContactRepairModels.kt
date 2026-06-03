package com.onlineimoti.calllog

internal data class RealContactCandidate(
    val rawContactId: Long,
    val normalizedPhone: String,
    val visiblePhone: String,
    val name: StructuredContactName,
)

internal data class StructuredContactName(
    val givenName: String = "",
    val middleName: String = "",
    val familyName: String = "",
    val displayName: String = "",
) {
    companion object {
        fun fromDisplayName(displayName: String): StructuredContactName = StructuredContactName(displayName = displayName)
    }
}
