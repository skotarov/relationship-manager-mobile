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
    @Suppress("UNUSED_PARAMETER") private val executor: Executor,
    private val setStatus: (String) -> Unit,
    private val dp: (Int) -> Int,
) {
    private var progress: ProgressBar? = null
    private var progressRow: LinearLayout? = null
    private var progressText: TextView? = null

    private val contactLink get() = binding.contactLinkSection
    private val syncButton get() = contactLink.registerAllContactsButton

    private val taskListener: (BulkContactsTaskState) -> Unit = { state ->
        applyTaskState(state)
    }

    init {
        BulkContactsTaskRunner.addListener(taskListener)
    }

    fun addProgressBar() {
        val parent = contactLink.contactLinkBulkActionsGroup.parent as? ViewGroup ?: return
        if (progressRow != null) {
            refreshFromCurrentTask()
            return
        }
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
        val label = TextView(activity).apply {
            text = "Обработка на RM записите…"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(syncButton.currentTextColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(spinner)
        row.addView(label)
        val actionsIndex = parent.indexOfChild(contactLink.contactLinkBulkActionsGroup)
        parent.addView(row, if (actionsIndex >= 0) actionsIndex + 1 else parent.childCount)
        progress = spinner
        progressText = label
        progressRow = row
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

    private fun applyTaskState(state: BulkContactsTaskState) {
        if (activity.isFinishing || activity.isDestroyed) return
        progressRow?.visibility = if (state.running) View.VISIBLE else View.GONE
        progress?.visibility = if (state.running) View.VISIBLE else View.GONE
        progressText?.text = when {
            state.running && state.progress.total > 0 -> taskLabel(state)
            state.running -> state.status.ifBlank { "Обработка на RM записите…" }
            else -> state.status.ifBlank { "Обработка на RM записите…" }
        }

        if (state.running) {
            syncButton.isEnabled = state.action == BulkContactsTaskAction.REGISTER && !state.stopping
            syncButton.text = if (state.stopping) "Спиране…" else "Стоп"
            setStatus(state.status)
        } else {
            syncButton.isEnabled = true
            syncButton.text = activity.getString(R.string.contact_link_sync_all_button)
            if (state.status.isNotBlank()) setStatus(state.status)
        }
    }

    private fun taskLabel(state: BulkContactsTaskState): String {
        val label = when (state.action) {
            BulkContactsTaskAction.REGISTER -> "Синхронизиране ${state.progress.percent}%"
            BulkContactsTaskAction.REPAIR -> "Поправяне ${state.progress.percent}%"
            BulkContactsTaskAction.CLEANUP_ORPHANS -> "Осиротели ${state.progress.percent}%"
            BulkContactsTaskAction.CLEANUP -> "Почистване ${state.progress.percent}%"
            BulkContactsTaskAction.IDLE -> "Обработка ${state.progress.percent}%"
        }
        return "$label (${state.progress.processed}/${state.progress.total})"
    }
}
