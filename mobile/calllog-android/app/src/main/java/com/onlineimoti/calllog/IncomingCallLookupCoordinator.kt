package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Produces the incoming-call popup progressively. Contact policy, local call
 * history, lookup.php and server history run independently, so the first useful
 * UI does not wait for the slowest source.
 */
internal class IncomingCallLookupCoordinator(
    context: Context,
    private val config: AppConfig,
    private val phone: String,
    private val direction: String,
    private val fullscreen: Boolean,
    private val onLookupFinished: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    private var contactInfo: IncomingCallContactInfo? = null
    private var lookupResult: LookupResult? = null
    private var lookupFinished = false
    private var finishedCallbackSent = false

    fun start() {
        val policyQueued = submit(::resolveContactAndStartLookups)
        submit(::loadLocalRows)
        if (!policyQueued) finishOnce()
    }

    private fun resolveContactAndStartLookups() {
        val resolvedContact = ContactGroupFilter.resolveIncomingCallContact(appContext, phone, config)
        synchronized(lock) { contactInfo = resolvedContact }
        if (!resolvedContact.shouldNotify) {
            finishOnce()
            return
        }

        CallReportRuntime.ensureNotificationChannel(appContext)
        publishCurrent()
        if (!CallReportRemoteAccess.isReady(config)) {
            synchronized(lock) { lookupFinished = true }
            publishCurrent()
            finishOnce()
            return
        }

        val lookupQueued = submit(::loadLookup)
        submit(::loadHistoryRows)
        if (!lookupQueued) {
            synchronized(lock) {
                lookupResult = fallbackLookup(resolvedContact)
                lookupFinished = true
            }
            publishCurrent()
            finishOnce()
        }
    }

    private fun loadLocalRows() {
        val rows = runCatching {
            LocalCallStatsProvider.buildPopupInfoRows(appContext, phone)
        }.getOrDefault(emptyList())
        IncomingLookupPopupRowsCache.putLocalRows(phone, rows)
        publishCurrent()
    }

    private fun loadLookup() {
        val contact = synchronized(lock) { contactInfo } ?: return
        val fallback = fallbackLookup(contact)
        val result = runCatching {
            CallReportRuntime.fetchLookup(
                config = config,
                phone = phone,
                direction = direction,
                context = CallReportLookupContext(
                    communicationType = "phone",
                    contactName = contact.displayName.orEmpty(),
                ),
            )
        }.getOrElse { fallback }
        synchronized(lock) {
            lookupResult = result
            lookupFinished = true
        }
        publishCurrent()
        // BroadcastReceiver work ends after lookup.php. Server history may still
        // populate the already-open overlay without holding the receiver alive.
        finishOnce()
    }

    private fun loadHistoryRows() {
        val rows = runCatching {
            PostCallLookupRemoteRows.fromHistory(
                history = CallReportHistoryLookupClient.lookup(config, phone),
                phone = phone,
            )
        }.getOrDefault(emptyList())
        IncomingLookupPopupRowsCache.putRemoteRows(phone, rows)
        publishCurrent()
    }

    private fun publishCurrent() {
        val snapshot = synchronized(lock) {
            val contact = contactInfo ?: return
            if (!contact.shouldNotify) return
            Snapshot(contact, lookupResult, lookupFinished)
        }
        val fallback = fallbackLookup(snapshot.contact)
        val remote = snapshot.lookup
        val result = if (remote == null) {
            fallback.copy(
                subtitle = if (snapshot.lookupFinished) phone else "Проверявам Call Report…",
            )
        } else {
            remote.copy(
                title = snapshot.contact.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: remote.title.ifBlank { phone },
            )
        }
        LookupPopupPresenter.show(
            context = appContext,
            result = result,
            fullscreen = fullscreen && remote == null && !snapshot.lookupFinished,
            phone = phone,
            direction = direction,
            remoteRowsArePreloaded = true,
        )
    }

    private fun fallbackLookup(contact: IncomingCallContactInfo): LookupResult = LookupResult(
        title = contact.displayName?.takeIf { it.isNotBlank() } ?: phone,
        subtitle = phone,
        lines = emptyList(),
        openFormUrl = "",
    )

    private fun submit(block: () -> Unit): Boolean {
        return try {
            EXECUTOR.execute(block)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun finishOnce() {
        val shouldFinish = synchronized(lock) {
            if (finishedCallbackSent) false else {
                finishedCallbackSent = true
                true
            }
        }
        if (shouldFinish) onLookupFinished()
    }

    private data class Snapshot(
        val contact: IncomingCallContactInfo,
        val lookup: LookupResult?,
        val lookupFinished: Boolean,
    )

    private companion object {
        private const val MAX_PENDING_TASKS = 24
        private val EXECUTOR = ThreadPoolExecutor(
            4,
            4,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAX_PENDING_TASKS),
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}
