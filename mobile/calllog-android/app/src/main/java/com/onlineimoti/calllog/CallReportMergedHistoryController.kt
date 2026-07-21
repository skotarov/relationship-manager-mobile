package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Loads and prepares local and remote history away from the main thread. */
internal class CallReportMergedHistoryController(
    private val activity: Activity,
    @Suppress("unused") private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val rerender: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val loadExecutor = Executors.newFixedThreadPool(2)
    private val prepareExecutor = Executors.newSingleThreadExecutor()
    private val rowsUi by lazy { CallReportHistoryRowsUi(activity, dp, roundedRect) }

    private var activePhone = ""
    private var started = false
    private var localLoading = false
    private var serverLoading = false
    private var prepareLoading = false
    private var serverLoaded = false
    private var localGeneration = 0
    private var serverGeneration = 0
    private var prepareGeneration = 0
    private var localBusyToken = 0L
    private var serverBusyToken = 0L
    private val prepareBusyTokens = linkedSetOf<Long>()

    private var latestLocalCall: PhoneCallRecord? = null
    private var localSms: List<SmsMessageRecord> = emptyList()
    private var localNotes: List<ContactCallNote> = emptyList()
    private var localGeneralNote = ""
    private var localGeneralNotePending = false
    private var contactExists = false
    private var companyScopeAvailable = false
    private var serverHistory = CallReportHistoryLookupResult()
    private var prepared = HistoryPreparedSnapshot()
    private var loadError = ""

    fun loadOnce(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (started) return
        started = true
        refreshLocal(phone)
        refreshServer(phone)
    }

    fun refreshServer(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (serverLoading) return
        val config = ConfigStore.load(activity)
        if (!CallReportRemoteAccess.isEnabled(config)) {
            clearServerStateAndRerenderIfNeeded()
            return
        }
        if (!CallReportRemoteAccess.isReady(config)) return

        serverLoading = true
        val generation = ++serverGeneration
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_SERVER)
        finishServerBusy()
        serverBusyToken = token
        val requestedPhone = phone
        loadExecutor.execute {
            val result = runCatching { CallReportHistoryLookupClient.lookup(config, requestedPhone) }
            handler.post {
                finishServerBusy(token)
                if (
                    activity.isFinishing || activity.isDestroyed ||
                    generation != serverGeneration || requestedPhone != activePhone
                ) return@post
                if (!CallReportRemoteAccess.isEnabled(activity)) {
                    clearServerStateAndRerenderIfNeeded()
                    return@post
                }
                serverLoading = false
                result.onSuccess { history ->
                    serverHistory = history
                    serverLoaded = true
                    loadError = ""
                }.onFailure {
                    serverLoaded = false
                    loadError = serverErrorText(it)
                }
                schedulePrepare(requestedPhone)
                rerender()
            }
        }
    }

    fun refreshLocal(phone: String) {
        if (phone.isBlank()) return
        selectPhone(phone)
        if (localLoading) return
        localLoading = true
        val generation = ++localGeneration
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_LOCAL)
        finishLocalBusy()
        localBusyToken = token
        val requestedPhone = phone
        val appContext = activity.applicationContext
        loadExecutor.execute {
            val snapshot = runCatching {
                HistoryBackgroundLoader.loadLocal(appContext, requestedPhone)
            }.getOrDefault(HistoryLocalSnapshot())
            handler.post {
                finishLocalBusy(token)
                if (
                    activity.isFinishing || activity.isDestroyed ||
                    generation != localGeneration || requestedPhone != activePhone
                ) return@post
                latestLocalCall = snapshot.latestCall
                localSms = snapshot.sms
                localNotes = snapshot.callNotes
                localGeneralNote = snapshot.generalNote
                localGeneralNotePending = snapshot.generalNotePending
                contactExists = snapshot.contactExists
                companyScopeAvailable = snapshot.companyScopeAvailable
                localLoading = false
                schedulePrepare(requestedPhone)
                rerender()
            }
        }
    }

    fun isLoading(): Boolean = localLoading || serverLoading || prepareLoading
    fun canPreviousPage(): Boolean = rowsUi.canPreviousPage()
    fun canNextPage(): Boolean = rowsUi.canNextPage()
    fun previousPage(): Boolean = rowsUi.previousPage(rerender)
    fun nextPage(): Boolean = rowsUi.nextPage(rerender)
    fun resetPage() = rowsUi.resetPage()

    fun contactExists(): Boolean = contactExists
    fun localGeneralNote(): String = localGeneralNote
    fun localGeneralNotePending(): Boolean = localGeneralNotePending
    fun companyScopeAvailable(): Boolean = companyScopeAvailable
    fun hasConfirmedLocalServerNote(): Boolean = prepared.confirmedLocalServerNote
    fun hasCompanyMainNoteScope(): Boolean = prepared.hasCompanyMainNoteScope

    fun hasServerRecordsFor(phone: String): Boolean {
        if (!serverLoaded || phone.isBlank() || phone != activePhone) return false
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        if (phoneKey.isBlank()) return false
        return serverHistory.events.any { event ->
            HomeCallPageLoader.noteKey(event.phone) == phoneKey &&
                event.communicationType.equals("note", ignoreCase = true) &&
                event.note.trim().isNotBlank()
        }
    }

    /** Temporary loading text rendered in the fixed slot below the contact header. */
    fun serverLoadingStatusText(): String = when {
        CallReportRemoteAccess.isEnabled(activity) && serverLoading -> "Добавям сървърни бележки и SMS…"
        prepareLoading -> "Подготвям историята…"
        else -> ""
    }

    fun companyMainNotes(phone: String): List<CallReportCompanyMainNote> =
        prepared.companyMainNotes.takeIf { phone == activePhone }.orEmpty()

    fun unscopedServerMainNote(phone: String): CallReportHistoryEvent? =
        prepared.unscopedServerMainNote.takeIf { phone == activePhone }

    fun addSection(
        root: LinearLayout,
        phone: String,
        openFilteredLog: () -> Unit,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
    ) {
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        addServerErrorBelowContactName(root, remoteEnabled)
        rowsUi.addSection(
            root = root,
            phone = phone,
            remoteEnabled = remoteEnabled,
            principal = serverHistory.principal,
            rows = prepared.rows,
            latestLocalCall = latestLocalCall,
            localNotes = localNotes,
            localLoading = localLoading || prepareLoading,
            serverLoading = serverLoading,
            openFilteredLog = openFilteredLog,
            onEditCallNote = onEditCallNote,
            onEditSms = onEditSms,
            onPageChanged = rerender,
        )
    }

    fun release() {
        localGeneration += 1
        serverGeneration += 1
        prepareGeneration += 1
        finishLocalBusy()
        finishServerBusy()
        finishAllPrepareBusy()
        loadExecutor.shutdownNow()
        prepareExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        HomeBusyTooltipUi.clear(activity)
    }

    private fun schedulePrepare(phone: String) {
        if (phone.isBlank() || phone != activePhone) return
        prepareLoading = true
        val generation = ++prepareGeneration
        val token = HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_PREPARE)
        prepareBusyTokens += token
        val appContext = activity.applicationContext
        val requestedPhone = phone
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        val loaded = serverLoaded
        val history = serverHistory
        val sms = localSms.toList()
        val notes = localNotes.toList()
        prepareExecutor.execute {
            val result = runCatching {
                HistoryBackgroundLoader.prepare(
                    context = appContext,
                    phone = requestedPhone,
                    remoteEnabled = remoteEnabled,
                    serverLoaded = loaded,
                    history = history,
                    localSms = sms,
                    localNotes = notes,
                )
            }
            handler.post {
                finishPrepareBusy(token)
                if (
                    activity.isFinishing || activity.isDestroyed ||
                    generation != prepareGeneration || requestedPhone != activePhone
                ) return@post
                prepareLoading = false
                result.onSuccess { prepared = it }
                rerender()
            }
        }
    }

    private fun selectPhone(phone: String) {
        if (activePhone == phone) return
        activePhone = phone
        started = false
        localGeneration += 1
        serverGeneration += 1
        prepareGeneration += 1
        finishLocalBusy()
        finishServerBusy()
        finishAllPrepareBusy()
        localLoading = false
        serverLoading = false
        prepareLoading = false
        serverLoaded = false
        latestLocalCall = null
        localSms = emptyList()
        localNotes = emptyList()
        localGeneralNote = ""
        localGeneralNotePending = false
        contactExists = false
        companyScopeAvailable = false
        serverHistory = CallReportHistoryLookupResult()
        prepared = HistoryPreparedSnapshot()
        loadError = ""
        rowsUi.resetPage()
    }

    private fun addServerErrorBelowContactName(root: LinearLayout, remoteEnabled: Boolean) {
        if (!remoteEnabled || loadError.isBlank()) return
        root.addView(TextView(activity).apply {
            text = loadError
            textSize = 12.5f
            setTextColor(Color.rgb(185, 28, 28))
            setPadding(dp(2), 0, dp(2), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }, minOf(1, root.childCount))
    }

    private fun clearServerStateAndRerenderIfNeeded() {
        val hadServerState = serverLoading || serverLoaded || serverHistory.events.isNotEmpty() ||
            serverHistory.principal != CallReportHistoryPrincipal() || loadError.isNotBlank()
        serverLoading = false
        serverLoaded = false
        serverHistory = CallReportHistoryLookupResult()
        loadError = ""
        if (hadServerState && activePhone.isNotBlank()) {
            schedulePrepare(activePhone)
            rerender()
        }
    }

    private fun finishLocalBusy(token: Long = localBusyToken) {
        if (token <= 0L) return
        if (localBusyToken == token) localBusyToken = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    private fun finishServerBusy(token: Long = serverBusyToken) {
        if (token <= 0L) return
        if (serverBusyToken == token) serverBusyToken = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    private fun finishPrepareBusy(token: Long) {
        if (token <= 0L) return
        prepareBusyTokens.remove(token)
        HomeBusyTooltipUi.end(activity, token)
    }

    private fun finishAllPrepareBusy() {
        prepareBusyTokens.toList().forEach(::finishPrepareBusy)
    }

    private fun serverErrorText(error: Throwable): String {
        val message = error.message.orEmpty().trim()
        val httpStatus = Regex("\\bHTTP\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (httpStatus != null) {
            return when (httpStatus) {
                400 -> "Сървър: невалидна заявка (400)"
                401 -> "Сървър: невалиден access token (401)"
                403 -> "Сървър: достъпът е отказан (403)"
                404 -> "Сървър: history_lookup.php не е намерен (404)"
                408 -> "Сървър: изтече времето за изчакване (408)"
                429 -> "Сървър: твърде много заявки (429)"
                in 500..599 -> "Сървър: вътрешна грешка ($httpStatus)"
                else -> "Сървър: HTTP $httpStatus"
            }
        }
        return when (rootCause(error)) {
            is java.net.UnknownHostException -> "Сървър: адресът не е открит"
            is java.net.ConnectException -> "Сървър: няма връзка със сървъра"
            is java.net.SocketTimeoutException -> "Сървър: изтече времето за изчакване"
            is org.json.JSONException -> "Сървър: невалиден JSON отговор"
            else -> {
                val safeMessage = message.replace(Regex("\\s+"), " ").take(120)
                if (safeMessage.isBlank() || safeMessage.equals("History lookup failed", ignoreCase = true)) {
                    "Сървър: неуспешно зареждане на историята"
                } else {
                    "Сървър: $safeMessage"
                }
            }
        }
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }
}
