package com.onlineimoti.calllog

import android.widget.LinearLayout
import android.widget.TextView

/** Views shared between the scrollable History header and its fixed hosts. */
internal data class ContactNotesStickyActions(
    val actionRow: LinearLayout,
    val stickyActionRow: LinearLayout,
    val topBar: LinearLayout,
    val compactTitle: TextView,
)
