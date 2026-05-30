package com.onlineimoti.calllog

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
        wireSettingsAutoSave()
        permissionFlowController.start()

        val quickStartButton = findViewById<MaterialButton>(R.id.testStartPopupButton)
        val quickEndButton = findViewById<MaterialButton>(R.id.testEndPopupButton)

        binding.backToHomeButton.setOnClickListener { finish() }
        binding.permissionsSection.openAppPermissionsButton.setOnClickListener { permissionFlowController.start() }
        binding.permissionsSection.openOverlayPermissionButton.setOnClickListener { permissionFlowController.requestOverlayPermissionIfNeeded() }
        binding.permissionsSection.openCallScreeningButton.setOnClickListener {
            saveConfig()
            permissionFlowController.requestCallScreeningRoleIfNeeded()
        }
        binding.permissionsSection.openFullscreenIntentButton.setOnClickListener { permissionFlowController.requestFullScreenIntentPermissionIfNeeded() }
        binding.permissionsSection.cleanupContactsButton.setOnClickListener { contactsCleanupController.cleanupCallReportContacts() }
        binding.remoteSettingsSection.saveServerSettingsButton.setOnClickListener {
            saveConfig()
            setStatus("Server настройките са записани.")
            refreshPermissionSummary()
        }
        binding.testsSection.openFormButton.setOnClickListener {
            saveConfig()
            openFormDirect()
        }
        quickStartButton.setOnClickListener {
            saveConfig()
            testStartPopup()
        }
        quickEndButton.setOnClickListener {
            saveConfig()
            testEndPopup()
        }
        binding.testsSection.testFullLogButton.setOnClickListener {
            saveConfig()
            openFullLogDirect()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionSummary()
    }

    private fun hydrateFields() {
        MainSettingsConfigUi.hydrate(binding, ConfigStore.load(this))
    }

    private fun wireSettingsAutoSave() {
        val remote = binding.remoteSettingsSection
        val popup = binding.popupSettingsSection
        val contactFilter = binding.contactFilterSection
        val contactLink = binding.contactLinkSection
        val storage = binding.storageSettingsSection
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
            contactFilter.homeCallPageSizeInput,
            contactFilter.contactGroupsInput,
        ).forEach { input -> input.autoSaveTextChanges() }

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

        storage.appLanguageGroup.setOnCheckedChangeListener { _, _ ->
            val config = autoSaveSettings()
            applyLanguageIfChanged(config.appLanguage)
        }
        storage.usePublicNotesFolderCheckBox.setOnCheckedChangeListener { _, isChecked ->
            autoSaveSettings()
            if (isChecked) permissionFlowController.start()
        }

        contactFilter.notifyUnknownContactsCheckBox.autoSaveCheckedChanges()
        contactFilter.notifyKnownContactsCheckBox.autoSaveCheckedChanges()

        permissions.useCallScreeningCheckBox.setOnCheckedChangeListener { _, isChecked ->
            autoSaveSettings()
            if (isChecked) permissionFlowController.requestCallScreeningRoleIfNeeded()
        }
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
        return ConfigStore.load(this)
    }

    private fun applyLanguageIfChanged(language: String) {
        if (language == currentLanguage) return
        currentLanguage = language
        AppLanguageManager.applyLanguage(language)
        recreate()
    }

    private fun disablePublicNotesFolder() {
        suppressAutoSave = true
        binding.storageSettingsSection.usePublicNotesFolderCheckBox.isChecked = false
        suppressAutoSave = false
        saveConfig()
        refreshPermissionSummary()
    }

    private fun disableOverlayPopups() {
        suppressAutoSave = true
        binding.popupSettingsSection.useOverlayPopupsCheckBox.isChecked = false
        binding.popupSettingsSection.overlayPopupOptionsGroup.visibility = View.GONE
        suppressAutoSave = false
        saveConfig()
        refreshPermissionSummary()
    }

    private fun disableCallScreening() {
        suppressAutoSave = true
        binding.permissionsSection.useCallScreeningCheckBox.isChecked = false
        suppressAutoSave = false
        saveConfig()
        refreshPermissionSummary()
    }

    private fun directionValue(): String = if (binding.testsSection.directionIn.isChecked) "in" else "out"
    private fun phoneValue(): String = binding.testsSection.phoneInput.text?.toString()?.trim().orEmpty()

    private fun remoteReady(config: AppConfig): Boolean {
        return config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()
    }

    private fun openFormDirect() {
        val config = ConfigStore.load(this)
        MainRemoteActions.openFormDirect(
            activity = this,
            config = config,
            phone = phoneValue(),
            direction = directionValue(),
            remoteReady = remoteReady(config),
            setStatus = ::setStatus,
        )
    }

    private fun openFullLogDirect() {
        val config = ConfigStore.load(this)
        MainRemoteActions.openFullLogDirect(
            activity = this,
            config = config,
            phone = phoneValue(),
            direction = directionValue(),
            remoteReady = remoteReady(config),
            setStatus = ::setStatus,
        )
    }

    private fun testStartPopup() {
        val config = ConfigStore.load(this)
        MainTestPopupActions.testStartPopup(
            activity = this,
            binding = binding,
            executor = executor,
            config = config,
            phone = phoneValue(),
            direction = directionValue(),
            remoteReady = remoteReady(config),
            setStatus = ::setStatus,
        )
    }

    private fun testEndPopup() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        MainTestPopupActions.testEndPopup(
            activity = this,
            binding = binding,
            executor = executor,
            phone = phone,
            direction = directionValue(),
            formUrl = if (remoteReady(config)) MainRemoteActions.buildFormUrl(config, phone, directionValue()) else "",
            setStatus = ::setStatus,
        )
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
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}