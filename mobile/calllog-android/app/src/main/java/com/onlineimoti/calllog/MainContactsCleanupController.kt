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
    private val cleanupOrphansButton get() = contactLink.cleanupOrphanContactsButton
    private val registerAllButton get() = contactLink.registerAllContactsButton
    private val repairButton get() = contactLink.debugCrmContactNameButton

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
        refreshFromCurrentTask()
    }

    fun refreshFromCurrentTask() {
        applyTaskState(BulkContactsTaskRunner.currentState())
    }

    fun registerAllCallReportContacts() {
        val state = BulkContactsTaskRunner.currentState()
        if (state.running && state.action == BulkContactsTaskAction.REGISTER) {
            BulkContactsTaskRunner.cancel()
        } else if (!state.running) {
            BulkContactsTaskRunner.registerAll(activity.applicationContext)
        }
    }

    fun repairAllRmContacts() {
        val state = BulkContactsTaskRunner.currentState()
        if (state.running && state.action == BulkContactsTaskAction.REPAIR) {
            BulkContactsTaskRunner.cancel()
        } else if (!state.running) {
            BulkContactsTaskRunner.repairAll(activity.applicationContext)
        }
    }

    fun cleanupOrphanRmContacts() {
        val state = BulkContactsTaskRunner.currentState()
        if (state.running && state.action == BulkContactsTaskAction.CLEANUP_ORPHANS) {
            BulkContactsTaskRunner.cancel()
        } else if (!state.running) {
            BulkContactsTaskRunner.cleanupOrphans(activity.applicationContext)
        }
    }

    fun cleanupCallReportContacts() {
        val state = BulkContactsTaskRunner.currentState()
        if (state.running && state.action == BulkContactsTaskAction.CLEANUP) {
            BulkContactsTaskRunner.cancel()
        } else if (!state.running) {
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
            state.running -> state.status.ifBlank { "Обработка на RM записите…" }
            else -> state.status.ifBlank { "Обработка на RM записите…" }
        }

        if (state.running) {
            registerAllButton.isEnabled = false
            repairButton.isEnabled = false
            cleanupOrphansButton.isEnabled = false
            cleanupButton.isEnabled = false
            when (state.action) {
                BulkContactsTaskAction.REGISTER -> {
                    registerAllButton.isEnabled = !state.stopping
                    registerAllButton.text = if (state.stopping) "Спиране…" else "Стоп"
                }
                BulkContactsTaskAction.REPAIR -> {
                    repairButton.isEnabled = !state.stopping
                    repairButton.text = if (state.stopping) "Спиране…" else "Стоп"
                }
                BulkContactsTaskAction.CLEANUP_ORPHANS -> {
                    cleanupOrphansButton.isEnabled = !state.stopping
                    cleanupOrphansButton.text = if (state.stopping) "Спиране…" else "Стоп"
                }
                BulkContactsTaskAction.CLEANUP -> {
                    cleanupButton.isEnabled = !state.stopping
                    cleanupButton.text = if (state.stopping) "Спиране…" else "Стоп"
                }
                BulkContactsTaskAction.IDLE -> Unit
            }
            setStatus(state.status)
        } else {
            cleanupButton.isEnabled = true
            cleanupOrphansButton.isEnabled = true
            registerAllButton.isEnabled = true
            repairButton.isEnabled = true
            cleanupButton.text = activity.getString(R.string.permissions_cleanup_contacts_button)
            cleanupOrphansButton.text = activity.getString(R.string.contact_link_clean_orphans_button)
            registerAllButton.text = activity.getString(R.string.contact_link_register_all_button)
            repairButton.text = activity.getString(R.string.contact_link_debug_button)
            if (state.status.isNotBlank()) setStatus(state.status)
        }
    }

    private fun taskLabel(state: BulkContactsTaskState): String {
        val label = when (state.action) {
            BulkContactsTaskAction.REGISTER -> "Регистриране ${state.progress.percent}%"
            BulkContactsTaskAction.REPAIR -> "Поправяне ${state.progress.percent}%"
            BulkContactsTaskAction.CLEANUP_ORPHANS -> "Осиротели ${state.progress.percent}%"
            BulkContactsTaskAction.CLEANUP -> "Почистване ${state.progress.percent}%"
            BulkContactsTaskAction.IDLE -> "Обработка ${state.progress.percent}%"
        }
        return "$label (${state.progress.processed}/${state.progress.total})"
    }
}
