package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Default-SMS role and delivery bridge.
 *
 * Call Report deliberately keeps the existing SMS app as an escape route: before requesting
 * ROLE_SMS it remembers the current default package. Incoming SMS is written to the system
 * provider by the role holder, then the user can decide whether to stay in RM or open the
 * previous SMS app.
 */
internal object SmsRoleController {
    fun isDefaultSmsApp(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_SMS) && roleManager.isRoleHeld(RoleManager.ROLE_SMS)
    }

    fun requestDefaultSmsRole(
        context: Context,
        launcher: ActivityResultLauncher<Intent>,
        setStatus: (String) -> Unit,
    ) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
            setStatus("Този телефон не предлага системната роля Default SMS.")
            return
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
            setStatus("Relationship Manager вече е избраното SMS приложение.")
            return
        }

        SmsExternalAppStore.rememberCurrentDefaultSmsApp(context)
        launcher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
    }
}

internal object SmsExternalAppStore {
    private const val PREFS = "sms_external_app"
    private const val KEY_PACKAGE = "previous_default_package"

    fun rememberCurrentDefaultSmsApp(context: Context) {
        @Suppress("DEPRECATION")
        val currentPackage = Telephony.Sms.getDefaultSmsPackage(context).orEmpty()
        if (currentPackage.isNotBlank() && currentPackage != context.packageName) {
            prefs(context).edit().putString(KEY_PACKAGE, currentPackage).apply()
        }
    }

    fun openExternalSmsComposer(context: Context, address: String, body: String): Boolean {
        val normalizedAddress = PhoneNormalizer.normalize(address).ifBlank { address.trim() }
        val packageName = prefs(context).getString(KEY_PACKAGE, null).orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(normalizedAddress)}")
            if (body.isNotBlank()) putExtra("sms_body", body)
            if (packageName.isNotBlank()) setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            openGenericSmsChooser(context, normalizedAddress, body)
        }
    }

    fun openExternalSmsInbox(context: Context): Boolean {
        val packageName = prefs(context).getString(KEY_PACKAGE, null).orEmpty()
        if (packageName.isBlank()) return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    private fun openGenericSmsChooser(context: Context, address: String, body: String): Boolean {
        val target = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(address)}")
            if (body.isNotBlank()) putExtra("sms_body", body)
        }
        val ownComponent = android.content.ComponentName(context, CommunicationActionActivity::class.java)
        val chooser = Intent.createChooser(target, "Избери SMS приложение").apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ownComponent))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal object SmsProviderStore {
    fun saveIncoming(context: Context, address: String, body: String, timestamp: Long): Boolean {
        if (!SmsRoleController.isDefaultSmsApp(context)) return false
        return runCatching {
            context.contentResolver.insert(
                Telephony.Sms.Inbox.CONTENT_URI,
                ContentValues().apply {
                    put("address", address)
                    put("body", body)
                    put("date", timestamp)
                    put("read", 0)
                    put("seen", 0)
                },
            )
        }.isSuccess
    }
}

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION || !SmsRoleController.isDefaultSmsApp(context)) return

        val pendingResult = goAsync()
        Thread {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isEmpty()) return@Thread

                val address = messages.firstOrNull()?.originatingAddress.orEmpty()
                val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
                val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                if (address.isBlank()) return@Thread

                SmsProviderStore.saveIncoming(context, address, body, timestamp)
                SmsIncomingNotifier.showIncomingSms(context, address, body, timestamp)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION || !SmsRoleController.isDefaultSmsApp(context)) return
        SmsIncomingNotifier.showIncomingMms(context)
    }
}

class SmsRespondViaMessageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.data?.schemeSpecificPart.orEmpty()
        SmsIncomingNotifier.showReplyRequest(this, address)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

internal object SmsIncomingNotifier {
    private const val CHANNEL_ID = "callreport_sms_messages"
    private const val CHANNEL_NAME = "SMS през Relationship Manager"

    fun showIncomingSms(context: Context, address: String, body: String, timestamp: Long) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val normalizedPhone = PhoneNormalizer.normalize(address).ifBlank { address }
        val displayName = RmRealContactLookup.resolveDisplayName(context, normalizedPhone).orEmpty().ifBlank { normalizedPhone }
        val pendingIntent = incomingChoicePendingIntent(context, normalizedPhone, body, timestamp, isMms = false)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_note)
            .setContentTitle("SMS от $displayName")
            .setContentText(body.ifBlank { "Ново SMS съобщение" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { "Ново SMS съобщение" }))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(normalizedPhone, timestamp), notification)
    }

    fun showIncomingMms(context: Context) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            8071,
            Intent(context, SmsIncomingChoiceActivity::class.java).apply {
                putExtra(SmsIncomingChoiceActivity.EXTRA_IS_MMS, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_note)
            .setContentTitle("Получено MMS")
            .setContentText("Отвори, за да избереш Call Report или другото SMS приложение.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(8071, notification)
    }

    fun showReplyRequest(context: Context, address: String) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val normalizedPhone = PhoneNormalizer.normalize(address).ifBlank { address }
        val pendingIntent = PendingIntent.getActivity(
            context,
            8072,
            Intent(context, CommunicationActionActivity::class.java).apply {
                action = Intent.ACTION_SENDTO
                data = Uri.parse("smsto:${Uri.encode(normalizedPhone)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_note)
            .setContentTitle("Отговор чрез SMS")
            .setContentText(if (normalizedPhone.isBlank()) "Отвори SMS избор" else "До $normalizedPhone")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(8072, notification)
    }

    private fun incomingChoicePendingIntent(
        context: Context,
        address: String,
        body: String,
        timestamp: Long,
        isMms: Boolean,
    ): PendingIntent {
        return PendingIntent.getActivity(
            context,
            notificationId(address, timestamp),
            Intent(context, SmsIncomingChoiceActivity::class.java).apply {
                putExtra(SmsIncomingChoiceActivity.EXTRA_ADDRESS, address)
                putExtra(SmsIncomingChoiceActivity.EXTRA_BODY, body)
                putExtra(SmsIncomingChoiceActivity.EXTRA_TIMESTAMP, timestamp)
                putExtra(SmsIncomingChoiceActivity.EXTRA_IS_MMS, isMms)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Входящи SMS/MMS, когато Relationship Manager е Default SMS приложение."
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationId(address: String, timestamp: Long): Int = 9000 + ((address.hashCode() xor timestamp.hashCode()) and 0x0FFF)
}
