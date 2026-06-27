package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

internal data class ContactNoteEditUiState(
    val phone: String,
    val titleText: String,
    val direction: String,
    val callAt: Long,
    val durationSeconds: Long,
    val isGeneralNote: Boolean,
    val topic: ContactNoteTopicState,
    val willEnableServerSync: Boolean = false,
)

internal class ContactNoteEditUi(
    private val activity: Activity,
    private val state: () -> ContactNoteEditUiState,
    private val onTopicSelected: (String, EditText) -> Unit,
    private val onNoteInputReady: (EditText) -> Unit,
    private val onTopicSpinnerReady: (Spinner) -> Unit,
    private val saveAndClose: (String) -> Unit,
    private val saveAndOpenCalendar: (String) -> Unit,
    private val close: () -> Unit,
) {
    private val topicFieldUi by lazy { ContactNoteTopicFieldUi(activity, ::dp) }

    fun buildContent(): ScrollView {
        val input = noteInput()
        onNoteInputReady(input)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setBackgroundColor(Color.rgb(248, 250, 252))
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedRect(Color.WHITE, dp(22), Color.TRANSPARENT, 0)
            elevation = dp(5).toFloat()
        }

        card.addView(titleRow(input))
        val current = state()
        if (!current.isGeneralNote && current.callAt > 0L) card.addView(callInfoRow(current))
        card.addView(crmModeRow(current))
        topicRow(current, input)?.let(card::addView)
        card.addView(input)
        card.addView(actionRow(input))
        root.addView(card)

        input.requestFocus()
        input.postDelayed({
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 250)

        return ScrollView(activity).apply { addView(root) }
    }

    private fun titleRow(input: EditText): LinearLayout {
        val current = state()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(ImageView(activity).apply {
                setImageResource(if (current.isGeneralNote) R.drawable.ic_note_lines else R.drawable.ic_chat_note)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(35), dp(35)).apply { marginEnd = dp(8) }
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(activity).apply {
                    text = activity.getString(
                        if (current.isGeneralNote) R.string.dynamic_note_general_title else R.string.dynamic_note_call_title,
                    )
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(17, 24, 39))
                })
                addView(TextView(activity).apply {
                    text = current.titleText
                    textSize = 13f
                    setTextColor(Color.rgb(107, 114, 128))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(iconButton(R.drawable.ic_calendar_event, activity.getString(R.string.dynamic_action_calendar)) {
                saveAndOpenCalendar(input.text?.toString().orEmpty())
            })
            addView(iconButton(R.drawable.ic_popup_close, activity.getString(R.string.dynamic_sms_close)) { close() })
        }
    }

    private fun callInfoRow(current: ContactNoteEditUiState): TextView {
        return TextView(activity).apply {
            text = listOf(
                PhoneCallReader.directionLabel(current.direction),
                PhoneCallReader.formatStartedAt(current.callAt),
                PhoneCallReader.formatDuration(current.durationSeconds),
            ).filter { it.isNotBlank() }.joinToString(" • ")
            textSize = 13f
            setTextColor(Color.rgb(107, 114, 128))
            setPadding(0, dp(12), 0, 0)
        }
    }

    private fun crmModeRow(current: ContactNoteEditUiState): TextView {
        val enabled = CrmContactSyncStore.isEnabled(activity, current.phone)
        val text = when {
            enabled -> activity.getString(R.string.dynamic_note_crm_enabled)
            current.willEnableServerSync -> activity.getString(R.string.note_server_sync_will_be_enabled)
            else -> activity.getString(R.string.dynamic_note_local_only)
        }
        val color = when {
            enabled || current.willEnableServerSync -> Color.rgb(20, 83, 45)
            else -> Color.rgb(107, 114, 128)
        }
        return TextView(activity).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(color)
            setPadding(0, dp(10), 0, 0)
        }
    }

    private fun topicRow(current: ContactNoteEditUiState, input: EditText): LinearLayout? = topicFieldUi.create(
        state = current.topic,
        onSelected = { companyId -> onTopicSelected(companyId, input) },
        onSpinnerReady = onTopicSpinnerReady,
    )

    private fun noteInput(): EditText {
        val current = state()
        val colors = if (current.isGeneralNote) NoteUiStyle.General else NoteUiStyle.Call
        val value = if (current.isGeneralNote) {
            ContactNoteReader.generalNoteForPhone(activity, current.phone)
        } else {
            ContactNoteReader.callNoteForPhone(activity, current.phone, current.callAt, current.direction)
        }
        return EditText(activity).apply {
            setText(value)
            setSelection(text?.length ?: 0)
            minLines = if (current.isGeneralNote) 4 else 3
            maxLines = 8
            textSize = 16f
            setTextColor(colors.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(colors.background, dp(12), colors.border, dp(1))
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
    }

    private fun actionRow(input: EditText): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
            addView(secondaryTextButton(activity.getString(R.string.dynamic_note_cancel)) { close() })
            addView(TextView(activity).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            addView(primaryTextButton(activity.getString(R.string.dynamic_note_save)) { saveAndClose(input.text?.toString().orEmpty()) })
        }
    }

    private fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(activity).apply {
            setImageResource(drawableRes)
            contentDescription = description
            background = roundedRect(Color.rgb(243, 244, 246), dp(18), Color.TRANSPARENT, 0)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) }
        }
    }

    private fun primaryTextButton(textValue: String, action: () -> Unit): TextView {
        return TextView(activity).apply {
            text = textValue
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedRect(Color.rgb(55, 65, 81), dp(12), Color.TRANSPARENT, 0)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { action() }
        }
    }

    private fun secondaryTextButton(textValue: String, action: () -> Unit): TextView {
        return TextView(activity).apply {
            text = textValue
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(55, 65, 81))
            background = roundedRect(Color.rgb(243, 244, 246), dp(12), Color.TRANSPARENT, 0)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { action() }
        }
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
