package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.util.AttributeSet
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton

/** Header keypad button that opens the configured phone app directly on its empty dial pad. */
class HomeDialPadButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    init {
        setOnClickListener { openDialPad() }
    }

    private fun openDialPad() {
        val baseIntent = Intent(Intent.ACTION_DIAL).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val defaultDialerPackage = (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
            ?.defaultDialerPackage
            ?.takeIf { it.isNotBlank() }

        if (defaultDialerPackage != null) {
            val explicitIntent = Intent(baseIntent).setPackage(defaultDialerPackage)
            if (startIfAvailable(explicitIntent)) return
        }

        val externalDialer = context.packageManager
            .queryIntentActivities(baseIntent, 0)
            .firstOrNull { it.activityInfo.packageName != context.packageName }
            ?.activityInfo
            ?.let { info -> Intent(baseIntent).setClassName(info.packageName, info.name) }

        if (externalDialer != null && startIfAvailable(externalDialer)) return

        Toast.makeText(context, "Не е намерено приложение за набиране", Toast.LENGTH_SHORT).show()
    }

    private fun startIfAvailable(intent: Intent): Boolean {
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
