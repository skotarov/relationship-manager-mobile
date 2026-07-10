package com.onlineimoti.calllog

/**
 * Runtime capabilities for the single Relationship Manager app.
 *
 * Google Play Billing can be enabled while the app still works as a local call log
 * when the server is disabled in Settings. Billing is only for the optional company
 * license flow; it must not turn the whole app into a server-only CRM build.
 */
internal object DistributionCapabilities {
    val isPlayBusinessBuild: Boolean
        get() = false

    val supportsLocalDeviceData: Boolean
        get() = true
}
