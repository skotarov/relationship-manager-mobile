package com.onlineimoti.calllog

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView

/** Buttons and compact widgets used by the contact-history header. */
internal class ContactHeaderActionWidgets(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
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

    fun verticalDivider(): View {
        return View(activity).apply {
            setBackgroundColor(Color.rgb(203, 213, 225))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(28)).apply {
                marginStart = dp(4)
                marginEnd = dp(12)
            }
        }
    }

    fun crmSyncButton(enabled: Boolean, busy: Boolean, action: () -> Unit): LinearLayout {
        val iconColor = if (enabled) Color.WHITE else Color.BLACK
        val description = when {
            busy -> activity.getString(R.string.dynamic_crm_sync_changing)
            enabled -> activity.getString(R.string.dynamic_crm_sync_enabled)
            else -> activity.getString(R.string.dynamic_crm_sync_enable)
        }
        val cloudIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_cloud_note)
            imageTintList = ColorStateList.valueOf(iconColor)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(36))
        }
        val crmLabel = TextView(activity).apply {
            text = "CRM"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(iconColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(9), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
            background = if (enabled) roundedIconBackground(activity.getColor(R.color.callreport_icon_background)) else null
            contentDescription = description
            isClickable = !busy
            isFocusable = !busy
            isEnabled = !busy
            alpha = if (busy) 0.78f else 1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36),
            ).apply { marginEnd = dp(8) }
            addView(cloudIcon)
            addView(crmLabel)
            setOnClickListener { action() }
            if (busy) cloudIcon.startAnimation(cloudSpinAnimation())
        }
    }

    fun contactMenuButton(
        description: String,
        openDefaultContact: () -> Unit,
        openRmContact: () -> Unit,
    ): ImageButton {
        val button = iconButton(R.drawable.ic_contact_person, description) { }
        button.setOnClickListener {
            PopupMenu(activity, button).apply {
                menu.add(0, MENU_PHONE_CONTACT, 0, "Тел. контакт")
                menu.add(0, MENU_RM_CONTACT, 1, "RM контакт")
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        MENU_PHONE_CONTACT -> openDefaultContact()
                        MENU_RM_CONTACT -> openRmContact()
                    }
                    true
                }
                show()
            }
        }
        return button
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

    private companion object {
        const val MENU_PHONE_CONTACT = 1
        const val MENU_RM_CONTACT = 2
    }
}
