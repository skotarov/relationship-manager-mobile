package com.onlineimoti.calllog

import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService

internal object MainTestActions {
    fun testStartPopup(
        activity: AppCompatActivity,
        binding: ActivityMainBinding,
        executor: ExecutorService,
        setStatus: (String) -> Unit,
    ) {
        executor.execute {
            activity.runOnUiThread {
                setStatus("Test start popup action is available. Use a real call to validate the live overlay.")
            }
        }
    }

    fun testEndPopup(
        activity: AppCompatActivity,
        binding: ActivityMainBinding,
        executor: ExecutorService,
        setStatus: (String) -> Unit,
    ) {
        executor.execute {
            activity.runOnUiThread {
                setStatus("Test end popup action is available. Use a real call to validate the final overlay.")
            }
        }
    }
}
