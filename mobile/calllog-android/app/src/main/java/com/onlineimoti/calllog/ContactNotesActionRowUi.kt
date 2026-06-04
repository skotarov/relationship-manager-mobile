package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal class ContactNotesActionRowUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun contactActionRow(
        linked: Boolean,
        busy: Boolean,
        status: RmContactReconcileAction?,
        onOpenContactLink: () -> Unit,
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(contactLinkButton(linked, busy, status, onOpenContactLink))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun contactLinkButton(
        linked: Boolean,
        busy: Boolean,
        status: RmContactReconcileAction?,
        onOpenContactLink: () -> Unit,
    ): LinearLayout {
        val actionColor = when {
            busy -> Color.rgb(100, 116, 139)
            status == RmContactReconcileAction.UNCHANGED -> Color.rgb(37, 99, 235)
            status == RmContactReconcileAction.FAILED -> Color.rgb(220, 38, 38)
            status == RmContactReconcileAction.DELETED -> Color.rgb(245, 158, 11)
            linked || status == RmContactReconcileAction.ADDED || status == RmContactReconcileAction.UPDATED -> Color.rgb(37, 99, 235)
            else -> Color.rgb(22, 163, 74)
        }
        val label = when {
            busy -> activity.getString(R.string.crm_contact_processing)
            status == RmContactReconcileAction.UNCHANGED -> "RM връзката е наред"
            status == RmContactReconcileAction.ADDED -> "RM връзката е добавена"
            status == RmContactReconcileAction.UPDATED -> "RM връзката е обновена"
            status == RmContactReconcileAction.DELETED -> "Осиротял RM запис е премахнат"
            status == RmContactReconcileAction.FAILED -> "Провери RM връзката"
            else -> activity.getString(R.string.crm_add_contact)
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = roundedRect(Color.WHITE, dp(16), actionColor, dp(1))
            isEnabled = !busy
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { if (!busy) onOpenContactLink() }

            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_crm_person_add)
                setColorFilter(actionColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) }
            })
            addView(TextView(activity).apply {
                text = label
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(actionColor)
                includeFontPadding = false
            })
        }
    }
}
