package com.onlineimoti.calllog

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.ContactsContract
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

    @Volatile
    private var activeContext: Context? = null

    fun currentState(): BulkContactsTaskState = state

    fun addListener(listener: (BulkContactsTaskState) -> Unit) {
        listeners.add(listener)
        val snapshot = state
        mainHandler.post { listener(snapshot) }
    }

    fun removeListener(listener: (BulkContactsTaskState) -> Unit) {
        listeners.remove(listener)
    }

    fun cancel(context: Context? = null) {
        val snapshot = state
        val appContext = context?.applicationContext ?: activeContext
        if (appContext != null) cancelAndroidContactsSync()
        if (!snapshot.running || snapshot.stopping) return
        cancelRequested.set(true)
        updateProgress(
            action = snapshot.action,
            progress = snapshot.progress,
            status = stoppingStatus(snapshot.action, appContext),
            stopping = true,
            context = appContext,
        )
    }

    fun registerAll(context: Context) {
        val appContext = context.applicationContext
        if (!tryStart(
                BulkContactsTaskAction.REGISTER,
                appContext.getString(R.string.contacts_sync_running, 0),
                appContext,
            )
        ) return
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
        if (!RmContactAutoSyncGate.shouldRunAutomaticSync(appContext)) return null
        if (!tryStart(
                BulkContactsTaskAction.REGISTER,
                appContext.getString(R.string.contacts_sync_running_auto, 0),
                appContext,
            )
        ) return null
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
        if (!tryStart(
                BulkContactsTaskAction.CLEANUP,
                appContext.getString(R.string.contacts_sync_cleanup_started),
                appContext,
            )
        ) return
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
                            stoppingStatus(BulkContactsTaskAction.CLEANUP, appContext)
                        } else {
                            appContext.getString(R.string.contacts_sync_cleanup_running, progress.percent)
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
                    appContext.getString(R.string.contacts_sync_stopped_removed, deleted)
                } else {
                    appContext.getString(R.string.contacts_sync_removed, deleted)
                },
                context = appContext,
            )
        }
    }

    private fun runReconcileAll(context: Context, automatic: Boolean): BulkContactRegistrationResult {
        val result = RmContactReconciler.reconcileAll(
            context = context,
            onProgress = { progress ->
                updateProgress(
                    action = BulkContactsTaskAction.REGISTER,
                    progress = progress,
                    status = if (cancelRequested.get()) {
                        stoppingStatus(BulkContactsTaskAction.REGISTER, context)
                    } else if (automatic) {
                        context.getString(R.string.contacts_sync_running_auto, progress.percent)
                    } else {
                        context.getString(R.string.contacts_sync_running, progress.percent)
                    },
                    stopping = cancelRequested.get(),
                    context = context,
                )
            },
            shouldCancel = { cancelRequested.get() },
        )
        if (!result.canceled) {
            RmContactAutoSyncGate.markFullSyncFinished(context)
        }
        finish(
            action = BulkContactsTaskAction.REGISTER,
            progress = BulkContactRegistrationProgress(result.scanned, result.scanned),
            status = reconcileFinishedStatus(context, result, automatic),
            context = context,
        )
        return result
    }

    private fun reconcileFinishedStatus(
        context: Context,
        result: BulkContactRegistrationResult,
        automatic: Boolean,
    ): String {
        return if (result.canceled) {
            context.getString(
                R.string.contacts_sync_canceled_summary,
                context.getString(if (automatic) R.string.contacts_sync_auto_label else R.string.contacts_sync_manual_label),
                result.created,
                result.skippedExisting,
                result.failed,
                result.scanned,
            )
        } else {
            context.getString(
                R.string.contacts_sync_finished_summary,
                result.created,
                result.skippedExisting,
                result.failed,
                result.scanned,
            )
        }
    }

    @Synchronized
    private fun tryStart(action: BulkContactsTaskAction, status: String, context: Context): Boolean {
        if (state.running) return false
        activeContext = context.applicationContext
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
        activeContext = null
        BulkContactsProgressNotification.showFinished(context, action, status)
        notifyListeners()
    }

    private fun stoppingStatus(action: BulkContactsTaskAction, context: Context?): String {
        if (context == null) return state.status
        return context.getString(
            when (action) {
                BulkContactsTaskAction.REGISTER -> R.string.contacts_sync_stop_register
                BulkContactsTaskAction.REPAIR -> R.string.contacts_sync_stop_repair
                BulkContactsTaskAction.CLEANUP_ORPHANS -> R.string.contacts_sync_stop_orphans
                BulkContactsTaskAction.CLEANUP -> R.string.contacts_sync_stop_cleanup
                BulkContactsTaskAction.IDLE -> R.string.contacts_sync_stop_generic
            },
        )
    }

    private fun cancelAndroidContactsSync() {
        runCatching {
            ContentResolver.cancelSync(
                Account(CrmContactAccountStore.ACCOUNT_NAME, CallReportContactIntegration.ACCOUNT_TYPE),
                ContactsContract.AUTHORITY,
            )
        }
    }

    private fun notifyListeners() {
        val snapshot = state
        mainHandler.post {
            listeners.forEach { listener -> listener(snapshot) }
        }
    }
}
