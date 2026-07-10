package com.onlineimoti.calllog

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.activity.result.ActivityResultLauncher

internal object DefaultSmsRoleController {
    fun isDefaultSmsApp(context: Context): Boolean {
        return Telephony.Sms.getDefaultSmsPackage(context.applicationContext) == context.packageName
    }

    fun requestDefaultSmsRole(
        activity: Activity,
        launcher: ActivityResultLauncher<Intent>,
        setStatus: (String) -> Unit,
    ) {
        if (isDefaultSmsApp(activity)) {
            setStatus(activity.getString(R.string.default_sms_status_active))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                setStatus(activity.getString(R.string.default_sms_role_unavailable))
                return
            }
            if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                setStatus(activity.getString(R.string.default_sms_status_active))
                return
            }
            launcher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
            return
        }

        launcher.launch(
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName)
            },
        )
    }
}
