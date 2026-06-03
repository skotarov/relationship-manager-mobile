package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal enum class BulkContactsTaskAction {
    IDLE,
    REGISTER,
    CLEANUP,
}

internal data class BulkContactsTaskState(
    val running: Boolean = false,
    val action: BulkContactsTaskAction = BulkContactsTaskAction.IDLE,
    val progress: BulkContactRegistrationProgress = BulkContactRegistrationProgress(0, 0),
    val status: String = "",
)

internal object BulkContactsTaskRunner {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CallReportBulkContacts").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = false
        }
    }
    private val listeners = CopyOnWriteArrayList<(BulkContactsTaskState) -> Unit>()

    @Volatile
    private var state = BulkContactsTaskState()

    fun addListener(listener: (BulkContactsTaskState) -> Unit) {
        listeners.add(listener)
        val snapshot = state
        mainHandler.post { listener(snapshot) }
    }

    fun removeListener(listener: (BulkContactsTaskState) -> Unit) {
        listeners.remove(listener)
    }

    fun registerAll(context: Context) {
        val appContext = context.applicationContext
        if (!tryStart(BulkContactsTaskAction.REGISTER, "Регистрирам всички контакти към Call Report… 0%")) return
        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val result = CallReportBulkContactRegistrar.registerPhoneOnlyLinks(appContext) { progress ->
                updateProgress(
                    action = BulkContactsTaskAction.REGISTER,
                    progress = progress,
                    status = "Регистрирам всички контакти към Call Report… ${progress.percent}%",
                )
            }
            finish(
                action = BulkContactsTaskAction.REGISTER,
                progress = BulkContactRegistrationProgress(result.scanned, result.scanned),
                status = "Регистрирани: ${result.created}, вече имащи: ${result.skippedExisting}, грешки: ${result.failed}, проверени: ${result.scanned}",
            )
        }
    }

    fun cleanupAll(context: Context) {
        val appContext = context.applicationContext
        if (!tryStart(BulkContactsTaskAction.CLEANUP, "Почиствам Call Report записите от контактите… 0%")) return
        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var latestProgress = BulkContactRegistrationProgress(0, 0)
            val deleted = CallReportContactIntegration.removeAllCallReportContacts(appContext) { progress ->
                latestProgress = progress
                updateProgress(
                    action = BulkContactsTaskAction.CLEANUP,
                    progress = progress,
                    status = "Почиствам Call Report записите от контактите… ${progress.percent}%",
                )
            }
            finish(
                action = BulkContactsTaskAction.CLEANUP,
                progress = latestProgress,
                status = "Премахнати Call Report записи от контактите: $deleted",
            )
        }
    }

    @Synchronized
    private fun tryStart(action: BulkContactsTaskAction, status: String): Boolean {
        if (state.running) return false
        state = BulkContactsTaskState(
            running = true,
            action = action,
            progress = BulkContactRegistrationProgress(0, 0),
            status = status,
        )
        notifyListeners()
        return true
    }

    @Synchronized
    private fun updateProgress(action: BulkContactsTaskAction, progress: BulkContactRegistrationProgress, status: String) {
        state = BulkContactsTaskState(
            running = true,
            action = action,
            progress = progress,
            status = status,
        )
        notifyListeners()
    }

    @Synchronized
    private fun finish(action: BulkContactsTaskAction, progress: BulkContactRegistrationProgress, status: String) {
        state = BulkContactsTaskState(
            running = false,
            action = action,
            progress = progress,
            status = status,
        )
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = state
        mainHandler.post {
            listeners.forEach { listener -> listener(snapshot) }
        }
    }
}
