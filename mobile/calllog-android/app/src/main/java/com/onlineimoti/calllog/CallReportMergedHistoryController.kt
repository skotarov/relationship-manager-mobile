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
    private var serverLoaded = false
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
                result.onSuccess(::acceptServerHistory).onFailure {
                    serverLoaded = false
                    loadError = serverErrorText(it)
                }
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

    fun isLoading(): Boolean = localLoading || serverLoading

    fun hasCompanyMainNoteScope(): Boolean = serverLoaded && serverHistory.principal.companies.isNotEmpty()

    fun hasServerRecordsFor(phone: String): Boolean {
        if (!serverLoaded || phone.isBlank()) return false
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        if (phoneKey.isBlank()) return false
        return serverHistory.events.any { HomeCallPageLoader.noteKey(it.phone) == phoneKey }
    }

    /** Temporary remote-loading text rendered in the fixed slot below the contact header. */
    fun serverLoadingStatusText(): String {
        return if (CallReportRemoteAccess.isEnabled(activity) && serverLoading) {
            "Добавям сървърни бележки и SMS…"
        } else {
            ""
        }
    }

    fun companyMainNotes(phone: String): List<CallReportCompanyMainNote> {
        if (!hasCompanyMainNoteScope() || phone.isBlank()) return emptyList()
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        val latestByCompany = mutableMapOf<String, CallReportHistoryEvent>()
        serverHistory.events.forEach { event ->
            if (!event.communicationType.equals("note", ignoreCase = true)) return@forEach
            if (event.companyId.isBlank() || HomeCallPageLoader.noteKey(event.phone) != phoneKey) return@forEach
            // Do not infer a main note from blank call metadata. Some server responses
            // omit direction/duration for a conversation note; only the explicit
            // topic/general record id is allowed to populate the yellow main-note card.
            if (!isExplicitCompanyMainNote(event.clientEventId)) return@forEach
            val current = latestByCompany[event.companyId]
            if (current == null || event.updatedAtMs >= current.updatedAtMs) latestByCompany[event.companyId] = event
        }
        return serverHistory.principal.companies.map { company ->
            val remote = latestByCompany[company.id]
            val pending = CallReportCompanyGeneralNotePending.isPending(activity, phone, company.id)
            val cached = CallReportCompanyGeneralNoteStore.noteFor(activity, phone, company.id)
            val note = when {
                pending && cached.isNotBlank() -> cached
                remote != null -> remote.note
                else -> cached
            }
            CallReportCompanyMainNote(
                companyId = company.id,
                companyName = company.name,
                note = note,
                updatedAtMs = remote?.updatedAtMs ?: 0L,
                confirmedByServer = remote != null && !pending && remote.note.isNotBlank(),
                pending = pending,
            )
        }
    }

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
            serverEvents = serverNotesAndSms(remoteEnabled),
            latestLocalCall = latestLocalCall,
            localSms = localSms,
            localNotes = localNotes,
            localLoading = localLoading,
            // The remote loading message is shown in the fixed header slot,
            // not at the bottom of the Notes and SMS section.
            serverLoading = false,
            openFilteredLog = openFilteredLog,
            onEditCallNote = onEditCallNote,
            onEditSms = onEditSms,
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
        val hadServerState = serverLoading || serverLoaded || serverHistory.events.isNotEmpty() ||
            serverHistory.principal != CallReportHistoryPrincipal() || loadError.isNotBlank()
        serverLoading = false
        serverLoaded = false
        serverHistory = CallReportHistoryLookupResult()
        loadError = ""
        if (hadServerState) rerender()
    }

    private fun acceptServerHistory(history: CallReportHistoryLookupResult) {
        serverHistory = history
        serverLoaded = true
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

    private fun isExplicitCompanyMainNote(clientEventId: String): Boolean {
        return clientEventId.contains(":topic:general:") || clientEventId.contains(":note:general:")
    }

    private data class LocalSnapshot(
        val latestCall: PhoneCallRecord? = null,
        val sms: List<SmsMessageRecord> = emptyList(),
        val notes: List<ContactCallNote> = emptyList(),
    )
}
