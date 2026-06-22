package com.onlineimoti.calllog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ContactNotesHeaderUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun headerRow(
        title: String,
        phone: String,
        contactExists: Boolean,
        showRmCallLogButton: Boolean,
        showCrmSyncButton: Boolean,
        crmSyncEnabled: Boolean,
        crmSyncBusy: Boolean,
        goBack: () -> Unit,
        openDialer: () -> Unit,
        openCalendarEvent: () -> Unit,
        openDefaultContact: () -> Unit,
        toggleCrmSync: () -> Unit,
        openRmCallLog: () -> Unit,
        openRmCallLogFiltered: () -> Unit,
    ): LinearLayout {
        val displayName = displayNameFromTitle(title, phone)
        val contactDescription = if (contactExists) "Отвори контакт" else "Създай контакт"

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconButton(R.drawable.ic_arrow_back, "Назад", goBack).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) }
                })
                if (showRmCallLogButton) {
                    addView(iconButton(R.drawable.ic_system_call_log, "Всички разговори", openRmCallLog))
                    addView(verticalDivider())
                }
                if (showCrmSyncButton) {
                    addView(crmSyncButton(crmSyncEnabled, crmSyncBusy, toggleCrmSync))
                }
                addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
                if (phone.isNotBlank()) {
                    if (contactExists) {
                        addView(iconButton(R.drawable.ic_contact_person, contactDescription, openDefaultContact))
                    }
                    addView(iconButton(R.drawable.ic_phone_call, "Обади се", openDialer))
                    addView(iconButton(R.drawable.ic_sms_message, "Напиши SMS") {
                        SmsComposeDialog(activity, dp).show(phone, displayName.ifBlank { title })
                    })
                }
                addView(iconButton(R.drawable.ic_calendar_event, "Календар", openCalendarEvent).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                })
            })

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                if (phone.isNotBlank()) {
                    addView(phoneNumberText(phone))
                    addView(textDivider())
                }
                if (contactExists) {
                    addView(contactNameText(displayName, true, contactDescription).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                } else {
                    addView(iconButton(R.drawable.ic_contact_person_add, contactDescription, openDefaultContact))
                }
            })
        }
    }

    fun sectionTitleWithDrawable(textValue: String, drawableRes: Int): LinearLayout {
        return titleRow(textValue).apply {
            addView(ImageView(activity).apply {
                setImageResource(drawableRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
            }, 0)
        }
    }

    fun directionArrowLabel(direction: String): String {
        return when (direction) {
            "in" -> "↙ входящ"
            "out" -> "↗ изходящ"
            else -> PhoneCallReader.directionLabel(direction)
        }
    }

    private fun displayNameFromTitle(title: String, phone: String): String {
        val value = title.trim()
        if (value.isBlank() || value == "Бележки") return ""
        if (phone.isNotBlank()) {
            if (value == phone) return ""
            if (value.contains("|")) return value.substringAfterLast("|").trim()
            if (value.startsWith(phone)) {
                return value.removePrefix(phone).trim().trimStart('|', '•', '-', '–').trim()
            }
        }
        return value
    }

    private fun titleRow(textValue: String): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(8))
            addView(TextView(activity).apply {
                text = textValue
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(30, 41, 59))
            })
        }
    }

    private fun phoneNumberText(phone: String): TextView {
        return TextView(activity).apply {
            text = phone
            textSize = 15f
            typeface = Typeface.DEFAULT
            setTextColor(Color.rgb(17, 24, 39))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            setPadding(0, dp(4), dp(4), dp(4))
            setOnClickListener { copyToClipboard("Телефон", phone, "Номерът е копиран") }
        }
    }

    private fun contactNameText(displayName: String, contactExists: Boolean, description: String): TextView {
        val value = displayName.ifBlank { if (contactExists) description else "Нов" }
        return TextView(activity).apply {
            text = value
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (contactExists) Color.rgb(15, 23, 42) else Color.rgb(30, 64, 175))
            maxLines = 2
            ellipsize = null
            isClickable = true
            isFocusable = true
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { copyToClipboard("Име", value, "Името е копирано") }
        }
    }

    private fun textDivider(): TextView {
        return TextView(activity).apply {
            text = "|"
            textSize = 17f
            setTextColor(Color.rgb(148, 163, 184))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
                marginEnd = dp(9)
            }
        }
    }

    private fun verticalDivider(): View {
        return View(activity).apply {
            setBackgroundColor(Color.rgb(203, 213, 225))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(28)).apply {
                marginStart = dp(4)
                marginEnd = dp(12)
            }
        }
    }

    private fun crmSyncButton(enabled: Boolean, busy: Boolean, action: () -> Unit): FrameLayout {
        val iconColor = if (enabled) Color.WHITE else Color.BLACK
        val description = when {
            busy -> "Синхронизацията се променя"
            enabled -> "CRM синхронизацията е включена"
            else -> "Включи CRM синхронизация"
        }
        val cloudIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_cloud_note)
            imageTintList = ColorStateList.valueOf(iconColor)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        return FrameLayout(activity).apply {
            background = if (enabled) roundedIconBackground(Color.BLACK) else null
            contentDescription = description
            isClickable = !busy
            isFocusable = !busy
            isEnabled = !busy
            alpha = if (busy) 0.78f else 1f
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
            addView(cloudIcon)
            setOnClickListener { action() }
            if (busy) cloudIcon.startAnimation(cloudSpinAnimation())
        }
    }

    private fun cloudSpinAnimation(): RotateAnimation {
        return RotateAnimation(
            0f,
            360f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
        ).apply {
            duration = 720L
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun roundedIconBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(color)
        }
    }

    private fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(activity).apply {
            setImageResource(drawableRes)
            contentDescription = description
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
            setOnClickListener { action() }
        }
    }

    private fun copyToClipboard(label: String, value: String, message: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
