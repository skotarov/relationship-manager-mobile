package com.onlineimoti.calllog

import android.content.Context

/**
 * Single source of truth for server mode.
 * When the server toggle is off, callers must stay fully local: no lookup, no sync work,
 * and no server-state messages in the UI.
 */
internal object CallReportRemoteAccess {
    fun isEnabled(context: Context): Boolean = ConfigStore.load(context.applicationContext).remoteEnabled

    fun isEnabled(config: AppConfig): Boolean = config.remoteEnabled

    fun isReady(config: AppConfig): Boolean =
        config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()
}
