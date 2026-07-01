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

    private val translationSettingsController by lazy {
        TranslationSettingsController(
            activity = this,
            binding = binding,
        )
    }

    private val settingsNavigationController by lazy {
        MainSettingsNavigationController(
            activity = this,
            binding = binding,
            onLanguageSectionShown = translationSettingsController::onSectionVisible,
        )
    }

    private val settingsAutoSaveController by lazy {
        MainSettingsAutoSaveController(
            binding = binding,
            autoSaveSettings = ::autoSaveSettings,
            applyLanguageIfChanged = ::applyLanguageIfChanged,
        )
    }

    private val serverSyncQueueStatusController by lazy {
        ServerSyncQueueStatusController(
            activity = this,
            binding = binding,
            saveConfig = ::saveConfig,
            setStatus = ::setStatus,
        )
    }

    private val defaultSmsSettingsController by lazy {
        DefaultSmsSettingsController(
            activity = this,
            binding = binding,
            requestDefaultRole = ::requestDefaultSmsRole,
            requestSmsPermissions = ::requestSmsPermissions,
            setStatus = ::setStatus,
        )
    }

    private val permissionFlowController: MainPermissionFlowController by lazy {
        MainPermissionFlowController(
            activity = this,
            requestPermissionLauncher = singlePermissionLauncher,
            callScreeningRoleLauncher = callScreeningRoleLauncher,
            storageSettingsLauncher = storageSettingsLauncher,
            overlaySettingsLauncher = overlaySettingsLauncher,
            requestDefaultSmsRole = ::requestDefaultSmsRole,
            requestSmsPermissions = ::requestSmsPermissions,
            isDefaultSmsApp = { SmsRoleController.isDefaultSmsApp(this) },
            hasSmsPermissions = ::hasSmsPermissions,
            hasPermission = ::hasPermission,
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

    private val storageSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionFlowController.onStorageSettingsResult()
    }

    private val smsRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionFlowController.onSmsRoleResult()
        defaultSmsSettingsController.refresh()
    }

    private val smsPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionFlowController.onSmsPermissionsResult()
        defaultSmsSettingsController.refresh()
    }

    private val overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionFlowController.onOverlaySettingsResult()
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

        syncPrivateNotesToSharedStorageWhenAvailable()
        CallReportRuntime.ensureNotificationChannel(this)
        hydrateFields()
        refreshPermissionSummary()
        renderBuildVersion()
        contactsCleanupController.addProgressBar()
        settingsAutoSaveController.wire()
        translationSettingsController.wire()
        serverSyncQueueStatusController.wire()
        serverSyncQueueStatusController.refresh()
        if (BuildConfig.DEBUG) {
            binding.settingsApplicationGroup.permissionsSection.statusSmsPermissionsSection.root.visibility = android.view.View.VISIBLE
            defaultSmsSettingsController.wire()
        } else {
            binding.settingsRmContactsGroup.defaultSmsSection.root.visibility = android.view.View.GONE
            binding.settingsApplicationGroup.permissionsSection.statusSmsPermissionsSection.root.visibility = android.view.View.GONE
        }
        wireSettingsActions()
        if (BuildConfig.DEBUG) {
            MainServerTestsController(
                activity = this,
                binding = binding,
                executor = executor,
                saveConfig = ::saveConfig,
                setStatus = ::setStatus,
            ).wire()
        }
        settingsNavigationController.wire()
        settingsNavigationController.showMenu()
        if (BuildConfig.DEBUG) defaultSmsSettingsController.refresh()
        permissionFlowController.start()
    }

    override fun onResume() {
        super.onResume()
        syncPrivateNotesToSharedStorageWhenAvailable()
        contactsCleanupController.addProgressBar()
        contactsCleanupController.refreshFromCurrentTask()
        refreshPermissionSummary()
        serverSyncQueueStatusController.refresh()
        if (BuildConfig.DEBUG) defaultSmsSettingsController.refresh()
    }

    override fun onDestroy() {
        translationSettingsController.release()
        contactsCleanupController.release()
        executor.shutdown()
        super.onDestroy()
    }

    private fun hydrateFields() {
        MainSettingsConfigUi.hydrate(binding, ConfigStore.load(this))
    }

    private fun wireSettingsActions() {
        binding.backToHomeButton.setOnClickListener { openCallLogHome() }
        binding.contactLinkSection.registerAllContactsButton.setOnClickListener { contactsCleanupController.syncAllRmContacts() }
        binding.remoteSettingsSection.saveServerSettingsButton.setOnClickListener {
            saveConfig()
            setStatus(getString(R.string.settings_server_saved))
            refreshPermissionSummary()
            serverSyncQueueStatusController.refresh()
        }
        binding.archiveSettingsSection.createArchiveButton.setOnClickListener {
            createArchiveLauncher.launch(MainArchiveActions.archiveFileName())
        }
        binding.archiveSettingsSection.restoreArchiveButton.setOnClickListener {
            restoreArchiveLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        if (BuildConfig.DEBUG) {
            val quickStartButton = findViewById<MaterialButton>(R.id.testStartPopupButton)
            val quickEndButton = findViewById<MaterialButton>(R.id.testEndPopupButton)
            quickStartButton.setOnClickListener {
                saveConfig()
                testStartPopup()
            }
            quickEndButton.setOnClickListener {
                saveConfig()
                testEndPopup()
            }
        }
    }

    internal fun requestAppPermissionFromSummary(permission: String, label: String) {
        permissionFlowController.requestAppPermissionOrOpenSettings(permission, label)
    }

    internal fun requestSharedNotesStoragePermissionFromSummary() {
        saveConfig()
        permissionFlowController.requestSharedNotesStoragePermission()
    }

    internal fun requestCallScreeningRoleFromSummary() {
        permissionFlowController.requestCallScreeningRole()
    }

    internal fun requestDefaultSmsRole() {
        SmsRoleController.requestDefaultSmsRole(this, smsRoleLauncher)
    }

    internal fun requestSmsPermissions() {
        permissionFlowController.requestSmsPermissions()
    }

    internal fun openOverlaySettingsFromSummary() {
        permissionFlowController.openOverlaySettings()
    }

    internal fun openAppDetailsFromSummary() {
        permissionFlowController.openAppDetails()
    }

    private fun refreshPermissionSummary() {
        PermissionStatusRenderer.render(this, binding, permissionFlowController)
        PermissionSummaryLocalizer.apply(this, binding)
    }

    private fun saveConfig(): AppConfig {
        val config = MainSettingsConfigUi.read(binding)
        ConfigStore.save(this, config)
        currentLanguage = config.appLanguage
        return config
    }

    private fun autoSaveSettings(): AppConfig {
        if (suppressAutoSave) return ConfigStore.load(this)
        val config = saveConfig()
        refreshPermissionSummary()
        serverSyncQueueStatusController.refresh()
        return config
    }

    private fun applyLanguageIfChanged(language: String) {
        if (language == currentLanguage) return
        currentLanguage = language
        AppLanguageManager.applyLanguage(language)
        recreate()
    }

    private fun disableOverlayPopups() {
        suppressAutoSave = true
        binding.popupSettingsSection.overlayEnabledCheckBox.isChecked = false
        suppressAutoSave = false
        saveConfig()
    }

    private fun disableCallScreening() {
        suppressAutoSave = true
        binding.popupSettingsSection.callScreeningEnabledCheckBox.isChecked = false
        suppressAutoSave = false
        saveConfig()
    }

    private fun hasSmsPermissions(): Boolean = hasPermission(Manifest.permission.READ_SMS) && hasPermission(Manifest.permission.RECEIVE_SMS)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
