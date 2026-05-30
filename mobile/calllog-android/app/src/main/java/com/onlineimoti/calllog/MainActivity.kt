package com.onlineimoti.calllog

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CallReportRuntime.ensureNotificationChannel(this)
        hydrateFields()
        refreshPermissionSummary()
        renderBuildVersion()
        contactsCleanupController.addProgressBar()
        permissionFlowController.start()

        binding.backToHomeButton.setOnClickListener { finish() }
        binding.remoteSettingsSection.remoteEnabledCheckBox.setOnCheckedChangeListener { _, isChecked ->
            binding.remoteSettingsSection.remoteSettingsGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.popupSettingsSection.useOverlayPopupsCheckBox.setOnCheckedChangeListener { _, isChecked ->
            binding.popupSettingsSection.overlayPopupOptionsGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            refreshPermissionSummary()
            if (isChecked) permissionFlowController.requestOverlayPermissionIfNeeded()
        }
        binding.storageSettingsSection.usePublicNotesFolderCheckBox.setOnCheckedChangeListener { _, isChecked ->
            saveConfig()
            refreshPermissionSummary()
            if (isChecked) permissionFlowController.start()
        }
        binding.permissionsSection.useCallScreeningCheckBox.setOnCheckedChangeListener { _, isChecked ->
            saveConfig()
            refreshPermissionSummary()
            if (isChecked) permissionFlowController.requestCallScreeningRoleIfNeeded()
        }
        binding.permissionsSection.openAppPermissionsButton.setOnClickListener { permissionFlowController.start() }
        binding.permissionsSection.openOverlayPermissionButton.setOnClickListener { permissionFlowController.requestOverlayPermissionIfNeeded() }
        binding.permissionsSection.openCallScreeningButton.setOnClickListener {
            saveConfig()
            permissionFlowController.requestCallScreeningRoleIfNeeded()
        }
        binding.permissionsSection.openFullscreenIntentButton.setOnClickListener { permissionFlowController.requestFullScreenIntentPermissionIfNeeded() }
        binding.permissionsSection.cleanupContactsButton.setOnClickListener { contactsCleanupController.cleanupCallReportContacts() }
        binding.saveSettingsButton.setOnClickListener {
            saveConfig()
            setStatus("Настройките са записани локално. Бележките са в ${LocalNotesFileStore.activeRootPath(this)}")
            refreshPermissionSummary()
        }
        binding.testsSection.openFormButton.setOnClickListener {
            saveConfig()
            openFormDirect()
        }
        binding.testsSection.testStartPopupButton.setOnClickListener {
            saveConfig()
            testStartPopup()
        }
        binding.testsSection.testEndPopupButton.setOnClickListener {
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

    private fun saveConfig(): AppConfig {
        val config = MainSettingsConfigUi.read(binding)
        ConfigStore.save(this, config)
        return ConfigStore.load(this)
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