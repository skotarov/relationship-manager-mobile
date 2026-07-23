package com.onlineimoti.calllog

import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService

/** Runs the Settings server connection test away from the main thread. */
internal class MainServerConnectionController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val executor: ExecutorService,
    private val saveConfig: () -> AppConfig,
    private val setStatus: (String) -> Unit,
) {
    fun test() {
        val config = saveConfig()
        val remote = binding.remoteSettingsSection
        remote.serverConnectionTestStatusText.visibility = android.view.View.VISIBLE
        remote.serverConnectionTestStatusText.text = activity.getString(R.string.test_server_connection_running)
        remote.testServerConnectionButton.isEnabled = false
        executor.execute {
            val result = runCatching { ServerConnectionTester.test(config) }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                remote.testServerConnectionButton.isEnabled = true
                result.onSuccess { status ->
                    remote.serverConnectionTestStatusText.text = buildString {
                        append(if (status.ok) "✅ " else "⚠️ ")
                        append(status.title)
                        if (status.detail.isNotBlank()) append("\n").append(status.detail)
                    }
                    setStatus(status.title)
                }.onFailure { error ->
                    val message = error.message.orEmpty().ifBlank {
                        activity.getString(R.string.test_server_connection_failed)
                    }
                    remote.serverConnectionTestStatusText.text = "❌ $message"
                    setStatus(message)
                }
            }
        }
    }
}
