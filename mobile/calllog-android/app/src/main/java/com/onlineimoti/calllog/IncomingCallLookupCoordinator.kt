package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Produces the incoming-call popup progressively. A preliminary popup is shown
 * immediately, then local contact/history data and server data replace it as
 * each source becomes available.
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
        // Do not wait for Contacts, Call Log or the network before replacing the
        // loading card. This matches the Settings test: show a usable popup first.
        publishInitial()
        val policyQueued = submit(CONTACT_EXECUTOR, ::resolveContactAndStartLookups)
        if (!policyQueued) {
            finishOnce()
            return
        }
        submit(LOCAL_ROWS_EXECUTOR, ::loadLocalRows)
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

        val lookupQueued = submit(LOOKUP_EXECUTOR, ::loadLookup)
        if (!lookupQueued) {
            synchronized(lock) {
                lookupResult = fallbackLookup(resolvedContact)
                lookupFinished = true
            }
            publishCurrent()
            finishOnce()
            return
        }

        // A slow known-contact lookup must never leave the preliminary popup on
        // screen indefinitely. A late server response may still replace it.
        scheduleLookupFallback(resolvedContact)
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
        val attempt = runCatching {
            CallReportRuntime.fetchLookup(
                config = config,
                phone = phone,
                direction = direction,
                context = CallReportLookupContext(
                    communicationType = "phone",
                    contactName = contact.displayName.orEmpty(),
                ),
            )
        }
        val result = attempt.getOrElse { fallback }
        synchronized(lock) {
            lookupResult = result
            lookupFinished = true
        }
        publishCurrent()

        // Server history is useful enrichment, but it must never occupy the
        // same queue as contact resolution or the primary lookup request.
        if (attempt.isSuccess) submit(HISTORY_EXECUTOR, ::loadHistoryRows)

        // BroadcastReceiver work ends after lookup.php. Server history may still
        // populate the already-open overlay without holding the receiver alive.
        finishOnce()
    }

    private fun loadHistoryRows() {
        val rows = runCatching {
            PostCallLookupRemoteRows.fromHistory(
                history = CallReportHistoryLookupClient.lookup(config, phone, limit = POPUP_HISTORY_LIMIT),
                phone = phone,
            )
        }.getOrDefault(emptyList())
        IncomingLookupPopupRowsCache.putRemoteRows(phone, rows)
        publishCurrent()
    }

    private fun scheduleLookupFallback(contact: IncomingCallContactInfo) {
        LOOKUP_TIMEOUT_EXECUTOR.schedule({
            val timedOut = synchronized(lock) {
                if (lookupFinished) {
                    false
                } else {
                    lookupResult = fallbackLookup(contact).copy(
                        lines = listOf("Call Report отговаря бавно. Показана е наличната локална информация."),
                    )
                    lookupFinished = true
                    true
                }
            }
            if (timedOut) {
                publishCurrent()
                finishOnce()
            }
        }, LOOKUP_DEADLINE_MS, TimeUnit.MILLISECONDS)
    }

    private fun publishInitial() {
        LookupPopupPresenter.show(
            context = appContext,
            result = LookupResult(
                title = phone,
                subtitle = "Проверявам контакта и историята…",
                lines = listOf("Зареждам наличната информация…"),
                openFormUrl = "",
            ),
            fullscreen = fullscreen,
            phone = phone,
            direction = direction,
            // The coordinator fills both local and remote rows in the background.
            remoteRowsArePreloaded = true,
        )
    }

    private fun publishCurrent() {
        val snapshot = synchronized(lock) {
            val contact = contactInfo
            if (contact?.shouldNotify == false) return
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
                title = snapshot.contact?.displayName
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
            // The coordinator owns the optional history request. Do not let the
            // overlay create a second competing lookup while the call is active.
            remoteRowsArePreloaded = true,
        )
    }

    private fun fallbackLookup(contact: IncomingCallContactInfo? = null): LookupResult = LookupResult(
        title = contact?.displayName?.takeIf { it.isNotBlank() } ?: phone,
        subtitle = phone,
        lines = emptyList(),
        openFormUrl = "",
    )

    private fun submit(executor: ThreadPoolExecutor, block: () -> Unit): Boolean {
        return try {
            executor.execute(block)
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
        val contact: IncomingCallContactInfo?,
        val lookup: LookupResult?,
        val lookupFinished: Boolean,
    )

    private companion object {
        private const val CONTACT_QUEUE_SIZE = 8
        private const val LOCAL_ROWS_QUEUE_SIZE = 8
        private const val LOOKUP_QUEUE_SIZE = 12
        private const val HISTORY_QUEUE_SIZE = 12
        private const val LOOKUP_DEADLINE_MS = 4_500L
        private const val POPUP_HISTORY_LIMIT = 20

        // Contact name/group checks run in a dedicated queue so a slow server
        // history request can never postpone the first local correction.
        private val CONTACT_EXECUTOR = ThreadPoolExecutor(
            1,
            1,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(CONTACT_QUEUE_SIZE),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val LOCAL_ROWS_EXECUTOR = ThreadPoolExecutor(
            1,
            1,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(LOCAL_ROWS_QUEUE_SIZE),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val LOOKUP_EXECUTOR = ThreadPoolExecutor(
            2,
            2,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(LOOKUP_QUEUE_SIZE),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val HISTORY_EXECUTOR = ThreadPoolExecutor(
            1,
            1,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(HISTORY_QUEUE_SIZE),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val LOOKUP_TIMEOUT_EXECUTOR = ScheduledThreadPoolExecutor(1).apply {
            removeOnCancelPolicy = true
        }
    }
}
