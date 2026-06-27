package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.CallLog
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

internal class HomeCallRowRenderer(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val noteKey: (String) -> String,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val openContactNotesScreen: (PhoneCallRecord, String) -> Unit,
    private val openContactNotePopupForCall: (PhoneCallRecord, String) -> Unit,
    private val openDialer: (String) -> Unit = {},
    private val togglePhoneFilter: (String) -> Unit = {},
) {
    private val companyScopeChipsUi by lazy {
        HomeCompanyScopeChipsUi(activity, dp, roundedRect)
    }

    fun compactCallRow(
        call: PhoneCallRecord,
        displayName: String,
        contactNote: String? = null,
        companyGeneralNoteLabels: List<HomeCompanyScopeLabel>? = null,
        callNote: String? = null,
        highlightQuery: String = "",
        showContactIdentity: Boolean = true,
        showGeneralContactNote: Boolean = true,
        showQuickActions: Boolean = true,
    ): MaterialCardView {
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

        if (call.isSms) {
            row.addView(ImageView(activity).apply {
                setImageResource(if (call.direction == "sms_out") R.drawable.ic_sms_bubble_left else R.drawable.ic_sms_bubble_right)
                contentDescription = call.smsDirectionLabel
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(36)).apply { marginEnd = dp(6) }
            })
        } else {
            row.addView(ImageButton(activity).apply {
                setImageResource(callStatusIcon(call))
                contentDescription = activity.getString(R.string.dynamic_action_call)
                background = null
                setBackgroundColor(Color.TRANSPARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(7), dp(7), dp(7), dp(7))
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(36)).apply { marginEnd = dp(6) }
                setOnClickListener { openDialer(call.number) }
            })
        }

        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(activity).apply {
            val hasContactName = showContactIdentity && displayName.isNotBlank() && noteKey(displayName) != noteKey(call.number)
            val metaText = if (call.isSms) {
                listOf(PhoneCallReader.formatStartedAt(call.startedAt), call.smsDirectionLabel, call.number.takeIf { hasContactName })
                    .filter { !it.isNullOrBlank() }.joinToString(" • ")
            } else {
                listOf(PhoneCallReader.formatStartedAt(call.startedAt), PhoneCallReader.formatDuration(call.durationSeconds), call.number.takeIf { hasContactName })
                    .filter { !it.isNullOrBlank() }.joinToString(" • ")
            }
            val mutedTextColor = activity.getColor(R.color.calllog_muted_text)
            text = highlightedText(metaText, highlightQuery, mutedTextColor)
            setTextColor(mutedTextColor)
            textSize = 12.5f
            maxLines = 1
        })
        if (showContactIdentity) textColumn.addView(mainNameRow(call, displayName, highlightQuery))
        if (call.isSms) {
            textColumn.addView(TextView(activity).apply {
                val body = call.smsBody.ifBlank { activity.getString(R.string.dynamic_sms_empty_body) }
                text = highlightedText(body, highlightQuery, activity.getColor(R.color.calllog_text))
                setTextColor(activity.getColor(R.color.calllog_text))
                textSize = 13f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (showGeneralContactNote && !companyGeneralNoteLabels.isNullOrEmpty()) {
            textColumn.addView(companyScopeChipsUi.create(companyGeneralNoteLabels))
        }
        if (showGeneralContactNote && !contactNote.isNullOrBlank()) {
            val colors = NoteUiStyle.General
            textColumn.addView(TextView(activity).apply {
                text = highlightedText(contactNote, highlightQuery, colors.text)
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
        if (!callNote.isNullOrBlank()) {
            val colors = NoteUiStyle.Call
            textColumn.addView(TextView(activity).apply {
                text = highlightedText(callNote, highlightQuery, colors.text)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_chat_note, 0, 0, 0)
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
        }

        row.addView(textColumn)

        if (showQuickActions || !call.isSms) {
            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(3) }
            }
            if (showQuickActions) {
                actions.addView(iconButton(R.drawable.ic_filter_calls, activity.getString(R.string.dynamic_action_filter)) { togglePhoneFilter(call.number) })
            }
            if (!call.isSms) {
                actions.addView(iconButton(R.drawable.ic_chat_note, activity.getString(R.string.dynamic_action_note)) {
                    openContactNotePopupForCall(call, displayName)
                })
            }
            row.addView(actions)
        }

        card.addView(row)
        return card
    }

    private fun mainNameRow(call: PhoneCallRecord, displayName: String, highlightQuery: String): LinearLayout {
        val mainTextColor = activity.getColor(R.color.calllog_text)
        val titleValue = displayName.ifBlank { call.number }
        val syncEnabled = CrmContactSyncStore.isEnabled(activity.applicationContext, call.number)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
            addView(TextView(activity).apply {
                text = highlightedText(titleValue, highlightQuery, mainTextColor)
                setTextColor(mainTextColor)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (syncEnabled) {
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_cloud_note)
                    contentDescription = activity.getString(R.string.dynamic_crm_synced_notes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(19), dp(19)).apply { marginStart = dp(5) }
                })
            }
        }
    }

    private fun highlightedText(value: String, query: String, textColor: Int): CharSequence {
        val trimmedQuery = query.trim()
        if (value.isBlank() || trimmedQuery.isBlank()) return value
        val spannable = SpannableString(value)
        applyTextHighlight(spannable, value, trimmedQuery, textColor)
        applyDigitHighlight(spannable, value, trimmedQuery, textColor)
        return spannable
    }

    private fun applyTextHighlight(spannable: SpannableString, value: String, query: String, textColor: Int) {
        val lowerValue = value.lowercase()
        val lowerQuery = query.lowercase()
        var start = lowerValue.indexOf(lowerQuery)
        while (start >= 0) {
            applyHighlightSpan(spannable, start, start + query.length, textColor)
            start = lowerValue.indexOf(lowerQuery, start + query.length)
        }
    }

    private fun applyDigitHighlight(spannable: SpannableString, value: String, query: String, textColor: Int) {
        val queryDigits = query.filter { it.isDigit() }
        if (queryDigits.length < 3) return
        val digitCharIndexes = value.mapIndexedNotNull { index, char -> index.takeIf { char.isDigit() } }
        val valueDigits = digitCharIndexes.map { value[it] }.joinToString("")
        var digitStart = valueDigits.indexOf(queryDigits)
        while (digitStart >= 0) {
            val charStart = digitCharIndexes.getOrNull(digitStart) ?: break
            val charEnd = (digitCharIndexes.getOrNull(digitStart + queryDigits.length - 1) ?: break) + 1
            applyHighlightSpan(spannable, charStart, charEnd, textColor)
            digitStart = valueDigits.indexOf(queryDigits, digitStart + queryDigits.length)
        }
    }

    private fun applyHighlightSpan(spannable: SpannableString, start: Int, end: Int, textColor: Int) {
        if (start < 0 || end <= start || end > spannable.length) return
        spannable.setSpan(BackgroundColorSpan(textColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(activity).apply {
            setImageResource(drawableRes)
            contentDescription = description
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(36))
            setOnClickListener { action() }
        }
    }

    private fun callStatusIcon(call: PhoneCallRecord): Int = when (call.callType) {
        CallLog.Calls.MISSED_TYPE,
        CallLog.Calls.VOICEMAIL_TYPE -> R.drawable.ic_call_missed
        CallLog.Calls.REJECTED_TYPE,
        CallLog.Calls.BLOCKED_TYPE -> R.drawable.ic_call_rejected
        CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_outgoing
        CallLog.Calls.INCOMING_TYPE -> R.drawable.ic_call_incoming
        // A synthetic or legacy row has no provider status. Preserve the prior
        // duration-based fallback only for those records.
        else -> when {
            call.direction == "out" && call.durationSeconds <= 0L -> R.drawable.ic_call_rejected
            call.direction != "out" && call.durationSeconds <= 0L -> R.drawable.ic_call_missed
            call.direction == "out" -> R.drawable.ic_call_outgoing
            else -> R.drawable.ic_call_incoming
        }
    }
}
