package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationManager
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
        val fullScreenState = state(config.useFullScreenPopup, canUseFullScreen(activity))

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
            "Notifications",
            Manifest.permission.POST_NOTIFICATIONS,
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(activity, Manifest.permission.POST_NOTIFICATIONS),
        )
        runtime("Phone", Manifest.permission.READ_PHONE_STATE)
        runtime("Call report log", Manifest.permission.READ_CALL_LOG)
        runtime("Contacts read", Manifest.permission.READ_CONTACTS)
        runtime("Contacts write", Manifest.permission.WRITE_CONTACTS)

        rows += Row(
            label = if (config.usePublicNotesFolder) "Public notes storage" else "Private notes storage",
            state = notesState,
            enable = { setNotesStorage(activity, binding, true) },
            disable = { setNotesStorage(activity, binding, false) },
        )
        rows += Row(
            label = "Popup над други приложения",
            state = overlayState,
            enable = { setOverlay(activity, binding, true) },
            disable = { setOverlay(activity, binding, false) },
        )
        rows += Row(
            label = "Call screening",
            state = screeningState,
            enable = { setScreening(activity, binding, true) },
            disable = { setScreening(activity, binding, false) },
        )

        val defaultSms = SmsRoleController.isDefaultSmsApp(activity)
        rows += Row(
            label = "Default SMS",
            state = if (defaultSms) State.ACTIVE else State.MISSING,
            enable = { activity.requestDefaultSmsRoleFromSummary() },
            disable = { openDefaults(activity) },
        )
        if (defaultSms) {
            runtime("SMS receive", Manifest.permission.RECEIVE_SMS)
            runtime("SMS read", Manifest.permission.READ_SMS)
            runtime("SMS send", Manifest.permission.SEND_SMS)
        }

        rows += Row(
            label = "Full-screen popup",
            state = fullScreenState,
            enable = { setFullScreenPopup(activity, binding, true) },
            disable = { setFullScreenPopup(activity, binding, false) },
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
        val config = ConfigStore.load(activity)
        when {
            !enabled -> toast(activity, "Локалните бележки са изключени. Старите бележки не са изтрити.")
            config.usePublicNotesFolder && !LocalNotesFileStore.canUsePublicFolder() -> activity.requestPublicNotesStoragePermissionFromSummary()
            config.usePublicNotesFolder -> toast(activity, "Публичното записване на бележки е включено.")
            else -> toast(activity, "Private notes storage е включено.")
        }
    }

    private fun setOverlay(activity: MainActivity, binding: ActivityMainBinding, enabled: Boolean) {
        save(activity) { it.copy(useOverlayPopups = enabled) }
        refresh(activity, binding)
        if (enabled) activity.requestOverlayPermissionFromSummary()
        else toast(activity, "Popup-ите над други приложения са изключени.")
    }

    private fun setScreening(activity: MainActivity, binding: ActivityMainBinding, enabled: Boolean) {
        save(activity) { it.copy(useCallScreening = enabled) }
        refresh(activity, binding)
        if (enabled) activity.requestCallScreeningPermissionFromSummary()
        else toast(activity, "Call screening е изключен в приложението.")
    }

    private fun setFullScreenPopup(activity: MainActivity, binding: ActivityMainBinding, enabled: Boolean) {
        save(activity) { it.copy(useFullScreenPopup = enabled) }
        refresh(activity, binding)
        if (!enabled) {
            toast(activity, "Full-screen popup е изключен в приложението.")
        } else if (!canUseFullScreen(activity)) {
            activity.requestFullScreenIntentPermissionFromSummary()
        } else {
            toast(activity, "Full-screen popup е включен.")
        }
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
                text = "${row.label}: ${row.state.label}"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.first)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            val active = row.state == State.ACTIVE
            val action = if (active) row.disable else row.enable
            action?.let {
                addView(MaterialButton(activity).apply {
                    text = if (active) "Изключи" else "Включи"
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

    private fun notesState(config: AppConfig): State = when {
        !config.useLocalNotesStorage -> State.DISABLED
        config.usePublicNotesFolder && !LocalNotesFileStore.canUsePublicFolder() -> State.MISSING
        else -> State.ACTIVE
    }

    private fun state(enabled: Boolean, granted: Boolean): State = when {
        !enabled -> State.DISABLED
        granted -> State.ACTIVE
        else -> State.MISSING
    }

    private fun has(activity: MainActivity, permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun canUseFullScreen(activity: MainActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return activity.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
    }

    private fun openAppPermissions(activity: MainActivity) {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${activity.packageName}")
        })
    }

    private fun openDefaults(activity: MainActivity) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
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

    private enum class State(val label: String) {
        ACTIVE("активно"),
        MISSING("липсва"),
        DISABLED("изключено"),
    }

    private data class Row(
        val label: String,
        val state: State,
        val enable: (() -> Unit)?,
        val disable: (() -> Unit)?,
    )
}
