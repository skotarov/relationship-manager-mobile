package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

internal class ContactNotesCrmHistoryController(
    private val activity: Activity,
    private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val rerender: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val serverDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val historyUi by lazy {
        ContactNotesCrmHistoryUi(activity, headerUi, dp, roundedRect)
    }

    private var started = false
    private var skippedReason = ""
    private var serverLoading = false
    private var localLoading = false
    private var localLoaded = false
    private var error = false
    private var serverNotes: List<CrmServerNote> = emptyList()
    private var localCalls: List<PhoneCallRecord> = emptyList()
    private var localNotes: List<ContactCallNote> = emptyList()
    private var localSmsMessages: List<SmsMessageRecord> = emptyList()

    fun loadOnce(phone: String) {
        if (started) return
        started = true
        if (phone.isBlank()) {
            skippedReason = "Няма телефон за CRM проверка"
            return
        }

        startLocalLoad(phone, force = true)

        val config = ConfigStore.load(activity)
        if (!config.remoteEnabled || config.baseUrl.isBlank()) {
            skippedReason = "CRM връзката е изключена от Server настройките"
            return
        }

        serverLoading = true
        error = false
        rerender()
        executor.execute {
            val result = runCatching { CrmContactHistoryClient.fetch(config, phone) }
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                serverLoading = false
                result.onSuccess {
                    serverNotes = it.serverNotes
                    error = false
                    skippedReason = ""
                }.onFailure {
                    serverNotes = emptyList()
                    error = true
                    skippedReason = ""
                }
                rerender()
            }
        }
    }

    /** Reloads device calls/SMS after a local note or SMS changes. */
    fun refreshLocal(phone: String) {
        if (phone.isBlank()) return
        startLocalLoad(phone, force = true)
    }

    fun release() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        serverNotes = emptyList()
        localCalls = emptyList()
        localNotes = emptyList()
        localSmsMessages = emptyList()
        skippedReason = ""
        serverLoading = false
        localLoading = false
        localLoaded = false
        error = false
    }

    fun addSection(
        root: LinearLayout,
        phone: String,
        openFilteredLog: () -> Unit,
        onEditCallNote: (ContactCallNote) -> Unit,
    ) {
        val latestCall = localCalls.firstOrNull()
        val latestCallWithoutNote = latestCall?.takeUnless { call -> hasNoteForCall(call, localNotes) }
        val hiddenCallsWithoutNotes = if (localLoaded) {
            localCalls.drop(1).count { call -> !hasNoteForCall(call, localNotes) }
        } else {
            0
        }
        val timeline = buildTimeline(localNotes, latestCallWithoutNote, localSmsMessages)
        historyUi.addSection(
            root = root,
            timeline = timeline,
            hiddenCallsWithoutNotes = hiddenCallsWithoutNotes,
            state = ContactNotesHistoryUiState(
                localLoaded = localLoaded,
                localLoading = localLoading,
                serverLoading = serverLoading,
                error = error,
                skippedReason = skippedReason,
                serverNotesEmpty = serverNotes.isEmpty(),
            ),
            openFilteredLog = openFilteredLog,
            onEditCallNote = onEditCallNote,
        )
    }

    private fun startLocalLoad(phone: String, force: Boolean) {
        if (phone.isBlank() || localLoading || (localLoaded && !force)) return
        localLoading = true
        executor.execute {
            val snapshot = runCatching {
                LocalHistorySnapshot(
                    calls = PhoneCallReader.callsForPhone(activity, phone, limit = 100),
                    notes = ContactNoteReader.callNotesForPhone(activity, phone),
                    smsMessages = SmsMessageReader.messagesForPhone(activity, phone),
                )
            }.getOrDefault(LocalHistorySnapshot())

            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                localCalls = snapshot.calls
                localNotes = snapshot.notes
                localSmsMessages = snapshot.smsMessages
                localLoading = false
                localLoaded = true
                rerender()
            }
        }
    }

    private fun buildTimeline(
        localNotes: List<ContactCallNote>,
        latestCallWithoutNote: PhoneCallRecord?,
        smsMessages: List<SmsMessageRecord>,
    ): List<ContactNotesTimelineItem> {
        val items = mutableListOf<ContactNotesTimelineItem>()
        val localClientIds = localNotes
            .map { it.clientNoteId }
            .filter { it.isNotBlank() }
            .toSet()
        latestCallWithoutNote?.let { call ->
            items.add(ContactNotesTimelineItem.LatestCallAction(call))
        }
        localNotes.forEach { note -> items.add(ContactNotesTimelineItem.LocalNote(note)) }
        smsMessages.forEach { sms -> items.add(ContactNotesTimelineItem.SmsMessage(sms)) }
        serverNotes
            .filterNot { note ->
                note.clientNoteId.isNotBlank() && localClientIds.contains(note.clientNoteId)
            }
            .forEach { note ->
                items.add(ContactNotesTimelineItem.ServerNote(note, serverTime(note)))
            }
        return items.sortedByDescending { it.timeMs }
    }

    private fun hasNoteForCall(
        call: PhoneCallRecord,
        localNotes: List<ContactCallNote>,
    ): Boolean {
        return localNotes.any { note -> note.callAt > 0L && note.callAt == call.startedAt }
    }

    private fun serverTime(note: CrmServerNote): Long {
        val value = note.createdAt.ifBlank { note.updatedAt }
        return runCatching { serverDateFormat.parse(value)?.time ?: 0L }.getOrDefault(0L)
    }

    private data class LocalHistorySnapshot(
        val calls: List<PhoneCallRecord> = emptyList(),
        val notes: List<ContactCallNote> = emptyList(),
        val smsMessages: List<SmsMessageRecord> = emptyList(),
    )
}
