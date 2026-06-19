package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class ContactNotesHeaderUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun headerRow(
        title: String,
        phone: String,
        contactExists: Boolean,
        goBack: () -> Unit,
        openDialer: () -> Unit,
        openCalendarEvent: () -> Unit,
        openDefaultContact: () -> Unit,
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
                addView(iconButton(R.drawable.ic_system_call_log, "Всички разговори", openRmCallLog))
                addView(verticalDivider())
                addView(iconButton(R.drawable.ic_call_log_filter, "Разговори само с този номер", openRmCallLogFiltered))
                addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
                addView(iconButton(R.drawable.ic_calendar_event, "Календар", openCalendarEvent).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                })
            })

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                if (phone.isNotBlank()) {
                    addView(phoneNumberButton(phone, openDialer))
                    addView(textDivider())
                }
                addView(contactNameView(displayName, contactExists, contactDescription, openDefaultContact).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
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

    private fun phoneNumberButton(phone: String, action: () -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(4), dp(4), dp(4))
            setOnClickListener { action() }
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_phone_call)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) }
            })
            addView(TextView(activity).apply {
                text = phone
                textSize = 15f
                typeface = Typeface.DEFAULT
                setTextColor(Color.rgb(17, 24, 39))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
    }

    private fun contactNameView(displayName: String, contactExists: Boolean, description: String, action: () -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { action() }
            addView(ImageView(activity).apply {
                setImageResource(if (contactExists) R.drawable.ic_contact_person else R.drawable.ic_contact_person_add)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) }
            })
            addView(TextView(activity).apply {
                text = if (contactExists) displayName.ifBlank { description } else "Нов"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (contactExists) Color.rgb(15, 23, 42) else Color.rgb(30, 64, 175))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
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

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }
}
