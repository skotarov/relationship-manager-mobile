package com.onlineimoti.calllog

import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal object MainPermissionSummary {
    fun refresh(activity: MainActivity, binding: ActivityMainBinding) {
        PermissionStatusRenderer.refresh(activity, binding)
    }
}
