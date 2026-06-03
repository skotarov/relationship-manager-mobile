package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    val stopping: Boolean = false,
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
    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var state = BulkContactsTaskState()

    fun currentState(): BulkContactsTaskState = state

    fun addListener(listener: (BulkContactsTaskState) -> Unit) {
        listeners.add(listener)
        val snapshot = state
        mainHandler.post { listener(snapshot) }
    }

    fun removeListener(listener: (BulkContactsTaskState) -> Unit) {
        listeners.remove(listener)
    }

    fun cancel() {
        val snapshot = state
        if (!snapshot.running || snapshot.stopping) return
        cancelRequested.set(true)
        updateProgress(
            action = snapshot.action,
            progress = snapshot.progress,
            status = when (snapshot.action) {
                BulkContactsTaskAction.REGISTER -> "Спирам регистрацията след текущия запис…"
                BulkContactsTaskAction.CLEANUP -> "Спирам почистването след текущия запис…"
                BulkContactsTaskAction.IDLE -> "Спирам…"
            },
            stopping = true,
        )
    }

    fun registerAll(context: Context) {
        val appContext = context.applicationContext
        if (!tryStart(BulkContactsTaskAction.REGISTER, "Регистрирам всички контакти към Call Report… 0%", appContext)) return
        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val result = CallReportBulkContactRegistrar.registerPhoneOnlyLinks(
                context = appContext,
                onProgress = { progress ->
                    updateProgress(
                        action = BulkContactsTaskAction.REGISTER,
                        progress = progress,
                        status = if (cancelRequested.get()) {
                            "Спирам регистрацията след текущия запис…"
                        } else {
                            "Регистрирам всички контакти към Call Report… ${progress.percent}%"
                        },
                        stopping = cancelRequested.get(),
                        context = appContext,
                    )
                },
                shouldCancel = { cancelRequested.get() },
            )
            finish(
                action = BulkContactsTaskAction.REGISTER,
                progress = BulkContactRegistrationProgress(result.scanned, result.scanned),
                status = if (result.canceled) {
                    "Регистрацията е спряна. Регистрирани: ${result.created}, вече имащи: ${result.skippedExisting}, грешки: ${result.failed}, проверени: ${result.scanned}"
                } else {
                    "Регистрирани: ${result.created}, вече имащи: ${result.skippedExisting}, грешки: ${result.failed}, проверени: ${result.scanned}"
                },
                context = appContext,
            )
        }
    }

    fun cleanupAll(context: Context) {
        val appContext = context.applicationContext
        if (!tryStart(BulkContactsTaskAction.CLEANUP, "Почиствам Call Report записите от контактите… 0%", appContext)) return
        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var latestProgress = BulkContactRegistrationProgress(0, 0)
            val deleted = CallReportContactIntegration.removeAllCallReportContacts(
                context = appContext,
                onProgress = { progress ->
                    latestProgress = progress
                    updateProgress(
                        action = BulkContactsTaskAction.CLEANUP,
                        progress = progress,
                        status = if (cancelRequested.get()) {
                            "Спирам почистването след текущия запис…"
                        } else {
                            "Почиствам Call Report записите от контактите… ${progress.percent}%"
                        },
                        stopping = cancelRequested.get(),
                        context = appContext,
                    )
                },
                shouldCancel = { cancelRequested.get() },
            )
            finish(
                action = BulkContactsTaskAction.CLEANUP,
                progress = latestProgress,
                status = if (cancelRequested.get()) {
                    "Почистването е спряно. Премахнати Call Report записи: $deleted"
                } else {
                    "Премахнати Call Report записи от контактите: $deleted"
                },
                context = appContext,
            )
        }
    }

    @Synchronized
    private fun tryStart(action: BulkContactsTaskAction, status: String, context: Context): Boolean {
        if (state.running) return false
        cancelRequested.set(false)
        state = BulkContactsTaskState(
            running = true,
            action = action,
            progress = BulkContactRegistrationProgress(0, 0),
            status = status,
            stopping = false,
        )
        BulkContactsProgressNotification.showRunning(context, action, state.progress, status)
        notifyListeners()
        return true
    }

    @Synchronized
    private fun updateProgress(
        action: BulkContactsTaskAction,
        progress: BulkContactRegistrationProgress,
        status: String,
        stopping: Boolean = false,
        context: Context? = null,
    ) {
        state = BulkContactsTaskState(
            running = true,
            action = action,
            progress = progress,
            status = status,
            stopping = stopping,
        )
        context?.let { BulkContactsProgressNotification.showRunning(it, action, progress, status, stopping) }
        notifyListeners()
    }

    @Synchronized
    private fun finish(action: BulkContactsTaskAction, progress: BulkContactRegistrationProgress, status: String, context: Context) {
        val wasCanceled = cancelRequested.get()
        cancelRequested.set(false)
        state = BulkContactsTaskState(
            running = false,
            action = action,
            progress = progress,
            status = status,
            stopping = wasCanceled,
        )
        BulkContactsProgressNotification.showFinished(context, action, status)
        notifyListeners()
    }

    private fun notifyListeners() {
        val snapshot = state
        mainHandler.post {
            listeners.forEach { listener -> listener(snapshot) }
        }
    }
}
