package com.onlineimoti.calllog

internal enum class RmContactReconcileAction {
    ADDED,
    UPDATED,
    DELETED,
    UNCHANGED,
    SKIPPED,
    FAILED,
}

internal data class RmContactReconcileResult(
    val action: RmContactReconcileAction,
    val phone: String,
)

internal data class RmRecord(
    val rawContactId: Long,
    val syncPhone: String,
    val normalizedPhones: Set<String>,
    val displayName: String,
    val nameRowId: Long,
    val phoneRowId: Long,
    val historyRowId: Long,
)

internal data class MutableRmRecord(
    val rawContactId: Long,
    val syncPhone: String,
    val normalizedPhones: MutableSet<String> = linkedSetOf(),
    var displayName: String = "",
    var nameRowId: Long = 0L,
    var phoneRowId: Long = 0L,
    var historyRowId: Long = 0L,
)
