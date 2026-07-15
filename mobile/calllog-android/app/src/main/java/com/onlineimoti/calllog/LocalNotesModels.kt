package com.onlineimoti.calllog

import android.net.Uri

internal data class LocalGeneralNote(
    val note: String,
    val updatedAt: Long,
)

internal data class LocalStoredGeneralNote(
    val phone: String,
    val phoneKey: String,
    val note: String,
    val noteAt: Long,
)

internal data class LocalStoredCallNote(
    val phone: String,
    val phoneKey: String,
    val note: String,
    val noteAt: Long,
    val callAt: Long,
    val direction: String,
    val durationSeconds: Long,
)

internal data class LocalNotesFolderSelection(
    val uri: Uri,
    val flags: Int,
)
