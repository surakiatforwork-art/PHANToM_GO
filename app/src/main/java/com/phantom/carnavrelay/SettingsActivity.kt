package com.phantom.carnavrelay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val PREF_OVERLAY_ENABLED = "overlay_enabled"
        private const val MAX_LOG_ENTRIES = 500
    }

    private lateinit var prefsManager: PrefsManager
    private lateinit var overlayController: OverlayController
    private lateinit var overlaySwitch: Switch
    private lateinit var overlayStatusText: TextView
    private lateinit var mapLinkHubSwitch: Switch
    private lateinit var mapLinkHubStatusText: TextView
    private lateinit var mapLinkHubInstructionText: TextView
    private lateinit var mapLinkHubDefaultStatusText: TextView
    private lateinit var deviceModeStatusText: TextView
    private lateinit var openByDefaultButton: Button
    private lateinit var transportModeStatusText: TextView
    private lateinit var transportModeGroup: android.widget.RadioGroup
    private lateinit var transportModeAuto: android.widget.RadioButton
    private lateinit var transportModeWifi: android.widget.RadioButton
    private lateinit var transportModeBluetooth: android.widget.RadioButton
    private lateinit var bluetoothTargetStatusText: TextView
    private lateinit var pickBluetoothDeviceButton: Button
    private lateinit var clearBluetoothDeviceButton: Button
    private lateinit var smartModeSwitch: Switch
    private lateinit var smartModeStatusText: TextView
    private lateinit var smartPolicyGroup: android.widget.RadioGroup
    private lateinit var policyAutoSendNavOnly: android.widget.RadioButton
    private lateinit var policyAlwaysAsk: android.widget.RadioButton
    private lateinit var policyAlwaysSend: android.widget.RadioButton
    private lateinit var policyAlwaysOpenOnPhone: android.widget.RadioButton
    private lateinit var afterSendGroup: android.widget.RadioGroup
    private lateinit var afterSendExit: android.widget.RadioButton
    private lateinit var afterSendStay: android.widget.RadioButton
    private lateinit var openMapsAfterSendSwitch: Switch
    private lateinit var displayNavModeGroup: android.widget.RadioGroup
    private lateinit var displayNavModeCar: android.widget.RadioButton
    private lateinit var displayNavModeMotorcycle: android.widget.RadioButton
    private lateinit var displayNavModeStatusText: TextView
    private lateinit var displayOpenModeGroup: android.widget.RadioGroup
    private lateinit var displayOpenModePreview: android.widget.RadioButton
    private lateinit var displayOpenModeNavigate: android.widget.RadioButton
    private lateinit var displayOpenModeStatusText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var logText: TextView
    private lateinit var copyLogButton: Button
    private lateinit var clearLogButton: Button
    private lateinit var grantPermissionButton: Button
    private lateinit var notificationStatusText: TextView
    private lateinit var requestNotificationButton: Button
    private lateinit var backButton: Button
    private var notificationRequestInFlight = false
    private var updatingMapHubUi = false
    private var updatingOpenMapsUi = false
    private var updatingSmartModeUi = false
    private var updatingPolicyUi = false
    private var updatingAfterSendUi = false
    private var updatingTransportUi = false
    private var updatingDisplayNavModeUi = false
    private var updatingDisplayOpenModeUi = false
    private var bluetoothPickPending = false

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationRequestInFlight = false
        updateNotificationStatus()

        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shouldShow = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (!shouldShow) {
                showNotificationSettingsDialog()
            }
        }
    }

    private val requestBluetoothPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (!granted) {
            Toast.makeText(this, "Bluetooth permission required to select device", Toast.LENGTH_SHORT).show()
            bluetoothPickPending = false
            return@registerForActivityResult
        }
        if (bluetoothPickPending) {
            bluetoothPickPending = false
            showBluetoothDevicePicker()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "⚙️ SettingsActivity onCreate")
        setContentView(R.layout.activity_settings)
        
        prefsManager = PrefsManager(this)
        overlayController = OverlayController(this)
        initViews()
        setupClickListeners()
        updateOverlayStatus()
        updateMapLinkHubStatus()
        updateTransportStatus()
        updateSmartModeStatus()
        updateDisplayNavModeStatus()
        updateDisplayOpenModeStatus()
        updateNotificationStatus()
        loadLogs()
    }

    private fun initViews() {
        overlaySwitch = findViewById(R.id.overlaySwitch)
        overlayStatusText = findViewById(R.id.overlayStatusText)
        mapLinkHubSwitch = findViewById(R.id.mapLinkHubSwitch)
        mapLinkHubStatusText = findViewById(R.id.mapLinkHubStatusText)
        mapLinkHubInstructionText = findViewById(R.id.mapLinkHubInstructionText)
        mapLinkHubDefaultStatusText = findViewById(R.id.mapLinkHubDefaultStatusText)
        deviceModeStatusText = findViewById(R.id.deviceModeStatusText)
        openByDefaultButton = findViewById(R.id.openByDefaultButton)
        transportModeStatusText = findViewById(R.id.transportModeStatusText)
        transportModeGroup = findViewById(R.id.transportModeGroup)
        transportModeAuto = findViewById(R.id.transportModeAuto)
        transportModeWifi = findViewById(R.id.transportModeWifi)
        transportModeBluetooth = findViewById(R.id.transportModeBluetooth)
        bluetoothTargetStatusText = findViewById(R.id.bluetoothTargetStatusText)
        pickBluetoothDeviceButton = findViewById(R.id.pickBluetoothDeviceButton)
        clearBluetoothDeviceButton = findViewById(R.id.clearBluetoothDeviceButton)
        smartModeSwitch = findViewById(R.id.smartModeSwitch)
        smartModeStatusText = findViewById(R.id.smartModeStatusText)
        smartPolicyGroup = findViewById(R.id.smartPolicyGroup)
        policyAutoSendNavOnly = findViewById(R.id.policyAutoSendNavOnly)
        policyAlwaysAsk = findViewById(R.id.policyAlwaysAsk)
        policyAlwaysSend = findViewById(R.id.policyAlwaysSend)
        policyAlwaysOpenOnPhone = findViewById(R.id.policyAlwaysOpenOnPhone)
        afterSendGroup = findViewById(R.id.afterSendGroup)
        afterSendExit = findViewById(R.id.afterSendExit)
        afterSendStay = findViewById(R.id.afterSendStay)
        openMapsAfterSendSwitch = findViewById(R.id.openMapsAfterSendSwitch)
        displayNavModeGroup = findViewById(R.id.displayNavModeGroup)
        displayNavModeCar = findViewById(R.id.displayNavModeCar)
        displayNavModeMotorcycle = findViewById(R.id.displayNavModeMotorcycle)
        displayNavModeStatusText = findViewById(R.id.displayNavModeStatusText)
        displayOpenModeGroup = findViewById(R.id.displayOpenModeGroup)
        displayOpenModePreview = findViewById(R.id.displayOpenModePreview)
        displayOpenModeNavigate = findViewById(R.id.displayOpenModeNavigate)
        displayOpenModeStatusText = findViewById(R.id.displayOpenModeStatusText)
        logScrollView = findViewById(R.id.logScrollView)
        logText = findViewById(R.id.logText)
        copyLogButton = findViewById(R.id.copyLogButton)
        clearLogButton = findViewById(R.id.clearLogButton)
        grantPermissionButton = findViewById(R.id.grantPermissionButton)
        notificationStatusText = findViewById(R.id.notificationStatusText)
        requestNotificationButton = findViewById(R.id.requestNotificationButton)
        backButton = findViewById(R.id.backButton)
    }

    private fun setupClickListeners() {
        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "🔄 Overlay toggle changed: $isChecked")
            overlayController.setOverlayEnabled(isChecked)
            updateOverlayStatus()
            
            // Haptic feedback
            overlaySwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        mapLinkHubSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingMapHubUi) return@setOnCheckedChangeListener
            Log.d(TAG, "🗺️ Map Link Hub toggle changed: $isChecked")
            prefsManager.setMapLinkHubEnabled(isChecked)
            setMapLinkHandlerEnabled(isChecked)
            updateMapLinkHubStatus()
            mapLinkHubSwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        transportModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingTransportUi) return@setOnCheckedChangeListener
            // Bluetooth transport disabled; keep Wi‑Fi selected.
            prefsManager.setConnectionTransport(PrefsManager.TRANSPORT_WIFI)
            updateTransportStatus()
        }

        pickBluetoothDeviceButton.setOnClickListener {
            requestBluetoothDeviceSelection()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        clearBluetoothDeviceButton.setOnClickListener {
            prefsManager.clearBluetoothTarget()
            updateTransportStatus()
            Toast.makeText(this, "Bluetooth target cleared", Toast.LENGTH_SHORT).show()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        smartModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSmartModeUi) return@setOnCheckedChangeListener
            Log.d(TAG, "🤖 Smart Mode toggle changed: $isChecked")
            prefsManager.setSmartModeEnabled(isChecked)
            updateSmartModeStatus()
            smartModeSwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        smartPolicyGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingPolicyUi) return@setOnCheckedChangeListener
            val policy = when (checkedId) {
                R.id.policyAutoSendNavOnly -> PrefsManager.SMART_POLICY_AUTO_SEND_NAV_ONLY
                R.id.policyAlwaysSend -> PrefsManager.SMART_POLICY_ALWAYS_SEND
                R.id.policyAlwaysOpenOnPhone -> PrefsManager.SMART_POLICY_ALWAYS_OPEN_ON_PHONE
                else -> PrefsManager.SMART_POLICY_ALWAYS_ASK
            }
            prefsManager.setSmartModePolicy(policy)
            updateSmartModeStatus()
        }

        afterSendGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingAfterSendUi) return@setOnCheckedChangeListener
            val behavior = when (checkedId) {
                R.id.afterSendStay -> PrefsManager.AFTER_SEND_STAY_IN_APP
                else -> PrefsManager.AFTER_SEND_EXIT_AND_REMOVE_TASK
            }
            prefsManager.setAfterSendBehavior(behavior)
        }

        openMapsAfterSendSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingOpenMapsUi) return@setOnCheckedChangeListener
            Log.d(TAG, "🧭 Open Maps after send: $isChecked")
            prefsManager.setOpenMapsAfterSendEnabled(isChecked)
            updateMapLinkHubStatus()
            openMapsAfterSendSwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        displayNavModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingDisplayNavModeUi) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.displayNavModeMotorcycle -> PrefsManager.DISPLAY_NAV_MODE_MOTORCYCLE
                else -> PrefsManager.DISPLAY_NAV_MODE_DRIVING
            }
            prefsManager.setDisplayNavMode(mode)
            updateDisplayNavModeStatus()
        }

        displayOpenModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingDisplayOpenModeUi) return@setOnCheckedChangeListener
            val behavior = when (checkedId) {
                R.id.displayOpenModeNavigate -> PrefsManager.DISPLAY_OPEN_BEHAVIOR_START_NAVIGATION
                else -> PrefsManager.DISPLAY_OPEN_BEHAVIOR_PREVIEW_ROUTE
            }
            prefsManager.setDisplayOpenBehavior(behavior)
            updateDisplayOpenModeStatus()
        }

        openByDefaultButton.setOnClickListener {
            openDefaultHandlerSettings()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        copyLogButton.setOnClickListener {
            copyLogToClipboard()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        clearLogButton.setOnClickListener {
            clearLogs()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        grantPermissionButton.setOnClickListener {
            requestOverlayPermission()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        requestNotificationButton.setOnClickListener {
            requestNotificationPermission()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    private fun updateOverlayStatus() {
        val hasPermission = overlayController.hasOverlayPermission()
        val isEnabled = overlayController.isOverlayEnabled()

        overlaySwitch.isChecked = isEnabled && hasPermission
        overlaySwitch.isEnabled = hasPermission

        when {
            !hasPermission -> {
                overlayStatusText.text = "Permission Required"
                overlayStatusText.setTextColor(ContextCompat.getColor(this, R.color.aurora_warning))
                grantPermissionButton.visibility = View.VISIBLE
            }
            isEnabled -> {
                overlayStatusText.text = "Overlay Enabled"
                overlayStatusText.setTextColor(ContextCompat.getColor(this, R.color.aurora_success))
                grantPermissionButton.visibility = View.GONE
            }
            else -> {
                overlayStatusText.text = "Overlay Disabled"
                overlayStatusText.setTextColor(ContextCompat.getColor(this, R.color.aurora_disconnected))
                grantPermissionButton.visibility = View.GONE
            }
        }
    }

    private fun updateMapLinkHubStatus() {
        val enabled = prefsManager.isMapLinkHubEnabled()

        updatingMapHubUi = true
        mapLinkHubSwitch.isChecked = enabled
        updatingMapHubUi = false

        setMapLinkHandlerEnabled(enabled)

        mapLinkHubStatusText.text = if (enabled) "On" else "Off"
        mapLinkHubStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.aurora_success else R.color.aurora_warning
            )
        )

        val deviceMode = prefsManager.getDeviceMode()
        deviceModeStatusText.text = "Device mode: $deviceMode"

        mapLinkHubInstructionText.text = if (enabled) {
            "ไปที่ Settings ของระบบ > Apps > Open by default > เลือก PHANToM GO แล้วกด Always"
        } else {
            "ปิดอยู่: ลิงก์แผนที่จะเปิด Google Maps ตามปกติ"
        }

        mapLinkHubDefaultStatusText.text = "System default: โปรดตั้งค่าในระบบ"

        updatingOpenMapsUi = true
        openMapsAfterSendSwitch.isChecked = prefsManager.isOpenMapsAfterSendEnabled()
        updatingOpenMapsUi = false
    }

    private fun updateTransportStatus() {
        prefsManager.setConnectionTransport(PrefsManager.TRANSPORT_WIFI)
        val targetName = prefsManager.getBluetoothTargetName()
        val targetAddress = prefsManager.getBluetoothTargetAddress()

        updatingTransportUi = true
        transportModeGroup.check(R.id.transportModeWifi)
        updatingTransportUi = false

        transportModeAuto.visibility = View.GONE
        transportModeBluetooth.visibility = View.GONE
        transportModeWifi.text = "Wi‑Fi Local (HTTP) - Recommended"
        transportModeWifi.isEnabled = false
        transportModeGroup.isEnabled = false

        transportModeStatusText.text = "Current transport: WIFI (Bluetooth disabled)"
        transportModeStatusText.setTextColor(
            ContextCompat.getColor(this, R.color.aurora_cyan)
        )

        bluetoothTargetStatusText.text = if (!targetAddress.isNullOrBlank()) {
            "Bluetooth transport disabled (saved target kept: ${targetName ?: "Unknown"})"
        } else {
            "Bluetooth transport disabled"
        }
        bluetoothTargetStatusText.setTextColor(ContextCompat.getColor(this, R.color.aurora_warning))
        pickBluetoothDeviceButton.visibility = View.GONE
        clearBluetoothDeviceButton.visibility = View.GONE
    }

    private fun requestBluetoothDeviceSelection() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Device does not support Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasBluetoothPermissions()) {
            bluetoothPickPending = true
            requestBluetoothPermissionsLauncher.launch(requiredBluetoothPermissions())
            return
        }
        showBluetoothDevicePicker()
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return requiredBluetoothPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyArray()
        }
    }

    @Suppress("MissingPermission")
    private fun showBluetoothDevicePicker() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        if (bonded.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = bonded.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Bluetooth Display")
            .setItems(labels) { _, which ->
                val device = bonded[which]
                prefsManager.saveBluetoothTarget(device.address, device.name)
                updateTransportStatus()
                Toast.makeText(this, "Selected ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateSmartModeStatus() {
        val enabled = prefsManager.isSmartModeEnabled()
        val policy = prefsManager.getSmartModePolicy()

        updatingSmartModeUi = true
        smartModeSwitch.isChecked = enabled
        updatingSmartModeUi = false

        smartModeStatusText.text = if (enabled) "On" else "Off"
        smartModeStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.aurora_success else R.color.aurora_warning
            )
        )

        updatingPolicyUi = true
        smartPolicyGroup.check(
            when (policy) {
                PrefsManager.SMART_POLICY_ALWAYS_SEND -> R.id.policyAlwaysSend
                PrefsManager.SMART_POLICY_ALWAYS_OPEN_ON_PHONE -> R.id.policyAlwaysOpenOnPhone
                PrefsManager.SMART_POLICY_ALWAYS_ASK -> R.id.policyAlwaysAsk
                else -> R.id.policyAutoSendNavOnly
            }
        )
        updatingPolicyUi = false

        smartPolicyGroup.isEnabled = enabled
        policyAutoSendNavOnly.isEnabled = enabled
        policyAlwaysAsk.isEnabled = enabled
        policyAlwaysSend.isEnabled = enabled
        policyAlwaysOpenOnPhone.isEnabled = enabled

        updatingAfterSendUi = true
        afterSendGroup.check(
            when (prefsManager.getAfterSendBehavior()) {
                PrefsManager.AFTER_SEND_STAY_IN_APP -> R.id.afterSendStay
                else -> R.id.afterSendExit
            }
        )
        updatingAfterSendUi = false
    }

    private fun updateDisplayNavModeStatus() {
        val mode = prefsManager.getDisplayNavMode()

        updatingDisplayNavModeUi = true
        displayNavModeGroup.check(
            when (mode) {
                PrefsManager.DISPLAY_NAV_MODE_MOTORCYCLE -> R.id.displayNavModeMotorcycle
                else -> R.id.displayNavModeCar
            }
        )
        updatingDisplayNavModeUi = false

        displayNavModeStatusText.text = when (mode) {
            PrefsManager.DISPLAY_NAV_MODE_MOTORCYCLE -> "Motorcycle"
            else -> "Car"
        }
        displayNavModeStatusText.setTextColor(
            ContextCompat.getColor(this, R.color.aurora_cyan)
        )
    }

    private fun updateDisplayOpenModeStatus() {
        val behavior = prefsManager.getDisplayOpenBehavior()

        updatingDisplayOpenModeUi = true
        displayOpenModeGroup.check(
            when (behavior) {
                PrefsManager.DISPLAY_OPEN_BEHAVIOR_START_NAVIGATION -> R.id.displayOpenModeNavigate
                else -> R.id.displayOpenModePreview
            }
        )
        updatingDisplayOpenModeUi = false

        displayOpenModeStatusText.text = when (behavior) {
            PrefsManager.DISPLAY_OPEN_BEHAVIOR_START_NAVIGATION -> "Start navigation"
            else -> "Show route preview"
        }
        displayOpenModeStatusText.setTextColor(
            ContextCompat.getColor(this, R.color.aurora_cyan)
        )
    }

    private fun setMapLinkHandlerEnabled(enabled: Boolean) {
        val component = android.content.ComponentName(
            this,
            "${packageName}.MapLinkHandlerAlias"
        )
        val state = if (enabled) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(
            component,
            state,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        Log.d(TAG, "🔧 MapLinkHandlerAlias ${if (enabled) "ENABLED" else "DISABLED"}")
        PhantomLog.i("MapLinkHandlerAlias ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    private fun openDefaultHandlerSettings() {
        val pkgUri = Uri.parse("package:$packageName")
        val intents = listOf(
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkgUri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        )

        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Unable to open settings: ${intent.action}", e)
            }
        }

        Toast.makeText(this, "Cannot open settings on this device", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        Toast.makeText(this, "Grant overlay permission and return", Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermission() {
        if (notificationRequestInFlight) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationRequestInFlight = true
            updateNotificationStatus()
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings()
        }
    }

    private fun updateNotificationStatus() {
        val enabled = NotificationHelper.canPostNotifications(this)
        val api33Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        if (enabled) {
            notificationStatusText.text = "Notifications: Enabled"
            notificationStatusText.setTextColor(ContextCompat.getColor(this, R.color.aurora_success))
            requestNotificationButton.visibility = View.GONE
        } else {
            notificationStatusText.text = "Notifications: Disabled"
            notificationStatusText.setTextColor(ContextCompat.getColor(this, R.color.aurora_warning))
            requestNotificationButton.visibility = View.VISIBLE
            requestNotificationButton.isEnabled = !notificationRequestInFlight
            requestNotificationButton.text = if (api33Plus) {
                "🔔 Request Notification Permission"
            } else {
                "🔔 Open Notification Settings"
            }
        }
    }

    private fun showNotificationSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Enable Notifications")
            .setMessage("Notifications are disabled. Please enable them in App Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppNotificationSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun loadLogs() {
        val logs = PhantomLog.getLogs(MAX_LOG_ENTRIES)
        logText.text = logs.joinToString("\n")
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun copyLogToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("PHANTOM_GO_LOGS", logText.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun clearLogs() {
        AlertDialog.Builder(this)
            .setTitle("Clear Logs")
            .setMessage("Are you sure you want to clear all logs?")
            .setPositiveButton("Clear") { _, _ ->
                PhantomLog.clearLogs()
                logText.text = ""
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
        updateMapLinkHubStatus()
        updateTransportStatus()
        updateSmartModeStatus()
        updateDisplayNavModeStatus()
        updateDisplayOpenModeStatus()
        updateNotificationStatus()
        loadLogs() // Refresh logs
    }
}
