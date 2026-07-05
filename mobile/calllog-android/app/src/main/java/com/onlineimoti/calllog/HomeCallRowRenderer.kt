package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.CallLog
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** Renders phone-call rows in the combined Call Log timeline. */
internal class HomeCallRowRenderer(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val noteKey: (String) -> String,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val openContactNotesScreen: (PhoneCallRecord, String) -> Unit,
    private val openContactNotePopupForCall: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    private val openDialer: (String) -> Unit = {},
    private val togglePhoneFilter: (String) -> Unit = {},
) {
    private val companyScopeChipsUi by lazy { HomeCompanyScopeChipsUi(activity, dp, roundedRect) }
    private val notesUi by lazy { TimelineNotesUi(activity, dp, roundedRect) }
    private val smsRowRenderer by lazy {
        HomeSmsRowRenderer(
            activity, dp, noteKey, roundedRect, companyScopeChipsUi,
            openContactNotesScreen, togglePhoneFilter, ::noteSyncStatus,
        )
    }

    fun compactCallRow(
        call: PhoneCallRecord,
        displayName: String,
        contactNote: String? = null,
        companyGeneralNoteLabels: List<HomeCompanyScopeLabel>? = null,
        callNote: HomeCallNote? = null,
        highlightQuery: String = "",
        showContactIdentity: Boolean = true,
        showGeneralContactNote: Boolean = true,
        showQuickActions: Boolean = true,
    ): MaterialCardView {
        if (call.isSms) return smsRowRenderer.compactRow(
            call, displayName, contactNote, companyGeneralNoteLabels, callNote,
            highlightQuery, showContactIdentity, showGeneralContactNote, showQuickActions,
        )
        val card = MaterialCardView(activity).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(activity.getColor(R.color.calllog_border))
            setCardBackgroundColor(activity.getColor(R.color.calllog_surface))
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener { openContactNotesScreen(call, displayName) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        row.addView(callStatusButton(call))
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(callMetaText(call, displayName, highlightQuery, showContactIdentity))
        if (showContactIdentity) column.addView(mainNameRow(call, displayName, highlightQuery))
        addCrmStatus(column, call, displayName, companyGeneralNoteLabels, showGeneralContactNote)
        addGeneralNote(column, contactNote, highlightQuery, showGeneralContactNote)
        notesUi.addCompanyGeneralNotes(
            column = column,
            labels = companyGeneralNoteLabels,
            highlightQuery = highlightQuery,
            visible = showGeneralContactNote,
        )
        addCallNote(column, call, callNote, highlightQuery)
        row.addView(column)
        row.addView(actions(call, displayName, callNote, showQuickActions))
        card.addView(row)
        return card
    }

    private fun callStatusButton(call: PhoneCallRecord) = ImageButton(activity).apply {
        setImageResource(callStatusIcon(call))
        contentDescription = activity.getString(R.string.dynamic_action_call)
        background = null
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(36)).apply { marginEnd = dp(6) }
        setOnClickListener { openDialer(call.number) }
    }

    private fun callMetaText(call: PhoneCallRecord, displayName: String, query: String, showIdentity: Boolean): TextView {
        val hasName = showIdentity && displayName.isNotBlank() && noteKey(displayName) != noteKey(call.number)
        val value = listOf(
            PhoneCallReader.formatStartedAt(call.startedAt),
            PhoneCallReader.formatDuration(call.durationSeconds),
            call.number.takeIf { hasName },
        ).filter { !it.isNullOrBlank() }.joinToString(" • ")
        val color = activity.getColor(R.color.calllog_muted_text)
        return TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(value, query, color)
            setTextColor(color)
            textSize = 12.5f
            maxLines = 1
        }
    }

    private fun addCrmStatus(
        column: LinearLayout,
        call: PhoneCallRecord,
        displayName: String,
        labels: List<HomeCompanyScopeLabel>?,
        visible: Boolean,
    ) {
        val crmClient = visible && CallReportRemoteAccess.isReady(ConfigStore.load(activity.applicationContext)) &&
            CrmContactSyncStore.isEnabled(activity.applicationContext, call.number)
        if (!crmClient) return
        column.addView(companyScopeChipsUi.create(labels, crmClient = true) {
            openContactNotesScreen(call, displayName)
        })
    }

    private fun addGeneralNote(column: LinearLayout, note: String?, query: String, visible: Boolean) {
        if (!visible || note.isNullOrBlank()) return
        val colors = NoteUiStyle.General
        column.addView(TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(note, query, colors.text)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_note_lines, 0, 0, 0)
            compoundDrawablePadding = dp(4)
            setTextColor(colors.text)
            textSize = 12.5f
            maxLines = 2
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = roundedRect(colors.background, dp(9), colors.border, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        })
    }

    private fun addCallNote(column: LinearLayout, call: PhoneCallRecord, callNote: HomeCallNote?, query: String) {
        val note = callNote?.takeIf { it.text.isNotBlank() } ?: return
        val colors = NoteUiStyle.Call
        column.addView(TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(note.text, query, colors.text)
            val drawable = if (ServerRecordIndex.isCallNoteConfirmed(activity, call.number, call.startedAt, call.direction)) {
                R.drawable.ic_cloud_note
            } else R.drawable.ic_chat_note
            val icon = activity.getDrawable(drawable)?.apply { setBounds(0, 0, dp(NOTE_ICON_SIZE_DP), dp(NOTE_ICON_SIZE_DP)) }
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(5)
            setTextColor(colors.text)
            textSize = 12.5f
            maxLines = 3
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = roundedRect(colors.background, dp(9), colors.border, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        })
        noteSyncStatus(call)?.let { status ->
            column.addView(TextView(activity).apply {
                text = status
                textSize = 11.5f
                setTextColor(Color.rgb(146, 64, 14))
                setPadding(dp(8), dp(4), dp(8), 0)
            })
        }
    }

    private fun actions(call: PhoneCallRecord, name: String, note: HomeCallNote?, quick: Boolean) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(3) }
        if (quick) addView(iconButton(
            R.drawable.ic_filter_calls,
            activity.getString(R.string.dynamic_action_filter),
            { togglePhoneFilter(call.number) },
        ))
        val editable = note?.editable != false
        addView(iconButton(
            R.drawable.ic_chat_note,
            if (editable) activity.getString(R.string.dynamic_action_note) else "Само за преглед",
            { openContactNotePopupForCall(call, name, note) }, editable,
        ))
    }

    private fun noteSyncStatus(call: PhoneCallRecord): String? {
        if (CallReportDeferredCompanyAssignmentStore.isCallPending(activity, call.number, call.direction, call.startedAt)) {
            return activity.getString(R.string.dynamic_note_pending_company_choice)
        }
        if (!CallReportTopicNoteOutbox.isCallPending(activity, call.number, call.direction, call.startedAt)) return null
        val failure = CallReportTopicNoteOutbox.lastFailure(activity)
        return if (failure.isBlank()) activity.getString(R.string.dynamic_note_pending_server_sync)
        else activity.getString(R.string.dynamic_note_pending_server_sync_failed, failure)
    }

    private fun mainNameRow(call: PhoneCallRecord, displayName: String, query: String) = LinearLayout(activity).apply {
        val color = activity.getColor(R.color.calllog_text)
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, 0)
        addView(TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(displayName.ifBlank { call.number }, query, color)
            setTextColor(color)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun iconButton(drawable: Int, description: String, action: () -> Unit, enabled: Boolean = true) = ImageButton(activity).apply {
        setImageResource(drawable)
        contentDescription = description
        background = null
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.38f
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(36))
        setOnClickListener { if (enabled) action() }
    }

    private fun callStatusIcon(call: PhoneCallRecord): Int = when (call.callType) {
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> R.drawable.ic_call_missed
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> R.drawable.ic_call_rejected
        CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_outgoing
        CallLog.Calls.INCOMING_TYPE -> R.drawable.ic_call_incoming
        else -> when {
            call.direction == "out" && call.durationSeconds <= 0L -> R.drawable.ic_call_rejected
            call.direction != "out" && call.durationSeconds <= 0L -> R.drawable.ic_call_missed
            call.direction == "out" -> R.drawable.ic_call_outgoing
            else -> R.drawable.ic_call_incoming
        }
    }

    private companion object { const val NOTE_ICON_SIZE_DP = 18 }
}
