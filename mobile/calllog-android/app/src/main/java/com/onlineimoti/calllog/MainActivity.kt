package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var isPermissionFlowRunning = false
    private var contactsOperationProgress: ProgressBar? = null
    private var contactsOperationProgressRow: LinearLayout? = null
    private var contactsOperationRunning = false

    private val singlePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        refreshPermissionSummary()
        requestNextPermissionStep()
    }

    private val callScreeningRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasCallScreeningRole()) setStatus("Call screening role е активирана.")
        refreshPermissionSummary()
        requestNextPermissionStep()
    }

    private val storageSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canUsePublicNotesFolder()) setStatus("Публичната папка за бележки е достъпна: ${LocalNotesFileStore.publicRootPath()}")
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
        addContactsOperationProgressBar()
        startPermissionFlow()

        binding.remoteEnabledCheckBox.setOnCheckedChangeListener { _, isChecked ->
            binding.remoteSettingsGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.openAppPermissionsButton.setOnClickListener { startPermissionFlow() }
        binding.openCallScreeningButton.setOnClickListener { requestCallScreeningRoleIfNeeded() }
        binding.openFullscreenIntentButton.setOnClickListener { requestFullScreenIntentPermissionIfNeeded() }
        binding.cleanupContactsButton.setOnClickListener { cleanupCallReportContacts() }
        binding.saveSettingsButton.setOnClickListener {
            saveConfig()
            setStatus("Настройките са записани локално. Бележките са в ${LocalNotesFileStore.publicRootPath()}")
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
        binding.remoteEnabledCheckBox.isChecked = config.remoteEnabled
        binding.remoteSettingsGroup.visibility = if (config.remoteEnabled) View.VISIBLE else View.GONE
        binding.baseUrlInput.setText(config.baseUrl)
        binding.accessTokenInput.setText(config.accessToken)
        binding.contactGroupsInput.setText(config.contactGroups)
        binding.notifyUnknownContactsCheckBox.isChecked = config.notifyUnknownContacts
        binding.notifyKnownContactsCheckBox.isChecked = config.notifyKnownContacts
        binding.lookupPathInput.setText(config.lookupPath)
        binding.formPathInput.setText(config.formPath)
        binding.historyPathInput.setText(config.historyPath)
        binding.postCallTimeoutInput.setText(config.postCallPromptTimeoutSeconds.toString())
        binding.useCustomStartPopupCheckBox.isChecked = config.useCustomStartPopup
        binding.useCustomEndPopupCheckBox.isChecked = config.useCustomEndPopup
    }

    private fun saveConfig(): AppConfig {
        val config = AppConfig(
            remoteEnabled = binding.remoteEnabledCheckBox.isChecked,
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
            useCustomStartPopup = binding.useCustomStartPopupCheckBox.isChecked,
            useCustomEndPopup = binding.useCustomEndPopupCheckBox.isChecked,
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
            !hasPermission(Manifest.permission.WRITE_CONTACTS) -> {
                setStatus("Разреши Contacts write за съвместимост със старите бележки към контакти.")
                singlePermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
            }
            !canUsePublicNotesFolder() -> {
                setStatus("Разреши достъп до всички файлове, за да пазим бележките в публичната папка ${LocalNotesFileStore.publicRootPath()}.")
                requestStorageManagerPermissionIfNeeded()
            }
            !Settings.canDrawOverlays(this) -> {
                setStatus("Разреши Display over other apps, за custom popup режимите.")
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
                setStatus("Основните разрешения са проверени. Бележките са в ${LocalNotesFileStore.publicRootPath()}.")
                refreshPermissionSummary()
            }
        }
    }

    private fun directionValue(): String = if (binding.directionIn.isChecked) "in" else "out"
    private fun phoneValue(): String = binding.phoneInput.text?.toString()?.trim().orEmpty()

    private fun remoteReady(config: AppConfig): Boolean {
        return config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()
    }

    private fun openFormDirect() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (!remoteReady(config) || phone.isBlank()) {
            setStatus("За server форма включи Сървър и попълни Base URL, access token и телефон.")
            return
        }
        openWebView(buildFormUrl(config, phone, directionValue()))
    }

    private fun openFullLogDirect() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (!remoteReady(config) || phone.isBlank()) {
            setStatus("За server лог включи Сървър и попълни Base URL, access token и телефон.")
            return
        }
        openWebView(buildHistoryUrl(config, phone, directionValue()))
        setStatus("Отворен е тестов пълен лог.")
    }

    private fun addContactsOperationProgressBar() {
        val parent = binding.cleanupContactsButton.parent as? ViewGroup ?: return
        if (contactsOperationProgressRow != null) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) }
        }
        row.addView(spinner)
        row.addView(TextView(this).apply {
            text = "Почистване на Call Report записите…"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(binding.cleanupContactsButton.currentTextColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val cleanupIndex = parent.indexOfChild(binding.cleanupContactsButton)
        parent.addView(row, if (cleanupIndex >= 0) cleanupIndex + 1 else parent.childCount)
        contactsOperationProgress = spinner
        contactsOperationProgressRow = row
    }

    private fun setContactsOperationRunning(running: Boolean) {
        contactsOperationRunning = running
        contactsOperationProgressRow?.visibility = if (running) View.VISIBLE else View.GONE
        contactsOperationProgress?.visibility = if (running) View.VISIBLE else View.GONE
        binding.cleanupContactsButton.isEnabled = !running
        binding.cleanupContactsButton.text = if (running) "Почистване…" else "Почисти Call Report от контактите"
    }

    private fun cleanupCallReportContacts() {
        if (contactsOperationRunning) return
        setContactsOperationRunning(true)
        setStatus("Почиствам Call Report записите от контактите…")
        val appContext = applicationContext
        executor.execute {
            val deleted = CallReportContactIntegration.removeAllCallReportContacts(appContext)
            runOnUiThread {
                setContactsOperationRunning(false)
                setStatus("Премахнати Call Report записи от контактите: $deleted")
            }
        }
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
            val result = if (remoteReady(config)) {
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
            val formUrl = if (remoteReady(config)) buildFormUrl(config, phone, directionValue()) else ""
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
        MainPermissionSummary.refresh(this, binding)
    }

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun canUsePublicNotesFolder(): Boolean = LocalNotesFileStore.canUsePublicFolder()

    private fun requestStorageManagerPermissionIfNeeded() {
        if (canUsePublicNotesFolder()) {
            requestNextPermissionStep()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            storageSettingsLauncher.launch(appIntent)
        } else {
            requestNextPermissionStep()
        }
    }

    private fun hasCallScreeningRole(): Boolean {
        return MainPermissionChecks.hasCallScreeningRole(this)
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
        return MainPermissionChecks.canUseFullScreenIntent(this)
    }

    private fun requestFullScreenIntentPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || canUseFullScreenIntent()) {
            isPermissionFlowRunning = false
            refreshPermissionSummary()
            return
        }
        fullscreenIntentSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply { data = Uri.parse("package:$packageName") })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inline fun String?.ifNullOrBlank(fallback: () -> String): String {
        return if (this.isNullOrBlank()) fallback() else this
    }
}
