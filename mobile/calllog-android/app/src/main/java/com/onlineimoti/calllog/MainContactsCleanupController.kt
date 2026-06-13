package com.onlineimoti.calllog

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
    private var overlay: View? = null
    private var overlayTitle: TextView? = null
    private var overlayStatus: TextView? = null
    private var overlayPercent: TextView? = null
    private var overlayProgress: ProgressBar? = null
    private var overlaySpinner: ProgressBar? = null
    private var overlayStopButton: MaterialButton? = null

    private val contactLink get() = binding.contactLinkSection
    private val syncButton get() = contactLink.registerAllContactsButton

    private val taskListener: (BulkContactsTaskState) -> Unit = { state ->
        applyTaskState(state)
    }

    init {
        BulkContactsTaskRunner.addListener(taskListener)
    }

    fun addProgressBar() {
        ensureOverlay()
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

    private fun ensureOverlay() {
        if (overlay != null) return
        val container = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP
            }
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedRect(Color.WHITE, dp(18), Color.rgb(203, 213, 225), dp(1))
            elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        overlayTitle = TextView(activity).apply {
            text = "Синхронизация на RM контакти"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 23, 42))
        }
        overlayStatus = TextView(activity).apply {
            text = "Подготовка…"
            textSize = 14f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(0, dp(6), 0, 0)
        }
        overlayPercent = TextView(activity).apply {
            text = "0%"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(37, 99, 235))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(4))
        }
        overlayProgress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8),
            ).apply { topMargin = dp(6) }
        }
        overlaySpinner = ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        }
        overlayStopButton = MaterialButton(activity).apply {
            text = "Стоп"
            textSize = 15f
            setOnClickListener { BulkContactsTaskRunner.cancel() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
        }
        val hint = TextView(activity).apply {
            text = "Можеш да продължиш да работиш с настройките, докато синхронизацията тече."
            textSize = 12.5f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(8), 0, 0)
        }

        card.addView(overlayTitle)
        card.addView(overlayStatus)
        card.addView(overlayPercent)
        card.addView(overlayProgress)
        card.addView(overlaySpinner)
        card.addView(overlayStopButton)
        card.addView(hint)
        root.addView(card)
        container.addView(root)
        overlay = root
    }

    private fun applyTaskState(state: BulkContactsTaskState) {
        if (activity.isFinishing || activity.isDestroyed) return
        ensureOverlay()
        renderOverlay(state)
        renderSettingsButton(state)
        if (state.status.isNotBlank()) setStatus(state.status)
    }

    private fun renderOverlay(state: BulkContactsTaskState) {
        overlay?.visibility = if (state.running) View.VISIBLE else View.GONE
        if (!state.running) return

        overlayTitle?.text = when (state.action) {
            BulkContactsTaskAction.REGISTER -> "Синхронизация на RM контакти"
            BulkContactsTaskAction.REPAIR -> "Поправка на RM контакти"
            BulkContactsTaskAction.CLEANUP_ORPHANS -> "Почистване на осиротели RM записи"
            BulkContactsTaskAction.CLEANUP -> "Почистване на RM контакти"
            BulkContactsTaskAction.IDLE -> "Обработка на контакти"
        }
        overlayStatus?.text = state.status.ifBlank { "Обработка на RM записите…" }
        overlayStopButton?.apply {
            isEnabled = !state.stopping
            text = if (state.stopping) "Спиране…" else "Стоп"
        }

        val progress = state.progress
        if (progress.total > 0) {
            overlaySpinner?.visibility = View.GONE
            overlayProgress?.visibility = View.VISIBLE
            overlayProgress?.max = progress.total
            overlayProgress?.progress = progress.processed.coerceAtMost(progress.total)
            overlayPercent?.text = "${progress.percent}%"
        } else {
            overlayProgress?.visibility = View.GONE
            overlaySpinner?.visibility = View.VISIBLE
            overlayPercent?.text = "Подготовка…"
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
