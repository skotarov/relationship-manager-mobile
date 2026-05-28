package com.onlineimoti.calllog

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
        contactsCleanupController.addProgressBar()
        startPermissionFlow()

        binding.remoteEnabledCheckBox.setOnCheckedChangeListener { _, isChecked ->
            binding.remoteSettingsGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.openAppPermissionsButton.setOnClickListener { startPermissionFlow() }
        binding.openCallScreeningButton.setOnClickListener { requestCallScreeningRoleIfNeeded() }
        binding.openFullscreenIntentButton.setOnClickListener { requestFullScreenIntentPermissionIfNeeded() }
        binding.cleanupContactsButton.setOnClickListener { contactsCleanupController.cleanupCallReportContacts() }
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
        MainSettingsConfigUi.hydrate(binding, ConfigStore.load(this))
    }

    private fun saveConfig(): AppConfig {
        val config = MainSettingsConfigUi.read(binding)
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
}
