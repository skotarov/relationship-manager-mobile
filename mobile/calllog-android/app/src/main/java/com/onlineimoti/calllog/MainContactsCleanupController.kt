package com.onlineimoti.calllog

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.Executor

internal class MainContactsCleanupController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    @Suppress("UNUSED_PARAMETER") private val executor: Executor,
    private val setStatus: (String) -> Unit,
    private val dp: (Int) -> Int,
) {
    private var progressPanel: View? = null
    private var progressTitle: TextView? = null
    private var progressStatus: TextView? = null
    private var progressPercent: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressSpinner: ProgressBar? = null
    private var progressStopButton: MaterialButton? = null

    private val contactLink get() = binding.contactLinkSection
    private val syncButton get() = contactLink.registerAllContactsButton

    private val taskListener: (BulkContactsTaskState) -> Unit = { state ->
        applyTaskState(state)
    }

    init {
        BulkContactsTaskRunner.addListener(taskListener)
    }

    fun addProgressBar() {
        ensureProgressPanel()
        refreshFromCurrentTask()
    }

    fun refreshFromCurrentTask() {
        applyTaskState(BulkContactsTaskRunner.currentState())
    }

    fun syncAllRmContacts() {
        val state = BulkContactsTaskRunner.currentState()
        if (state.running && state.action == BulkContactsTaskAction.REGISTER) {
            BulkContactsTaskRunner.cancel()
        } else if (!state.running) {
            BulkContactsTaskRunner.registerAll(activity.applicationContext)
        }
    }

    fun release() {
        BulkContactsTaskRunner.removeListener(taskListener)
    }

    private fun ensureProgressPanel() {
        if (progressPanel != null) return
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(Color.rgb(248, 250, 252), dp(16), Color.rgb(203, 213, 225), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }

        progressTitle = TextView(activity).apply {
            text = "Синхронизация на RM контакти"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 23, 42))
        }
        progressStatus = TextView(activity).apply {
            text = "Подготовка…"
            textSize = 13.5f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(5), 0, 0)
        }
        progressPercent = TextView(activity).apply {
            text = "0%"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(37, 99, 235))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(2))
        }
        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(7),
            ).apply { topMargin = dp(5) }
        }
        progressSpinner = ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            }
        }
        progressStopButton = MaterialButton(activity).apply {
            text = "Стоп"
            textSize = 14f
            setOnClickListener { BulkContactsTaskRunner.cancel() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        val hint = TextView(activity).apply {
            text = "Настройките остават активни, докато синхронизацията тече."
            textSize = 12f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(6), 0, 0)
        }

        root.addView(progressTitle)
        root.addView(progressStatus)
        root.addView(progressPercent)
        root.addView(progressBar)
        root.addView(progressSpinner)
        root.addView(progressStopButton)
        root.addView(hint)
        contactLink.contactLinkBulkActionsGroup.addView(root)
        progressPanel = root
    }

    private fun applyTaskState(state: BulkContactsTaskState) {
        if (activity.isFinishing || activity.isDestroyed) return
        ensureProgressPanel()
        renderProgressPanel(state)
        renderSettingsButton(state)
        if (state.status.isNotBlank()) setStatus(state.status)
    }

    private fun renderProgressPanel(state: BulkContactsTaskState) {
        progressPanel?.visibility = if (state.running) View.VISIBLE else View.GONE
        if (!state.running) return

        progressTitle?.text = when (state.action) {
            BulkContactsTaskAction.REGISTER -> "Синхронизация на RM контакти"
            BulkContactsTaskAction.REPAIR -> "Поправка на RM контакти"
            BulkContactsTaskAction.CLEANUP_ORPHANS -> "Почистване на осиротели RM записи"
            BulkContactsTaskAction.CLEANUP -> "Почистване на RM контакти"
            BulkContactsTaskAction.IDLE -> "Обработка на контакти"
        }
        progressStatus?.text = state.status.ifBlank { "Обработка на RM записите…" }
        progressStopButton?.apply {
            isEnabled = !state.stopping
            text = if (state.stopping) "Спиране…" else "Стоп"
        }

        val progress = state.progress
        if (progress.total > 0) {
            progressSpinner?.visibility = View.GONE
            progressBar?.visibility = View.VISIBLE
            progressBar?.max = progress.total
            progressBar?.progress = progress.processed.coerceAtMost(progress.total)
            progressPercent?.text = "${progress.percent}%"
        } else {
            progressBar?.visibility = View.GONE
            progressSpinner?.visibility = View.VISIBLE
            progressPercent?.text = "Подготовка…"
        }
    }

    private fun renderSettingsButton(state: BulkContactsTaskState) {
        if (state.running) {
            syncButton.isEnabled = state.action == BulkContactsTaskAction.REGISTER && !state.stopping
            syncButton.text = if (state.stopping) "Спиране…" else "Стоп"
        } else {
            syncButton.isEnabled = true
            syncButton.text = activity.getString(R.string.contact_link_sync_all_button)
        }
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }
}
