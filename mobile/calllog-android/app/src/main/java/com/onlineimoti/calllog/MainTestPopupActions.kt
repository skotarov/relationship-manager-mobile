package com.onlineimoti.calllog

import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.Executor

internal object MainTestPopupActions {
    fun testStartPopup(
        activity: MainActivity,
        binding: ActivityMainBinding,
        executor: Executor,
        config: AppConfig,
        phone: String,
        direction: String,
        remoteReady: Boolean,
        setStatus: (String) -> Unit,
    ) {
        if (phone.isBlank()) {
            setStatus("Попълни телефон.")
            return
        }
        binding.testStartPopupButton.isEnabled = false
        setStatus("Тест: показвам popup при старт за $phone …")
        executor.execute {
            val displayName = ContactGroupFilter.resolveDisplayName(activity, phone)
            val title = displayName.ifNullOrBlank { phone }
            val result = if (remoteReady) {
                runCatching {
                    CallReportRuntime.fetchLookup(config, phone, direction).let { lookup ->
                        if (displayName.isNullOrBlank()) lookup else lookup.copy(title = displayName)
                    }
                }.getOrElse {
                    LookupResult(title, "Локален режим", emptyList(), "")
                }
            } else {
                LookupResult(title, "Локален режим — без сървърни данни", emptyList(), "")
            }
            activity.runOnUiThread {
                binding.testStartPopupButton.isEnabled = true
                LookupPopupPresenter.show(activity, result, fullscreen = true, phone = phone, direction = direction)
                setStatus("Показан е тестов popup при старт.")
            }
        }
    }

    fun testEndPopup(
        activity: MainActivity,
        binding: ActivityMainBinding,
        executor: Executor,
        phone: String,
        direction: String,
        formUrl: String,
        setStatus: (String) -> Unit,
    ) {
        if (phone.isBlank()) {
            setStatus("Попълни телефон.")
            return
        }
        binding.testEndPopupButton.isEnabled = false
        setStatus("Тест: показвам popup след край за $phone …")
        executor.execute {
            val displayName = ContactGroupFilter.resolveDisplayName(activity, phone)
            val title = displayName.ifNullOrBlank { "Локални действия след разговора" }
            activity.runOnUiThread {
                binding.testEndPopupButton.isEnabled = true
                CallReportRuntime.showPostCallPromptNotification(
                    context = activity,
                    formUrl = formUrl,
                    phone = phone,
                    direction = direction,
                    title = title,
                )
                setStatus("Показан е тестов popup след край.")
            }
        }
    }

    private inline fun String?.ifNullOrBlank(fallback: () -> String): String {
        return if (this.isNullOrBlank()) fallback() else this
    }
}