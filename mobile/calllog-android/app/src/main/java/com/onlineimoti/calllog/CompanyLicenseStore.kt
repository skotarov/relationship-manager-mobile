package com.onlineimoti.calllog

import android.content.Context

/** Holds a short-lived server-issued activation code until the user creates a company. */
object CompanyLicenseStore {
    private const val PREFS = "relationship_manager_license"
    private const val KEY_ACTIVATION_TOKEN = "company_creation_activation_token"
    private const val KEY_EXPIRES_AT_MS = "company_creation_activation_expires_at_ms"
    private const val KEY_PRODUCT_ID = "company_creation_product_id"

    data class Activation(
        val token: String,
        val expiresAtMs: Long,
        val productId: String,
    )

    fun save(context: Context, token: String, expiresAtMs: Long, productId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVATION_TOKEN, token.trim())
            .putLong(KEY_EXPIRES_AT_MS, expiresAtMs)
            .putString(KEY_PRODUCT_ID, productId.trim())
            .apply()
    }

    fun loadValid(context: Context, nowMs: Long = System.currentTimeMillis()): Activation? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_ACTIVATION_TOKEN, "").orEmpty().trim()
        val expiresAtMs = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        val productId = prefs.getString(KEY_PRODUCT_ID, "").orEmpty().trim()
        if (token.isBlank() || expiresAtMs <= nowMs || productId.isBlank()) {
            if (token.isNotBlank() || expiresAtMs > 0L || productId.isNotBlank()) clear(context)
            return null
        }
        return Activation(token, expiresAtMs, productId)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVATION_TOKEN)
            .remove(KEY_EXPIRES_AT_MS)
            .remove(KEY_PRODUCT_ID)
            .apply()
    }
}
