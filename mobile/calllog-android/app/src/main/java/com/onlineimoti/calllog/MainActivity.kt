package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.role.RoleManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var isPermissionFlowRunning = false

    private val singlePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        refreshPermissionSummary()
        requestNextPermissionStep()
    }

    private val callScreeningRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasCallScreeningRole()) setStatus("Call screening role е активирана.")
        refreshPermissionSummary()
        requestNextPermissionStep()
    }

    private val overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) setStatus("Разрешението за кръгла floating икона е дадено.")
        refreshPermissionSummary()
        requestNextPermissionStep()
    }

    private val fullscreenIntentSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canUseFullScreenIntent()) setStatus("Разрешението за full-screen call report popup е дадено.")
        refreshPermissionSummary()
        isPermissionFlowRunning = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CallReportRuntime.ensureNotificationChannel(this)
        hydrateFields()
        refreshPermissionSummary()
        renderBuildVersion()
        startPermissionFlow()

        binding.openAppPermissionsButton.setOnClickListener { startPermissionFlow() }
        binding.openCallScreeningButton.setOnClickListener { requestCallScreeningRoleIfNeeded() }
        binding.openFullscreenIntentButton.setOnClickListener { requestFullScreenIntentPermissionIfNeeded() }
        binding.saveSettingsButton.setOnClickListener {
            saveConfig()
            setStatus("Настройките са записани локално.")
        }
        binding.openFormButton.setOnClickListener {
            saveConfig()
            openFormDirect()
        }
        binding.testStartPopupButton.setOnClickListener {
            saveConfig()
            testStartPopup()
        }
        binding.testEndPopupButton.setOnClickListener {
            saveConfig()
            testEndPopup()
        }
        binding.testFullLogButton.setOnClickListener {
            saveConfig()
            openFullLogDirect()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionSummary()
    }

    private fun hydrateFields() {
        val config = ConfigStore.load(this)
        binding.baseUrlInput.setText(config.baseUrl)
        binding.accessTokenInput.setText(config.accessToken)
        binding.contactGroupsInput.setText(config.contactGroups)
        binding.notifyUnknownContactsCheckBox.isChecked = config.notifyUnknownContacts
        binding.notifyKnownContactsCheckBox.isChecked = config.notifyKnownContacts
        binding.lookupPathInput.setText(config.lookupPath)
        binding.formPathInput.setText(config.formPath)
        binding.historyPathInput.setText(config.historyPath)
        binding.postCallTimeoutInput.setText(config.postCallPromptTimeoutSeconds.toString())
    }

    private fun saveConfig(): AppConfig {
        val config = AppConfig(
            baseUrl = binding.baseUrlInput.text?.toString().orEmpty(),
            accessToken = binding.accessTokenInput.text?.toString().orEmpty(),
            contactGroups = binding.contactGroupsInput.text?.toString().orEmpty(),
            notifyUnknownContacts = binding.notifyUnknownContactsCheckBox.isChecked,
            notifyKnownContacts = binding.notifyKnownContactsCheckBox.isChecked,
            lookupPath = binding.lookupPathInput.text?.toString().orEmpty(),
            formPath = binding.formPathInput.text?.toString().orEmpty(),
            historyPath = binding.historyPathInput.text?.toString().orEmpty(),
            postCallPromptTimeoutSeconds = binding.postCallTimeoutInput.text?.toString()?.toIntOrNull()
                ?: ConfigStore.DEFAULT_POST_CALL_TIMEOUT_SECONDS,
        )
        ConfigStore.save(this, config)
        return ConfigStore.load(this)
    }

    private fun startPermissionFlow() {
        if (isPermissionFlowRunning) return
        isPermissionFlowRunning = true
        requestNextPermissionStep()
    }

    private fun requestNextPermissionStep() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS) -> {
                setStatus("Разреши notifications, за да могат popup-ите да се показват.")
                singlePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            !hasPermission(Manifest.permission.READ_PHONE_STATE) -> {
                setStatus("Разреши Phone, за да засичаме начало и край на разговор.")
                singlePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            }
            !hasPermission(Manifest.permission.READ_CALL_LOG) -> {
                setStatus("Разреши Call log, за да виждаме последните разговори.")
                singlePermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
            }
            !hasPermission(Manifest.permission.READ_CONTACTS) -> {
                setStatus("Разреши Contacts, за да показваме имена вместо само номера.")
                singlePermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
            !Settings.canDrawOverlays(this) -> {
                setStatus("Разреши Display over other apps, за да може popup-ът да е custom overlay.")
                requestOverlayPermissionIfNeeded()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasCallScreeningRole() -> {
                setStatus("Активирай Call screening, ако Android го предложи, за по-надежден popup при разговор.")
                requestCallScreeningRoleIfNeeded()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !canUseFullScreenIntent() -> {
                setStatus("Разреши Full-screen popup от системния екран.")
                requestFullScreenIntentPermissionIfNeeded()
            }
            else -> {
                isPermissionFlowRunning = false
                setStatus("Основните разрешения са проверени.")
                refreshPermissionSummary()
            }
        }
    }

    private fun directionValue(): String = if (binding.directionIn.isChecked) "in" else "out"
    private fun phoneValue(): String = binding.phoneInput.text?.toString()?.trim().orEmpty()

    private fun openFormDirect() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (config.baseUrl.isBlank() || config.accessToken.isBlank() || phone.isBlank()) {
            setStatus("За директна server форма попълни Base URL, access token и телефон.")
            return
        }
        openWebView(buildFormUrl(config, phone, directionValue()))
    }

    private fun openFullLogDirect() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (config.baseUrl.isBlank() || config.accessToken.isBlank() || phone.isBlank()) {
            setStatus("За server лог попълни Base URL, access token и телефон.")
            return
        }
        openWebView(buildHistoryUrl(config, phone, directionValue()))
        setStatus("Отворен е тестов пълен лог.")
    }

    private fun testStartPopup() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (phone.isBlank()) {
            setStatus("Попълни телефон.")
            return
        }
        binding.testStartPopupButton.isEnabled = false
        setStatus("Тест: показвам popup при старт за $phone …")
        executor.execute {
            val displayName = ContactGroupFilter.resolveDisplayName(this, phone)
            val title = displayName.ifNullOrBlank { phone }
            val result = if (config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()) {
                runCatching {
                    CallReportRuntime.fetchLookup(config, phone, directionValue()).let { lookup ->
                        if (displayName.isNullOrBlank()) lookup else lookup.copy(title = displayName)
                    }
                }.getOrElse {
                    LookupResult(title, "Локален режим", emptyList(), "")
                }
            } else {
                LookupResult(title, "Локален режим — без сървърни данни", emptyList(), "")
            }
            runOnUiThread {
                binding.testStartPopupButton.isEnabled = true
                LookupPopupPresenter.show(this, result, fullscreen = true, phone = phone, direction = directionValue())
                setStatus("Показан е тестов popup при старт.")
            }
        }
    }

    private fun testEndPopup() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (phone.isBlank()) {
            setStatus("Попълни телефон.")
            return
        }
        binding.testEndPopupButton.isEnabled = false
        setStatus("Тест: показвам popup след край за $phone …")
        executor.execute {
            val displayName = ContactGroupFilter.resolveDisplayName(this, phone)
            val title = displayName.ifNullOrBlank { "Локални действия след разговора" }
            val formUrl = if (config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()) {
                buildFormUrl(config, phone, directionValue())
            } else {
                ""
            }
            runOnUiThread {
                binding.testEndPopupButton.isEnabled = true
                CallReportRuntime.showPostCallPromptNotification(
                    context = this,
                    formUrl = formUrl,
                    phone = phone,
                    direction = directionValue(),
                    title = title,
                )
                setStatus("Показан е тестов popup след край.")
            }
        }
    }

    private fun buildFormUrl(config: AppConfig, phone: String, direction: String): String = buildEndpoint(
        baseUrl = config.baseUrl,
        path = config.formPath,
        params = linkedMapOf("phone" to phone, "direction" to direction, "access_token" to config.accessToken),
    )

    private fun buildHistoryUrl(config: AppConfig, phone: String, direction: String): String = buildEndpoint(
        baseUrl = config.baseUrl,
        path = config.historyPath,
        params = linkedMapOf("phone" to phone, "direction" to direction, "access_token" to config.accessToken),
    )

    private fun openWebView(url: String) {
        startActivity(Intent(this, WebViewActivity::class.java).putExtra(WebViewActivity.EXTRA_URL, url))
    }

    private fun setStatus(message: String) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = message
    }

    private fun renderBuildVersion() {
        binding.buildVersionText.text = "Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • ${BuildConfig.BUILD_TIME}"
    }

    private fun refreshPermissionSummary() {
        val notificationsGranted = hasNotificationPermission()
        val phoneGranted = hasPermission(Manifest.permission.READ_PHONE_STATE)
        val callLogGranted = hasPermission(Manifest.permission.READ_CALL_LOG)
        val contactsGranted = hasPermission(Manifest.permission.READ_CONTACTS)
        val overlayGranted = Settings.canDrawOverlays(this)
        val callScreeningGranted = hasCallScreeningRole()
        val fullscreenGranted = canUseFullScreenIntent()

        val rows = listOf(
            "Notifications" to notificationsGranted,
            "Phone" to phoneGranted,
            "Call report log" to callLogGranted,
            "Contacts" to contactsGranted,
            "Floating icon" to overlayGranted,
            "Call screening" to callScreeningGranted,
            "Full-screen popup" to fullscreenGranted,
        )
        val missingColor = ContextCompat.getColor(this, R.color.calllog_error)
        val builder = SpannableStringBuilder()
        rows.forEachIndexed { index, row ->
            val start = builder.length
            builder.append("${row.first}: ${permissionStateLabel(row.second)}")
            if (!row.second) builder.setSpan(ForegroundColorSpan(missingColor), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index < rows.lastIndex) builder.append('\n')
        }
        binding.permissionsSummaryText.text = builder

        val needsAppPermissions = !notificationsGranted || !phoneGranted || !callLogGranted || !contactsGranted || !overlayGranted
        binding.openAppPermissionsButton.visibility = if (needsAppPermissions) View.VISIBLE else View.GONE
        binding.openCallScreeningButton.visibility = if (callScreeningGranted) View.GONE else View.VISIBLE
        binding.openFullscreenIntentButton.visibility = if (fullscreenGranted) View.GONE else View.VISIBLE
    }

    private fun permissionStateLabel(active: Boolean): String = if (active) "активно" else "липсва"
    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppPermissionSettings() {
        startPermissionFlow()
    }

    private fun hasCallScreeningRole(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Settings.canDrawOverlays(this)) {
            requestNextPermissionStep()
            return
        }
        overlaySettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = Uri.parse("package:$packageName") })
    }

    private fun requestCallScreeningRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasCallScreeningRole()) {
            isPermissionFlowRunning = false
            refreshPermissionSummary()
            return
        }
        val roleManager = getSystemService(RoleManager::class.java) ?: run {
            isPermissionFlowRunning = false
            return
        }
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            isPermissionFlowRunning = false
            refreshPermissionSummary()
            return
        }
        callScreeningRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return false
        return notificationManager.canUseFullScreenIntent()
    }

    private fun requestFullScreenIntentPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || canUseFullScreenIntent()) {
            isPermissionFlowRunning = false
            refreshPermissionSummary()
            return
        }
        fullscreenIntentSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply { data = Uri.parse("package:$packageName") })
    }

    private inline fun String?.ifNullOrBlank(fallback: () -> String): String {
        return if (this.isNullOrBlank()) fallback() else this
    }
}
