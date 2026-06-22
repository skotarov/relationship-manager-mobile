package com.onlineimoti.calllog

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Small in-place SMS composer used from the contact-history header. */
internal class SmsComposeDialog(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class SmsSubscriptionOption(
        val subscriptionId: Int,
        val label: String,
        val slotIndex: Int,
    )

    private data class DialogViews(
        val root: LinearLayout,
        val messageInput: EditText,
        val status: TextView,
        val sendButton: Button,
    )

    fun show(phone: String, title: String) {
        if (phone.isBlank() || activity.isFinishing || activity.isDestroyed) return

        runCatching {
            // The feature must be requested before setContentView. Doing it from onCreate after
            // the content is attached crashes on some Xiaomi/HyperOS builds.
            val dialog = Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
            }
            val views = content(dialog, phone, title)
            dialog.setContentView(views.root)
            dialog.setOnShowListener {
                dialog.window?.apply {
                    setBackgroundDrawable(roundedRect(Color.WHITE, dp(20), Color.TRANSPARENT, 0))
                    setLayout(
                        activity.resources.displayMetrics.widthPixels - dp(28),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                }
                views.messageInput.requestFocus()
                val keyboard = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager
                keyboard?.showSoftInput(views.messageInput, InputMethodManager.SHOW_IMPLICIT)
            }
            dialog.show()
        }.onFailure { error ->
            Toast.makeText(
                activity,
                error.message.orEmpty().ifBlank { "Не успях да отворя SMS екрана." },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun content(dialog: Dialog, phone: String, title: String): DialogViews {
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

        val selectedSubscriptionId = addSubscriptionChooser(root)

        val messageInput = EditText(activity).apply {
            hint = "Напиши съобщение"
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
        root.addView(messageInput)

        val status = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.rgb(185, 28, 28))
            visibility = TextView.GONE
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(status)

        val sendButton = primaryButton("Изпрати")
        root.addView(sendButton.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(16) }
        })

        sendButton.setOnClickListener {
            val message = messageInput.text?.toString().orEmpty()
            sendButton.isEnabled = false
            sendButton.text = "Изпращане…"
            status.visibility = TextView.GONE
            sendInBackground(
                dialog = dialog,
                phone = phone,
                message = message,
                subscriptionId = selectedSubscriptionId(),
                sendButton = sendButton,
                status = status,
            )
        }

        return DialogViews(root, messageInput, status, sendButton)
    }

    private fun addSubscriptionChooser(root: LinearLayout): () -> Int? {
        val options = activeSmsSubscriptions()
        val defaultSubscriptionId = defaultSmsSubscriptionId()
        val initiallySelected = options.firstOrNull { it.subscriptionId == defaultSubscriptionId }
            ?: options.firstOrNull()

        if (options.isEmpty()) return { defaultSubscriptionId }

        root.addView(TextView(activity).apply {
            text = "Изпращане от"
            textSize = 13f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, 0, 0, dp(4))
        })

        if (options.size == 1) {
            root.addView(TextView(activity).apply {
                text = initiallySelected?.label.orEmpty()
                textSize = 15f
                setTextColor(Color.rgb(15, 23, 42))
                setPadding(dp(12), dp(9), dp(12), dp(10))
                background = roundedRect(Color.rgb(248, 250, 252), dp(12), Color.rgb(203, 213, 225), dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(12) }
            })
            return { initiallySelected?.subscriptionId ?: defaultSubscriptionId }
        }

        val group = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(5))
            background = roundedRect(Color.rgb(248, 250, 252), dp(12), Color.rgb(203, 213, 225), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        options.forEach { option ->
            group.addView(RadioButton(activity).apply {
                id = View.generateViewId()
                tag = option.subscriptionId
                text = option.label
                textSize = 15f
                setTextColor(Color.rgb(15, 23, 42))
                setPadding(dp(4), dp(3), dp(4), dp(3))
                isChecked = option.subscriptionId == initiallySelected?.subscriptionId
            })
        }
        root.addView(group)

        return {
            group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? Int
                ?: initiallySelected?.subscriptionId
                ?: defaultSubscriptionId
        }
    }

    private fun activeSmsSubscriptions(): List<SmsSubscriptionOption> {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val manager = activity.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching {
            manager.activeSubscriptionInfoList.orEmpty()
                .map { info ->
                    val simLabel = if (info.simSlotIndex >= 0) "SIM ${info.simSlotIndex + 1}" else "SIM"
                    val carrierLabel = info.displayName?.toString()?.trim().orEmpty()
                        .ifBlank { info.carrierName?.toString()?.trim().orEmpty() }
                    SmsSubscriptionOption(
                        subscriptionId = info.subscriptionId,
                        label = if (carrierLabel.isBlank()) simLabel else "$simLabel • $carrierLabel",
                        slotIndex = info.simSlotIndex,
                    )
                }
                .sortedBy { it.slotIndex }
        }.getOrDefault(emptyList())
    }

    private fun defaultSmsSubscriptionId(): Int? {
        return runCatching {
            SubscriptionManager.getDefaultSmsSubscriptionId()
                .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        }.getOrNull()
    }

    private fun sendInBackground(
        dialog: Dialog,
        phone: String,
        message: String,
        subscriptionId: Int?,
        sendButton: Button,
        status: TextView,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val result = runCatching {
                SmsMessageSender.send(activity.applicationContext, phone, message, subscriptionId)
            }.getOrElse { error ->
                Result.failure(error)
            }
            mainHandler.post {
                executor.shutdown()
                runCatching {
                    if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@post
                    result.onSuccess { outcome ->
                        activity.sendBroadcast(Intent(PostCallOverlayService.ACTION_NOTES_CHANGED))
                        Toast.makeText(
                            activity,
                            if (outcome.historySaved) {
                                "SMS е изпратен към оператора"
                            } else {
                                "SMS е изпратен към оператора. Отвори отново историята, ако редът още не се е появил."
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                        dialog.dismiss()
                    }.onFailure { error ->
                        restoreAfterFailure(sendButton, status, error)
                    }
                }.onFailure { error ->
                    if (!activity.isFinishing && !activity.isDestroyed && dialog.isShowing) {
                        restoreAfterFailure(sendButton, status, error)
                    }
                }
            }
        }
    }

    private fun restoreAfterFailure(sendButton: Button, status: TextView, error: Throwable) {
        sendButton.isEnabled = true
        sendButton.text = "Изпрати"
        status.text = error.message.orEmpty().ifBlank { "Не успях да изпратя SMS." }
        status.visibility = TextView.VISIBLE
    }

    private fun primaryButton(label: String): Button {
        return Button(activity).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedRect(Color.rgb(15, 23, 42), dp(13), Color.TRANSPARENT, 0)
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
