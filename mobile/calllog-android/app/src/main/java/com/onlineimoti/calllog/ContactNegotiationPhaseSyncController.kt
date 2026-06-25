package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors

/** Synchronizes the contact phase with server last-write-wins semantics. */
internal class ContactNegotiationPhaseSyncController(
    private val context: Context,
    private val mainHandler: Handler,
    private val onStateChanged: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()

    fun refresh(phone: String) {
        val config = ConfigStore.load(context)
        if (phone.isBlank() || !CallReportRemoteAccess.isReady(config)) return
        executor.execute {
            val local = ContactNegotiationPhaseStore.state(context, phone)
            val server = runCatching {
                ContactNegotiationPhaseRemoteClient.fetch(config, phone)
            }.getOrNull() ?: return@execute
            val canonical = if (local.updatedAtMs > server.updatedAtMs) {
                runCatching {
                    ContactNegotiationPhaseRemoteClient.update(config, phone, local)
                }.getOrElse { server }
            } else {
                server
            }
            applyCanonical(phone, canonical)
        }
    }

    fun syncCurrent(phone: String) {
        val config = ConfigStore.load(context)
        val local = ContactNegotiationPhaseStore.state(context, phone)
        if (phone.isBlank() || local.updatedAtMs <= 0L || !CallReportRemoteAccess.isReady(config)) return
        executor.execute {
            val canonical = runCatching {
                ContactNegotiationPhaseRemoteClient.update(config, phone, local)
            }.getOrNull() ?: return@execute
            applyCanonical(phone, canonical)
        }
    }

    fun release() {
        executor.shutdownNow()
    }

    private fun applyCanonical(phone: String, canonical: ContactNegotiationPhaseState) {
        val before = ContactNegotiationPhaseStore.state(context, phone)
        val after = ContactNegotiationPhaseStore.applyServerState(context, phone, canonical)
        if (after != before) {
            mainHandler.post(onStateChanged)
        }
    }
}
