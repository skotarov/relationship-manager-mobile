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
    REPAIR,
    CLEANUP_ORPHANS,
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
                BulkContactsTaskAction.REGISTER -> "Спирам синхронизацията след текущия запис…"
                BulkContactsTaskAction.REPAIR -> "Спирам поправката след текущия запис…"
                BulkContactsTaskAction.CLEANUP_ORPHANS -> "Спирам почистването на осиротели записи след текущия запис…"
                BulkContactsTaskAction.CLEANUP -> "Спирам почистването след текущия запис…"
                BulkContactsTaskAction.IDLE -> "Спирам…"
            },
            stopping = true,
        )
    }

    fun registerAll(context: Context) {
        val appContext = context.applicationContext
        if (!tryStart(BulkContactsTaskAction.REGISTER, "Синхронизирам RM контактите… 0%", appContext)) return
        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runReconcileAll(
                context = appContext,
                automatic = false,
            )
        }
    }

    fun registerAllFromSync(context: Context): BulkContactRegistrationResult? {
        val appContext = context.applicationContext
        if (!tryStart(BulkContactsTaskAction.REGISTER, "Автоматична синхронизация на RM контактите… 0%", appContext)) return null
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        return runReconcileAll(
            context = appContext,
            automatic = true,
        )
    }

    fun repairAll(context: Context) {
        registerAll(context)
    }

    fun cleanupOrphans(context: Context) {
        registerAll(context)
    }

    fun cleanupAll(context: Context) {
        val appContext = context.applicationContext
        if (!tryStart(BulkContactsTaskAction.CLEANUP, "Почиствам RM записите от контактите… 0%", appContext)) return
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
                            "Почиствам RM записите от контактите… ${progress.percent}%"
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
                    "Почистването е спряно. Премахнати RM записи: $deleted"
                } else {
                    "Премахнати RM записи от контактите: $deleted"
                },
                context = appContext,
            )
        }
    }

    private fun runReconcileAll(context: Context, automatic: Boolean): BulkContactRegistrationResult {
        val prefix = if (automatic) "Автоматична синхронизация на RM контактите" else "Синхронизирам RM контактите"
        val result = RmContactReconciler.reconcileAll(
            context = context,
            onProgress = { progress ->
                updateProgress(
                    action = BulkContactsTaskAction.REGISTER,
                    progress = progress,
                    status = if (cancelRequested.get()) {
                        "Спирам синхронизацията след текущия запис…"
                    } else {
                        "$prefix… ${progress.percent}%"
                    },
                    stopping = cancelRequested.get(),
                    context = context,
                )
            },
            shouldCancel = { cancelRequested.get() },
        )
        finish(
            action = BulkContactsTaskAction.REGISTER,
            progress = BulkContactRegistrationProgress(result.scanned, result.scanned),
            status = reconcileFinishedStatus(result, automatic),
            context = context,
        )
        return result
    }

    private fun reconcileFinishedStatus(result: BulkContactRegistrationResult, automatic: Boolean): String {
        val prefix = if (automatic) "Автоматичната синхронизация" else "Синхронизацията"
        return if (result.canceled) {
            "$prefix е спряна. Променени: ${result.created}, без промяна: ${result.skippedExisting}, грешки: ${result.failed}, проверени: ${result.scanned}"
        } else {
            "Променени: ${result.created}, без промяна: ${result.skippedExisting}, грешки: ${result.failed}, проверени: ${result.scanned}"
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
