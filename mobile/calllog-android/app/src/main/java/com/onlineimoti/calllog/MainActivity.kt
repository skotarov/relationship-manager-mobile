package com.onlineimoti.calllog

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
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
        CallReportRuntime.ensureContactsSync(this)
        hydrateFields()
        refreshPermissionSummary()
        renderBuildVersion()
        contactsCleanupController.addProgressBar()
        wireSettingsAutoSave()
        permissionFlowController.start()

        val quickStartButton = findViewById<MaterialButton>(R.id.testStartPopupButton)
        val quickEndButton = findViewById<MaterialButton>(R.id.testEndPopupButton)

        binding.backToHomeButton.setOnClickListener { openCallLogHome() }
        binding.permissionsSection.openAppPermissionsButton.setOnClickListener { permissionFlowController.start() }
        binding.permissionsSection.openOverlayPermissionButton.setOnClickListener { permissionFlowController.requestOverlayPermissionIfNeeded() }
        binding.permissionsSection.openCallScreeningButton.setOnClickListener {
            saveConfig()
            permissionFlowController.requestCallScreeningRoleIfNeeded()
        }
        binding.permissionsSection.openFullscreenIntentButton.setOnClickListener { permissionFlowController.requestFullScreenIntentPermissionIfNeeded() }
        binding.contactLinkSection.registerAllContactsButton.setOnClickListener { contactsCleanupController.registerAllCallReportContacts() }
        binding.contactLinkSection.debugCrmContactNameButton.setOnClickListener { contactsCleanupController.repairAllRmContacts() }
        binding.contactLinkSection.cleanupContactsButton.setOnClickListener { contactsCleanupController.cleanupCallReportContacts() }
        binding.remoteSettingsSection.saveServerSettingsButton.setOnClickListener {
            saveConfig()
            setStatus("Server настройките са записани.")
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

    override fun onResume() {
        super.onResume()
        CallReportRuntime.ensureContactsSync(this)
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
        MainCallLogOverlaySettings.hydrate(this, binding)
    }

    private fun wireSettingsAutoSave() {
        val remote = binding.remoteSettingsSection
        val popup = binding.popupSettingsSection
        val popupFilter = binding.popupContactFilterSection
        val callLog = binding.callLogSettingsSection
        val contactLink = binding.contactLinkSection
        val storage = binding.storageSettingsSection
        val language = binding.languageSettingsSection
        val permissions = binding.permissionsSection

        remote.remoteEnabledCheckBox.setOnCheckedChangeListener { _, isChecked ->
            remote.remoteSettingsGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            autoSaveSettings()
        }

        listOf(
            remote.baseUrlInput,
            remote.accessTokenInput,
            remote.lookupPathInput,
            remote.formPathInput,
            remote.historyPathInput,
            popup.postCallTimeoutInput,
            callLog.homeCallPageSizeInput,
            popupFilter.contactGroupsInput,
        ).forEach { input -> input.autoSaveTextChanges() }

        MainCallLogOverlaySettings.wire(
            activity = this,
            binding = binding,
            saveOverlaySettings = ::saveCallLogOverlayButtonSettings,
            requestOverlayPermissionIfNeeded = { permissionFlowController.requestOverlayPermissionIfNeeded() },
            setStatus = ::setStatus,
        )

        popup.postCallEndActionGroup.setOnCheckedChangeListener { _, _ -> autoSaveSettings() }
        popup.useOverlayPopupsCheckBox.setOnCheckedChangeListener { _, isChecked ->
            popup.overlayPopupOptionsGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            autoSaveSettings()
            if (isChecked) permissionFlowController.requestOverlayPermissionIfNeeded()
        }
        popup.useCustomStartPopupCheckBox.autoSaveCheckedChanges()
        popup.useCustomEndPopupCheckBox.autoSaveCheckedChanges()

        contactLink.showCrmActionButtonsCheckBox.autoSaveCheckedChanges()
        contactLink.contactLinkModeGroup.setOnCheckedChangeListener { _, _ -> autoSaveSettings() }

        language.appLanguageGroup.setOnCheckedChangeListener { _, _ ->
            val config = autoSaveSettings()
            applyLanguageIfChanged(config.appLanguage)
        }
        storage.usePublicNotesFolderCheckBox.setOnCheckedChangeListener { _, isChecked ->
            autoSaveSettings()
            if (isChecked) permissionFlowController.start()
        }

        popupFilter.notifyUnknownContactsCheckBox.autoSaveCheckedChanges()
        popupFilter.notifyKnownContactsCheckBox.autoSaveCheckedChanges()

        permissions.useCallScreeningCheckBox.setOnCheckedChangeListener { _, isChecked ->
            autoSaveSettings()
            if (isChecked) permissionFlowController.requestCallScreeningRoleIfNeeded()
        }
    }

    private fun saveCallLogOverlayButtonSettings() {
        MainCallLogOverlaySettings.save(this, binding, suppressAutoSave)
    }

    private fun TextInputEditText.autoSaveTextChanges() {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                autoSaveSettings()
            }
        })
    }

    private fun CompoundButton.autoSaveCheckedChanges() {
        setOnCheckedChangeListener { _, _ -> autoSaveSettings() }
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
        saveCallLogOverlayButtonSettings()
        return ConfigStore.load(this)
    }

    private fun applyLanguageIfChanged(language: String) {
        if (language == currentLanguage) return
        currentLanguage = language
        AppLanguageManager.applyFromConfig(this)
        recreate()
    }

    private fun setStatus(message: String) {
        binding.statusText.visibility = View.VISIBLE
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

    private fun debugCrmContactName() {
        val phone = binding.testsSection.phoneInput.text?.toString().orEmpty()
        val report = CrmContactDebugInspector.inspect(this, phone)
        AlertDialog.Builder(this)
            .setTitle("RM record check")
            .setMessage(report)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun canUsePublicNotesFolder(): Boolean = MainStorageSettings.canUsePublicNotesFolder(this)
    private fun disablePublicNotesFolder() = MainStorageSettings.disablePublicNotesFolder(this)
    private fun disableOverlayPopups() = MainPopupSettings.disableOverlayPopups(this)
    private fun disableCallScreening() = MainPermissionSettings.disableCallScreening(this)
    private fun refreshPermissionSummary() = MainPermissionSummary.refresh(this, binding)
    private fun renderBuildVersion() = MainBuildVersion.render(this, binding)
    private fun testStartPopup() = MainTestActions.testStartPopup(this, binding, executor, ::setStatus)
    private fun testEndPopup() = MainTestActions.testEndPopup(this, binding, executor, ::setStatus)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
