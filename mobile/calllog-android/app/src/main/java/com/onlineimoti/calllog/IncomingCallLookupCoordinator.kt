package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
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
        if (!lookupQueued) {
            synchronized(lock) {
                lookupResult = fallbackLookup(resolvedContact)
                lookupFinished = true
            }
            publishCurrent()
            finishOnce()
            return
        }

        // A slow known-contact lookup must never leave the initial loading popup
        // on screen indefinitely. The server result may still replace this safe
        // fallback later when it arrives.
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

        // The initial lookup and full server history used to run together. For a
        // known number this could create two expensive server scans at the same
        // time. Start the secondary history enrichment only after lookup.php has
        // answered, and request a compact preview rather than a 200-record log.
        if (attempt.isSuccess) submit(::loadHistoryRows)

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
            // The coordinator owns the optional history request. Do not let the
            // overlay create a second competing lookup while the call is active.
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
        private const val LOOKUP_DEADLINE_MS = 4_500L
        private const val POPUP_HISTORY_LIMIT = 20
        private val EXECUTOR = ThreadPoolExecutor(
            4,
            4,
            20L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAX_PENDING_TASKS),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val LOOKUP_TIMEOUT_EXECUTOR = ScheduledThreadPoolExecutor(1).apply {
            removeOnCancelPolicy = true
        }
    }
}
