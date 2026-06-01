package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
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
    ): LinearLayout {
        val mainTitle = title.takeIf { it.isNotBlank() && it != "Бележки" }
            ?: phone.takeIf { it.isNotBlank() }
            ?: "Информация"
        val phoneLine = phone.takeIf { it.isNotBlank() && it != mainTitle }.orEmpty()
        val contactIcon = if (contactExists) R.drawable.ic_contact_person else R.drawable.ic_contact_person_add
        val contactDescription = if (contactExists) "Отвори контакт" else "Създай контакт"

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(iconButton(R.drawable.ic_arrow_back, "Назад", goBack).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) }
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(activity).apply {
                    text = mainTitle
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(15, 23, 42))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (phoneLine.isNotBlank()) {
                    addView(TextView(activity).apply {
                        text = phoneLine
                        textSize = 15.5f
                        setTextColor(Color.rgb(71, 85, 105))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            })
            addView(iconButton(R.drawable.ic_phone_call, "Обади се", openDialer))
            addView(iconButton(R.drawable.ic_calendar_event, "Календар", openCalendarEvent))
            addView(iconButton(contactIcon, contactDescription, openDefaultContact))
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

    private fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(activity).apply {
            setImageResource(drawableRes)
            contentDescription = description
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) }
            setOnClickListener { action() }
        }
    }
}
