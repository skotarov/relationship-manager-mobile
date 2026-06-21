package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private val openDialer: (String) -> Unit,
    private val togglePhoneFilter: (String) -> Unit,
    private val openContactNotePopupForCall: (PhoneCallRecord, String) -> Unit,
) {
    fun compactCallRow(
        call: PhoneCallRecord,
        displayName: String,
        contactNote: String? = null,
        callNote: String? = null,
        highlightQuery: String = "",
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        row.addView(TextView(activity).apply {
            text = callIcon(call)
            textSize = if (call.isSms) 28f else 36f
            gravity = Gravity.CENTER
            setTextColor(callIconColor(call))
            layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        })

        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(activity).apply {
            val hasContactName = displayName.isNotBlank() && noteKey(displayName) != noteKey(call.number)
            val metaText = if (call.isSms) {
                listOf(
                    PhoneCallReader.formatStartedAt(call.startedAt),
                    call.smsDirectionLabel,
                    call.number.takeIf { hasContactName },
                ).filter { !it.isNullOrBlank() }.joinToString(" • ")
            } else {
                listOf(
                    PhoneCallReader.formatStartedAt(call.startedAt),
                    PhoneCallReader.formatDuration(call.durationSeconds),
                    call.number.takeIf { hasContactName },
                ).filter { !it.isNullOrBlank() }.joinToString(" • ")
            }
            val mutedTextColor = activity.getColor(R.color.calllog_muted_text)
            text = highlightedText(metaText, highlightQuery, mutedTextColor)
            setTextColor(mutedTextColor)
            textSize = 12.5f
            maxLines = 1
        })
        textColumn.addView(mainNameRow(call, displayName, highlightQuery))
        if (call.isSms) {
            textColumn.addView(TextView(activity).apply {
                val body = call.smsBody.ifBlank { "(SMS без текст)" }
                text = highlightedText(body, highlightQuery, activity.getColor(R.color.calllog_text))
                setTextColor(activity.getColor(R.color.calllog_text))
                textSize = 13f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (!contactNote.isNullOrBlank()) {
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
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
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
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            })
        }
        row.addView(textColumn)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(3) }
        }
        actions.addView(iconButton(R.drawable.ic_phone_call, "Обади се") { openDialer(call.number) })
        actions.addView(iconButton(R.drawable.ic_filter_calls, "Филтър") { togglePhoneFilter(call.number) })
        if (!call.isSms) {
            actions.addView(iconButton(R.drawable.ic_chat_note, "Бележка") { openContactNotePopupForCall(call, displayName) })
        }
        row.addView(actions)

        card.addView(row)
        return card
    }

    private fun mainNameRow(call: PhoneCallRecord, displayName: String, highlightQuery: String): LinearLayout {
        val mainTextColor = activity.getColor(R.color.calllog_text)
        val titleValue = displayName.ifBlank { call.number }
        val showCloud = ConfigStore.load(activity).remoteEnabled && CrmContactSyncStore.isEnabled(activity, call.number)
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
            if (showCloud) {
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_cloud_note)
                    contentDescription = "Има синхронизирани CRM бележки"
                    alpha = 0.9f
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) }
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
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(36))
            setOnClickListener { action() }
        }
    }

    private fun callIcon(call: PhoneCallRecord): String {
        return when {
            call.isSms -> "✉"
            call.direction == "out" -> "↗"
            else -> "↙"
        }
    }

    private fun callIconColor(call: PhoneCallRecord): Int {
        if (call.isSms) return Color.rgb(124, 58, 237)
        if (call.durationSeconds <= 0) return Color.rgb(239, 68, 68)
        return when (call.direction) {
            "out" -> Color.rgb(34, 197, 94)
            else -> Color.rgb(59, 130, 246)
        }
    }
}
