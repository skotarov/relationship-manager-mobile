package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import com.onlineimoti.calllog.databinding.ItemRecentCallBinding
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val recentCalls = mutableListOf<RecentCallItem>()
    private var loadedCount = 0
    private var hasMoreCalls = true
    private var isLoadingCalls = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val text = if (granted) {
            "Разрешението за notifications е дадено."
        } else {
            "Notifications остават забранени."
        }
        setStatus(text)
        refreshPermissionSummary()
    }

    private val phonePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val phoneStateGranted = result[Manifest.permission.READ_PHONE_STATE] == true
        val callLogGranted = result[Manifest.permission.READ_CALL_LOG] == true
        val contactsGranted = result[Manifest.permission.READ_CONTACTS] == true
        if (phoneStateGranted && callLogGranted && contactsGranted) {
            setStatus("Достъпът до телефон, call log и contacts е разрешен.")
        } else {
            setStatus("Без достъп до телефон, call log и contacts няма да работи коректно.")
        }
        refreshPermissionSummary()
        reloadRecentCalls(reset = true)
    }

    private val callScreeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasCallScreeningRole()) {
            setStatus("Call screening role е активирана.")
        }
        refreshPermissionSummary()
    }

    private val fullscreenIntentSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (canUseFullScreenIntent()) {
            setStatus("Разрешението за full-screen call popup е дадено.")
        }
        refreshPermissionSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CallReportRuntime.ensureNotificationChannel(this)
        requestNotificationPermissionIfNeeded()
        requestPhonePermissionsIfNeeded()
        requestCallScreeningRoleIfNeeded()
        requestFullScreenIntentPermissionIfNeeded()
        hydrateFields()
        refreshPermissionSummary()
        bindActions()
        reloadRecentCalls(reset = true)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionSummary()
        updateBuildInfo()
        reloadRecentCalls(reset = true)
    }

    private fun bindActions() {
        binding.openAppPermissionsButton.setOnClickListener {
            openAppPermissionSettings()
        }
        binding.openCallScreeningButton.setOnClickListener {
            requestCallScreeningRoleIfNeeded()
        }
        binding.openFullscreenIntentButton.setOnClickListener {
            requestFullScreenIntentPermissionIfNeeded()
        }
        binding.saveSettingsButton.setOnClickListener {
            val config = saveConfig()
            ServerHistoryRepository.clearIfConfigChanged(config)
            setStatus("Настройките са записани локално.")
            reloadRecentCalls(reset = true)
        }
        binding.openFormButton.setOnClickListener {
            saveConfig()
            openFormDirect()
        }
        binding.testNotificationButton.setOnClickListener {
            saveConfig()
            fetchLookupAndNotify()
        }
        binding.moreCallsButton.setOnClickListener {
            reloadRecentCalls(reset = false)
        }
    }

    private fun hydrateFields() {
        val config = ConfigStore.load(this)
        binding.baseUrlInput.setText(config.baseUrl)
        binding.accessTokenInput.setText(config.accessToken)
        binding.contactGroupsInput.setText(config.contactGroups)
        binding.formPathInput.setText(config.formPath.ifBlank { AppConfig.DEFAULT_FORM_PATH })
        binding.historyPathInput.setText(config.historyPath.ifBlank { AppConfig.DEFAULT_HISTORY_PATH })
        binding.phoneInput.setText(config.testPhoneNumber.ifBlank { AppConfig.DEFAULT_TEST_PHONE_NUMBER })
        binding.postCallTimeoutInput.setText(config.resolvedPostCallAutoCloseSeconds.toString())
        updateBuildInfo()
    }

    private fun updateBuildInfo() {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val versionName = packageInfo.versionName.orEmpty()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
        binding.versionText.text = getString(R.string.version_format, versionName, versionCode)
        binding.buildText.text = getString(R.string.build_format, BuildConfig.BUILD_TIME_UTC)
    }

    private fun saveConfig(): AppConfig {
        val config = AppConfig(
            baseUrl = binding.baseUrlInput.text?.toString().orEmpty(),
            accessToken = binding.accessTokenInput.text?.toString().orEmpty(),
            contactGroups = binding.contactGroupsInput.text?.toString().orEmpty(),
            formPath = binding.formPathInput.text?.toString().orEmpty(),
            historyPath = binding.historyPathInput.text?.toString().orEmpty(),
            testPhoneNumber = binding.phoneInput.text?.toString().orEmpty(),
            postCallAutoCloseSeconds = binding.postCallTimeoutInput.text?.toString()
                ?.trim()
                ?.toIntOrNull()
                ?: AppConfig.DEFAULT_POST_CALL_AUTO_CLOSE_SECONDS,
        )
        ConfigStore.save(this, config)
        return ConfigStore.load(this)
    }

    private fun reloadRecentCalls(reset: Boolean) {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            recentCalls.clear()
            loadedCount = 0
            hasMoreCalls = false
            binding.callListContainer.removeAllViews()
            binding.callListStatusText.text = getString(R.string.missing_call_log_permission)
            binding.callListEmptyText.visibility = View.VISIBLE
            binding.callListEmptyText.text = getString(R.string.missing_call_log_permission)
            binding.moreCallsButton.visibility = View.GONE
            return
        }
        if (isLoadingCalls) {
            return
        }
        if (!reset && !hasMoreCalls) {
            return
        }

        if (reset) {
            loadedCount = 0
            hasMoreCalls = true
            recentCalls.clear()
            binding.callListContainer.removeAllViews()
        }

        isLoadingCalls = true
        binding.callListStatusText.text = getString(R.string.loading_calls)
        binding.moreCallsButton.isEnabled = false
        val offset = loadedCount

        executor.execute {
            val page = RecentCallsRepository.loadPage(this, offset = offset, limit = PAGE_SIZE)
            val config = ConfigStore.load(this)
            val noteMap = runCatching {
                ServerHistoryRepository.fetchLatestNotes(
                    config = config,
                    phones = page.items.map { it.number }.distinct(),
                )
            }.getOrDefault(emptyMap())
            val hydratedItems = page.items.map { item ->
                item.copy(serverHistory = noteMap[item.lastDigits])
            }

            runOnUiThread {
                if (reset) {
                    recentCalls.clear()
                }
                recentCalls += hydratedItems
                loadedCount = recentCalls.size
                hasMoreCalls = page.hasMore
                isLoadingCalls = false
                renderCallRows()
                binding.callListStatusText.text = "Показани ${recentCalls.size} разговора"
                binding.moreCallsButton.isEnabled = true
                binding.moreCallsButton.visibility = if (hasMoreCalls) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderCallRows() {
        binding.callListContainer.removeAllViews()
        if (recentCalls.isEmpty()) {
            binding.callListEmptyText.visibility = View.VISIBLE
            binding.callListEmptyText.text = getString(R.string.no_calls)
            return
        }

        binding.callListEmptyText.visibility = View.GONE
        var previousGroupDate: LocalDate? = null
        recentCalls.forEach { call ->
            val groupDate = callGroupDate(call)
            if (groupDate != previousGroupDate) {
                binding.callListContainer.addView(createDateHeaderView(groupDate, isFirst = previousGroupDate == null))
            } else {
                binding.callListContainer.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            resources.displayMetrics.density.toInt().coerceAtLeast(1)
                        )
                        setBackgroundColor(ContextCompat.getColor(context, R.color.calllog_divider))
                    }
                )
            }

            val rowBinding = ItemRecentCallBinding.inflate(layoutInflater, binding.callListContainer, false)
            rowBinding.nameText.text = call.displayLabel
            rowBinding.nameText.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (call.isMissedLike) R.color.calllog_missed else R.color.calllog_text
                )
            )
            rowBinding.metaText.text = formatCallMeta(call)
            rowBinding.callTypeIcon.setImageResource(callIconRes(call))

            val latestNote = call.serverHistory?.latestNote.orEmpty()
            if (latestNote.isNotBlank()) {
                rowBinding.noteText.visibility = View.VISIBLE
                rowBinding.noteText.text = latestNote
            } else {
                rowBinding.noteText.visibility = View.GONE
            }

            rowBinding.noteButton.setOnClickListener {
                openFormForCall(call)
            }
            rowBinding.root.setOnClickListener {
                openHistoryForCall(call)
            }

            binding.callListContainer.addView(rowBinding.root)
            previousGroupDate = groupDate
        }
    }

    private fun callIconRes(call: RecentCallItem): Int {
        return when {
            call.isMissedLike -> R.drawable.ic_call_missed
            call.callType == android.provider.CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_outgoing_answered
            else -> R.drawable.ic_call_incoming_answered
        }
    }

    private fun formatCallMeta(call: RecentCallItem): String {
        val dateTime = Instant.ofEpochMilli(call.callDateMs).atZone(ZoneId.systemDefault())
        val formatter = if (android.text.format.DateUtils.isToday(call.callDateMs)) {
            DateTimeFormatter.ofPattern("'Днес', HH:mm", Locale("bg"))
        } else {
            DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale("bg"))
        }
        val parts = mutableListOf(formatter.format(dateTime))
        if (call.durationSeconds > 0) {
            parts += formatDuration(call.durationSeconds)
        }
        if (call.number.isNotBlank() && call.displayName.isNotBlank()) {
            parts += call.number
        }
        return parts.joinToString(" • ")
    }

    private fun callGroupDate(call: RecentCallItem): LocalDate {
        return Instant.ofEpochMilli(call.callDateMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }

    private fun createDateHeaderView(date: LocalDate, isFirst: Boolean): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (isFirst) 0 else dp(18)
                bottomMargin = dp(8)
            }
            text = formatDateHeader(date)
            setTextColor(ContextCompat.getColor(context, R.color.calllog_text_secondary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun formatDateHeader(date: LocalDate): String {
        val locale = Locale("bg", "BG")
        val dateLabel = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
        val weekday = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
            .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(locale) else char.toString() }
        val daysAgo = ChronoUnit.DAYS.between(date, LocalDate.now(ZoneId.systemDefault()))
        val relativeLabel = when (daysAgo) {
            0L -> "днес"
            1L -> "преди 1 ден"
            else -> "преди $daysAgo дни"
        }
        return "$dateLabel • $weekday • $relativeLabel"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun formatDuration(durationSeconds: Long): String {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return when {
            minutes > 0 && seconds > 0 -> "${minutes}м ${seconds}с"
            minutes > 0 -> "${minutes}м"
            else -> "${seconds}с"
        }
    }

    private fun openFormForCall(call: RecentCallItem) {
        val config = ConfigStore.load(this)
        if (config.baseUrl.isBlank()) {
            setStatus("Попълни Base URL.")
            return
        }
        openWebView(
            config.buildFormUrl(
                phone = call.number,
                direction = call.directionSlug,
                extraParams = formPrefillParams(call)
            )
        )
    }

    private fun openHistoryForCall(call: RecentCallItem) {
        val config = ConfigStore.load(this)
        if (config.baseUrl.isBlank()) {
            setStatus("Попълни Base URL.")
            return
        }
        openWebView(config.buildHistoryUrl(call.number))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestPhonePermissionsIfNeeded() {
        val missingPermissions = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_PHONE_STATE)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_CALL_LOG)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_CONTACTS)
            }
        }
        if (missingPermissions.isEmpty()) {
            return
        }
        phonePermissionsLauncher.launch(missingPermissions.toTypedArray())
    }

    private fun directionValue(): String {
        return if (binding.directionIn.isChecked) "in" else "out"
    }

    private fun phoneValue(): String {
        return binding.phoneInput.text?.toString()?.trim().orEmpty()
    }

    private fun openFormDirect() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (config.baseUrl.isBlank() || phone.isBlank()) {
            setStatus("Попълни Base URL и телефон.")
            return
        }
        openWebView(config.buildFormUrl(phone, directionValue()))
    }

    private fun formPrefillParams(call: RecentCallItem): Map<String, String> {
        val params = linkedMapOf<String, String>()
        if (call.displayName.isNotBlank()) {
            params["contact"] = call.displayName
        }
        if (call.durationSeconds > 0) {
            params["duration"] = call.durationSeconds.toString()
        }
        params["occurred_at"] = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(call.callDateMs),
            ZoneId.systemDefault()
        ).format(FORM_OCCURED_AT_FORMATTER)

        val history = call.serverHistory
        if (history != null && matchesExistingServerNote(call, history)) {
            if (history.latestNote.isNotBlank()) {
                params["notes"] = history.latestNote
            }
            val contact = history.latestNoteContact.ifBlank { history.latestContact }
            if (contact.isNotBlank()) {
                params["contact"] = contact
            }
            if (history.latestNotePropertyId.isNotBlank()) {
                params["property_id"] = history.latestNotePropertyId
            }
        }
        return params
    }

    private fun matchesExistingServerNote(call: RecentCallItem, history: ServerHistorySnippet): Boolean {
        if (history.latestNote.isBlank() || history.latestNoteTimestampMs <= 0L) {
            return false
        }
        if (history.latestNoteDirection.isNotBlank() && history.latestNoteDirection != call.directionSlug) {
            return false
        }
        return abs(history.latestNoteTimestampMs - call.callDateMs) <= EXISTING_NOTE_MATCH_WINDOW_MS
    }

    private fun fetchLookupAndNotify() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (config.baseUrl.isBlank() || phone.isBlank()) {
            setStatus("Попълни Base URL и телефон.")
            return
        }

        binding.testNotificationButton.isEnabled = false
        binding.statusText.visibility = View.VISIBLE
        setStatus("Търся информация за $phone …")

        executor.execute {
            runCatching {
                val displayName = ContactGroupFilter.resolveDisplayName(this, phone)
                CallReportRuntime.fetchLookup(config, phone, directionValue()).let { lookup ->
                    if (displayName.isNullOrBlank()) {
                        lookup
                    } else {
                        lookup.copy(title = displayName)
                    }
                }
            }.onSuccess { result ->
                runOnUiThread {
                    binding.testNotificationButton.isEnabled = true
                    CallReportRuntime.showLookupNotification(this, result, fullscreen = true)
                    setStatus("Notification е обновен с lookup данните.")
                }
            }.onFailure { throwable ->
                runOnUiThread {
                    binding.testNotificationButton.isEnabled = true
                    setStatus("Lookup грешка: ${throwable.message}")
                }
            }
        }
    }

    private fun openWebView(url: String) {
        startActivity(WebViewActivity.intent(this, url))
    }

    private fun setStatus(message: String) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = message
    }

    private fun refreshPermissionSummary() {
        val notificationsGranted = hasNotificationPermission()
        val phoneGranted = hasPermission(Manifest.permission.READ_PHONE_STATE)
        val callLogGranted = hasPermission(Manifest.permission.READ_CALL_LOG)
        val contactsGranted = hasPermission(Manifest.permission.READ_CONTACTS)
        val callScreeningGranted = hasCallScreeningRole()
        val fullscreenGranted = canUseFullScreenIntent()

        binding.permissionsSummaryText.text = listOf(
            "Notifications: ${permissionStateLabel(notificationsGranted)}",
            "Phone: ${permissionStateLabel(phoneGranted)}",
            "Call log: ${permissionStateLabel(callLogGranted)}",
            "Contacts: ${permissionStateLabel(contactsGranted)}",
            "Call screening: ${permissionStateLabel(callScreeningGranted)}",
            "Full-screen popup: ${permissionStateLabel(fullscreenGranted)}",
        ).joinToString("\n")

        val needsAppPermissions = !notificationsGranted || !phoneGranted || !callLogGranted || !contactsGranted
        binding.openAppPermissionsButton.visibility = if (needsAppPermissions) View.VISIBLE else View.GONE
        binding.openCallScreeningButton.visibility = if (callScreeningGranted) View.GONE else View.VISIBLE
        binding.openFullscreenIntentButton.visibility = if (fullscreenGranted) View.GONE else View.VISIBLE
    }

    private fun permissionStateLabel(active: Boolean): String {
        return if (active) "активно" else "липсва"
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun hasCallScreeningRole(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        val roleManager = getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun requestCallScreeningRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        if (hasCallScreeningRole()) {
            return
        }
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            return
        }
        callScreeningRoleLauncher.launch(
            roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        )
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return false
        return notificationManager.canUseFullScreenIntent()
    }

    private fun requestFullScreenIntentPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        if (canUseFullScreenIntent()) {
            return
        }

        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:$packageName")
        }
        fullscreenIntentSettingsLauncher.launch(intent)
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val EXISTING_NOTE_MATCH_WINDOW_MS = 10 * 60 * 1000L
        private val FORM_OCCURED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    }
}
