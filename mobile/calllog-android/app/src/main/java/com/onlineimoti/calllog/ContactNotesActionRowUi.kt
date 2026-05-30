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
        onToggle: () -> Unit,
        onEditCrm: () -> Unit,
    ): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(contactRegistrationToggle(linked, busy, onToggle))
            if (linked) addView(editCrmContactButton(onEditCrm))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun contactRegistrationToggle(linked: Boolean, busy: Boolean, onToggle: () -> Unit): LinearLayout {
        val actionColor = when {
            busy -> Color.rgb(100, 116, 139)
            linked -> Color.rgb(220, 38, 38)
            else -> Color.rgb(22, 163, 74)
        }
        val iconRes = if (linked) R.drawable.ic_crm_person_remove else R.drawable.ic_crm_person_add
        val labelRes = when {
            busy -> R.string.crm_contact_processing
            linked -> R.string.crm_remove_contact
            else -> R.string.crm_add_contact
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = roundedRect(Color.WHITE, dp(16), actionColor, dp(1))
            isEnabled = !busy
            isClickable = true
            isFocusable = true
            contentDescription = activity.getString(labelRes)
            setOnClickListener { if (!busy) onToggle() }

            addView(ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(actionColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) }
            })
            addView(TextView(activity).apply {
                text = activity.getString(labelRes)
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(actionColor)
                includeFontPadding = false
            })
        }
    }

    private fun editCrmContactButton(onEdit: () -> Unit): TextView {
        return TextView(activity).apply {
            text = "Едит"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(37, 99, 235))
            background = roundedRect(Color.WHITE, dp(14), Color.rgb(37, 99, 235), dp(1))
            isClickable = true
            isFocusable = true
            contentDescription = "Редактирай CRM полета"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onEdit() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply { marginStart = dp(8) }
        }
    }
}