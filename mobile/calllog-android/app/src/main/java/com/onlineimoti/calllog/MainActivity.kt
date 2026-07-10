package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : FontScaledAppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var suppressAutoSave = false
    private var currentLanguage = ConfigStore.DEFAULT_APP_LANGUAGE
    private var currentFontScale = AppFontScaleStore.NORMAL

    private val contactsCleanupController: MainContactsCleanupController by lazy {
        MainContactsCleanupController(this, binding, executor, ::setStatus, ::dp)
    }
    private val settingsNavigationController: MainSettingsNavigationController by lazy {
        MainSettingsNavigationController(this, binding, translationSettingsController::onSectionVisible)
    }
    private val settingsAutoSaveController: MainSettingsAutoSaveController by lazy {
        MainSettingsAutoSaveController(binding, ::autoSaveSettings, ::applyLanguageIfChanged, ::applyFontScaleIfChanged)
    }
    private val translationSettingsController: TranslationSettingsController by lazy {
        TranslationSettingsController(this, binding)
    }
    private val serverSyncQueueStatusController: ServerSyncQueueStatusController by lazy {
        ServerSyncQueueStatusController(this, binding, ::saveConfig, ::setStatus)
    }
    private val defaultSmsSettingsController: DefaultSmsSettingsController by lazy {
        DefaultSmsSettingsController(this, binding, ::requestDefaultSmsRole, ::requestSmsPermissions, ::setStatus)
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
            isDefaultSmsApp = { DefaultSmsRoleController.isDefaultSmsApp(this) },
            hasSmsPermissions = ::hasSmsPermissions,
            hasPermission = ::hasPermission,
            disableOverlayPopups = ::disableOverlayPopups,
            disableCallScreening = ::disableCallScreening,
            refreshPermissionSummary = ::refreshPermissionSummary,
            setStatus = ::setStatus,
        )
    }

    private val singlePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionFlowController.onPermissionResult()
    }
    private val callScreeningRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionFlowController.onCallScreeningResult()
    }
    private val storageSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionFlowController.onStorageSettingsResult()
    }
    private val localNotesFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        onLocalNotesFolderPicked(uri)
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
        currentFontScale = AppFontScaleStore.loadMultiplier(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (DistributionCapabilities.supportsLocalDeviceData) syncPrivateNotesToSharedStorageWhenAvailable()
        CallReportRuntime.ensureNotificationChannel(this)
        hydrateFields()
        refreshPermissionSummary()
        renderBuildVersion()
        if (DistributionCapabilities.supportsLocalDeviceData) contactsCleanupController.addProgressBar()
        settingsAutoSaveController.wire()
        translationSettingsController.wire()
        serverSyncQueueStatusController.wire()
        serverSyncQueueStatusController.refresh()
        configureBuildSpecificSettings()
        wireSettingsActions()
        if (BuildConfig.DEBUG) {
            MainServerTestsController(this, binding, executor, ::saveConfig, ::setStatus).wire()
        }
        settingsNavigationController.wire()
        settingsNavigationController.showMenu()
        defaultSmsSettingsController.refresh()
        permissionFlowController.start()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (DistributionCapabilities.supportsLocalDeviceData) {
            syncPrivateNotesToSharedStorageWhenAvailable()
            contactsCleanupController.addProgressBar()
            contactsCleanupController.refreshFromCurrentTask()
        }
        refreshPermissionSummary()
        serverSyncQueueStatusController.refresh()
        defaultSmsSettingsController.refresh()
    }

    override fun onDestroy() {
        translationSettingsController.release()
        contactsCleanupController.release()
        executor.shutdown()
        super.onDestroy()
    }

    private fun configureBuildSpecificSettings() {
        val permissionsSection = binding.settingsApplicationGroup.permissionsSection.statusSmsPermissionsSection.root
        if (DistributionCapabilities.isPlayBusinessBuild) {
            binding.settingsMenuGroup.settingsApplicationButton.visibility = android.view.View.GONE
            binding.settingsMenuGroup.settingsPopupButton.visibility = android.view.View.GONE
            binding.settingsMenuGroup.settingsRmContactsButton.visibility = android.view.View.GONE
            binding.settingsMenuGroup.settingsDataArchiveButton.visibility = android.view.View.GONE
            binding.settingsApplicationGroup.root.visibility = android.view.View.GONE
            binding.settingsPopupGroup.root.visibility = android.view.View.GONE
            binding.settingsRmContactsGroup.root.visibility = android.view.View.GONE
            binding.settingsDataArchiveGroup.root.visibility = android.view.View.GONE
            return
        }
        permissionsSection.visibility = android.view.View.VISIBLE
        defaultSmsSettingsController.wire()
    }

    private fun hydrateFields() = MainSettingsConfigUi.hydrate(binding, ConfigStore.load(this))

    private fun wireSettingsActions() {
        MainSettingsActionBinder.wire(
            activity = this,
            binding = binding,
            openHome = ::openCallLogHome,
            syncContacts = contactsCleanupController::syncAllRmContacts,
            saveServerSettings = ::saveServerSettings,
            testServerConnection = ::testServerConnection,
            createArchive = { createArchiveLauncher.launch(MainArchiveActions.archiveFileName()) },
            restoreArchive = { restoreArchiveLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
            testStart = if (BuildConfig.DEBUG) ({ saveConfig(); testStartPopup() }) else null,
            testEnd = if (BuildConfig.DEBUG) ({ saveConfig(); testEndPopup() }) else null,
        )
    }

    private fun saveServerSettings() {
        saveConfig()
        setStatus(getString(R.string.settings_server_saved))
        refreshPermissionSummary()
        serverSyncQueueStatusController.refresh()
    }

    private fun testServerConnection() {
        val config = saveConfig()
        val remote = binding.remoteSettingsSection
        remote.serverConnectionTestStatusText.visibility = android.view.View.VISIBLE
        remote.serverConnectionTestStatusText.text = getString(R.string.test_server_connection_running)
        remote.testServerConnectionButton.isEnabled = false
        executor.execute {
            val result = runCatching { ServerConnectionTester.test(config) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                remote.testServerConnectionButton.isEnabled = true
                result.onSuccess { status ->
                    remote.serverConnectionTestStatusText.text = buildString {
                        append(if (status.ok) "✅ " else "⚠️ ")
                        append(status.title)
                        if (status.detail.isNotBlank()) append("\n").append(status.detail)
                    }
                    setStatus(status.title)
                }.onFailure { error ->
                    val message = error.message.orEmpty().ifBlank { getString(R.string.test_server_connection_failed) }
                    remote.serverConnectionTestStatusText.text = "❌ $message"
                    setStatus(message)
                }
            }
        }
    }

    internal fun requestAppPermissionFromSummary(permission: String, label: String) {
        permissionFlowController.requestAppPermissionOrOpenSettings(permission, label)
    }

    internal fun requestSharedNotesStoragePermissionFromSummary() {
        saveConfig()
        setStatus("Избери папка за локалните бележки. В нея ще се създаде .callreport и ще остане след преинсталиране.")
        localNotesFolderLauncher.launch(null)
    }

    internal fun openSharedNotesStorageSettingsFromSummary() {
        requestSharedNotesStoragePermissionFromSummary()
    }

    internal fun clearSharedNotesStorageFromSummary() {
        LocalNotesFileStore.clearSelectedFolder(this)
        setStatus("Избраната папка е изключена. Локалните бележки ще се пазят в личната папка на приложението.")
        refreshPermissionSummary()
    }

    internal fun requestOverlayPermissionFromSummary() {
        saveConfig()
        permissionFlowController.requestOverlayPermissionIfNeeded()
    }

    internal fun requestCallScreeningPermissionFromSummary() {
        saveConfig()
        permissionFlowController.requestCallScreeningRoleIfNeeded()
    }

    private fun requestDefaultSmsRole() = DefaultSmsRoleController.requestDefaultSmsRole(this, smsRoleLauncher, ::setStatus)

    private fun requestSmsPermissions() {
        val missing = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
        ).filterNot(::hasPermission).toTypedArray()
        if (missing.isEmpty()) {
            permissionFlowController.onSmsPermissionsResult()
        } else {
            smsPermissionsLauncher.launch(missing)
        }
    }

    private fun hasSmsPermissions(): Boolean = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
    ).all(::hasPermission)

    private fun autoSaveSettings(): AppConfig {
        if (suppressAutoSave) return ConfigStore.load(this)
        return saveConfig().also {
            refreshPermissionSummary()
            serverSyncQueueStatusController.refresh()
        }
    }

    private fun saveConfig(): AppConfig {
        ConfigStore.save(this, MainSettingsConfigUi.read(binding))
        return ConfigStore.load(this)
    }

    private fun applyLanguageIfChanged(language: String) {
        if (language == currentLanguage) return
        currentLanguage = language
        AppLanguageManager.applyFromConfig(this)
        recreate()
    }

    private fun applyFontScaleIfChanged(scale: Float) {
        val normalized = AppFontScaleStore.normalize(scale)
        if (normalized == currentFontScale) return
        currentFontScale = normalized
        recreate()
    }

    private fun setStatus(message: String) {
        binding.statusText.visibility = android.view.View.VISIBLE
        binding.statusText.text = message
    }

    private fun openCallLogHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun syncPrivateNotesToSharedStorageWhenAvailable() {
        if (LocalNotesFileStore.hasSelectedFolderAccess(this)) LocalNotesFileStore.migratePrivateToSelected(this)
        else if (LocalNotesFileStore.canUsePublicFolder()) LocalNotesFileStore.migratePrivateToPublic(this)
    }

    private fun onLocalNotesFolderPicked(uri: Uri?) {
        if (uri == null) {
            setStatus("Не е избрана папка. Локалните бележки остават в личната папка на приложението.")
            refreshPermissionSummary()
            return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching { contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
        LocalNotesFileStore.setSelectedFolder(this, uri)
        val migrated = LocalNotesFileStore.migratePrivateToSelected(this)
        setStatus(
            when {
                !persisted -> "Папката е избрана, но Android не даде постоянен достъп. Избери папката отново."
                migrated -> "Локалните бележки ще се пазят в избраната папка/.callreport и ще останат след преинсталиране."
                else -> "Папката е избрана. Новите локални бележки ще се пазят в нея."
            },
        )
        refreshPermissionSummary()
    }

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
