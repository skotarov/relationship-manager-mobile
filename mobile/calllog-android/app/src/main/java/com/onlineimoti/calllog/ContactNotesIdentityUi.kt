package com.onlineimoti.calllog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Creates the contact name/phone identity shown above History actions. */
internal class ContactNotesIdentityUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun displayNameFromTitle(title: String, phone: String): String {
        val value = title.trim()
        if (value.isBlank() || value == activity.getString(R.string.dynamic_notes_default_title)) return ""
        if (phone.isNotBlank()) {
            if (value == phone) return ""
            if (value.contains("|")) return value.substringAfterLast("|").trim()
            if (value.startsWith(phone)) {
                return value.removePrefix(phone).trim().trimStart('|', '•', '-', '–').trim()
            }
        }
        return value
    }

    fun compactTitle(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(15, 23, 42))
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        visibility = View.INVISIBLE
        setPadding(dp(4), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
    }

    fun block(displayName: String, phone: String, contactExists: Boolean): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            if (contactExists && displayName.isNotBlank()) {
                addView(contactNameText(displayName))
                if (phone.isNotBlank()) addView(phoneNumberText(phone, prominent = false))
            } else if (phone.isNotBlank()) {
                addView(phoneNumberText(phone, prominent = true))
            }
        }

    private fun phoneNumberText(phone: String, prominent: Boolean): TextView = TextView(activity).apply {
        text = phone
        textSize = if (prominent) 20f else 15f
        typeface = if (prominent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (prominent) Color.rgb(15, 23, 42) else Color.rgb(71, 85, 105))
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        isClickable = true
        isFocusable = true
        setPadding(dp(8), dp(3), dp(8), dp(3))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener {
            copyToClipboard(
                activity.getString(R.string.dynamic_clipboard_phone_label),
                phone,
                activity.getString(R.string.dynamic_phone_copied),
            )
        }
    }

    private fun contactNameText(displayName: String): TextView = TextView(activity).apply {
        text = displayName
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(15, 23, 42))
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        maxLines = 2
        ellipsize = null
        isClickable = true
        isFocusable = true
        setPadding(dp(8), dp(3), dp(8), dp(3))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener {
            copyToClipboard(
                activity.getString(R.string.dynamic_clipboard_name_label),
                displayName,
                activity.getString(R.string.dynamic_name_copied),
            )
        }
    }

    private fun copyToClipboard(label: String, value: String, message: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
