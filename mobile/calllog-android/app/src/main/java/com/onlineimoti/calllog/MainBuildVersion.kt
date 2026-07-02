package com.onlineimoti.calllog

import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal object MainBuildVersion {
    fun render(activity: AppCompatActivity, binding: ActivityMainBinding) {
        val packageInfo = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: BuildConfig.VERSION_NAME
        val versionCode = packageInfo?.longVersionCode?.toString() ?: BuildConfig.VERSION_CODE.toString()
        binding.settingsGeneralGroup.buildVersionText.text = "Build version: $versionName ($versionCode)"
    }
}
