package com.onlineimoti.calllog

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

/** Small in-place SMS composer used from the contact-history header. */
internal class SmsComposeDialog(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val subscriptionChooser by lazy { SmsSubscriptionChooser(activity, dp, ::roundedRect) }

    fun show(phone: String, title: String) {
        if (phone.isBlank() || activity.isFinishing || activity.isDestroyed) return
        runCatching {
            val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
            val views = content(dialog, phone, title)
            dialog.setContentView(views.root)
            dialog.setOnShowListener { configureWindow(dialog, views.messageInput) }
            dialog.show()
        }.onFailure { error ->
            Toast.makeText(
                activity,
                error.message.orEmpty().ifBlank { activity.getString(R.string.dynamic_sms_open_failed) },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun configureWindow(dialog: Dialog, input: EditText) {
        dialog.window?.apply {
            setBackgroundDrawable(roundedRect(Color.WHITE, dp(20), Color.TRANSPARENT, 0))
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = dp(12) }
            setLayout(activity.resources.displayMetrics.widthPixels - dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
        }
        input.requestFocus()
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun content(dialog: Dialog, phone: String, title: String): DialogViews {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(dialogTitle(dialog))
        root.addView(contactSummary(title.trim().ifBlank { phone }, phone))
        val selectedSubscriptionId = subscriptionChooser.addTo(root)
        val messageInput = messageInput()
        val status = statusText()
        val sendButton = primaryButton(activity.getString(R.string.dynamic_sms_send))
        root.addView(messageInput)
        root.addView(status)
        root.addView(sendButton.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(16) }
        })
        sendButton.setOnClickListener {
            send(
                dialog = dialog,
                phone = phone,
                message = messageInput.text?.toString().orEmpty(),
                subscriptionId = selectedSubscriptionId(),
                sendButton = sendButton,
                status = status,
            )
        }
        return DialogViews(root, messageInput)
    }

    private fun dialogTitle(dialog: Dialog): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = activity.getString(R.string.dynamic_sms_new)
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
            contentDescription = activity.getString(R.string.dynamic_sms_close)
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
    }

    private fun contactSummary(title: String, phone: String): TextView = TextView(activity).apply {
        text = "$title • $phone"
        textSize = 14f
        setTextColor(Color.rgb(71, 85, 105))
        setPadding(0, dp(2), 0, dp(14))
    }

    private fun messageInput(): EditText = EditText(activity).apply {
        hint = activity.getString(R.string.dynamic_sms_message_hint)
        textSize = 16f
        minLines = 4
        maxLines = 8
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = roundedRect(Color.rgb(248, 250, 252), dp(14), Color.rgb(203, 213, 225), dp(1))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun statusText(): TextView = TextView(activity).apply {
        textSize = 13f
        setTextColor(Color.rgb(185, 28, 28))
        visibility = TextView.GONE
        setPadding(0, dp(8), 0, 0)
    }

    private fun send(
        dialog: Dialog,
        phone: String,
        message: String,
        subscriptionId: Int?,
        sendButton: Button,
        status: TextView,
    ) {
        sendButton.isEnabled = false
        sendButton.text = activity.getString(R.string.dynamic_sms_sending)
        status.visibility = TextView.GONE
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val result = runCatching {
                SmsMessageSender.send(activity.applicationContext, phone, message, subscriptionId)
            }.getOrElse { Result.failure(it) }
            mainHandler.post {
                executor.shutdown()
                if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@post
                result.onSuccess { outcome ->
                    subscriptionChooser.rememberSuccessfulSubscriptionId(subscriptionId)
                    activity.sendBroadcast(
                        Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(activity.packageName),
                    )
                    Toast.makeText(
                        activity,
                        activity.getString(if (outcome.historySaved) R.string.dynamic_sms_sent else R.string.dynamic_sms_sent_refresh),
                        Toast.LENGTH_LONG,
                    ).show()
                    dialog.dismiss()
                }.onFailure { restoreAfterFailure(sendButton, status, it) }
            }
        }
    }

    private fun restoreAfterFailure(sendButton: Button, status: TextView, error: Throwable) {
        sendButton.isEnabled = true
        sendButton.text = activity.getString(R.string.dynamic_sms_send)
        status.text = error.message.orEmpty().ifBlank { activity.getString(R.string.dynamic_sms_send_failed) }
        status.visibility = TextView.VISIBLE
    }

    private fun primaryButton(label: String): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = roundedRect(Color.rgb(15, 23, 42), dp(13), Color.TRANSPARENT, 0)
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private data class DialogViews(val root: LinearLayout, val messageInput: EditText)
}
