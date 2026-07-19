package com.onlineimoti.calllog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.CallLog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** History-style phone row used only by the filtered Full Log screen. */
internal class FullLogTimelineRowUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val openHistory: (PhoneCallRecord, String) -> Unit,
    private val openNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    private val syncStatus: (PhoneCallRecord) -> String?,
) {
    fun create(
        call: PhoneCallRecord,
        displayName: String,
        callNote: HomeCallNote?,
    ): MaterialCardView {
        val notes = callNote?.expandedNotes().orEmpty().filter { it.text.isNotBlank() }
        val allForeign = notes.isNotEmpty() && notes.all { !it.editable }
        val palette = palette(notes.isNotEmpty(), allForeign)
        val card = MaterialCardView(activity).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(palette.border)
            setCardBackgroundColor(palette.background)
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener { openHistory(call, displayName) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        column.addView(metaText(call, palette.meta))
        notes.forEach { note ->
            companyBadge(note.companyId, !note.editable)?.let(column::addView)
            column.addView(noteText(note, if (note.editable) palette.text else FOREIGN_TEXT))
            if (!note.editable) {
                column.addView(authorText(note.authorName.ifBlank { "друг потребител" }))
            }
        }
        if (notes.isNotEmpty()) syncStatus(call)?.let { column.addView(statusText(it)) }
        row.addView(column)
        val editableNote = notes.firstOrNull { it.editable }
        val editorEnabled = notes.isEmpty() || editableNote != null
        row.addView(noteButton(call, displayName, editableNote, editorEnabled))
        card.addView(row)
        return card
    }

    private fun metaText(call: PhoneCallRecord, color: Int): TextView = TextView(activity).apply {
        text = listOf(
            "Телефон",
            PhoneCallReader.formatStartedAt(call.startedAt),
            callTypeLabel(call),
            PhoneCallReader.formatDuration(call.durationSeconds),
        ).filter { it.isNotBlank() }.joinToString(" • ")
        textSize = 12.5f
        setTextColor(color)
        maxLines = 1
    }

    private fun noteText(note: HomeCallNote, color: Int): TextView = TextView(activity).apply {
        text = note.text.trim()
        textSize = 14.5f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(5), 0, 0)
        if (note.fromServer || note.companyId.isNotBlank()) {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_cloud_note_filled, 0, 0, 0)
            compoundDrawablePadding = dp(5)
            compoundDrawableTintList = ColorStateList.valueOf(
                activity.getColor(R.color.callreport_icon_background),
            )
        }
    }

    private fun companyBadge(companyId: String, muted: Boolean): TextView? {
        val id = companyId.trim()
        if (id.isBlank()) return null
        return TextView(activity).apply {
            text = companyNameFor(id)
            textSize = 11.5f
            setTextColor(if (muted) FOREIGN_TEXT else Color.rgb(71, 85, 105))
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = roundedRect(
                if (muted) Color.rgb(226, 232, 240) else Color.rgb(241, 245, 249),
                dp(8),
                Color.TRANSPARENT,
                0,
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
    }

    private fun statusText(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 11.5f
        setTextColor(Color.rgb(146, 64, 14))
        setPadding(0, dp(6), 0, 0)
    }

    private fun authorText(value: String): TextView = TextView(activity).apply {
        text = "Записал: $value"
        textSize = 12f
        setTextColor(FOREIGN_TEXT)
        setPadding(0, dp(6), 0, 0)
    }

    private fun noteButton(
        call: PhoneCallRecord,
        displayName: String,
        note: HomeCallNote?,
        enabled: Boolean,
    ): ImageButton = ImageButton(activity).apply {
        setImageResource(R.drawable.ic_chat_note)
        contentDescription = activity.getString(
            if (enabled) R.string.dynamic_action_note else R.string.runtime_view_only,
        )
        background = null
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.38f
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(36)).apply {
            marginStart = dp(4)
        }
        setOnClickListener {
            if (enabled) openNoteEditor(call, displayName, note)
        }
    }

    private fun callTypeLabel(call: PhoneCallRecord): String = when (call.callType) {
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> "пропуснат"
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "отказан"
        CallLog.Calls.OUTGOING_TYPE -> "изходящ"
        CallLog.Calls.INCOMING_TYPE -> "входящ"
        else -> if (call.direction == "out") "изходящ" else "входящ"
    }

    private fun companyNameFor(companyId: String): String {
        val config = ConfigStore.load(activity.applicationContext)
        return CallReportTopicCompaniesCache.read(activity.applicationContext, config)
            ?.companies
            ?.firstOrNull { it.id == companyId }
            ?.name
            ?.trim()
            ?.ifBlank { companyId }
            ?: companyId
    }

    private fun palette(hasNote: Boolean, foreign: Boolean): Palette = when {
        foreign -> Palette(
            background = Color.rgb(241, 245, 249),
            border = Color.rgb(203, 213, 225),
            text = FOREIGN_TEXT,
            meta = FOREIGN_TEXT,
        )
        hasNote -> Palette(
            background = NoteUiStyle.Call.background,
            border = NoteUiStyle.Call.border,
            text = NoteUiStyle.Call.text,
            meta = Color.rgb(71, 85, 105),
        )
        else -> Palette(
            background = activity.getColor(R.color.calllog_surface),
            border = activity.getColor(R.color.calllog_border),
            text = activity.getColor(R.color.calllog_text),
            meta = activity.getColor(R.color.calllog_muted_text),
        )
    }

    private data class Palette(
        val background: Int,
        val border: Int,
        val text: Int,
        val meta: Int,
    )

    private companion object {
        val FOREIGN_TEXT: Int = Color.rgb(100, 116, 139)
    }
}
