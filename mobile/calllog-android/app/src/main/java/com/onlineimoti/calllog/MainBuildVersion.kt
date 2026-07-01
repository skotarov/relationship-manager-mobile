package com.onlineimoti.calllog

import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal object MainBuildVersion {
    fun render(activity: AppCompatActivity, binding: ActivityMainBinding) {
        val packageInfo = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: BuildConfig.VERSION_NAME
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode?.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toString()
        } ?: BuildConfig.VERSION_CODE.toString()
        binding.settingsGeneralGroup.buildVersionText.text = "Build version: $versionName ($versionCode)"
    }
}
