package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Filtered "Пълен лог" for one contact. Source data is loaded and grouped on a
 * background executor once; the dedicated row renderer presents the current page.
 */
internal class FilteredFullLogController(
    private val activity: Activity,
    private val binding: ActivityHomeBinding,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val openContactNotes: (PhoneCallRecord, String) -> Unit,
    private val openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    private val pageSize: () -> Int,
    private val onStateChanged: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val rowRenderer by lazy {
        FilteredFullLogRowRenderer(activity, dp, roundedRect, openContactNotes, openCallNoteEditor)
    }

    private var selectedPhone = ""
    private var loadedPhone = ""
    private var loadedRemoteEnabled: Boolean? = null
    private var loading = false
    private var pageIndex = 0
    private var loadGeneration = 0
    private var entries: List<FilteredFullLogEntry> = emptyList()
    private var errorText = ""

    fun invalidate() {
        loadGeneration += 1
        loadedPhone = ""
        loadedRemoteEnabled = null
        loading = false
        pageIndex = 0
        entries = emptyList()
        errorText = ""
    }

    fun previousPage() {
        if (loading || pageIndex <= 0) return
        pageIndex -= 1
        onStateChanged()
    }

    fun nextPage() {
        if (loading || pageIndex >= lastPageIndex()) return
        pageIndex += 1
        onStateChanged()
    }

    fun render(phone: String) {
        if (phone.isBlank()) return
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        val modeChanged = loadedRemoteEnabled != null && loadedRemoteEnabled != remoteEnabled
        if (selectedPhone != phone || modeChanged) {
            selectedPhone = phone
            invalidate()
        }
        if (loadedPhone != phone && !loading) startLoad(phone, remoteEnabled)

        val size = safePageSize()
        val pageCount = pageCount(size)
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        val pageEntries = if (loading) emptyList() else entries.drop(pageIndex * size).take(size)

        binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.paginationContainer.visibility = View.VISIBLE
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, size)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, size)
        binding.previousCallsButton.isEnabled = !loading && pageIndex > 0
        binding.nextCallsButton.isEnabled = !loading && pageIndex < pageCount - 1
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex + 1)
        binding.homeStatusText.text = statusText(remoteEnabled, pageEntries)
        pageEntries.forEach { entry ->
            binding.homeCallsContainer.addView(rowRenderer.rowView(phone, entry, remoteEnabled))
        }
    }

    fun release() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun statusText(remoteEnabled: Boolean, pageEntries: List<FilteredFullLogEntry>): String = when {
        loading -> "Зареждам пълния лог…"
        errorText.isNotBlank() -> errorText
        entries.isEmpty() -> if (remoteEnabled) "Няма локални или сървърни записи за този номер"
        else "Няма локални записи за този номер"
        else -> {
            val first = pageIndex * safePageSize() + 1
            val last = first + pageEntries.size - 1
            "Пълен лог: $first–$last от ${entries.size}"
        }
    }

    private fun startLoad(phone: String, remoteEnabled: Boolean) {
        loading = true
        val requestedPhone = phone
        val generation = ++loadGeneration
        executor.execute {
            val result = runCatching {
                val localCalls = PhoneCallReader.callsForPhone(activity, requestedPhone, limit = SOURCE_CALL_LIMIT)
                val localSms = SmsMessageReader.messagesForPhone(activity, requestedPhone, limit = SOURCE_SMS_LIMIT)
                val localNotes = ContactNoteReader.callNotesForPhone(activity, requestedPhone)
                val serverHistory = if (remoteEnabled) {
                    val config = ConfigStore.load(activity)
                    runCatching { CallReportHistoryLookupClient.lookup(config, requestedPhone) }
                        .getOrDefault(CallReportHistoryLookupResult())
                } else {
                    CallReportHistoryLookupResult()
                }
                val merged = CallReportHistoryMerge.merge(
                    context = activity,
                    phone = requestedPhone,
                    principal = serverHistory.principal,
                    localCalls = localCalls,
                    localSms = localSms,
                    localNotes = localNotes,
                    serverEvents = serverHistory.events,
                )
                groupedEntries(merged)
            }
            handler.post {
                if (
                    activity.isFinishing || activity.isDestroyed ||
                    generation != loadGeneration || requestedPhone != selectedPhone
                ) return@post
                if (remoteEnabled != CallReportRemoteAccess.isEnabled(activity)) {
                    loading = false
                    loadedPhone = ""
                    loadedRemoteEnabled = null
                    onStateChanged()
                    return@post
                }
                loading = false
                result.onSuccess {
                    entries = it
                    loadedPhone = requestedPhone
                    loadedRemoteEnabled = remoteEnabled
                    errorText = ""
                }.onFailure {
                    entries = emptyList()
                    loadedPhone = requestedPhone
                    loadedRemoteEnabled = remoteEnabled
                    errorText = "Пълният лог не е зареден"
                }
                onStateChanged()
            }
        }
    }

    private fun safePageSize(): Int = pageSize().coerceIn(5, 100)
    private fun pageCount(size: Int): Int = if (entries.isEmpty()) 1 else ((entries.size - 1) / size) + 1
    private fun lastPageIndex(): Int = pageCount(safePageSize()) - 1

    private fun groupedEntries(timeline: List<CallReportHistoryRow>): List<FilteredFullLogEntry> {
        val indexedCalls = timeline.mapIndexedNotNull { index, row ->
            index.takeIf { row.kind == CallReportHistoryRowKind.PHONE }
        }
        val notesByCallIndex = mutableMapOf<Int, MutableList<CallReportHistoryRow>>()
        val attachedNoteIndexes = mutableSetOf<Int>()
        timeline.forEachIndexed { noteIndex, note ->
            if (note.kind != CallReportHistoryRowKind.NOTE) return@forEachIndexed
            val targetCallIndex = matchingCallIndex(note, indexedCalls, timeline) ?: return@forEachIndexed
            notesByCallIndex.getOrPut(targetCallIndex) { mutableListOf() }.add(note)
            attachedNoteIndexes += noteIndex
        }
        return timeline.mapIndexedNotNull { index, row ->
            if (row.kind == CallReportHistoryRowKind.NOTE && index in attachedNoteIndexes) {
                null
            } else {
                FilteredFullLogEntry(row, notesByCallIndex[index].orEmpty().sortedBy { it.timeMs })
            }
        }
    }

    private fun matchingCallIndex(
        note: CallReportHistoryRow,
        callIndexes: List<Int>,
        timeline: List<CallReportHistoryRow>,
    ): Int? {
        val notePhone = HomeCallPageLoader.noteKey(note.phone)
        var closestIndex: Int? = null
        var closestDelta = Long.MAX_VALUE
        callIndexes.forEach { callIndex ->
            val call = timeline[callIndex]
            if (HomeCallPageLoader.noteKey(call.phone) != notePhone) return@forEach
            if (note.direction.isNotBlank() && call.direction.isNotBlank() && note.direction != call.direction) return@forEach
            val delta = abs(note.timeMs - call.timeMs)
            if (delta <= NOTE_CALL_MATCH_WINDOW_MS && delta < closestDelta) {
                closestIndex = callIndex
                closestDelta = delta
            }
        }
        return closestIndex
    }

    private companion object {
        const val SOURCE_CALL_LIMIT = 200
        const val SOURCE_SMS_LIMIT = 100
        const val NOTE_CALL_MATCH_WINDOW_MS = 90_000L
    }
}
