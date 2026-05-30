package com.onlineimoti.calllog

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews

internal object SystemLookupNotificationView {
    private const val ICON_PERSON_TEXT = "👤"

    fun build(
        context: Context,
        title: String,
        rows: List<String>,
        editIntent: PendingIntent,
        allNotesIntent: PendingIntent,
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.notification_lookup_heads_up).apply {
            setTextViewText(R.id.notificationHeadsUpTitle, title)
            bindRow(this, 0, rows.getOrNull(0))
            bindRow(this, 1, rows.getOrNull(1))
            bindRow(this, 2, rows.getOrNull(2))
            setOnClickPendingIntent(R.id.notificationHeadsUpNoteAction, editIntent)
            setOnClickPendingIntent(R.id.notificationHeadsUpHistoryAction, allNotesIntent)
        }
    }

    private fun bindRow(remoteViews: RemoteViews, index: Int, rawLine: String?) {
        val rowId = when (index) {
            0 -> R.id.notificationHeadsUpRow1
            1 -> R.id.notificationHeadsUpRow2
            else -> R.id.notificationHeadsUpRow3
        }
        val iconId = when (index) {
            0 -> R.id.notificationHeadsUpIcon1
            1 -> R.id.notificationHeadsUpIcon2
            else -> R.id.notificationHeadsUpIcon3
        }
        val textId = when (index) {
            0 -> R.id.notificationHeadsUpText1
            1 -> R.id.notificationHeadsUpText2
            else -> R.id.notificationHeadsUpText3
        }
        val line = rawLine.orEmpty().trim()
        if (line.isBlank()) {
            remoteViews.setViewVisibility(rowId, View.GONE)
            return
        }

        remoteViews.setViewVisibility(rowId, View.VISIBLE)
        when {
            line.startsWith("☰") -> bindIconRow(remoteViews, iconId, textId, R.drawable.ic_note_lines, line.removePrefix("☰").trim())
            line.startsWith("💬") -> bindIconRow(remoteViews, iconId, textId, R.drawable.ic_chat_note, line.removePrefix("💬").trim())
            else -> {
                remoteViews.setViewVisibility(iconId, View.GONE)
                remoteViews.setTextViewText(textId, "$ICON_PERSON_TEXT $line")
            }
        }
    }

    private fun bindIconRow(remoteViews: RemoteViews, iconId: Int, textId: Int, drawableRes: Int, text: String) {
        remoteViews.setViewVisibility(iconId, View.VISIBLE)
        remoteViews.setImageViewResource(iconId, drawableRes)
        remoteViews.setTextViewText(textId, text)
    }
}