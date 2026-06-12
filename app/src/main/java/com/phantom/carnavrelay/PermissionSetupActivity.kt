package com.phantom.carnavrelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class PermissionSetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PHANTOM_GO"
    }

    private lateinit var prefsManager: PrefsManager
    private lateinit var overlayController: OverlayController
    private lateinit var tvCameraStatus: TextView
    private lateinit var tvNotificationStatus: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvBackgroundLocationStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvSystemSettingsStatus: TextView
    private lateinit var tvDeviceAdminStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvPermissionSummary: TextView
    private lateinit var btnGrantPermissions: MaterialButton
    private lateinit var btnContinueToApp: MaterialButton
    private var continueFlowAfterOverlay = false
    private var continueFlowAfterBackgroundLocationSettings = false

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionState()
        if (hasMissingRuntimePermissions() && hasPermanentlyDeniedRuntimePermission()) {
            showAppSettingsDialog()
        } else {
            requestBackgroundLocationIfNeeded()
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionState()
        if (continueFlowAfterOverlay) {
            continueFlowAfterOverlay = false
            requestWriteSettingsIfNeeded()
        }
    }

    private val writeSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionState()
        requestDeviceAdminIfNeeded()
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionState()
        requestBatteryOptimizationIfNeeded()
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updatePermissionState()
        requestOverlayAccessIfNeeded()
    }

    private val batterySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_setup)

        prefsManager = PrefsManager(this)
        overlayController = OverlayController(this)

        tvCameraStatus = findViewById(R.id.tvCameraStatus)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        tvLocationStatus = findViewById(R.id.tvLocationStatus)
        tvBackgroundLocationStatus = findViewById(R.id.tvBackgroundLocationStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvSystemSettingsStatus = findViewById(R.id.tvSystemSettingsStatus)
        tvDeviceAdminStatus = findViewById(R.id.tvDeviceAdminStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvPermissionSummary = findViewById(R.id.tvPermissionSummary)
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        btnContinueToApp = findViewById(R.id.btnContinueToApp)

        btnGrantPermissions.setOnClickListener {
            startPermissionFlow()
        }

        btnContinueToApp.setOnClickListener {
            prefsManager.markInitialPermissionSetupShown()
            finish()
        }

        updatePermissionState()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        if (continueFlowAfterBackgroundLocationSettings) {
            continueFlowAfterBackgroundLocationSettings = false
            requestOverlayAccessIfNeeded()
        }
    }

    private fun startPermissionFlow() {
        val runtimePermissions = runtimePermissionsToRequest()
        if (runtimePermissions.isNotEmpty()) {
            runtimePermissionsLauncher.launch(runtimePermissions.toTypedArray())
            return
        }
        requestBackgroundLocationIfNeeded()
    }

    private fun requestOverlayAccessIfNeeded() {
        if (overlayController.hasOverlayPermission()) {
            requestWriteSettingsIfNeeded()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.setup_allow_overlay_title)
            .setMessage(R.string.setup_allow_overlay_msg)
            .setPositiveButton(R.string.common_open_settings) { _, _ ->
                continueFlowAfterOverlay = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlaySettingsLauncher.launch(intent)
            }
            .setNegativeButton(R.string.common_skip) { _, _ ->
                requestWriteSettingsIfNeeded()
            }
            .show()
    }

    private fun requestWriteSettingsIfNeeded() {
        if (DisplayPowerAutomationManager.canWriteSystemSettings(this)) {
            requestDeviceAdminIfNeeded()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.setup_allow_modify_title)
            .setMessage(R.string.setup_allow_modify_msg)
            .setPositiveButton(R.string.common_open_settings) { _, _ ->
                writeSettingsLauncher.launch(DisplayPowerAutomationManager.buildWriteSettingsIntent(this))
            }
            .setNegativeButton(R.string.common_skip) { _, _ ->
                requestDeviceAdminIfNeeded()
            }
            .show()
    }

    private fun requestDeviceAdminIfNeeded() {
        if (DisplayPowerAutomationManager.isDeviceAdminActive(this)) {
            requestBatteryOptimizationIfNeeded()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.setup_optional_device_admin_title)
            .setMessage(R.string.setup_optional_device_admin_msg)
            .setPositiveButton(R.string.common_enable) { _, _ ->
                deviceAdminLauncher.launch(DisplayPowerAutomationManager.buildDeviceAdminIntent(this))
            }
            .setNegativeButton(R.string.common_skip) { _, _ ->
                requestBatteryOptimizationIfNeeded()
            }
            .show()
    }

    private fun requestBatteryOptimizationIfNeeded() {
        if (isIgnoringBatteryOptimizations()) {
            updatePermissionState()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.setup_disable_battery_title)
            .setMessage(R.string.setup_disable_battery_msg)
            .setPositiveButton(R.string.common_open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batterySettingsLauncher.launch(intent)
            }
            .setNegativeButton(R.string.common_skip, null)
            .show()
    }

    private fun updatePermissionState() {
        val cameraGranted = isPermissionGranted(Manifest.permission.CAMERA)
        val notificationsGranted = NotificationHelper.canPostNotifications(this)
        val locationGranted = DisplaySpeedMonitor.hasLocationPermission(this)
        val backgroundLocationGranted = DisplaySpeedMonitor.hasBackgroundLocationPermission(this)
        val overlayGranted = overlayController.hasOverlayPermission()
        val systemSettingsGranted = DisplayPowerAutomationManager.canWriteSystemSettings(this)
        val deviceAdminGranted = DisplayPowerAutomationManager.isDeviceAdminActive(this)
        val batteryGranted = isIgnoringBatteryOptimizations()

        updateStatusLine(tvCameraStatus, cameraGranted, getString(R.string.setup_granted), getString(R.string.setup_needs_action))
        updateStatusLine(tvNotificationStatus, notificationsGranted, getString(R.string.setup_granted), getString(R.string.setup_recommended))
        updateStatusLine(tvLocationStatus, locationGranted, getString(R.string.setup_granted), getString(R.string.setup_recommended))
        updateStatusLine(tvBackgroundLocationStatus, backgroundLocationGranted, getString(R.string.setup_granted), getString(R.string.setup_recommended))
        updateStatusLine(tvOverlayStatus, overlayGranted, getString(R.string.setup_granted), getString(R.string.setup_recommended))
        updateStatusLine(tvSystemSettingsStatus, systemSettingsGranted, getString(R.string.setup_granted), getString(R.string.setup_recommended))
        updateStatusLine(tvDeviceAdminStatus, deviceAdminGranted, getString(R.string.setup_enabled), getString(R.string.common_optional))
        updateStatusLine(tvBatteryStatus, batteryGranted, getString(R.string.setup_granted), getString(R.string.setup_recommended))

        val missingCount = listOf(
            cameraGranted,
            notificationsGranted,
            locationGranted,
            backgroundLocationGranted,
            overlayGranted,
            systemSettingsGranted,
            batteryGranted
        ).count { !it }
        tvPermissionSummary.text = if (missingCount == 0) {
            getString(R.string.setup_all_permissions_ready)
        } else {
            getString(R.string.setup_missing_attention_fmt, missingCount)
        }

        btnGrantPermissions.text = if (missingCount == 0) {
            getString(R.string.setup_all_permissions_button)
        } else {
            getString(R.string.setup_grant_missing_permissions)
        }
        btnGrantPermissions.isEnabled = missingCount > 0
        btnGrantPermissions.alpha = if (btnGrantPermissions.isEnabled) 1f else 0.52f
    }

    private fun updateStatusLine(view: TextView, granted: Boolean, grantedText: String, missingText: String) {
        view.text = if (granted) grantedText else missingText
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (granted) R.color.aurora_success else R.color.aurora_warning
            )
        )
    }

    private fun runtimePermissionsToRequest(): List<String> {
        val permissions = mutableListOf<String>()

        if (!isPermissionGranted(Manifest.permission.CAMERA)) {
            permissions += Manifest.permission.CAMERA
        }
        if (!isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        return permissions
    }

    private fun hasMissingRuntimePermissions(): Boolean {
        return runtimePermissionsToRequest().isNotEmpty()
    }

    private fun hasPermanentlyDeniedRuntimePermission(): Boolean {
        return runtimePermissionsToRequest().any { permission ->
            !shouldShowRequestPermissionRationale(permission)
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        } else {
            true
        }
    }

    private fun showAppSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_open_app_settings_title)
            .setMessage(R.string.setup_open_app_settings_msg)
            .setPositiveButton(R.string.common_open_settings) { _, _ ->
                continueFlowAfterBackgroundLocationSettings = true
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (!DisplaySpeedMonitor.hasLocationPermission(this)) {
            requestOverlayAccessIfNeeded()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            DisplaySpeedMonitor.hasBackgroundLocationPermission(this)
        ) {
            requestOverlayAccessIfNeeded()
            return
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.setup_allow_all_time_title)
            .setMessage(R.string.setup_allow_all_time_msg)
            .setPositiveButton(R.string.common_open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.common_skip) { _, _ ->
                requestOverlayAccessIfNeeded()
            }
            .show()
    }
}
