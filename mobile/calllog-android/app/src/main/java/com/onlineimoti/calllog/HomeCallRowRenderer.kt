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
    private val companyScopeChipsUi by lazy {
        HomeCompanyScopeChipsUi(activity, dp, roundedRect)
    }
    private val smsRowRenderer by lazy {
        HomeSmsRowRenderer(
            activity = activity,
            dp = dp,
            noteKey = noteKey,
            roundedRect = roundedRect,
            companyScopeChipsUi = companyScopeChipsUi,
            openContactNotesScreen = openContactNotesScreen,
            togglePhoneFilter = togglePhoneFilter,
            noteSyncStatus = ::noteSyncStatus,
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
        if (call.isSms) {
            return smsRowRenderer.compactRow(
                call = call,
                displayName = displayName,
                contactNote = contactNote,
                companyGeneralNoteLabels = companyGeneralNoteLabels,
                callNote = callNote,
                highlightQuery = highlightQuery,
                showContactIdentity = showContactIdentity,
                showGeneralContactNote = showGeneralContactNote,
                showQuickActions = showQuickActions,
            )
        }

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

        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(callMetaText(call, displayName, highlightQuery, showContactIdentity))
        if (showContactIdentity) textColumn.addView(mainNameRow(call, displayName, highlightQuery))
        addCrmStatus(textColumn, call, displayName, companyGeneralNoteLabels, showGeneralContactNote)
        addGeneralNote(textColumn, contactNote, highlightQuery, showGeneralContactNote)
        addCallNote(textColumn, call, callNote, highlightQuery)

        row.addView(textColumn)
        row.addView(actions(call, displayName, callNote, showQuickActions))
        card.addView(row)
        return card
    }

    private fun callStatusButton(call: PhoneCallRecord): ImageButton = ImageButton(activity).apply {
        setImageResource(callStatusIcon(call))
        contentDescription = activity.getString(R.string.dynamic_action_call)
        background = null
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(36)).apply { marginEnd = dp(6) }
        setOnClickListener { openDialer(call.number) }
    }

    private fun callMetaText(
        call: PhoneCallRecord,
        displayName: String,
        highlightQuery: String,
        showContactIdentity: Boolean,
    ): TextView {
        val hasContactName = showContactIdentity && displayName.isNotBlank() && noteKey(displayName) != noteKey(call.number)
        val metaText = listOf(
            PhoneCallReader.formatStartedAt(call.startedAt),
            PhoneCallReader.formatDuration(call.durationSeconds),
            call.number.takeIf { hasContactName },
        ).filter { !it.isNullOrBlank() }.joinToString(" • ")
        val mutedTextColor = activity.getColor(R.color.calllog_muted_text)
        return TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(metaText, highlightQuery, mutedTextColor)
            setTextColor(mutedTextColor)
            textSize = 12.5f
            maxLines = 1
        }
    }

    private fun addCrmStatus(
        column: LinearLayout,
        call: PhoneCallRecord,
        displayName: String,
        companyGeneralNoteLabels: List<HomeCompanyScopeLabel>?,
        showGeneralContactNote: Boolean,
    ) {
        val crmClient = showGeneralContactNote &&
            CallReportRemoteAccess.isReady(ConfigStore.load(activity.applicationContext)) &&
            CrmContactSyncStore.isEnabled(activity.applicationContext, call.number)
        if (!crmClient) return
        column.addView(companyScopeChipsUi.create(
            labels = companyGeneralNoteLabels,
            crmClient = true,
            onClick = { openContactNotesScreen(call, displayName) },
        ))
    }

    private fun addGeneralNote(
        column: LinearLayout,
        contactNote: String?,
        highlightQuery: String,
        showGeneralContactNote: Boolean,
    ) {
        if (!showGeneralContactNote || contactNote.isNullOrBlank()) return
        val colors = NoteUiStyle.General
        column.addView(TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(contactNote, highlightQuery, colors.text)
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

    private fun addCallNote(
        column: LinearLayout,
        call: PhoneCallRecord,
        callNote: HomeCallNote?,
        highlightQuery: String,
    ) {
        val note = callNote?.takeIf { it.text.isNotBlank() } ?: return
        val colors = NoteUiStyle.Call
        column.addView(TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(note.text, highlightQuery, colors.text)
            val iconRes = if (ServerRecordIndex.isCallNoteConfirmed(activity, call.number, call.startedAt, call.direction)) {
                R.drawable.ic_cloud_note
            } else {
                R.drawable.ic_chat_note
            }
            val icon = activity.getDrawable(iconRes)?.apply {
                setBounds(0, 0, dp(NOTE_ICON_SIZE_DP), dp(NOTE_ICON_SIZE_DP))
            }
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

    private fun actions(
        call: PhoneCallRecord,
        displayName: String,
        callNote: HomeCallNote?,
        showQuickActions: Boolean,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(3) }
        if (showQuickActions) {
            addView(iconButton(
                drawableRes = R.drawable.ic_filter_calls,
                description = activity.getString(R.string.dynamic_action_filter),
                action = { togglePhoneFilter(call.number) },
            ))
        }
        val noteEditable = callNote?.editable != false
        addView(iconButton(
            drawableRes = R.drawable.ic_chat_note,
            description = if (noteEditable) activity.getString(R.string.dynamic_action_note) else "Само за преглед",
            action = { openContactNotePopupForCall(call, displayName, callNote) },
            enabled = noteEditable,
        ))
    }

    private fun noteSyncStatus(call: PhoneCallRecord): String? {
        if (CallReportDeferredCompanyAssignmentStore.isCallPending(activity, call.number, call.direction, call.startedAt)) {
            return activity.getString(R.string.dynamic_note_pending_company_choice)
        }
        if (!CallReportTopicNoteOutbox.isCallPending(activity, call.number, call.direction, call.startedAt)) return null
        val failure = CallReportTopicNoteOutbox.lastFailure(activity)
        return if (failure.isBlank()) {
            activity.getString(R.string.dynamic_note_pending_server_sync)
        } else {
            activity.getString(R.string.dynamic_note_pending_server_sync_failed, failure)
        }
    }

    private fun mainNameRow(call: PhoneCallRecord, displayName: String, highlightQuery: String): LinearLayout {
        val mainTextColor = activity.getColor(R.color.calllog_text)
        val titleValue = displayName.ifBlank { call.number }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
            addView(TextView(activity).apply {
                text = SearchTextHighlighter.highlightedText(titleValue, highlightQuery, mainTextColor)
                setTextColor(mainTextColor)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun iconButton(
        drawableRes: Int,
        description: String,
        action: () -> Unit,
        enabled: Boolean = true,
    ): ImageButton = ImageButton(activity).apply {
        setImageResource(drawableRes)
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
        CallLog.Calls.MISSED_TYPE,
        CallLog.Calls.VOICEMAIL_TYPE -> R.drawable.ic_call_missed
        CallLog.Calls.REJECTED_TYPE,
        CallLog.Calls.BLOCKED_TYPE -> R.drawable.ic_call_rejected
        CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_outgoing
        CallLog.Calls.INCOMING_TYPE -> R.drawable.ic_call_incoming
        else -> when {
            call.direction == "out" && call.durationSeconds <= 0L -> R.drawable.ic_call_rejected
            call.direction != "out" && call.durationSeconds <= 0L -> R.drawable.ic_call_missed
            call.direction == "out" -> R.drawable.ic_call_outgoing
            else -> R.drawable.ic_call_incoming
        }
    }

    private companion object {
        const val NOTE_ICON_SIZE_DP = 18
    }
}
