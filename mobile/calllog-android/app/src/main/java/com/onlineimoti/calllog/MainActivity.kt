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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val text = if (granted) "Разрешението за notifications е дадено." else "Notifications остават забранени."
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
            setStatus("Достъпът до телефон, call report log и contacts е разрешен.")
        } else {
            setStatus("Без достъп до телефон, call report log и contacts няма да работи коректно филтърът за познати номера.")
        }
        refreshPermissionSummary()
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
            setStatus("Разрешението за full-screen call report popup е дадено.")
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
        renderBuildVersion()

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
    }

    private fun saveConfig(): AppConfig {
        val config = AppConfig(
            baseUrl = binding.baseUrlInput.text?.toString().orEmpty(),
            accessToken = binding.accessTokenInput.text?.toString().orEmpty(),
            contactGroups = binding.contactGroupsInput.text?.toString().orEmpty(),
        )
        ConfigStore.save(this, config)
        return ConfigStore.load(this)
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

        val url = buildEndpoint(
            baseUrl = config.baseUrl,
            path = "/broker/callreport/form.php",
            params = linkedMapOf(
                "phone" to phone,
                "direction" to directionValue(),
                "access_token" to config.accessToken,
            )
        )
        openWebView(url)
    }

    private fun testStartPopup() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (config.baseUrl.isBlank() || phone.isBlank()) {
            setStatus("Попълни Base URL и телефон.")
            return
        }

        binding.testStartPopupButton.isEnabled = false
        setStatus("Тест: зареждам popup при старт за $phone …")

        executor.execute {
            runCatching {
                val displayName = ContactGroupFilter.resolveDisplayName(this, phone)
                CallReportRuntime.fetchLookup(config, phone, directionValue()).let { lookup ->
                    if (displayName.isNullOrBlank()) lookup else lookup.copy(title = displayName)
                }
            }.onSuccess { result ->
                runOnUiThread {
                    binding.testStartPopupButton.isEnabled = true
                    CallReportRuntime.showLookupNotification(
                        context = this,
                        result = result,
                        fullscreen = true,
                        phone = phone,
                        direction = directionValue(),
                    )
                    setStatus("Показан е тестов popup при старт.")
                }
            }.onFailure { throwable ->
                runOnUiThread {
                    binding.testStartPopupButton.isEnabled = true
                    setStatus("Lookup грешка: ${throwable.message}")
                }
            }
        }
    }

    private fun testEndPopup() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (config.baseUrl.isBlank() || phone.isBlank()) {
            setStatus("Попълни Base URL и телефон.")
            return
        }

        binding.testEndPopupButton.isEnabled = false
        setStatus("Тест: зареждам popup след край за $phone …")

        executor.execute {
            runCatching {
                val displayName = ContactGroupFilter.resolveDisplayName(this, phone).orEmpty()
                CallReportRuntime.fetchLookup(config, phone, directionValue()).let { lookup ->
                    if (displayName.isBlank()) lookup else lookup.copy(title = displayName)
                }
            }.onSuccess { result ->
                runOnUiThread {
                    binding.testEndPopupButton.isEnabled = true
                    CallReportRuntime.showPostCallPromptNotification(
                        context = this,
                        formUrl = result.openFormUrl,
                        phone = phone,
                        direction = directionValue(),
                        title = result.title,
                    )
                    setStatus("Показан е тестов popup след край.")
                }
            }.onFailure { throwable ->
                runOnUiThread {
                    binding.testEndPopupButton.isEnabled = true
                    setStatus("Lookup грешка: ${throwable.message}")
                }
            }
        }
    }

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
        val callScreeningGranted = hasCallScreeningRole()
        val fullscreenGranted = canUseFullScreenIntent()

        val rows = listOf(
            "Notifications" to notificationsGranted,
            "Phone" to phoneGranted,
            "Call report log" to callLogGranted,
            "Contacts" to contactsGranted,
            "Call screening" to callScreeningGranted,
            "Full-screen popup" to fullscreenGranted,
        )
        val missingColor = ContextCompat.getColor(this, R.color.calllog_error)
        val builder = SpannableStringBuilder()
        rows.forEachIndexed { index, row ->
            val start = builder.length
            val line = "${row.first}: ${permissionStateLabel(row.second)}"
            builder.append(line)
            if (!row.second) {
                builder.setSpan(
                    ForegroundColorSpan(missingColor),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (index < rows.lastIndex) {
                builder.append('\n')
            }
        }
        binding.permissionsSummaryText.text = builder

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
}
