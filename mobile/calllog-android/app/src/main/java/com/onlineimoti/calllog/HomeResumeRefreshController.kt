package com.onlineimoti.calllog

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/**
 * Rechecks an already rendered Call Log after returning from another screen.
 * The initial page load is deliberately excluded so it is never parsed twice.
 */
internal class HomeResumeRefreshController private constructor(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var firstResume = true
    private var settingsWasOpened = false
    private var receiverRegistered = false

    private val refreshRunnable = Runnable {
        if (!canRefreshLoadedPage() || !activity.hasWindowFocus()) return@Runnable
        // This is a recheck of an already visible page, not a first load. Keep the
        // existing rows on screen while the fresh snapshot is prepared.
        HomeRefreshRenderPolicy.requestKeepExistingRows()
        activity.sendBroadcast(
            Intent(HomeActivity.ACTION_CONTACT_NOTE_SAVED)
                .setPackage(activity.packageName),
        )
    }

    private val dataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A real save already causes an immediate authoritative refresh.
            // Do not repeat it again five seconds later.
            cancelPending()
        }
    }

    private fun install() {
        activity.application.registerActivityLifecycleCallbacks(this)
        val filter = IntentFilter().apply {
            addAction(HomeActivity.ACTION_CONTACT_NOTE_SAVED)
            addAction(PostCallOverlayService.ACTION_NOTES_CHANGED)
        }
        ContextCompat.registerReceiver(
            activity,
            dataChangedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    override fun onActivityResumed(resumedActivity: Activity) {
        if (resumedActivity is MainActivity) {
            settingsWasOpened = true
            return
        }
        if (resumedActivity !== activity) return
        cancelPending()
        if (firstResume) {
            firstResume = false
            return
        }
        // Settings does not change the Android call records themselves. Reusing the
        // already rendered page avoids an unnecessary refresh cycle and prevents the
        // visible Call Log from being replaced by an empty loading container.
        if (settingsWasOpened) {
            settingsWasOpened = false
            HomeRefreshRenderPolicy.clear()
            return
        }
        if (canRefreshLoadedPage()) handler.postDelayed(refreshRunnable, RESUME_REFRESH_DELAY_MS)
    }

    override fun onActivityPaused(pausedActivity: Activity) {
        if (pausedActivity === activity) cancelPending()
    }

    override fun onActivityDestroyed(destroyedActivity: Activity) {
        if (destroyedActivity !== activity) return
        release()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun canRefreshLoadedPage(): Boolean {
        return !activity.isFinishing &&
            !activity.isDestroyed &&
            HomePageReadyState.isReady() &&
            binding.homeCallsContainer.childCount > 0 &&
            !binding.homeCallsRefreshLayout.isRefreshing &&
            binding.searchRow.visibility != View.VISIBLE &&
            !HomeCrmTimelineModeToggle.isContactsMode() &&
            !HomeCrmModeStore.isEnabled(activity)
    }

    private fun cancelPending() {
        handler.removeCallbacks(refreshRunnable)
    }

    private fun release() {
        cancelPending()
        HomeRefreshRenderPolicy.clear()
        activity.application.unregisterActivityLifecycleCallbacks(this)
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(dataChangedReceiver) }
            receiverRegistered = false
        }
    }

    companion object {
        private const val RESUME_REFRESH_DELAY_MS = 5_000L

        fun install(activity: AppCompatActivity, binding: ActivityHomeBinding) {
            HomeResumeRefreshController(activity, binding).install()
        }
    }
}
