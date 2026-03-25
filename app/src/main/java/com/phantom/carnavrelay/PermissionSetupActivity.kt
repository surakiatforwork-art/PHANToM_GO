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
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvPermissionSummary: TextView
    private lateinit var btnGrantPermissions: MaterialButton
    private lateinit var btnContinueToApp: MaterialButton
    private var continueFlowAfterOverlay = false

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionState()
        if (hasMissingRuntimePermissions() && hasPermanentlyDeniedRuntimePermission()) {
            showAppSettingsDialog()
        } else {
            requestOverlayAccessIfNeeded()
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionState()
        if (continueFlowAfterOverlay) {
            continueFlowAfterOverlay = false
            requestBatteryOptimizationIfNeeded()
        }
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
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvPermissionSummary = findViewById(R.id.tvPermissionSummary)
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions)
        btnContinueToApp = findViewById(R.id.btnContinueToApp)

        btnGrantPermissions.setOnClickListener {
            startPermissionFlow()
        }

        btnContinueToApp.setOnClickListener {
            finish()
        }

        updatePermissionState()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
    }

    private fun startPermissionFlow() {
        val runtimePermissions = runtimePermissionsToRequest()
        if (runtimePermissions.isNotEmpty()) {
            runtimePermissionsLauncher.launch(runtimePermissions.toTypedArray())
            return
        }
        requestOverlayAccessIfNeeded()
    }

    private fun requestOverlayAccessIfNeeded() {
        if (overlayController.hasOverlayPermission()) {
            requestBatteryOptimizationIfNeeded()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Allow Overlay")
            .setMessage("PHANToM GO can show a floating navigation overlay above other apps. Open the system page to allow overlay access?")
            .setPositiveButton("Open Settings") { _, _ ->
                continueFlowAfterOverlay = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlaySettingsLauncher.launch(intent)
            }
            .setNegativeButton("Skip") { _, _ ->
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
            .setTitle("Disable Battery Optimization")
            .setMessage("Display mode works more reliably if Android does not optimize PHANToM GO in the background. Open the system page now?")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batterySettingsLauncher.launch(intent)
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun updatePermissionState() {
        val cameraGranted = isPermissionGranted(Manifest.permission.CAMERA)
        val notificationsGranted = NotificationHelper.canPostNotifications(this)
        val overlayGranted = overlayController.hasOverlayPermission()
        val batteryGranted = isIgnoringBatteryOptimizations()

        updateStatusLine(tvCameraStatus, cameraGranted, "Granted", "Needs action")
        updateStatusLine(tvNotificationStatus, notificationsGranted, "Granted", "Recommended")
        updateStatusLine(tvOverlayStatus, overlayGranted, "Granted", "Recommended")
        updateStatusLine(tvBatteryStatus, batteryGranted, "Granted", "Recommended")

        val missingCount = listOf(cameraGranted, notificationsGranted, overlayGranted, batteryGranted).count { !it }
        tvPermissionSummary.text = if (missingCount == 0) {
            "All requested permissions are ready. You can start using the app now."
        } else {
            "$missingCount item(s) still need attention. You can continue now and adjust any remaining permission later in Settings."
        }

        btnGrantPermissions.text = if (missingCount == 0) {
            "All Permissions Ready"
        } else {
            "Grant Missing Permissions"
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
            .setTitle("Open App Settings")
            .setMessage("Some Android permissions were denied permanently. Open App Settings to allow them manually.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
