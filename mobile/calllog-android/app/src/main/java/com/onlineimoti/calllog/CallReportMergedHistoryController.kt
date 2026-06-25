package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Loads local and remote history; [CallReportHistoryRowsUi] renders the result. */
internal class CallReportMergedHistoryController(
    private val activity: Activity,
    @Suppress("unused") private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val rerender: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val rowsUi by lazy { CallReportHistoryRowsUi(activity, dp, roundedRect) }

    private var started = false
    private var localLoading = false
    private var serverLoading = false
    private var latestLocalCall: PhoneCallRecord? = null
    private var localSms: List<SmsMessageRecord> = emptyList()
    private var localNotes: List<ContactCallNote> = emptyList()
    private var serverHistory = CallReportHistoryLookupResult()
    private var loadError = ""

    fun loadOnce(phone: String) {
        if (started || phone.isBlank()) return
        started = true
        refreshLocal(phone)
        refreshServer(phone)
    }

    fun refreshServer(phone: String) {
        if (phone.isBlank() || serverLoading) return
        val config = ConfigStore.load(activity)
        if (!CallReportRemoteAccess.isEnabled(config)) {
            clearServerStateAndRerenderIfNeeded()
            return
        }
        if (!CallReportRemoteAccess.isReady(config)) return

        serverLoading = true
        executor.execute {
            val result = runCatching { CallReportHistoryLookupClient.lookup(config, phone) }
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (!CallReportRemoteAccess.isEnabled(activity)) {
                    clearServerStateAndRerenderIfNeeded()
                    return@post
                }
                serverLoading = false
                result.onSuccess(::acceptServerHistory).onFailure { loadError = serverErrorText(it) }
                rerender()
            }
        }
    }

    fun refreshLocal(phone: String) {
        if (phone.isBlank() || localLoading) return
        localLoading = true
        executor.execute {
            val snapshot = runCatching {
                LocalSnapshot(
                    latestCall = PhoneCallReader.callsForPhone(activity, phone, limit = 1).firstOrNull(),
                    sms = SmsMessageReader.messagesForPhone(activity, phone, limit = 150),
                    notes = ContactNoteReader.callNotesForPhone(activity, phone),
                )
            }.getOrDefault(LocalSnapshot())
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                latestLocalCall = snapshot.latestCall
                localSms = snapshot.sms
                localNotes = snapshot.notes
                localLoading = false
                rerender()
            }
        }
    }

    fun addSection(
        root: LinearLayout,
        phone: String,
        openFilteredLog: () -> Unit,
        onEditCallNote: (ContactCallNote) -> Unit,
    ) {
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        addServerErrorBelowContactName(root, remoteEnabled)
        rowsUi.addSection(
            root = root,
            phone = phone,
            remoteEnabled = remoteEnabled,
            principal = serverHistory.principal,
            serverEvents = serverNotesAndSms(remoteEnabled),
            latestLocalCall = latestLocalCall,
            localSms = localSms,
            localNotes = localNotes,
            localLoading = localLoading,
            serverLoading = serverLoading,
            openFilteredLog = openFilteredLog,
            onEditCallNote = onEditCallNote,
            onPageChanged = rerender,
        )
    }

    fun release() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
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
        val hadServerState = serverLoading || serverHistory.events.isNotEmpty() ||
            serverHistory.principal != CallReportHistoryPrincipal() || loadError.isNotBlank()
        serverLoading = false
        serverHistory = CallReportHistoryLookupResult()
        loadError = ""
        if (hadServerState) rerender()
    }

    private fun acceptServerHistory(history: CallReportHistoryLookupResult) {
        serverHistory = history
        loadError = ""
        ServerRecordIndex.markConfirmed(activity, history.events.map { it.clientEventId })
    }

    private fun serverNotesAndSms(remoteEnabled: Boolean): List<CallReportHistoryEvent> {
        if (!remoteEnabled) return emptyList()
        return serverHistory.events.filter {
            it.communicationType.equals("sms", ignoreCase = true) ||
                it.communicationType.equals("note", ignoreCase = true)
        }
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

    private data class LocalSnapshot(
        val latestCall: PhoneCallRecord? = null,
        val sms: List<SmsMessageRecord> = emptyList(),
        val notes: List<ContactCallNote> = emptyList(),
    )
}
