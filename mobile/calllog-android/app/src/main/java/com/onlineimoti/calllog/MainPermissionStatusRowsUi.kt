package com.onlineimoti.calllog

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal data class MainPermissionRow(
    val label: String,
    val active: Boolean,
    val inactiveLabel: String = "липсва",
    val onEnable: () -> Unit,
    val onDisable: (() -> Unit)? = null,
)

internal object MainPermissionStatusRowsUi {
    private const val STATUS_ROWS_TAG = "callreport_permission_status_rows"

    fun render(activity: MainActivity, binding: ActivityMainBinding, rows: List<MainPermissionRow>) {
        val permissions = binding.permissionsSection
        val parent = permissions.permissionsSummaryText.parent as? LinearLayout ?: return
        parent.findViewWithTag<View>(STATUS_ROWS_TAG)?.let { parent.removeView(it) }

        val container = LinearLayout(activity).apply {
            tag = STATUS_ROWS_TAG
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(activity, 8) }
        }

        rows.forEach { row ->
            container.addView(permissionRowView(activity, row))
        }

        val insertIndex = parent.indexOfChild(permissions.permissionsSummaryText).coerceAtLeast(0) + 1
        parent.addView(container, insertIndex)
    }

    private fun permissionRowView(activity: MainActivity, row: MainPermissionRow): View {
        val activeColor = Color.rgb(20, 83, 45)
        val activeBg = Color.rgb(220, 252, 231)
        val activeBorder = Color.rgb(134, 239, 172)
        val missingColor = ContextCompat.getColor(activity, R.color.calllog_error)
        val missingBg = Color.rgb(254, 242, 242)
        val missingBorder = Color.rgb(252, 165, 165)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
            background = roundedRect(
                if (row.active) activeBg else missingBg,
                dp(activity, 12),
                if (row.active) activeBorder else missingBorder,
                dp(activity, 1),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(activity, 6) }

            addView(
                TextView(activity).apply {
                    text = "${row.label}: ${permissionStateLabel(row)}"
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (row.active) activeColor else missingColor)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                },
            )

            when {
                !row.active -> addView(actionButton(activity, "Включи", row.onEnable))
                row.onDisable != null -> addView(actionButton(activity, "Изключи", row.onDisable))
            }
        }
    }

    private fun actionButton(activity: MainActivity, text: String, action: () -> Unit): MaterialButton {
        return MaterialButton(activity).apply {
            this.text = text
            textSize = 13f
            minHeight = dp(activity, 36)
            minimumHeight = dp(activity, 36)
            setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(activity, 8) }
        }
    }

    private fun permissionStateLabel(row: MainPermissionRow): String =
        if (row.active) "активно" else row.inactiveLabel

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(activity: MainActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
