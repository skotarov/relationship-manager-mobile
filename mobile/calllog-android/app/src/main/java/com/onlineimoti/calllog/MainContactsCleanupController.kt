package com.onlineimoti.calllog

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.Executor

internal class MainContactsCleanupController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val executor: Executor,
    private val setStatus: (String) -> Unit,
    private val dp: (Int) -> Int,
) {
    private var progress: ProgressBar? = null
    private var progressRow: LinearLayout? = null
    private var running = false

    fun addProgressBar() {
        val parent = binding.cleanupContactsButton.parent as? ViewGroup ?: return
        if (progressRow != null) return
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val spinner = ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) }
        }
        row.addView(spinner)
        row.addView(TextView(activity).apply {
            text = "Почистване на Call Report записите…"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(binding.cleanupContactsButton.currentTextColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val cleanupIndex = parent.indexOfChild(binding.cleanupContactsButton)
        parent.addView(row, if (cleanupIndex >= 0) cleanupIndex + 1 else parent.childCount)
        progress = spinner
        progressRow = row
    }

    fun cleanupCallReportContacts() {
        if (running) return
        setRunning(true)
        setStatus("Почиствам Call Report записите от контактите…")
        val appContext = activity.applicationContext
        executor.execute {
            val deleted = CallReportContactIntegration.removeAllCallReportContacts(appContext)
            activity.runOnUiThread {
                setRunning(false)
                setStatus("Премахнати Call Report записи от контактите: $deleted")
            }
        }
    }

    private fun setRunning(value: Boolean) {
        running = value
        progressRow?.visibility = if (value) View.VISIBLE else View.GONE
        progress?.visibility = if (value) View.VISIBLE else View.GONE
        binding.cleanupContactsButton.isEnabled = !value
        binding.cleanupContactsButton.text = if (value) "Почистване…" else "Почисти Call Report от контактите"
    }
}
