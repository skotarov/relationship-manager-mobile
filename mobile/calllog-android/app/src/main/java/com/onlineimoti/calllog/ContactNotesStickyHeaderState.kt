package com.onlineimoti.calllog

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Views shared between the scrollable History header and its fixed overlays. */
internal data class ContactNotesStickyActions(
    val overlay: LinearLayout,
    val topBar: LinearLayout,
    val compactTitle: TextView,
    val identityAnchor: View,
)
