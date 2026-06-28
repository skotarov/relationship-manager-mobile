package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal object PermissionStatusRenderer {
    private const val TAG = "callreport_permission_status_rows"

    fun refresh(activity: MainActivity, binding: ActivityMainBinding) {
        val config = ConfigStore.load(activity)
        val popup = binding.popupSettingsSection
        val notesState = notesState(config)
        val overlayState = state(config.useOverlayPopups, Settings.canDrawOverlays(activity))
        val screeningState = state(config.useCallScreening, MainPermissionChecks.hasCallScreeningRole(activity))

        popup.overlayPopupOptionsGroup.visibility = if (config.useOverlayPopups) View.VISIBLE else View.GONE
        popup.overlayPermissionWarningText.visibility = if (overlayState == State.MISSING) View.VISIBLE else View.GONE
        popup.useCustomStartPopupCheckBox.isEnabled = overlayState != State.MISSING
        popup.useCustomEndPopupCheckBox.isEnabled = overlayState != State.MISSING
        popup.overlayPopupOptionsGroup.alpha = if (overlayState == State.MISSING) 0.55f else 1f

        val rows = mutableListOf<Row>()
        fun runtime(label: String, permission: String, granted: Boolean = has(activity, permission)) {
            rows += Row(
                label = label,
                state = if (granted) State.ACTIVE else State.MISSING,
                enable = { activity.requestAppPermissionFromSummary(permission, label) },
                disable = { openAppPermissions(activity) },
            )
        }

        runtime(
            activity.getString(R.string.permission_label_notifications),
            Manifest.permission.POST_NOTIFICATIONS,
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(activity, Manifest.permission.POST_NOTIFICATIONS),
        )
        runtime(activity.getString(R.string.permission_label_phone), Manifest.permission.READ_PHONE_STATE)
        runtime(activity.getString(R.string.permission_label_call_log), Manifest.permission.READ_CALL_LOG)
        runtime(activity.getString(R.string.permission_label_contacts_read), Manifest.permission.READ_CONTACTS)
        runtime(activity.getString(R.string.permission_label_contacts_write), Manifest.permission.WRITE_CONTACTS)

        rows += Row(
            label = activity.getString(R.string.permission_label_private_notes_storage),
            state = notesState,
            enable = { setNotesStorage(activity, binding, true) },
            disable = { setNotesStorage(activity, binding, false) },
        )
        rows += Row(
            label = activity.getString(R.string.permission_label_overlay),
            state = overlayState,
            enable = { setOverlay(activity, binding, true) },
            disable = { setOverlay(activity, binding, false) },
        )
        rows += Row(
            label = activity.getString(R.string.permission_label_call_screening),
            state = screeningState,
            enable = { setScreening(activity, binding, true) },
            disable = { setScreening(activity, binding, false) },
        )

        val summary = binding.permissionsSection.permissionsSummaryText
        summary.visibility = View.GONE
        val parent = summary.parent as? LinearLayout ?: return
        parent.findViewWithTag<View>(TAG)?.let(parent::removeView)
        val box = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) }
        }
        rows.forEach { box.addView(rowView(activity, it)) }
        parent.addView(box, parent.indexOfChild(summary) + 1)
    }

    private fun setNotesStorage(activity: MainActivity, binding: ActivityMainBinding, enabled: Boolean) {
        save(activity) { it.copy(useLocalNotesStorage = enabled) }
        refresh(activity, binding)
        toast(
            activity,
            activity.getString(if (enabled) R.string.settings_private_notes_enabled else R.string.settings_local_notes_disabled),
        )
    }

    private fun setOverlay(activity: MainActivity, binding: ActivityMainBinding, enabled: Boolean) {
        save(activity) { it.copy(useOverlayPopups = enabled) }
        refresh(activity, binding)
        if (enabled) activity.requestOverlayPermissionFromSummary()
        else toast(activity, activity.getString(R.string.settings_overlay_disabled))
    }

    private fun setScreening(activity: MainActivity, binding: ActivityMainBinding, enabled: Boolean) {
        save(activity) { it.copy(useCallScreening = enabled) }
        refresh(activity, binding)
        if (enabled) activity.requestCallScreeningPermissionFromSummary()
        else toast(activity, activity.getString(R.string.settings_screening_disabled))
    }

    private fun save(activity: MainActivity, update: (AppConfig) -> AppConfig) {
        ConfigStore.save(activity, update(ConfigStore.load(activity)))
    }

    private fun rowView(activity: MainActivity, row: Row): View {
        val colors = when (row.state) {
            State.ACTIVE -> Triple(Color.rgb(20, 83, 45), Color.rgb(220, 252, 231), Color.rgb(134, 239, 172))
            State.MISSING -> Triple(ContextCompat.getColor(activity, R.color.calllog_error), Color.rgb(254, 242, 242), Color.rgb(252, 165, 165))
            State.DISABLED -> Triple(Color.rgb(71, 85, 105), Color.rgb(241, 245, 249), Color.rgb(203, 213, 225))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
            background = shape(colors.second, dp(activity, 12), colors.third)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 6) }
            addView(TextView(activity).apply {
                text = activity.getString(
                    R.string.permission_row_status,
                    row.label,
                    activity.getString(row.state.labelRes),
                )
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.first)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            val active = row.state == State.ACTIVE
            val action = if (active) row.disable else row.enable
            action?.let {
                addView(MaterialButton(activity).apply {
                    text = activity.getString(if (active) R.string.permission_action_disable else R.string.permission_action_enable)
                    isAllCaps = false
                    textSize = 13f
                    minHeight = dp(activity, 36)
                    minimumHeight = dp(activity, 36)
                    setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
                    setOnClickListener { action() }
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 8) }
                })
            }
        }
    }

    private fun notesState(config: AppConfig): State = if (config.useLocalNotesStorage) State.ACTIVE else State.DISABLED

    private fun state(enabled: Boolean, granted: Boolean): State = when {
        !enabled -> State.DISABLED
        granted -> State.ACTIVE
        else -> State.MISSING
    }

    private fun has(activity: MainActivity, permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppPermissions(activity: MainActivity) {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        })
    }

    private fun toast(activity: MainActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun shape(color: Int, radius: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
        setStroke(1, stroke)
    }

    private fun dp(activity: MainActivity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private enum class State(val labelRes: Int) {
        ACTIVE(R.string.permission_state_active),
        MISSING(R.string.permission_state_missing),
        DISABLED(R.string.permission_state_disabled),
    }

    private data class Row(
        val label: String,
        val state: State,
        val enable: (() -> Unit)?,
        val disable: (() -> Unit)?,
    )
}
