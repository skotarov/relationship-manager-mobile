package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/** Renders SMS timeline cards shown within the combined Call Log. */
internal class HomeSmsRowRenderer(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val noteKey: (String) -> String,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val companyScopeChipsUi: HomeCompanyScopeChipsUi,
    private val openContactNotesScreen: (PhoneCallRecord, String) -> Unit,
    private val togglePhoneFilter: (String) -> Unit,
    private val noteSyncStatus: (PhoneCallRecord) -> String?,
) {
    fun compactRow(
        call: PhoneCallRecord,
        displayName: String,
        contactNote: String?,
        companyGeneralNoteLabels: List<HomeCompanyScopeLabel>?,
        callNote: HomeCallNote?,
        highlightQuery: String,
        showContactIdentity: Boolean,
        showGeneralContactNote: Boolean,
        showQuickActions: Boolean,
    ): MaterialCardView {
        val hasContactName = showContactIdentity && displayName.isNotBlank() && noteKey(displayName) != noteKey(call.number)
        val title = displayName.ifBlank { call.number }
        val metaText = listOf(
            PhoneCallReader.formatStartedAt(call.startedAt),
            call.smsDirectionLabel,
            call.number.takeIf { hasContactName },
        ).filter { !it.isNullOrBlank() }.joinToString(" • ")
        val crmClient = showGeneralContactNote &&
            CallReportRemoteAccess.isReady(ConfigStore.load(activity.applicationContext)) &&
            CrmContactSyncStore.isEnabled(activity.applicationContext, call.number)

        return SmsTimelineCard.create(
            activity = activity,
            dp = dp,
            message = call,
            displayName = SearchTextHighlighter.highlightedText(title, highlightQuery, activity.getColor(R.color.calllog_text)),
            metaText = SearchTextHighlighter.highlightedText(metaText, highlightQuery, activity.getColor(R.color.calllog_muted_text)),
            bodyText = SearchTextHighlighter.highlightedText(
                call.smsBody.ifBlank { activity.getString(R.string.dynamic_sms_empty_body) },
                highlightQuery,
                activity.getColor(R.color.calllog_text),
            ),
            showTitle = showContactIdentity,
            actions = if (showQuickActions) {
                listOf(
                    SmsTimelineCard.Action(
                        drawableRes = R.drawable.ic_filter_calls,
                        contentDescription = activity.getString(R.string.dynamic_action_filter),
                        onClick = { togglePhoneFilter(call.number) },
                    ),
                )
            } else {
                emptyList()
            },
            beforeBody = { textColumn ->
                if (crmClient) {
                    textColumn.addView(companyScopeChipsUi.create(
                        labels = companyGeneralNoteLabels,
                        crmClient = true,
                        onClick = { openContactNotesScreen(call, displayName) },
                    ))
                }
            },
            afterBody = { textColumn ->
                addGeneralNote(textColumn, contactNote, highlightQuery, showGeneralContactNote)
                addCallNote(textColumn, call, callNote, highlightQuery)
            },
            onClick = { openContactNotesScreen(call, displayName) },
        )
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

    private companion object {
        const val NOTE_ICON_SIZE_DP = 18
    }
}
