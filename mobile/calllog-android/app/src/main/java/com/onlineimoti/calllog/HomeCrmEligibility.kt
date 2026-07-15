package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal enum class HomeCrmContactKind {
    /** Number does not exist in a regular Android contact. */
    UNKNOWN,
    /** Existing Android contact explicitly enabled for CRM/cloud sync. */
    KNOWN_CRM,
    /** Existing Android contact without the CRM switch. */
    NOT_ELIGIBLE,
}

internal object HomeCrmEligibility {
    private const val CRM_CALL_SCAN_LIMIT = 1_000
    private const val REAL_CONTACT_CACHE_MS = 30_000L

    private val realContactPhoneKeysLock = Any()
    private var cachedRealContactPhoneKeys: Set<String> = emptySet()
    private var realContactPhoneKeysCachedAtMs = 0L

    /** A CRM row is either an unknown number or a number explicitly marked CRM. */
    fun isEligible(context: Context, phone: String): Boolean {
        if (HomeCallPageLoader.noteKey(phone).isBlank()) return false
        return CrmContactSyncStore.isEnabled(context.applicationContext, phone) ||
            ContactServerCompanyScope.isUnknownNumber(context.applicationContext, phone)
    }

    /** Resolves the CRM category for each phone in one Contacts pass for Home-only filtering. */
    fun contactKinds(context: Context, phones: Iterable<String>): Map<String, HomeCrmContactKind> {
        val candidateKeys = phones
            .map(HomeCallPageLoader::noteKey)
            .filter { it.isNotBlank() }
            .toSet()
        if (candidateKeys.isEmpty()) return emptyMap()
        val appContext = context.applicationContext
        val explicitlyCrmKeys = CrmContactSyncStore.enabledPhoneKeys(appContext)
        val knownRealContactKeys = realContactPhoneKeys(appContext)
        return candidateKeys.associateWith { key ->
            when {
                key !in knownRealContactKeys -> HomeCrmContactKind.UNKNOWN
                key in explicitlyCrmKeys -> HomeCrmContactKind.KNOWN_CRM
                else -> HomeCrmContactKind.NOT_ELIGIBLE
            }
        }
    }

    fun eligiblePhoneKeys(context: Context, phones: Iterable<String>): Set<String> {
        return contactKinds(context, phones)
            .filterValues { it != HomeCrmContactKind.NOT_ELIGIBLE }
            .keys
    }

    /** Raw chronological CRM candidates before contact, direction or company filters are applied. */
    fun candidateCalls(context: Context): List<PhoneCallRecord> {
        return filter(
            context,
            PhoneCallReader.recentCalls(context, limit = CRM_CALL_SCAN_LIMIT, offset = 0),
        )
    }

    fun filter(context: Context, calls: List<PhoneCallRecord>): List<PhoneCallRecord> {
        if (calls.isEmpty()) return emptyList()
        val appContext = context.applicationContext
        val categories = contactKinds(appContext, calls.asSequence().map { it.number }.asIterable())
        val localEligibleKeys = categories
            .filterValues { it != HomeCrmContactKind.NOT_ELIGIBLE }
            .keys
        val knownButNotLocallyMarkedPhones = calls
            .map { it.number }
            .distinctBy(HomeCallPageLoader::noteKey)
            .filter { phone -> categories[HomeCallPageLoader.noteKey(phone)] == HomeCrmContactKind.NOT_ELIGIBLE }
        val serverEligibleKeys = serverBackedCrmPhoneKeys(appContext, knownButNotLocallyMarkedPhones)
        return calls.filter { call ->
            val key = HomeCallPageLoader.noteKey(call.number)
            key in localEligibleKeys || key in serverEligibleKeys
        }
    }

    private fun serverBackedCrmPhoneKeys(context: Context, phones: List<String>): Set<String> {
        if (phones.isEmpty()) return emptySet()
        val config = ConfigStore.load(context.applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return emptySet()
        return runCatching {
            HomeCrmCompanyMembershipStore.resolve(
                context = context.applicationContext,
                config = config,
                phones = phones,
            ).companyIdsByPhoneKey
                .filterValues { companyIds -> companyIds.isNotEmpty() }
                .keys
        }.onFailure { error ->
            ServerConnectionNotifier.notifyFailure(context.applicationContext, config, error)
        }.getOrDefault(emptySet())
    }

    /**
     * Known-contact lookup is cached because CRM Home and the company-label loader
     * both need it. Without READ_CONTACTS the established behavior is to consider
     * all numbers unknown, so the cache remains empty.
     */
    private fun realContactPhoneKeys(context: Context): Set<String> {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptySet()
        }

        val now = System.currentTimeMillis()
        synchronized(realContactPhoneKeysLock) {
            if (
                realContactPhoneKeysCachedAtMs > 0L &&
                now - realContactPhoneKeysCachedAtMs < REAL_CONTACT_CACHE_MS
            ) {
                return cachedRealContactPhoneKeys
            }
        }

        val loaded = loadRealContactPhoneKeys(context)
        synchronized(realContactPhoneKeysLock) {
            cachedRealContactPhoneKeys = loaded
            realContactPhoneKeysCachedAtMs = now
            return cachedRealContactPhoneKeys
        }
    }

    /**
     * Query only provider-valid columns: collect normal raw contact IDs first,
     * then collect phone rows belonging to those IDs. The app's own Call Report
     * contact account is intentionally excluded from the known-contact set.
     */
    private fun loadRealContactPhoneKeys(context: Context): Set<String> {
        return runCatching {
            val realRawContactIds = context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.DELETED}=0 AND " +
                    "(${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE}!=?)",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            } ?: emptySet()

            if (realRawContactIds.isEmpty()) return@runCatching emptySet()

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        if (cursor.getLong(0) !in realRawContactIds) continue
                        HomeCallPageLoader.noteKey(cursor.getString(1).orEmpty())
                            .takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
            } ?: emptySet()
        }.getOrDefault(emptySet())
    }
}
