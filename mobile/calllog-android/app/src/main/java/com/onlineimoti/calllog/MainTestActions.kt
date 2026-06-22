package com.onlineimoti.calllog

import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService

internal object MainTestActions {
    fun testStartPopup(
        activity: AppCompatActivity,
        binding: ActivityMainBinding,
        executor: ExecutorService,
        setStatus: (String) -> Unit,
    ) {
        val phone = binding.testsSection.phoneInput.text?.toString().orEmpty().ifBlank { "0877904903" }
        val direction = selectedDirection(binding)
        executor.execute {
            val title = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty()
                .ifBlank { activity.getString(R.string.test_start_title) }
            val result = LookupResult(
                title = title,
                subtitle = phone,
                lines = listOf(
                    activity.getString(R.string.test_start_line),
                    activity.getString(
                        R.string.test_direction_line,
                        PhoneCallReader.directionLabel(direction).ifBlank { direction },
                    ),
                    activity.getString(R.string.test_no_real_call),
                ),
                openFormUrl = "",
            )
            activity.runOnUiThread {
                LookupPopupPresenter.show(
                    context = activity,
                    result = result,
                    fullscreen = false,
                    phone = phone,
                    direction = direction,
                )
                val config = ConfigStore.load(activity)
                val mode = activity.getString(
                    if (config.useOverlayPopups && config.useCustomStartPopup && Settings.canDrawOverlays(activity)) {
                        R.string.test_mode_custom_overlay
                    } else {
                        R.string.test_mode_system_notification
                    },
                )
                setStatus(activity.getString(R.string.test_start_status, mode, phone))
            }
        }
    }

    fun testEndPopup(
        activity: AppCompatActivity,
        binding: ActivityMainBinding,
        executor: ExecutorService,
        setStatus: (String) -> Unit,
    ) {
        val phone = binding.testsSection.phoneInput.text?.toString().orEmpty().ifBlank { "0877904903" }
        val direction = selectedDirection(binding)
        executor.execute {
            val title = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty()
                .ifBlank { activity.getString(R.string.test_end_title) }
            activity.runOnUiThread {
                val config = ConfigStore.load(activity)
                if (config.postCallEndAction == ConfigStore.POST_CALL_END_ACTION_NOTHING) {
                    setStatus(activity.getString(R.string.test_end_disabled))
                    return@runOnUiThread
                }
                CallReportRuntime.showPostCallPromptNotification(
                    context = activity,
                    formUrl = "",
                    phone = phone,
                    direction = direction,
                    title = title,
                )
                val mode = activity.getString(
                    when {
                        config.useOverlayPopups && config.useCustomEndPopup && Settings.canDrawOverlays(activity) -> R.string.test_mode_custom_overlay
                        config.postCallEndAction == ConfigStore.POST_CALL_END_ACTION_HISTORY -> R.string.test_mode_history
                        else -> R.string.test_mode_note_editor
                    },
                )
                setStatus(activity.getString(R.string.test_end_status, mode, phone))
            }
        }
    }

    private fun selectedDirection(binding: ActivityMainBinding): String {
        return if (binding.testsSection.directionIn.isChecked) "in" else "out"
    }
}
