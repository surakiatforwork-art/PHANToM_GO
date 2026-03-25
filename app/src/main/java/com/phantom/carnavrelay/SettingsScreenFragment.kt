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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class SettingsScreenFragment : Fragment(R.layout.activity_settings) {

    companion object {
        private const val TAG = "PHANTOM_GO"
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
            Toast.makeText(requireContext(), "Bluetooth permission required to select device", Toast.LENGTH_SHORT).show()
            bluetoothPickPending = false
            return@registerForActivityResult
        }
        if (bluetoothPickPending) {
            bluetoothPickPending = false
            showBluetoothDevicePicker()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "⚙️ SettingsScreenFragment.onViewCreated()")
        prefsManager = PrefsManager(requireContext())
        overlayController = OverlayController(requireContext())
        initViews(view)
        setupClickListeners()
        refreshUi()
    }

    private fun initViews(root: View) {
        overlaySwitch = root.findViewById(R.id.overlaySwitch)
        overlayStatusText = root.findViewById(R.id.overlayStatusText)
        mapLinkHubSwitch = root.findViewById(R.id.mapLinkHubSwitch)
        mapLinkHubStatusText = root.findViewById(R.id.mapLinkHubStatusText)
        mapLinkHubInstructionText = root.findViewById(R.id.mapLinkHubInstructionText)
        mapLinkHubDefaultStatusText = root.findViewById(R.id.mapLinkHubDefaultStatusText)
        deviceModeStatusText = root.findViewById(R.id.deviceModeStatusText)
        openByDefaultButton = root.findViewById(R.id.openByDefaultButton)
        transportModeStatusText = root.findViewById(R.id.transportModeStatusText)
        transportModeGroup = root.findViewById(R.id.transportModeGroup)
        transportModeAuto = root.findViewById(R.id.transportModeAuto)
        transportModeWifi = root.findViewById(R.id.transportModeWifi)
        transportModeBluetooth = root.findViewById(R.id.transportModeBluetooth)
        bluetoothTargetStatusText = root.findViewById(R.id.bluetoothTargetStatusText)
        pickBluetoothDeviceButton = root.findViewById(R.id.pickBluetoothDeviceButton)
        clearBluetoothDeviceButton = root.findViewById(R.id.clearBluetoothDeviceButton)
        smartModeSwitch = root.findViewById(R.id.smartModeSwitch)
        smartModeStatusText = root.findViewById(R.id.smartModeStatusText)
        smartPolicyGroup = root.findViewById(R.id.smartPolicyGroup)
        policyAutoSendNavOnly = root.findViewById(R.id.policyAutoSendNavOnly)
        policyAlwaysAsk = root.findViewById(R.id.policyAlwaysAsk)
        policyAlwaysSend = root.findViewById(R.id.policyAlwaysSend)
        policyAlwaysOpenOnPhone = root.findViewById(R.id.policyAlwaysOpenOnPhone)
        afterSendGroup = root.findViewById(R.id.afterSendGroup)
        afterSendExit = root.findViewById(R.id.afterSendExit)
        afterSendStay = root.findViewById(R.id.afterSendStay)
        openMapsAfterSendSwitch = root.findViewById(R.id.openMapsAfterSendSwitch)
        displayNavModeGroup = root.findViewById(R.id.displayNavModeGroup)
        displayNavModeCar = root.findViewById(R.id.displayNavModeCar)
        displayNavModeMotorcycle = root.findViewById(R.id.displayNavModeMotorcycle)
        displayNavModeStatusText = root.findViewById(R.id.displayNavModeStatusText)
        displayOpenModeGroup = root.findViewById(R.id.displayOpenModeGroup)
        displayOpenModePreview = root.findViewById(R.id.displayOpenModePreview)
        displayOpenModeNavigate = root.findViewById(R.id.displayOpenModeNavigate)
        displayOpenModeStatusText = root.findViewById(R.id.displayOpenModeStatusText)
        logScrollView = root.findViewById(R.id.logScrollView)
        logText = root.findViewById(R.id.logText)
        copyLogButton = root.findViewById(R.id.copyLogButton)
        clearLogButton = root.findViewById(R.id.clearLogButton)
        grantPermissionButton = root.findViewById(R.id.grantPermissionButton)
        notificationStatusText = root.findViewById(R.id.notificationStatusText)
        requestNotificationButton = root.findViewById(R.id.requestNotificationButton)
        backButton = root.findViewById(R.id.backButton)
    }

    private fun setupClickListeners() {
        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            overlayController.setOverlayEnabled(isChecked)
            updateOverlayStatus()
            overlaySwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        mapLinkHubSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingMapHubUi) return@setOnCheckedChangeListener
            prefsManager.setMapLinkHubEnabled(isChecked)
            setMapLinkHandlerEnabled(isChecked)
            updateMapLinkHubStatus()
            mapLinkHubSwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        transportModeGroup.setOnCheckedChangeListener { _, _ ->
            if (updatingTransportUi) return@setOnCheckedChangeListener
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
            Toast.makeText(requireContext(), "Bluetooth target cleared", Toast.LENGTH_SHORT).show()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        smartModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSmartModeUi) return@setOnCheckedChangeListener
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
            (activity as? MainActivity)?.navigateBackToPreviousTab()
        }
    }

    private fun refreshUi() {
        updateOverlayStatus()
        updateMapLinkHubStatus()
        updateTransportStatus()
        updateSmartModeStatus()
        updateDisplayNavModeStatus()
        updateDisplayOpenModeStatus()
        updateNotificationStatus()
        loadLogs()
    }

    private fun updateOverlayStatus() {
        val hasPermission = overlayController.hasOverlayPermission()
        val isEnabled = overlayController.isOverlayEnabled()

        overlaySwitch.isChecked = isEnabled && hasPermission
        overlaySwitch.isEnabled = hasPermission

        when {
            !hasPermission -> {
                overlayStatusText.text = "Permission Required"
                overlayStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_warning))
                grantPermissionButton.visibility = View.VISIBLE
            }
            isEnabled -> {
                overlayStatusText.text = "Overlay Enabled"
                overlayStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_success))
                grantPermissionButton.visibility = View.GONE
            }
            else -> {
                overlayStatusText.text = "Overlay Disabled"
                overlayStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_disconnected))
                grantPermissionButton.visibility = View.GONE
            }
        }
    }

    private fun updateMapLinkHubStatus() {
        val enabled = prefsManager.isMapLinkHubEnabled()
        val displayModeActive = isDisplayModeActive()
        val effectiveEnabled = enabled && !displayModeActive

        updatingMapHubUi = true
        mapLinkHubSwitch.isChecked = enabled
        updatingMapHubUi = false

        setMapLinkHandlerEnabled(effectiveEnabled)

        mapLinkHubStatusText.text = when {
            enabled && displayModeActive -> "Paused in Display Mode"
            enabled -> "On"
            else -> "Off"
        }
        mapLinkHubStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    enabled && displayModeActive -> R.color.aurora_warning
                    enabled -> R.color.aurora_success
                    else -> R.color.aurora_warning
                }
            )
        )

        val deviceMode = prefsManager.getDeviceMode()
        deviceModeStatusText.text = "Device mode: $deviceMode"

        mapLinkHubInstructionText.text = when {
            enabled && displayModeActive -> {
                "Map Link Hub ถูกพักไว้ชั่วคราวระหว่างที่เครื่องนี้ทำงานเป็น Display mode"
            }
            enabled -> {
                "ไปที่ Settings ของระบบ > Apps > Open by default > เลือก PHANToM GO แล้วกด Always"
            }
            else -> {
                "ปิดอยู่: ลิงก์แผนที่จะเปิด Google Maps ตามปกติ"
            }
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
        transportModeStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_cyan))

        bluetoothTargetStatusText.text = if (!targetAddress.isNullOrBlank()) {
            "Bluetooth transport disabled (saved target kept: ${targetName ?: "Unknown"})"
        } else {
            "Bluetooth transport disabled"
        }
        bluetoothTargetStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_warning))
        pickBluetoothDeviceButton.visibility = View.GONE
        clearBluetoothDeviceButton.visibility = View.GONE
    }

    private fun requestBluetoothDeviceSelection() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(requireContext(), "Device does not support Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(requireContext(), "Please enable Bluetooth first", Toast.LENGTH_SHORT).show()
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
            ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
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
            Toast.makeText(requireContext(), "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        if (bonded.isEmpty()) {
            Toast.makeText(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = bonded.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Select Bluetooth Display")
            .setItems(labels) { _, which ->
                val device = bonded[which]
                prefsManager.saveBluetoothTarget(device.address, device.name)
                updateTransportStatus()
                Toast.makeText(requireContext(), "Selected ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
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
                requireContext(),
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
        displayNavModeStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_cyan))
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
        displayOpenModeStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_cyan))
    }

    private fun setMapLinkHandlerEnabled(enabled: Boolean) {
        val component = android.content.ComponentName(
            requireContext(),
            "${requireContext().packageName}.MapLinkHandlerAlias"
        )
        val state = if (enabled) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        requireContext().packageManager.setComponentEnabledSetting(
            component,
            state,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        Log.d(TAG, "🔧 MapLinkHandlerAlias ${if (enabled) "ENABLED" else "DISABLED"}")
        PhantomLog.i("MapLinkHandlerAlias ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    private fun isDisplayModeActive(): Boolean {
        if (prefsManager.getDeviceMode() == PrefsManager.MODE_DISPLAY) return true
        return try {
            val activityManager = requireContext().getSystemService(android.app.ActivityManager::class.java)
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            services.any { it.service.className == DisplayServerService::class.java.name }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not check DisplayServerService state", e)
            false
        }
    }

    private fun openDefaultHandlerSettings() {
        val pkgUri = Uri.parse("package:${requireContext().packageName}")
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

        Toast.makeText(requireContext(), "Cannot open settings on this device", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${requireContext().packageName}")
        )
        startActivity(intent)
        Toast.makeText(requireContext(), "Grant overlay permission and return", Toast.LENGTH_LONG).show()
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
        val enabled = NotificationHelper.canPostNotifications(requireContext())
        val api33Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        if (enabled) {
            notificationStatusText.text = "Notifications: Enabled"
            notificationStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_success))
            requestNotificationButton.visibility = View.GONE
        } else {
            notificationStatusText.text = "Notifications: Disabled"
            notificationStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_warning))
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
        AlertDialog.Builder(requireContext())
            .setTitle("Enable Notifications")
            .setMessage("Notifications are disabled. Please enable them in App Settings.")
            .setPositiveButton("Open Settings") { _, _ -> openAppNotificationSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppNotificationSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        startActivity(intent)
    }

    private fun loadLogs() {
        val logs = PhantomLog.getLogs(MAX_LOG_ENTRIES)
        logText.text = logs.joinToString("\n")
        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyLogToClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PHANTOM_GO_LOGS", logText.text))
        Toast.makeText(requireContext(), "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun clearLogs() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear Logs")
            .setMessage("Are you sure you want to clear all logs?")
            .setPositiveButton("Clear") { _, _ ->
                PhantomLog.clearLogs()
                logText.text = ""
                Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }
}
