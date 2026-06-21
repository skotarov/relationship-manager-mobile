package com.onlineimoti.calllog

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Small in-place SMS composer used from the contact-history header. */
internal class SmsComposeDialog(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun show(phone: String, title: String) {
        if (phone.isBlank()) return
        val dialog = object : Dialog(activity) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                requestWindowFeature(Window.FEATURE_NO_TITLE)
            }
        }
        dialog.setContentView(content(dialog, phone, title))
        dialog.window?.apply {
            setBackgroundDrawable(roundedRect(Color.WHITE, dp(20), Color.TRANSPARENT, 0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                activity.resources.displayMetrics.widthPixels - dp(28),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
    }

    private fun content(dialog: Dialog, phone: String, title: String): LinearLayout {
        val displayTitle = title.trim().ifBlank { phone }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "Нов SMS"
                textSize = 21f
                setTextColor(Color.rgb(15, 23, 42))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(activity).apply {
                text = "×"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(71, 85, 105))
                contentDescription = "Затвори"
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
        })

        root.addView(TextView(activity).apply {
            text = "$displayTitle • $phone"
            textSize = 14f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(2), 0, dp(14))
        })

        val messageInput = EditText(activity).apply {
            hint = "Напиши съобщение"
            textSize = 16f
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRect(Color.rgb(248, 250, 252), dp(14), Color.rgb(203, 213, 225), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(messageInput)

        val status = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.rgb(185, 28, 28))
            visibility = TextView.GONE
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(status)

        root.addView(primaryButton("Изпрати") {
            val result = SmsMessageSender.send(activity, phone, messageInput.text?.toString().orEmpty())
            result.onSuccess {
                Toast.makeText(activity, "SMS е изпратен", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }.onFailure { error ->
                status.text = error.message.orEmpty().ifBlank { "Не успях да изпратя SMS." }
                status.visibility = TextView.VISIBLE
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(16) }
        })

        dialog.setOnShowListener {
            messageInput.requestFocus()
            val keyboard = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager
            keyboard?.showSoftInput(messageInput, InputMethodManager.SHOW_IMPLICIT)
        }
        return root
    }

    private fun primaryButton(label: String, action: () -> Unit): Button {
        return Button(activity).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedRect(Color.rgb(15, 23, 42), dp(13), Color.TRANSPARENT, 0)
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
}
