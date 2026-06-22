package com.onlineimoti.calllog

import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal object StatusBridge {
    fun draw(activity: MainActivity, binding: ActivityMainBinding) {
        PermissionStatusRenderer.refresh(activity, binding)
    }
}
