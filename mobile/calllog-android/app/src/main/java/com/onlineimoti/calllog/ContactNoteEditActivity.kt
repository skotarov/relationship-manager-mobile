package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ContactNoteEditActivity : Activity() {
    private var phone: String = ""
    private var titleText: String = ""
    private var direction: String = ""
    private var callAt: Long = 0L
    private var durationSeconds: Long = 0L
    private var isGeneralNote = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        phone = intent.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty()
        titleText = intent.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty().ifBlank { phone.ifBlank { "Бележка" } }
        direction = intent.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty()
        callAt = intent.getLongExtra(PostCallOverlayService.EXTRA_CALL_AT, 0L)
        durationSeconds = intent.getLongExtra(PostCallOverlayService.EXTRA_DURATION, 0L)
        isGeneralNote = intent.getStringExtra(PostCallOverlayService.EXTRA_MODE) == PostCallOverlayService.MODE_GENERAL_NOTE

        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val input = noteInput()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setBackgroundColor(Color.rgb(248, 250, 252))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedRect(Color.WHITE, dp(22), Color.TRANSPARENT, 0)
            elevation = dp(5).toFloat()
        }

        card.addView(titleRow())
        if (!isGeneralNote && callAt > 0L) card.addView(callInfoRow())
        card.addView(input)
        card.addView(actionRow(input))
        root.addView(card)

        input.requestFocus()
        input.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 250)

        return ScrollView(this).apply { addView(root) }
    }

    private fun titleRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(ImageView(this@ContactNoteEditActivity).apply {
                setImageResource(if (isGeneralNote) R.drawable.ic_note_lines else R.drawable.ic_chat_note)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(35), dp(35)).apply { marginEnd = dp(8) }
            })
            addView(LinearLayout(this@ContactNoteEditActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@ContactNoteEditActivity).apply {
                    text = if (isGeneralNote) "Основна бележка" else "Бележка от разговора"
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(17, 24, 39))
                })
                addView(TextView(this@ContactNoteEditActivity).apply {
                    text = titleText
                    textSize = 13f
                    setTextColor(Color.rgb(107, 114, 128))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(ImageButton(this@ContactNoteEditActivity).apply {
                setImageResource(R.drawable.ic_popup_close)
                contentDescription = "Затвори"
                background = roundedRect(Color.rgb(243, 244, 246), dp(18), Color.TRANSPARENT, 0)
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setOnClickListener { finish() }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) }
            })
        }
    }

    private fun callInfoRow(): TextView {
        return TextView(this).apply {
            text = listOf(
                PhoneCallReader.directionLabel(direction),
                PhoneCallReader.formatStartedAt(callAt),
                PhoneCallReader.formatDuration(durationSeconds),
            ).filter { it.isNotBlank() }.joinToString(" • ")
            textSize = 13f
            setTextColor(Color.rgb(107, 114, 128))
            setPadding(0, dp(12), 0, 0)
        }
    }

    private fun noteInput(): EditText {
        val colors = if (isGeneralNote) NoteUiStyle.General else NoteUiStyle.Call
        val value = if (isGeneralNote) {
            ContactNoteReader.generalNoteForPhone(this, phone)
        } else {
            ContactNoteReader.callNoteForPhone(phone, callAt, direction)
        }
        return EditText(this).apply {
            setText(value)
            setSelection(text?.length ?: 0)
            hint = if (isGeneralNote) "Основна бележка към контакта/номера" else "Бележка към това обаждане"
            minLines = if (isGeneralNote) 4 else 3
            maxLines = 8
            textSize = 16f
            setTextColor(colors.text)
            setHintTextColor(colors.metaText)
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
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
            addView(secondaryTextButton("Отказ") { finish() })
            addView(TextView(this@ContactNoteEditActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            addView(primaryTextButton("Запази") { saveAndClose(input.text?.toString().orEmpty()) })
        }
    }

    private fun saveAndClose(noteText: String) {
        val saved = if (isGeneralNote) {
            NotePersistence.saveOrDeleteGeneralNote(this, phone, noteText)
        } else {
            NotePersistence.saveOrDeleteCallNote(
                context = this,
                phoneNumber = phone,
                note = noteText,
                direction = direction,
                callAt = callAt,
                durationSeconds = durationSeconds,
            )
        }
        Toast.makeText(this, if (saved) "Бележката е записана" else "Не успях да запиша бележката", Toast.LENGTH_SHORT).show()
        if (saved) {
            sendBroadcast(Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(packageName))
            finish()
        }
    }

    private fun primaryTextButton(textValue: String, action: () -> Unit): TextView {
        return TextView(this).apply {
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
        return TextView(this).apply {
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
