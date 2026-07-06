package com.onlineimoti.calllog

/**
 * Keeps the public Google Play build focused on the server-backed business CRM.
 * The sideloaded internal build retains the local telephony, SMS and contact tools.
 */
internal object DistributionCapabilities {
    val isPlayBusinessBuild: Boolean
        get() = BuildConfig.PLAY_BILLING_ENABLED

    val supportsLocalDeviceData: Boolean
        get() = !isPlayBusinessBuild
}
