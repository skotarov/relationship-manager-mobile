package com.onlineimoti.calllog

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews

internal object SystemLookupNotificationView {
    private val rowIds = intArrayOf(
        R.id.notificationHeadsUpRow1,
        R.id.notificationHeadsUpRow2,
        R.id.notificationHeadsUpRow3,
        R.id.notificationHeadsUpRow4,
        R.id.notificationHeadsUpRow5,
        R.id.notificationHeadsUpRow6,
        R.id.notificationHeadsUpRow7,
    )
    private val iconIds = intArrayOf(
        R.id.notificationHeadsUpIcon1,
        R.id.notificationHeadsUpIcon2,
        R.id.notificationHeadsUpIcon3,
        R.id.notificationHeadsUpIcon4,
        R.id.notificationHeadsUpIcon5,
        R.id.notificationHeadsUpIcon6,
        R.id.notificationHeadsUpIcon7,
    )
    private val textIds = intArrayOf(
        R.id.notificationHeadsUpText1,
        R.id.notificationHeadsUpText2,
        R.id.notificationHeadsUpText3,
        R.id.notificationHeadsUpText4,
        R.id.notificationHeadsUpText5,
        R.id.notificationHeadsUpText6,
        R.id.notificationHeadsUpText7,
    )

    fun build(
        context: Context,
        content: PostCallLookupDisplayContent,
        editIntent: PendingIntent,
        allNotesIntent: PendingIntent,
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.notification_lookup_heads_up).apply {
            setTextViewText(R.id.notificationHeadsUpTitle, content.header)
            rowIds.indices.forEach { index -> bindRow(this, index, content.rows.getOrNull(index)) }
            setOnClickPendingIntent(R.id.notificationHeadsUpNoteAction, editIntent)
            setOnClickPendingIntent(R.id.notificationHeadsUpHistoryAction, allNotesIntent)
        }
    }

    private fun bindRow(
        remoteViews: RemoteViews,
        index: Int,
        row: PostCallLookupDisplayRow?,
    ) {
        val rowId = rowIds[index]
        val iconId = iconIds[index]
        val textId = textIds[index]
        if (row == null || row.text.isBlank()) {
            remoteViews.setViewVisibility(rowId, View.GONE)
            return
        }

        remoteViews.setViewVisibility(rowId, View.VISIBLE)
        when (row.kind) {
            PostCallLookupDisplayRow.Kind.IDENTITY -> {
                remoteViews.setViewVisibility(iconId, View.GONE)
                remoteViews.setTextViewText(textId, row.text)
            }
            PostCallLookupDisplayRow.Kind.GENERAL_NOTE -> {
                bindIconRow(remoteViews, iconId, textId, R.drawable.ic_note_lines, row.text)
            }
            PostCallLookupDisplayRow.Kind.CALL_NOTE -> {
                bindIconRow(remoteViews, iconId, textId, R.drawable.ic_chat_note, row.text)
            }
        }
    }

    private fun bindIconRow(
        remoteViews: RemoteViews,
        iconId: Int,
        textId: Int,
        drawableRes: Int,
        text: String,
    ) {
        remoteViews.setViewVisibility(iconId, View.VISIBLE)
        remoteViews.setImageViewResource(iconId, drawableRes)
        remoteViews.setTextViewText(textId, text)
    }
}
