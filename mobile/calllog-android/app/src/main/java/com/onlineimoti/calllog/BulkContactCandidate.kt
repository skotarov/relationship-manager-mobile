package com.onlineimoti.calllog

internal data class BulkContactCandidate(
    val phone: String,
    val displayPhone: String,
    val displayName: String,
    val existingRawContactId: Long,
)
