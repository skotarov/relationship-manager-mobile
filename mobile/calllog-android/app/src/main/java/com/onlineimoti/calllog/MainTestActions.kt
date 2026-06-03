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
            val title = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty().ifBlank { "Тестов стартов popup" }
            val result = LookupResult(
                title = title,
                subtitle = phone,
                lines = listOf(
                    "Тест: popup при старт на разговор",
                    "Посока: ${PhoneCallReader.directionLabel(direction).ifBlank { direction }}",
                    "Това е тест без реално обаждане.",
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
                val mode = if (config.useOverlayPopups && config.useCustomStartPopup && Settings.canDrawOverlays(activity)) {
                    "custom overlay"
                } else {
                    "system notification"
                }
                setStatus("Пуснат е тестов стартов popup ($mode) за $phone.")
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
            val title = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty().ifBlank { "Тестов финален popup" }
            activity.runOnUiThread {
                val config = ConfigStore.load(activity)
                if (config.postCallEndAction == ConfigStore.POST_CALL_END_ACTION_NOTHING) {
                    setStatus("Финалният popup е изключен: настройката After the call ends е Nothing.")
                    return@runOnUiThread
                }
                CallReportRuntime.showPostCallPromptNotification(
                    context = activity,
                    formUrl = "",
                    phone = phone,
                    direction = direction,
                    title = title,
                )
                val mode = if (config.useOverlayPopups && config.useCustomEndPopup && Settings.canDrawOverlays(activity)) {
                    "custom overlay"
                } else if (config.postCallEndAction == ConfigStore.POST_CALL_END_ACTION_HISTORY) {
                    "history screen"
                } else {
                    "note editor"
                }
                setStatus("Пуснат е тестов финален popup/action ($mode) за $phone.")
            }
        }
    }

    private fun selectedDirection(binding: ActivityMainBinding): String {
        return if (binding.testsSection.directionIn.isChecked) "in" else "out"
    }
}
