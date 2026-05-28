package com.onlineimoti.calllog

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity

internal class MainPermissionFlowController(
    private val activity: AppCompatActivity,
    private val requestPermissionLauncher: ActivityResultLauncher<String>,
    private val callScreeningRoleLauncher: ActivityResultLauncher<Intent>,
    private val storageSettingsLauncher: ActivityResultLauncher<Intent>,
    private val overlaySettingsLauncher: ActivityResultLauncher<Intent>,
    private val fullscreenIntentSettingsLauncher: ActivityResultLauncher<Intent>,
    private val hasPermission: (String) -> Boolean,
    private val canUsePublicNotesFolder: () -> Boolean,
    private val refreshPermissionSummary: () -> Unit,
    private val setStatus: (String) -> Unit,
) {
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        requestNextStep()
    }

    fun onPermissionResult() {
        refreshPermissionSummary()
        requestNextStep()
    }

    fun onCallScreeningResult() {
        if (hasCallScreeningRole()) setStatus("Call screening role е активирана.")
        refreshPermissionSummary()
        requestNextStep()
    }

    fun onStorageSettingsResult() {
        if (canUsePublicNotesFolder()) setStatus("Публичната папка за бележки е достъпна: ${LocalNotesFileStore.publicRootPath()}")
        refreshPermissionSummary()
        requestNextStep()
    }

    fun onOverlaySettingsResult() {
        if (Settings.canDrawOverlays(activity)) setStatus("Разрешението за кръгла floating икона е дадено.")
        refreshPermissionSummary()
        requestNextStep()
    }

    fun onFullscreenIntentSettingsResult() {
        if (canUseFullScreenIntent()) setStatus("Разрешението за full-screen call report popup е дадено.")
        refreshPermissionSummary()
        isRunning = false
    }

    fun requestNextStep() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS) -> {
                setStatus("Разреши notifications, за да могат popup-ите да се показват.")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            !hasPermission(Manifest.permission.READ_PHONE_STATE) -> {
                setStatus("Разреши Phone, за да засичаме начало и край на разговор.")
                requestPermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            }
            !hasPermission(Manifest.permission.READ_CALL_LOG) -> {
                setStatus("Разреши Call log, за да виждаме последните разговори.")
                requestPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
            }
            !hasPermission(Manifest.permission.READ_CONTACTS) -> {
                setStatus("Разреши Contacts, за да показваме имена вместо само номера.")
                requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
            !hasPermission(Manifest.permission.WRITE_CONTACTS) -> {
                setStatus("Разреши Contacts write за съвместимост със старите бележки към контакти.")
                requestPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
            }
            !canUsePublicNotesFolder() -> {
                setStatus("Разреши достъп до всички файлове, за да пазим бележките в публичната папка ${LocalNotesFileStore.publicRootPath()}.")
                requestStorageManagerPermissionIfNeeded()
            }
            !Settings.canDrawOverlays(activity) -> {
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
            else -> finishFlowWithSuccess()
        }
    }

    fun requestCallScreeningRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasCallScreeningRole()) {
            finishFlowWithoutStatus()
            return
        }
        val roleManager = activity.getSystemService(RoleManager::class.java) ?: run {
            isRunning = false
            return
        }
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            finishFlowWithoutStatus()
            return
        }
        callScreeningRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    fun requestFullScreenIntentPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || canUseFullScreenIntent()) {
            finishFlowWithoutStatus()
            return
        }
        fullscreenIntentSettingsLauncher.launch(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply { data = Uri.parse("package:${activity.packageName}") }
        )
    }

    private fun requestStorageManagerPermissionIfNeeded() {
        if (canUsePublicNotesFolder()) {
            requestNextStep()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageSettingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${activity.packageName}") }
            )
        } else {
            requestNextStep()
        }
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Settings.canDrawOverlays(activity)) {
            requestNextStep()
            return
        }
        overlaySettingsLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = Uri.parse("package:${activity.packageName}") }
        )
    }

    private fun hasCallScreeningRole(): Boolean = MainPermissionChecks.hasCallScreeningRole(activity)
    private fun canUseFullScreenIntent(): Boolean = MainPermissionChecks.canUseFullScreenIntent(activity)

    private fun finishFlowWithSuccess() {
        isRunning = false
        setStatus("Основните разрешения са проверени. Бележките са в ${LocalNotesFileStore.publicRootPath()}.")
        refreshPermissionSummary()
    }

    private fun finishFlowWithoutStatus() {
        isRunning = false
        refreshPermissionSummary()
    }
}
