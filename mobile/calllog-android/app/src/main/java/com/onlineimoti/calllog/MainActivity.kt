package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var suppressAutoSave = false
    private var currentLanguage = ConfigStore.DEFAULT_APP_LANGUAGE

    private val contactsCleanupController by lazy {
        MainContactsCleanupController(
            activity = this,
            binding = binding,
            executor = executor,
            setStatus = ::setStatus,
            dp = ::dp,
        )
    }

    private val settingsNavigationController by lazy {
        MainSettingsNavigationController(
            activity = this,
            binding = binding,
        )
    }

    private val settingsAutoSaveController by lazy {
        MainSettingsAutoSaveController(
            binding = binding,
            autoSaveSettings = ::autoSaveSettings,
            applyLanguageIfChanged = ::applyLanguageIfChanged,
        )
    }

    private val permissionFlowController: MainPermissionFlowController by lazy {
        MainPermissionFlowController(
            activity = this,
            requestPermissionLauncher = singlePermissionLauncher,
            callScreeningRoleLauncher = callScreeningRoleLauncher,
            storageSettingsLauncher = storageSettingsLauncher,
            overlaySettingsLauncher = overlaySettingsLauncher,
            fullscreenIntentSettingsLauncher = fullscreenIntentSettingsLauncher,
            hasPermission = ::hasPermission,
            canUsePublicNotesFolder = ::canUsePublicNotesFolder,
            disablePublicNotesFolder = ::disablePublicNotesFolder,
            disableOverlayPopups = ::disableOverlayPopups,
            disableCallScreening = ::disableCallScreening,
            refreshPermissionSummary = ::refreshPermissionSummary,
            setStatus = ::setStatus,
        )
    }

    private val singlePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        permissionFlowController.onPermissionResult()
    }

    private val callScreeningRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionFlowController.onCallScreeningResult()
    }

    private val smsRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val active = SmsRoleController.isDefaultSmsApp(this)
        if (active) {
            setStatus(getString(R.string.settings_sms_role_active))
            smsPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS,
                )
            )
        } else {
            setStatus(getString(R.string.settings_sms_role_not_changed))
        }
        refreshPermissionSummary()
    }

    private val smsPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissionSummary()
    }

    private val storageSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionFlowController.onStorageSettingsResult()
    }

    private val overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionFlowController.onOverlaySettingsResult()
    }

    private val fullscreenIntentSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionFlowController.onFullscreenIntentSettingsResult()
    }

    private val createArchiveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) MainArchiveActions.createArchive(this, uri, ::setStatus)
    }

    private val restoreArchiveLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) MainArchiveActions.askRestoreMode(this, uri, ::setStatus)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        currentLanguage = ConfigStore.load(this).appLanguage
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CallReportRuntime.ensureNotificationChannel(this)
        hydrateFields()
        refreshPermissionSummary()
        renderBuildVersion()
        contactsCleanupController.addProgressBar()
        settingsAutoSaveController.wire()
        wireSettingsActions()
        settingsNavigationController.wire()
        settingsNavigationController.showMenu()
        permissionFlowController.start()
    }

    override fun onResume() {
        super.onResume()
        contactsCleanupController.addProgressBar()
        contactsCleanupController.refreshFromCurrentTask()
        refreshPermissionSummary()
    }

    override fun onDestroy() {
        contactsCleanupController.release()
        executor.shutdown()
        super.onDestroy()
    }

    private fun hydrateFields() {
        MainSettingsConfigUi.hydrate(binding, ConfigStore.load(this))
    }

    private fun wireSettingsActions() {
        val quickStartButton = findViewById<MaterialButton>(R.id.testStartPopupButton)
        val quickEndButton = findViewById<MaterialButton>(R.id.testEndPopupButton)

        binding.backToHomeButton.setOnClickListener { openCallLogHome() }
        binding.contactLinkSection.registerAllContactsButton.setOnClickListener { contactsCleanupController.syncAllRmContacts() }
        binding.remoteSettingsSection.saveServerSettingsButton.setOnClickListener {
            saveConfig()
            setStatus(getString(R.string.settings_server_saved))
            refreshPermissionSummary()
        }
        binding.archiveSettingsSection.createArchiveButton.setOnClickListener {
            createArchiveLauncher.launch(MainArchiveActions.archiveFileName())
        }
        binding.archiveSettingsSection.restoreArchiveButton.setOnClickListener {
            restoreArchiveLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        quickStartButton.setOnClickListener {
            saveConfig()
            testStartPopup()
        }
        quickEndButton.setOnClickListener {
            saveConfig()
            testEndPopup()
        }
    }

    internal fun requestAppPermissionFromSummary(permission: String, label: String) {
        permissionFlowController.requestAppPermissionOrOpenSettings(permission, label)
    }

    internal fun requestPublicNotesStoragePermissionFromSummary() {
        saveConfig()
        permissionFlowController.requestPublicNotesStoragePermission()
    }

    internal fun requestOverlayPermissionFromSummary() {
        saveConfig()
        permissionFlowController.requestOverlayPermissionIfNeeded()
    }

    internal fun requestCallScreeningPermissionFromSummary() {
        saveConfig()
        permissionFlowController.requestCallScreeningRoleIfNeeded()
    }

    internal fun requestDefaultSmsRoleFromSummary() {
        saveConfig()
        requestDefaultSmsRole()
    }

    internal fun requestFullScreenIntentPermissionFromSummary() {
        permissionFlowController.requestFullScreenIntentPermissionIfNeeded()
    }

    private fun requestDefaultSmsRole() {
        SmsRoleController.requestDefaultSmsRole(this, smsRoleLauncher, ::setStatus)
    }

    private fun autoSaveSettings(): AppConfig {
        if (suppressAutoSave) return ConfigStore.load(this)
        val config = saveConfig()
        refreshPermissionSummary()
        return config
    }

    private fun saveConfig(): AppConfig {
        val config = MainSettingsConfigUi.read(binding)
        ConfigStore.save(this, config)
        return ConfigStore.load(this)
    }

    private fun applyLanguageIfChanged(language: String) {
        if (language == currentLanguage) return
        currentLanguage = language
        AppLanguageManager.applyFromConfig(this)
        recreate()
    }

    private fun setStatus(message: String) {
        binding.statusText.visibility = android.view.View.VISIBLE
        binding.statusText.text = message
    }

    private fun openCallLogHome() {
        startActivity(
            Intent(this, HomeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun canUsePublicNotesFolder(): Boolean = LocalNotesFileStore.canUsePublicFolder()
    private fun disablePublicNotesFolder() = MainStorageSettings.disablePublicNotesFolder(this)
    private fun disableOverlayPopups() = MainPopupSettings.disableOverlayPopups(this)
    private fun disableCallScreening() = MainPermissionSettings.disableCallScreening(this)
    private fun refreshPermissionSummary() {
        PermissionStatusRenderer.refresh(this, binding)
        PermissionSummaryLocalizer.apply(this, binding)
    }
    private fun renderBuildVersion() = MainBuildVersion.render(this, binding)
    private fun testStartPopup() = MainTestActions.testStartPopup(this, binding, executor, ::setStatus)
    private fun testEndPopup() = MainTestActions.testEndPopup(this, binding, executor, ::setStatus)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
