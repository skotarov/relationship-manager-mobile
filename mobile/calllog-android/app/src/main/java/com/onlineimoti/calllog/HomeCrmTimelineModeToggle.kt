package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Adds the Calls / Contacts switch next to the existing CRM switch. */
internal class HomeCrmTimelineModeToggle(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val dp: (Int) -> Int,
    private val onToggle: () -> Unit,
) {
    private val button = MaterialButton(
        activity,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        minHeight = 0
        minWidth = 0
        textAllCaps = false
        textSize = 12f
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(36),
        ).apply { marginStart = dp(4) }
        setOnClickListener { onToggle() }
    }

    init {
        binding.crmControlsScroll.addView(button)
        button.visibility = View.GONE
    }

    fun prepare(visible: Boolean, contactsMode: Boolean) {
        button.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            binding.homeStatusText.visibility = View.VISIBLE
            return
        }
        button.text = modeLabel(contactsMode)
        button.contentDescription = if (contactsMode) "Покажи разговорите" else "Покажи контактите"
        style(contactsMode)
        binding.homeStatusText.visibility = View.VISIBLE
    }

    fun showRange(contactsMode: Boolean, pageIndex: Int, pageSize: Int, itemCount: Int) {
        prepare(visible = true, contactsMode = contactsMode)
        if (itemCount <= 0) return
        val first = pageIndex * pageSize + 1
        val last = first + itemCount - 1
        button.text = "${modeLabel(contactsMode)} $first–$last"
        binding.homeStatusText.visibility = View.GONE
    }

    fun showEmpty(contactsMode: Boolean) {
        prepare(visible = true, contactsMode = contactsMode)
        binding.homeStatusText.visibility = View.VISIBLE
    }

    private fun modeLabel(contactsMode: Boolean): String = when {
        contactsMode && AppLocaleText.isBulgarian() -> "Контакти"
        contactsMode -> "Contacts"
        AppLocaleText.isBulgarian() -> "Разговори"
        else -> "Calls"
    }

    private fun style(contactsMode: Boolean) {
        val fill = if (contactsMode) activity.getColor(R.color.callreport_icon_background) else Color.WHITE
        val border = if (contactsMode) activity.getColor(R.color.callreport_icon_background) else Color.rgb(203, 213, 225)
        button.backgroundTintList = ColorStateList.valueOf(fill)
        button.strokeColor = ColorStateList.valueOf(border)
        button.setTextColor(if (contactsMode) Color.WHITE else Color.rgb(51, 65, 85))
    }
}
