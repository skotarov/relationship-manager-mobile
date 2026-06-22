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
        val storageState = state(config.usePublicNotesFolder, LocalNotesFileStore.canUsePublicFolder())
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
                label,
                if (granted) State.ACTIVE else State.MISSING,
                { activity.requestAppPermissionFromSummary(permission, label) },
                { openAppPermissions(activity) },
            )
        }
        runtime("Notifications", Manifest.permission.POST_NOTIFICATIONS, Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(activity, Manifest.permission.POST_NOTIFICATIONS))
        runtime("Phone", Manifest.permission.READ_PHONE_STATE)
        runtime("Call report log", Manifest.permission.READ_CALL_LOG)
        runtime("Contacts read", Manifest.permission.READ_CONTACTS)
        runtime("Contacts write", Manifest.permission.WRITE_CONTACTS)
        rows += Row("Достъп до файловата система", storageState, { setStorage(activity, binding, true) }, { setStorage(activity, binding, false) })
        rows += Row("Popup над други приложения", overlayState, { setOverlay(activity, binding, true) }, { setOverlay(activity, binding, false) })
        rows += Row("Call screening", screeningState, { setScreening(activity, binding, true) }, { setScreening(activity, binding, false) })

        val defaultSms = SmsRoleController.isDefaultSmsApp(activity)
        rows += Row("Default SMS", if (defaultSms) State.ACTIVE else State.MISSING, { activity.requestDefaultSmsRoleFromSummary() }, { openDefaults(activity) })
        if (defaultSms) {
            runtime("SMS receive", Manifest.permission.RECEIVE_SMS)
            runtime("SMS read", Manifest.permission.READ_SMS)
            runtime("SMS send", Manifest.permission.SEND_SMS)
        }
        val fullScreen = canUseFullScreen(activity)
        rows += Row("Full-screen popup", if (fullScreen) State.ACTIVE else State.MISSING, { activity.requestFullScreenIntentPermissionFromSummary() }, { openFullScreen(activity) })

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

    private fun setStorage(activity: MainActivity, binding: ActivityMainBinding, enabled: Boolean) {
        save(activity) { it.copy(usePublicNotesFolder = enabled) }
        refresh(activity, binding)
        if (enabled) activity.requestPublicNotesStoragePermissionFromSummary()
        else toast(activity, "Файловата система е изключена. Новите бележки ще са в частната памет.")
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

    private fun openFullScreen(activity: MainActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${activity.packageName}")
            })
        } else {
            toast(activity, "На тази Android версия няма отделна настройка за Full-screen popup.")
        }
    }

    private fun toast(activity: MainActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun shape(color: Int, radius: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
        setStroke(dpDummy(1), stroke)
    }

    private fun dp(activity: MainActivity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun dpDummy(value: Int): Int = value

    private enum class State(val label: String) { ACTIVE("активно"), MISSING("липсва"), DISABLED("изключено") }
    private data class Row(val label: String, val state: State, val enable: (() -> Unit)?, val disable: (() -> Unit)?)
}
