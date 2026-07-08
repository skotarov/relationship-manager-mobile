package com.onlineimoti.calllog

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Modal viewer for one concrete incoming SMS opened from a notification tap. */
internal class SmsMessageViewDialog(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun show(
        phone: String,
        title: String,
        body: String,
        receivedAtMs: Long,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (phone.isBlank() || activity.isFinishing || activity.isDestroyed) {
            onDismiss?.invoke()
            return
        }
        runCatching {
            val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
            dialog.setContentView(content(dialog, phone, title.ifBlank { phone }, body, receivedAtMs))
            dialog.setOnShowListener { configureWindow(dialog) }
            dialog.setOnDismissListener { onDismiss?.invoke() }
            dialog.show()
        }.onFailure { error ->
            Toast.makeText(
                activity,
                error.message.orEmpty().ifBlank { "Не успях да отворя SMS." },
                Toast.LENGTH_LONG,
            ).show()
            onDismiss?.invoke()
        }
    }

    private fun configureWindow(dialog: Dialog) {
        dialog.window?.apply {
            setBackgroundDrawable(roundedRect(Color.WHITE, dp(20), Color.TRANSPARENT, 0))
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(12) }
            setLayout(activity.resources.displayMetrics.widthPixels - dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun content(dialog: Dialog, phone: String, title: String, body: String, receivedAtMs: Long): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(header(dialog))
        root.addView(TextView(activity).apply {
            text = listOf(
                title.takeIf { it != phone },
                phone,
                PhoneCallReader.formatStartedAt(receivedAtMs),
            ).filter { !it.isNullOrBlank() }.joinToString(" • ")
            textSize = 13.5f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(2), 0, dp(12))
        })
        root.addView(TextView(activity).apply {
            text = body.ifBlank { "Празно SMS" }
            textSize = 16f
            setTextColor(Color.rgb(15, 23, 42))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedRect(Color.rgb(248, 250, 252), dp(14), Color.rgb(203, 213, 225), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        root.addView(Button(activity).apply {
            text = "Отговори"
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedRect(Color.rgb(15, 23, 42), dp(13), Color.TRANSPARENT, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(16) }
            setOnClickListener {
                dialog.dismiss()
                SmsComposeDialog(activity, dp).show(phone, title, onDismiss)
            }
        })
        return root
    }

    private fun header(dialog: Dialog): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = "SMS"
            textSize = 21f
            setTextColor(Color.rgb(15, 23, 42))
            typeface = Typeface.DEFAULT_BOLD
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
