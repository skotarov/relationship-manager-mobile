package com.onlineimoti.calllog

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.activity.result.ActivityResultLauncher

internal object SmsRoleController {
    fun isDefaultSmsApp(context: Context): Boolean =
        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    fun requestDefaultSmsRole(
        activity: Activity,
        launcher: ActivityResultLauncher<Intent>,
        setStatus: (String) -> Unit,
    ) {
        val roleManager = activity.getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
            setStatus(activity.getString(R.string.settings_sms_role_not_changed))
            return
        }
        launcher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
    }
}
