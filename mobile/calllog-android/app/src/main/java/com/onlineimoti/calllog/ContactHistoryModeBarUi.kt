package com.onlineimoti.calllog

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Builds the fixed bottom navigation between Notes/SMS and Calls. */
internal class ContactHistoryModeBarUi(
    private val activity: ContactNotesActivity,
    private val dp: (Int) -> Int,
) {
    fun create(
        selectedMode: ContactHistoryListMode,
        onModeSelected: (ContactHistoryListMode) -> Unit,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
        elevation = 0f
        stateListAnimator = null
        setPadding(
            dp(PAGE_HORIZONTAL_PADDING_DP),
            dp(MODE_BAR_VERTICAL_PADDING_DP),
            dp(PAGE_HORIZONTAL_PADDING_DP),
            dp(MODE_BAR_VERTICAL_PADDING_DP),
        )
        addView(modeButton(
            textValue = "Бележки и SMS",
            drawableRes = R.drawable.ic_menu_sms,
            mode = ContactHistoryListMode.NOTES_AND_SMS,
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
        ))
        addView(modeButton(
            textValue = "Обаждания",
            drawableRes = R.drawable.ic_history_clock,
            mode = ContactHistoryListMode.FULL_LOG,
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
        ))
    }

    private fun modeButton(
        textValue: String,
        drawableRes: Int,
        mode: ContactHistoryListMode,
        selectedMode: ContactHistoryListMode,
        onModeSelected: (ContactHistoryListMode) -> Unit,
    ): LinearLayout {
        val selected = mode == selectedMode
        val activeColor = ContextCompat.getColor(activity, R.color.callreport_icon_background)
        val inactiveColor = Color.rgb(71, 85, 105)
        val itemColor = if (selected) activeColor else inactiveColor
        val activePillColor = Color.argb(
            34,
            Color.red(activeColor),
            Color.green(activeColor),
            Color.blue(activeColor),
        )
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setPadding(0, dp(1), 0, 0)
            isClickable = !selected
            isFocusable = !selected
            contentDescription = textValue
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
            addView(FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(MODE_ICON_PILL_RADIUS_DP).toFloat()
                    setColor(if (selected) activePillColor else Color.TRANSPARENT)
                }
                addView(ImageView(activity).apply {
                    setImageResource(drawableRes)
                    setColorFilter(itemColor)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, FrameLayout.LayoutParams(
                    dp(MODE_ICON_SIZE_DP),
                    dp(MODE_ICON_SIZE_DP),
                    Gravity.CENTER,
                ))
            }, LinearLayout.LayoutParams(
                dp(MODE_ICON_PILL_WIDTH_DP),
                dp(MODE_ICON_PILL_HEIGHT_DP),
            ))
            addView(TextView(activity).apply {
                text = textValue
                textSize = 12f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(itemColor)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) })
            setOnClickListener { if (!selected) onModeSelected(mode) }
        }
    }

    companion object {
        const val BAR_HEIGHT_DP = 64
        private const val PAGE_HORIZONTAL_PADDING_DP = 16
        private const val MODE_BAR_VERTICAL_PADDING_DP = 3
        private const val MODE_ICON_SIZE_DP = 22
        private const val MODE_ICON_PILL_WIDTH_DP = 56
        private const val MODE_ICON_PILL_HEIGHT_DP = 28
        private const val MODE_ICON_PILL_RADIUS_DP = 16
    }
}
