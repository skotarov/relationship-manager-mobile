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
    private var progressText: TextView? = null
    private var running = false

    private val contactLink get() = binding.contactLinkSection
    private val cleanupButton get() = contactLink.cleanupContactsButton
    private val registerAllButton get() = contactLink.registerAllContactsButton

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
        if (running) return
        setRunning(true, "Регистриране…")
        setStatus("Регистрирам всички контакти към Call Report…")
        val appContext = activity.applicationContext
        executor.execute {
            val result = CallReportBulkContactRegistrar.registerPhoneOnlyLinks(appContext)
            activity.runOnUiThread {
                setRunning(false, "")
                setStatus("Регистрирани: ${result.created}, вече имащи: ${result.skippedExisting}, грешки: ${result.failed}, проверени: ${result.scanned}")
            }
        }
    }

    fun cleanupCallReportContacts() {
        if (running) return
        setRunning(true, "Почистване…")
        setStatus("Почиствам Call Report записите от контактите…")
        val appContext = activity.applicationContext
        executor.execute {
            val deleted = CallReportContactIntegration.removeAllCallReportContacts(appContext)
            activity.runOnUiThread {
                setRunning(false, "")
                setStatus("Премахнати Call Report записи от контактите: $deleted")
            }
        }
    }

    private fun setRunning(value: Boolean, label: String) {
        running = value
        progressRow?.visibility = if (value) View.VISIBLE else View.GONE
        progress?.visibility = if (value) View.VISIBLE else View.GONE
        progressText?.text = label.ifBlank { "Обработка на Call Report записите…" }
        cleanupButton.isEnabled = !value
        registerAllButton.isEnabled = !value
    }
}