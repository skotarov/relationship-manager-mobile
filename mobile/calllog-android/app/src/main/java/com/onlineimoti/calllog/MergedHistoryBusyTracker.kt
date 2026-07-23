package com.onlineimoti.calllog

import android.app.Activity

/** Owns tooltip tokens for local, server and preparation work in History. */
internal class MergedHistoryBusyTracker(
    private val activity: Activity,
) {
    private var localToken = 0L
    private var serverToken = 0L
    private val prepareTokens = linkedSetOf<Long>()

    fun beginLocal(): Long {
        finishLocal()
        return HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_LOCAL).also { localToken = it }
    }

    fun beginServer(): Long {
        finishServer()
        return HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_SERVER).also { serverToken = it }
    }

    fun beginPrepare(): Long =
        HomeBusyTooltipUi.begin(activity, HomeBusyWork.HISTORY_PREPARE).also(prepareTokens::add)

    fun finishLocal(token: Long = localToken) {
        if (token <= 0L) return
        if (localToken == token) localToken = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    fun finishServer(token: Long = serverToken) {
        if (token <= 0L) return
        if (serverToken == token) serverToken = 0L
        HomeBusyTooltipUi.end(activity, token)
    }

    fun finishPrepare(token: Long) {
        if (token <= 0L) return
        prepareTokens.remove(token)
        HomeBusyTooltipUi.end(activity, token)
    }

    fun hasPrepareWork(): Boolean = prepareTokens.isNotEmpty()

    fun finishAllPrepare() {
        prepareTokens.toList().forEach(::finishPrepare)
    }

    fun clear() {
        finishLocal()
        finishServer()
        finishAllPrepare()
        HomeBusyTooltipUi.clear(activity)
    }
}
