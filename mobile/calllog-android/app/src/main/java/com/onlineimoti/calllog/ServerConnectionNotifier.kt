package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Shows a small user-visible warning when a configured server request fails.
 *
 * The warning is intentionally rate-limited: several Home/History background
 * requests can fail together when the token is wrong or the server is offline.
 */
internal object ServerConnectionNotifier {
    private const val MIN_INTERVAL_MS = 4_000L
    private const val MESSAGE = "Няма връзка със сървъра"
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastShownAtMs = 0L

    fun notifyFailure(context: Context?, config: AppConfig, error: Throwable? = null, force: Boolean = false) {
        if (context == null) return
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastShownAtMs < MIN_INTERVAL_MS) return
        lastShownAtMs = now
        val appContext = context.applicationContext
        mainHandler.post {
            Toast.makeText(appContext, MESSAGE, Toast.LENGTH_SHORT).show()
        }
    }
}
