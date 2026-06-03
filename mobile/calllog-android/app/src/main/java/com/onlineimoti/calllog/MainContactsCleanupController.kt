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
    private val cleanupButton get() = contactLink.cleanupContactsButton
    private val registerAllButton get() = contactLink.registerAllContactsButton

    private val taskListener: (BulkContactsTaskState) -> Unit = { state ->
        applyTaskState(state)
    }

    init {
        BulkContactsTaskRunner.addListener(taskListener)
    }

    fun addProgressBar() {
        val parent = contactLink.contactLinkBulkActionsGroup.parent as? ViewGroup ?: return
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
        val label = TextView(activity).apply {
            text = "Обработка на Call Report записите…"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cleanupButton.currentTextColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(spinner)
        row.addView(label)
        val actionsIndex = parent.indexOfChild(contactLink.contactLinkBulkActionsGroup)
        parent.addView(row, if (actionsIndex >= 0) actionsIndex + 1 else parent.childCount)
        progress = spinner
        progressText = label
        progressRow = row
    }

    fun registerAllCallReportContacts() {
        val state = BulkContactsTaskRunner.currentState()
        if (state.running && state.action == BulkContactsTaskAction.REGISTER) {
            BulkContactsTaskRunner.cancel()
        } else {
            BulkContactsTaskRunner.registerAll(activity.applicationContext)
        }
    }

    fun cleanupCallReportContacts() {
        val state = BulkContactsTaskRunner.currentState()
        if (state.running && state.action == BulkContactsTaskAction.CLEANUP) {
            BulkContactsTaskRunner.cancel()
        } else {
            BulkContactsTaskRunner.cleanupAll(activity.applicationContext)
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
            state.running -> state.status.ifBlank { "Обработка на Call Report записите…" }
            else -> state.status.ifBlank { "Обработка на Call Report записите…" }
        }

        if (state.running) {
            when (state.action) {
                BulkContactsTaskAction.REGISTER -> {
                    registerAllButton.isEnabled = !state.stopping
                    registerAllButton.text = if (state.stopping) "Спиране…" else "Стоп"
                    cleanupButton.isEnabled = false
                }
                BulkContactsTaskAction.CLEANUP -> {
                    cleanupButton.isEnabled = !state.stopping
                    cleanupButton.text = if (state.stopping) "Спиране…" else "Стоп"
                    registerAllButton.isEnabled = false
                }
                BulkContactsTaskAction.IDLE -> Unit
            }
            setStatus(state.status)
        } else {
            cleanupButton.isEnabled = true
            registerAllButton.isEnabled = true
            cleanupButton.text = activity.getString(R.string.permissions_cleanup_contacts_button)
            registerAllButton.text = activity.getString(R.string.contact_link_register_all_button)
            if (state.status.isNotBlank()) setStatus(state.status)
        }
    }

    private fun taskLabel(state: BulkContactsTaskState): String {
        val label = when (state.action) {
            BulkContactsTaskAction.REGISTER -> "Регистриране ${state.progress.percent}%"
            BulkContactsTaskAction.CLEANUP -> "Почистване ${state.progress.percent}%"
            BulkContactsTaskAction.IDLE -> "Обработка ${state.progress.percent}%"
        }
        return "$label (${state.progress.processed}/${state.progress.total})"
    }
}
