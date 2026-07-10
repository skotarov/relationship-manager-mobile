package com.onlineimoti.calllog

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.Executors

/** Required quick-response service for Android's default-SMS role. */
class SmsRespondViaMessageService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val phone = phoneFromIntent(intent)
        val body = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent?.getStringExtra("sms_body")
            ?: ""
        if (phone.isBlank() || body.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        executor.execute {
            runCatching { SmsMessageSender.send(applicationContext, phone, body) }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun phoneFromIntent(intent: Intent?): String {
        val fromData = intent?.data?.schemeSpecificPart
            ?.substringBefore('?')
            ?.substringBefore(';')
            ?.trim()
            .orEmpty()
        if (fromData.isNotBlank()) return fromData
        return intent?.getStringExtra("address").orEmpty().trim()
    }
}
