package com.onlineimoti.calllog

import android.app.Activity
import android.app.Application
import android.os.Bundle

class TranslationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                scheduleTranslationApply(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                scheduleTranslationApply(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun scheduleTranslationApply(activity: Activity) {
        val decorView = activity.window?.decorView ?: return
        decorView.post {
            TranslationManager.applyOverridesToViewTree(activity, decorView)
        }
    }
}
