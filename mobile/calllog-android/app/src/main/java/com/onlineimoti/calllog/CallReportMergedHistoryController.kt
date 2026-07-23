package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import java.util.concurrent.Executors

/** Loads and prepares local and remote History data away from the main thread. */
internal class CallReportMergedHistoryController(
    private val activity: Activity,
    @Suppress("unused") private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val rerender: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val loadExecutor = Executors.newFixedThreadPool(2)
    private val prepareExecutor = Executors.newSingleThreadExecutor()
    private val state = MergedHistoryState(activity)
    private val busy = MergedHistoryBusyTracker(activity)
    private val presentation = MergedHistoryPresentation(
        activity = activity,
        state = state,
        dp = dp,
        roundedRect = roundedRect,
        rerender = rerender,
        isLoading = ::isLoading,
    )

    fun loadOnce(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (state.started) return
        state.started = true
        refreshLocal(phone)
        refreshServer(phone)
    }

    fun refreshServer(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (state.serverLoading) return
        val config = ConfigStore.load(activity)
        val requestedSignature = HistorySnapshotCache.remoteSignature(config)
        if (requestedSignature != state.remoteSignature) {
            state.remoteSignature = requestedSignature
            if (state.serverLoaded || state.serverHistory != CallReportHistoryLookupResult()) {
                state.serverLoaded = false
                state.serverHistory = CallReportHistoryLookupResult()
                state.serverDataDirty = true
            }
        }
        if (!CallReportRemoteAccess.isEnabled(config)) {
            clearServerStateAndPrepareIfNeeded()
            return
        }
        if (!CallReportRemoteAccess.isReady(config)) {
            publishIfNeeded()
            return
        }

        invalidatePrepareForNewData()
        state.serverLoading = true
        val generation = ++state.serverGeneration
        val token = busy.beginServer()
        val requestedPhone = phone
        loadExecutor.execute {
            val result = runCatching { CallReportHistoryLookupClient.lookup(config, requestedPhone) }
            handler.post {
                busy.finishServer(token)
                if (!isCurrent(generation, state.serverGeneration, requestedPhone)) return@post
                if (!CallReportRemoteAccess.isEnabled(activity)) {
                    clearServerStateAndPrepareIfNeeded()
                    return@post
                }
                state.serverLoading = false
                result.onSuccess { history ->
                    if (!state.serverLoaded || history != state.serverHistory) state.serverDataDirty = true
                    state.serverHistory = history
                    state.serverLoaded = true
                    state.loadError = ""
                }.onFailure { error ->
                    // Keep the last good server snapshot visible during a temporary connection error.
                    state.loadError = MergedHistoryServerFeedback.text(error)
                }
                prepareWhenDataReady(requestedPhone)
            }
        }
    }

    fun refreshLocal(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (state.localLoading) return
        invalidatePrepareForNewData()
        state.localLoading = true
        val generation = ++state.localGeneration
        val token = busy.beginLocal()
        val requestedPhone = phone
        val appContext = activity.applicationContext
        loadExecutor.execute {
            val result = runCatching { HistoryBackgroundLoader.loadLocal(appContext, requestedPhone) }
            handler.post {
                busy.finishLocal(token)
                if (!isCurrent(generation, state.localGeneration, requestedPhone)) return@post
                state.localLoading = false
                result.onSuccess { snapshot ->
                    if (snapshot != state.currentLocalSnapshot()) {
                        state.applyLocalSnapshot(snapshot)
                        state.localDataDirty = true
                    }
                }
                // On a provider error keep the last cached local snapshot instead of collapsing the list.
                prepareWhenDataReady(requestedPhone)
            }
        }
    }

    fun isLoading(): Boolean = state.localLoading || state.serverLoading || state.prepareLoading

    fun canPreviousNotesPage(): Boolean = presentation.canPreviousNotesPage()
    fun canNextNotesPage(): Boolean = presentation.canNextNotesPage()
    fun previousNotesPage(): Boolean = presentation.previousNotesPage()
    fun nextNotesPage(): Boolean = presentation.nextNotesPage()
    fun resetNotesPage() = presentation.resetNotesPage()
    fun canPreviousFullLogPage(): Boolean = presentation.canPreviousFullLogPage()
    fun canNextFullLogPage(): Boolean = presentation.canNextFullLogPage()
    fun previousFullLogPage(): Boolean = presentation.previousFullLogPage()
    fun nextFullLogPage(): Boolean = presentation.nextFullLogPage()
    fun resetFullLogPage() = presentation.resetFullLogPage()

    fun contactExists(): Boolean = state.contactExists
    fun localGeneralNote(): String = state.localGeneralNote
    fun localGeneralNotePending(): Boolean = state.localGeneralNotePending
    fun companyScopeAvailable(): Boolean = state.companyScopeAvailable
    fun hasConfirmedLocalServerNote(): Boolean = state.prepared.confirmedLocalServerNote
    fun hasCompanyMainNoteScope(): Boolean = state.prepared.hasCompanyMainNoteScope
    fun hasServerRecordsFor(phone: String): Boolean = state.hasServerRecordsFor(phone)

    /** Loading progress is shown only through the non-blocking black tooltip. */
    fun serverLoadingStatusText(): String = ""

    fun companyMainNotes(phone: String): List<CallReportCompanyMainNote> =
        state.prepared.companyMainNotes.takeIf { phone == state.activePhone }.orEmpty()

    fun unscopedServerMainNote(phone: String): CallReportHistoryEvent? =
        state.prepared.unscopedServerMainNote.takeIf { phone == state.activePhone }

    fun addNotesSection(
        root: LinearLayout,
        phone: String,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
    ) = presentation.addNotesSection(root, phone, onEditCallNote, onEditSms)

    fun addFullLogSection(
        root: LinearLayout,
        phone: String,
        openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    ) = presentation.addFullLogSection(root, phone, openCallNoteEditor)

    fun markRendered() {
        state.lastRenderedState = state.renderedState(isLoading())
    }

    fun forceNextRenderAfterDataReady() {
        state.forceRenderAfterPrepare = true
    }

    fun release() {
        state.localGeneration += 1
        state.serverGeneration += 1
        state.prepareGeneration += 1
        busy.clear()
        loadExecutor.shutdownNow()
        prepareExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    /** Wait for all source loads, then publish one coherent snapshot. */
    private fun prepareWhenDataReady(phone: String) {
        if (phone.isBlank() || phone != state.activePhone || state.localLoading || state.serverLoading) return
        if (!state.localDataDirty && !state.serverDataDirty) {
            publishIfNeeded()
            return
        }
        schedulePrepare(phone)
    }

    private fun schedulePrepare(phone: String) {
        if (phone.isBlank() || phone != state.activePhone) return
        state.prepareLoading = true
        val generation = ++state.prepareGeneration
        val token = busy.beginPrepare()
        val appContext = activity.applicationContext
        val requestedSignature = state.remoteSignature
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        val loaded = state.serverLoaded
        val history = state.serverHistory
        val calls = state.localCalls.toList()
        val sms = state.localSms.toList()
        val notes = state.localNotes.toList()
        prepareExecutor.execute {
            val result = runCatching {
                HistoryBackgroundLoader.prepare(
                    context = appContext,
                    phone = phone,
                    remoteEnabled = remoteEnabled,
                    serverLoaded = loaded,
                    history = history,
                    localCalls = calls,
                    localSms = sms,
                    localNotes = notes,
                )
            }
            handler.post {
                busy.finishPrepare(token)
                if (!isCurrent(generation, state.prepareGeneration, phone)) return@post
                state.prepareLoading = false
                result.onSuccess {
                    state.prepared = it
                    state.localDataDirty = false
                    state.serverDataDirty = false
                    state.putMemory(phone, requestedSignature)
                }
                publishIfNeeded()
            }
        }
    }

    /** Rebuild only when visible data differs; loading flags alone do not recreate the page. */
    private fun publishIfNeeded() {
        val nextState = state.renderedState(isLoading())
        if (!state.forceRenderAfterPrepare && nextState == state.lastRenderedState) return
        state.forceRenderAfterPrepare = false
        state.lastRenderedState = nextState
        rerender()
    }

    private fun invalidatePrepareForNewData() {
        if (!state.prepareLoading && !busy.hasPrepareWork()) return
        state.prepareGeneration += 1
        state.prepareLoading = false
        busy.finishAllPrepare()
    }

    private fun selectPhone(phone: String) {
        if (state.activePhone == phone) return
        busy.clear()
        if (state.selectPhone(phone)) presentation.resetPages()
    }

    private fun clearServerStateAndPrepareIfNeeded() {
        val hadServerData = state.serverLoaded || state.serverHistory != CallReportHistoryLookupResult()
        val errorChanged = state.loadError.isNotBlank()
        state.serverLoading = false
        state.serverLoaded = false
        state.serverHistory = CallReportHistoryLookupResult()
        state.loadError = ""
        state.remoteSignature = ""
        if (hadServerData) state.serverDataDirty = true
        if (state.activePhone.isBlank()) return
        if (state.serverDataDirty || state.localDataDirty) prepareWhenDataReady(state.activePhone)
        else if (errorChanged) publishIfNeeded()
    }

    private fun isCurrent(generation: Int, expectedGeneration: Int, phone: String): Boolean =
        !activity.isFinishing && !activity.isDestroyed &&
            generation == expectedGeneration && phone == state.activePhone
}
