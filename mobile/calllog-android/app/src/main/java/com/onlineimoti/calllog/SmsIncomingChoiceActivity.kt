package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Shown from the incoming-SMS notification after Relationship Manager receives a message as
 * the default SMS app. It keeps the routing decision explicit instead of silently trapping
 * the user inside a new messaging client.
 */
class SmsIncomingChoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val isMms = intent.getBooleanExtra(EXTRA_IS_MMS, false)
        val phone = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val displayPhone = PhoneNormalizer.normalize(phone).ifBlank { phone }
        val displayName = RmRealContactLookup.resolveDisplayName(this, displayPhone).orEmpty().ifBlank { displayPhone }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
            setBackgroundColor(Color.rgb(248, 250, 252))
        }

        root.addView(TextView(this).apply {
            text = if (isMms) "Получено MMS" else "Ново SMS"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 23, 42))
        })

        root.addView(TextView(this).apply {
            text = when {
                displayName.isNotBlank() -> "От: $displayName"
                else -> "Новото съобщение е получено от непознат номер"
            }
            textSize = 15f
            setTextColor(Color.rgb(51, 65, 85))
            setPadding(0, dp(10), 0, 0)
        })

        if (displayPhone.isNotBlank() && displayPhone != displayName) {
            root.addView(TextView(this).apply {
                text = displayPhone
                textSize = 14f
                setTextColor(Color.rgb(71, 85, 105))
                setPadding(0, dp(2), 0, 0)
            })
        }

        if (body.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = body
                textSize = 16f
                setTextColor(Color.rgb(30, 41, 59))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedRect(Color.WHITE, dp(14), Color.rgb(203, 213, 225), dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(14) }
            })
        } else if (isMms) {
            root.addView(TextView(this).apply {
                text = "MMS съдържанието се пази от Android. Отвори другото SMS приложение, за да го прегледаш." 
                textSize = 14f
                setTextColor(Color.rgb(71, 85, 105))
                setPadding(0, dp(14), 0, 0)
            })
        }

        if (displayPhone.isNotBlank()) {
            root.addView(primaryButton("Остани в Call Report") {
                openCrmHistory(displayPhone, displayName)
            }.apply { topMargin(dp(20)) })
        }

        root.addView(secondaryButton("Отвори другото SMS приложение") {
            val opened = SmsExternalAppStore.openExternalSmsInbox(this)
            if (!opened) {
                Toast.makeText(this, "Не е намерено предишно SMS приложение", Toast.LENGTH_SHORT).show()
                return@secondaryButton
            }
            finish()
        }.apply { topMargin(dp(10)) })

        root.addView(secondaryButton("Затвори") { finish() }.apply { topMargin(dp(8)) })

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(248, 250, 252))
            addView(root)
        }
    }

    private fun openCrmHistory(phone: String, title: String) {
        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, title.ifBlank { phone })
        )
        finish()
    }

    private fun primaryButton(textValue: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedRect(Color.rgb(15, 23, 42), dp(12), Color.TRANSPARENT, 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            )
        }
    }

    private fun secondaryButton(textValue: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.rgb(15, 23, 42))
            background = roundedRect(Color.WHITE, dp(12), Color.rgb(148, 163, 184), dp(1))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            )
        }
    }

    private fun Button.topMargin(value: Int) {
        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = value
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

    companion object {
        const val EXTRA_ADDRESS = "sms_address"
        const val EXTRA_BODY = "sms_body"
        const val EXTRA_TIMESTAMP = "sms_timestamp"
        const val EXTRA_IS_MMS = "sms_is_mms"
    }
}
