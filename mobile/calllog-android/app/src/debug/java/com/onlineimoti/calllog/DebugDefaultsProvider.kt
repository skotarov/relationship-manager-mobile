package com.onlineimoti.calllog

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.google.android.material.textfield.TextInputEditText

class DebugDefaultsProvider : ContentProvider() {
    private var startupSyncScheduled = false

    override fun onCreate(): Boolean {
        val appContext = context ?: return true
        appContext.getSharedPreferences("callreport_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("notify_known_contacts", true)
            .putInt("post_call_timeout", 6)
            .apply()

        (appContext.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (activity !is MainActivity) return

                    val phoneInput = activity.findViewById<TextInputEditText>(R.id.phoneInput) ?: return
                    val currentPhone = phoneInput.text?.toString().orEmpty().trim()
                    if (currentPhone.isBlank() || currentPhone == OLD_TEST_PHONE) {
                        phoneInput.setText(DEFAULT_TEST_PHONE)
                    }

                    scheduleStartupSyncAfterUiReady(activity)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        return true
    }

    private fun scheduleStartupSyncAfterUiReady(activity: Activity) {
        if (startupSyncScheduled) return
        startupSyncScheduled = true
        activity.window.decorView.post {
            runCatching {
                CallReportSyncScheduler.enqueueCatchUp(activity.applicationContext, reason = "app_start")
                CallReportNoteOutboxScheduler.enqueue(activity.applicationContext, reason = "app_start")
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val DEFAULT_TEST_PHONE = "0877904903"
        private const val OLD_TEST_PHONE = "0876442321"
    }
}
