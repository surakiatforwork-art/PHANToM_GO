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
import android.media.RingtoneManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsScreenFragment : Fragment(R.layout.activity_settings) {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val MAX_LOG_ENTRIES = 500
    }

    private lateinit var prefsManager: PrefsManager
    private lateinit var overlayController: OverlayController
    private lateinit var overlaySwitch: MaterialSwitch
    private lateinit var overlayStatusText: TextView
    private lateinit var mapLinkHubSwitch: MaterialSwitch
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
    private lateinit var smartModeSwitch: MaterialSwitch
    private lateinit var smartModeStatusText: TextView
    private lateinit var smartPolicyGroup: android.widget.RadioGroup
    private lateinit var policyAutoSendNavOnly: android.widget.RadioButton
    private lateinit var policyAlwaysAsk: android.widget.RadioButton
    private lateinit var policyAlwaysSend: android.widget.RadioButton
    private lateinit var policyAlwaysOpenOnPhone: android.widget.RadioButton
    private lateinit var afterSendGroup: android.widget.RadioGroup
    private lateinit var afterSendExit: android.widget.RadioButton
    private lateinit var afterSendStay: android.widget.RadioButton
    private lateinit var openMapsAfterSendSwitch: MaterialSwitch
    private lateinit var displayNavModeGroup: android.widget.RadioGroup
    private lateinit var displayNavModeCar: android.widget.RadioButton
    private lateinit var displayNavModeMotorcycle: android.widget.RadioButton
    private lateinit var displayNavModeStatusText: TextView
    private lateinit var displayOpenModeGroup: android.widget.RadioGroup
    private lateinit var displayOpenModePreview: android.widget.RadioButton
    private lateinit var displayOpenModeNavigate: android.widget.RadioButton
    private lateinit var displayOpenModeStatusText: TextView
    private lateinit var speedometerSwitch: MaterialSwitch
    private lateinit var speedometerStatusText: TextView
    private lateinit var speedometerPermissionText: TextView
    private lateinit var requestLocationPermissionButton: Button
    private lateinit var displayPowerAutomationSwitch: MaterialSwitch
    private lateinit var displayPowerAutomationStatusText: TextView
    private lateinit var displayPowerAutomationDetailText: TextView
    private lateinit var displayWakeBeforeMapsSwitch: MaterialSwitch
    private lateinit var displayWakeBeforeMapsStatusText: TextView
    private lateinit var lockOnDisconnectSwitch: MaterialSwitch
    private lateinit var lockOnDisconnectStatusText: TextView
    private lateinit var systemSettingsStatusText: TextView
    private lateinit var requestSystemSettingsButton: Button
    private lateinit var deviceAdminStatusText: TextView
    private lateinit var requestDeviceAdminButton: Button
    private lateinit var antiTheftEnabledSwitch: MaterialSwitch
    private lateinit var antiTheftStatusText: TextView
    private lateinit var antiTheftDetailText: TextView
    private lateinit var antiTheftAttachLocationSwitch: MaterialSwitch
    private lateinit var antiTheftAttachLocationStatus: TextView
    private lateinit var antiTheftPinButton: Button
    private lateinit var antiTheftSoundButton: Button
    private lateinit var antiTheftTestButton: Button
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
    private var updatingSpeedometerUi = false
    private var updatingDisplayPowerUi = false
    private var updatingAntiTheftUi = false
    private var bluetoothPickPending = false

    private val pickAntiTheftSoundLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data == null) return@registerForActivityResult
        val pickedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        DisplayAntiTheftManager.setAlarmSoundUri(requireContext(), pickedUri)
        updateAntiTheftStatus()
        Toast.makeText(requireContext(), getString(R.string.at_alarm_sound_updated), Toast.LENGTH_SHORT).show()
    }

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
            Toast.makeText(requireContext(), getString(R.string.settings_bt_perm_required), Toast.LENGTH_SHORT).show()
            bluetoothPickPending = false
            return@registerForActivityResult
        }
        if (bluetoothPickPending) {
            bluetoothPickPending = false
            showBluetoothDevicePicker()
        }
    }

    private val requestLocationPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted) {
            requestBackgroundLocationPermissionIfNeeded()
        }
        updateSpeedometerStatus()
        notifyDisplayServiceSpeedSettingsChanged()
    }

    private val requestBackgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updateSpeedometerStatus()
        notifyDisplayServiceSpeedSettingsChanged()
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
        speedometerSwitch = root.findViewById(R.id.speedometerSwitch)
        speedometerStatusText = root.findViewById(R.id.speedometerStatusText)
        speedometerPermissionText = root.findViewById(R.id.speedometerPermissionText)
        requestLocationPermissionButton = root.findViewById(R.id.requestLocationPermissionButton)
        displayPowerAutomationSwitch = root.findViewById(R.id.displayPowerAutomationSwitch)
        displayPowerAutomationStatusText = root.findViewById(R.id.displayPowerAutomationStatusText)
        displayPowerAutomationDetailText = root.findViewById(R.id.displayPowerAutomationDetailText)
        displayWakeBeforeMapsSwitch = root.findViewById(R.id.displayWakeBeforeMapsSwitch)
        displayWakeBeforeMapsStatusText = root.findViewById(R.id.displayWakeBeforeMapsStatusText)
        lockOnDisconnectSwitch = root.findViewById(R.id.lockOnDisconnectSwitch)
        lockOnDisconnectStatusText = root.findViewById(R.id.lockOnDisconnectStatusText)
        systemSettingsStatusText = root.findViewById(R.id.systemSettingsStatusText)
        requestSystemSettingsButton = root.findViewById(R.id.requestSystemSettingsButton)
        deviceAdminStatusText = root.findViewById(R.id.deviceAdminStatusText)
        requestDeviceAdminButton = root.findViewById(R.id.requestDeviceAdminButton)
        antiTheftEnabledSwitch = root.findViewById(R.id.antiTheftEnabledSwitch)
        antiTheftStatusText = root.findViewById(R.id.antiTheftStatusText)
        antiTheftDetailText = root.findViewById(R.id.antiTheftDetailText)
        antiTheftAttachLocationSwitch = root.findViewById(R.id.antiTheftAttachLocationSwitch)
        antiTheftAttachLocationStatus = root.findViewById(R.id.antiTheftAttachLocationStatus)
        antiTheftPinButton = root.findViewById(R.id.antiTheftPinButton)
        antiTheftSoundButton = root.findViewById(R.id.antiTheftSoundButton)
        antiTheftTestButton = root.findViewById(R.id.antiTheftTestButton)
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
            Toast.makeText(requireContext(), getString(R.string.settings_bt_target_cleared), Toast.LENGTH_SHORT).show()
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

        speedometerSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSpeedometerUi) return@setOnCheckedChangeListener
            prefsManager.setSpeedometerEnabled(isChecked)
            if (isChecked && !DisplaySpeedMonitor.hasLocationPermission(requireContext())) {
                requestLocationPermissionFlow()
            }
            updateSpeedometerStatus()
            notifyDisplayServiceSpeedSettingsChanged()
            speedometerSwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        displayPowerAutomationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingDisplayPowerUi) return@setOnCheckedChangeListener
            DisplayPowerAutomationManager.setPowerAutomationEnabled(requireContext(), isChecked)
            updateDisplayPowerAutomationStatus()
            displayPowerAutomationSwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        displayWakeBeforeMapsSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingDisplayPowerUi) return@setOnCheckedChangeListener
            DisplayPowerAutomationManager.setWakeBeforeMapsEnabled(requireContext(), isChecked)
            updateDisplayPowerAutomationStatus()
            displayWakeBeforeMapsSwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        lockOnDisconnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingDisplayPowerUi) return@setOnCheckedChangeListener
            DisplayPowerAutomationManager.setLockOnPowerDisconnectEnabled(requireContext(), isChecked)
            if (isChecked) {
                DisplayPowerAutomationManager.setPowerAutomationEnabled(requireContext(), true)
                if (!DisplayPowerAutomationManager.isDeviceAdminActive(requireContext())) {
                    requestDeviceAdminAccess()
                }
            }
            updateDisplayPowerAutomationStatus()
            lockOnDisconnectSwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        requestSystemSettingsButton.setOnClickListener {
            requestWriteSettingsAccess()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        requestDeviceAdminButton.setOnClickListener {
            requestDeviceAdminAccess()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        antiTheftEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingAntiTheftUi) return@setOnCheckedChangeListener
            DisplayAntiTheftManager.setFeatureEnabled(requireContext(), isChecked)
            updateAntiTheftStatus()
            antiTheftEnabledSwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        antiTheftAttachLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingAntiTheftUi) return@setOnCheckedChangeListener
            DisplayAntiTheftManager.setAttachLocationEnabled(requireContext(), isChecked)
            updateAntiTheftStatus()
            antiTheftAttachLocationSwitch.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        antiTheftPinButton.setOnClickListener {
            promptAntiTheftPinDialog()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        antiTheftSoundButton.setOnClickListener {
            openAntiTheftSoundPicker()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        antiTheftTestButton.setOnClickListener {
            DisplayAntiTheftManager.testAlarm(requireContext())
            Toast.makeText(requireContext(), getString(R.string.at_alarm_test_started), Toast.LENGTH_SHORT).show()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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

        requestLocationPermissionButton.setOnClickListener {
            requestLocationPermissionFlow()
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
        updateSpeedometerStatus()
        updateDisplayPowerAutomationStatus()
        updateAntiTheftStatus()
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
                overlayStatusText.text = getString(R.string.settings_overlay_permission_required)
                overlayStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_warning))
                grantPermissionButton.visibility = View.VISIBLE
            }
            isEnabled -> {
                overlayStatusText.text = getString(R.string.settings_overlay_enabled)
                overlayStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_success))
                grantPermissionButton.visibility = View.GONE
            }
            else -> {
                overlayStatusText.text = getString(R.string.settings_overlay_disabled)
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
            enabled && displayModeActive -> getString(R.string.settings_paused_display_mode)
            enabled -> getString(R.string.common_on)
            else -> getString(R.string.common_off)
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
        val deviceModeLabel = if (deviceMode == PrefsManager.MODE_DISPLAY) {
            getString(R.string.settings_device_mode_display_value)
        } else {
            getString(R.string.settings_device_mode_main_value)
        }
        deviceModeStatusText.text = getString(R.string.settings_device_mode_fmt, deviceModeLabel)

        mapLinkHubInstructionText.text = when {
            enabled && displayModeActive -> {
                getString(R.string.settings_map_link_hub_paused)
            }
            enabled -> {
                getString(R.string.settings_map_link_hub_path_hint)
            }
            else -> {
                getString(R.string.settings_map_link_hub_off)
            }
        }

        mapLinkHubDefaultStatusText.text = getString(R.string.settings_system_default_hint)

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
        transportModeWifi.text = getString(R.string.settings_transport_wifi_recommended)
        transportModeWifi.isEnabled = false
        transportModeGroup.isEnabled = false

        transportModeStatusText.text = getString(R.string.settings_current_transport_wifi_bt_off)
        transportModeStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_cyan))

        bluetoothTargetStatusText.text = if (!targetAddress.isNullOrBlank()) {
            getString(
                R.string.settings_bluetooth_disabled_kept_fmt,
                targetName ?: getString(R.string.common_unknown)
            )
        } else {
            getString(R.string.settings_bluetooth_disabled)
        }
        bluetoothTargetStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_warning))
        pickBluetoothDeviceButton.visibility = View.GONE
        clearBluetoothDeviceButton.visibility = View.GONE
    }

    private fun requestBluetoothDeviceSelection() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(requireContext(), getString(R.string.settings_bt_not_supported), Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(requireContext(), getString(R.string.settings_bt_enable_first), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), getString(R.string.settings_bt_perm_denied), Toast.LENGTH_SHORT).show()
            return
        }

        if (bonded.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.settings_bt_no_paired), Toast.LENGTH_SHORT).show()
            return
        }

        val labels = bonded.map { "${it.name ?: getString(R.string.common_unknown)}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_bt_select_title)
            .setItems(labels) { _, which ->
                val device = bonded[which]
                prefsManager.saveBluetoothTarget(device.address, device.name)
                updateTransportStatus()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_bt_selected_fmt, device.name ?: device.address),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateSmartModeStatus() {
        val enabled = prefsManager.isSmartModeEnabled()
        val policy = prefsManager.getSmartModePolicy()

        updatingSmartModeUi = true
        smartModeSwitch.isChecked = enabled
        updatingSmartModeUi = false

        smartModeStatusText.text = if (enabled) getString(R.string.common_on) else getString(R.string.common_off)
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
            PrefsManager.DISPLAY_NAV_MODE_MOTORCYCLE -> getString(R.string.settings_motorcycle)
            else -> getString(R.string.settings_car)
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
            PrefsManager.DISPLAY_OPEN_BEHAVIOR_START_NAVIGATION -> getString(R.string.settings_start_navigation_status)
            else -> getString(R.string.settings_preview_route_status)
        }
        displayOpenModeStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_cyan))
    }

    private fun updateSpeedometerStatus() {
        val enabled = prefsManager.isSpeedometerEnabled()
        val hasLocation = DisplaySpeedMonitor.hasLocationPermission(requireContext())
        val hasBackground = DisplaySpeedMonitor.hasBackgroundLocationPermission(requireContext())
        val locationEnabled = DisplaySpeedMonitor.isLocationEnabled(requireContext())

        updatingSpeedometerUi = true
        speedometerSwitch.isChecked = enabled
        updatingSpeedometerUi = false

        speedometerStatusText.text = when {
            !enabled -> getString(R.string.common_off)
            !hasLocation -> getString(R.string.settings_speed_need_location)
            !hasBackground -> getString(R.string.settings_speed_need_alltime)
            !locationEnabled -> getString(R.string.settings_speed_location_off)
            else -> getString(R.string.common_on)
        }
        speedometerStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    !enabled -> R.color.aurora_warning
                    !hasLocation || !hasBackground || !locationEnabled -> R.color.aurora_warning
                    else -> R.color.aurora_success
                }
            )
        )

        speedometerPermissionText.text = getString(
            R.string.settings_speed_perm_fmt,
            if (hasLocation) getString(R.string.settings_granted) else getString(R.string.settings_not_granted),
            if (hasBackground) getString(R.string.settings_granted) else getString(R.string.settings_required),
            if (locationEnabled) getString(R.string.settings_gps_on) else getString(R.string.settings_gps_off)
        )
        requestLocationPermissionButton.visibility = if (enabled || !hasLocation || !hasBackground) View.VISIBLE else View.GONE
    }

    private fun updateDisplayPowerAutomationStatus() {
        val powerAutomationEnabled = DisplayPowerAutomationManager.isPowerAutomationEnabled(requireContext())
        val wakeBeforeMapsEnabled = DisplayPowerAutomationManager.isWakeBeforeMapsEnabled(requireContext())
        val lockOnDisconnectEnabled = DisplayPowerAutomationManager.isLockOnPowerDisconnectEnabled(requireContext())
        val canWriteSettings = DisplayPowerAutomationManager.canWriteSystemSettings(requireContext())
        val deviceAdminEnabled = DisplayPowerAutomationManager.isDeviceAdminActive(requireContext())
        val expectedBrightness = DisplayPowerAutomationManager.getExpectedBrightnessNow()
        val readiness = DisplayPowerAutomationManager.evaluateReadiness(requireContext())

        updatingDisplayPowerUi = true
        displayPowerAutomationSwitch.isChecked = powerAutomationEnabled
        displayWakeBeforeMapsSwitch.isChecked = wakeBeforeMapsEnabled
        lockOnDisconnectSwitch.isChecked = lockOnDisconnectEnabled
        updatingDisplayPowerUi = false

        displayPowerAutomationStatusText.text = when {
            powerAutomationEnabled -> getString(R.string.settings_power_conn_disc_on)
            wakeBeforeMapsEnabled -> getString(R.string.settings_power_wake_only)
            else -> getString(R.string.common_off)
        }
        displayPowerAutomationStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    powerAutomationEnabled || wakeBeforeMapsEnabled -> R.color.aurora_success
                    else -> R.color.aurora_warning
                }
            )
        )

        displayPowerAutomationDetailText.text = getString(
            R.string.settings_power_detail_fmt,
            if (expectedBrightness >= 200) getString(R.string.settings_profile_day) else getString(R.string.settings_profile_night),
            expectedBrightness,
            if (readiness.ready) getString(R.string.common_yes) else getString(R.string.common_no),
            if (readiness.interactive) getString(R.string.common_on) else getString(R.string.common_off),
            if (readiness.unlocked) getString(R.string.common_unlocked) else getString(R.string.common_locked),
            readiness.currentBrightness?.toString() ?: getString(R.string.common_unknown)
        )

        displayWakeBeforeMapsStatusText.text = if (wakeBeforeMapsEnabled) getString(R.string.common_enabled) else getString(R.string.common_disabled)
        displayWakeBeforeMapsStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (wakeBeforeMapsEnabled) R.color.aurora_success else R.color.aurora_warning
            )
        )

        lockOnDisconnectStatusText.text = when {
            !lockOnDisconnectEnabled -> getString(R.string.common_disabled)
            deviceAdminEnabled -> getString(R.string.settings_armed_device_admin)
            else -> getString(R.string.settings_needs_device_admin)
        }
        lockOnDisconnectStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    !lockOnDisconnectEnabled -> R.color.aurora_warning
                    deviceAdminEnabled -> R.color.aurora_success
                    else -> R.color.aurora_warning
                }
            )
        )

        systemSettingsStatusText.text = if (canWriteSettings) getString(R.string.settings_modify_granted) else getString(R.string.settings_modify_system_required)
        systemSettingsStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (canWriteSettings) R.color.aurora_success else R.color.aurora_warning
            )
        )
        requestSystemSettingsButton.visibility = if (canWriteSettings) View.GONE else View.VISIBLE

        deviceAdminStatusText.text = if (deviceAdminEnabled) getString(R.string.settings_device_admin_enabled) else getString(R.string.settings_device_admin_optional_lock)
        deviceAdminStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (deviceAdminEnabled) R.color.aurora_success else R.color.aurora_warning
            )
        )
        requestDeviceAdminButton.visibility = if (deviceAdminEnabled) View.GONE else View.VISIBLE
    }

    private fun updateAntiTheftStatus() {
        val snapshot = DisplayAntiTheftManager.snapshot(requireContext())
        val currentSound = DisplayAntiTheftManager.getAlarmSoundUri(requireContext())
        updatingAntiTheftUi = true
        antiTheftEnabledSwitch.isChecked = snapshot.featureEnabled
        antiTheftAttachLocationSwitch.isChecked = DisplayAntiTheftManager.isAttachLocationEnabled(requireContext())
        updatingAntiTheftUi = false

        antiTheftStatusText.text = when {
            !snapshot.featureEnabled -> getString(R.string.common_off)
            snapshot.armed -> getString(R.string.settings_anti_theft_armed)
            snapshot.state == AntiTheftState.READY_UNPLUGGED -> getString(R.string.settings_anti_theft_waiting_jack)
            snapshot.hasPin -> getString(R.string.settings_anti_theft_ready)
            else -> getString(R.string.settings_anti_theft_need_pin)
        }
        antiTheftStatusText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    !snapshot.featureEnabled -> R.color.aurora_warning
                    snapshot.armed -> R.color.aurora_success
                    snapshot.hasPin -> R.color.aurora_cyan
                    else -> R.color.aurora_warning
                }
            )
        )

        antiTheftDetailText.text = getString(
            R.string.settings_at_detail_fmt,
            snapshot.detail,
            if (snapshot.hasPin) getString(R.string.settings_pin_configured) else getString(R.string.settings_pin_not_set),
            if (snapshot.headsetPlugged) getString(R.string.settings_trigger_detected) else getString(R.string.settings_trigger_not_detected),
            " • " + if (currentSound != null) getString(R.string.settings_sound_custom) else getString(R.string.settings_sound_default)
        )

        antiTheftAttachLocationStatus.text = if (DisplayAntiTheftManager.isAttachLocationEnabled(requireContext())) getString(R.string.common_on) else getString(R.string.common_off)
        antiTheftAttachLocationStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (DisplayAntiTheftManager.isAttachLocationEnabled(requireContext())) R.color.aurora_success
                else R.color.aurora_warning
            )
        )
    }

    private fun promptAntiTheftPinDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.at_pin_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.at_set_change_pin_title)
            .setView(input)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val ok = DisplayAntiTheftManager.savePin(requireContext(), input.text?.toString().orEmpty())
                Toast.makeText(
                    requireContext(),
                    if (ok) getString(R.string.at_pin_saved) else getString(R.string.at_pin_invalid),
                    Toast.LENGTH_SHORT
                ).show()
                updateAntiTheftStatus()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAntiTheftSoundPicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.settings_choose_alarm_sound_title))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, DisplayAntiTheftManager.getAlarmSoundUri(requireContext()))
        }
        pickAntiTheftSoundLauncher.launch(intent)
    }

    private fun requestWriteSettingsAccess() {
        startActivity(DisplayPowerAutomationManager.buildWriteSettingsIntent(requireContext()))
        Toast.makeText(requireContext(), getString(R.string.settings_grant_write_toast), Toast.LENGTH_LONG).show()
    }

    private fun requestDeviceAdminAccess() {
        startActivity(DisplayPowerAutomationManager.buildDeviceAdminIntent(requireContext()))
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

        Toast.makeText(requireContext(), getString(R.string.settings_cannot_open_settings), Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${requireContext().packageName}")
        )
        startActivity(intent)
        Toast.makeText(requireContext(), getString(R.string.settings_grant_overlay_toast), Toast.LENGTH_LONG).show()
    }

    private fun requestLocationPermissionFlow() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }

        if (permissions.isNotEmpty()) {
            requestLocationPermissionsLauncher.launch(permissions.toTypedArray())
            return
        }

        requestBackgroundLocationPermissionIfNeeded()
    }

    private fun requestBackgroundLocationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            DisplaySpeedMonitor.hasBackgroundLocationPermission(requireContext())
        ) {
            updateSpeedometerStatus()
            notifyDisplayServiceSpeedSettingsChanged()
            return
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            requestBackgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_allow_alltime_title)
            .setMessage(R.string.settings_allow_alltime_msg)
            .setPositiveButton(R.string.common_open_settings) { _, _ ->
                openAppLocationSettings()
            }
            .setNegativeButton(R.string.common_later, null)
            .show()
    }

    private fun openAppLocationSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${requireContext().packageName}")
        }
        startActivity(intent)
    }

    private fun notifyDisplayServiceSpeedSettingsChanged() {
        if (isDisplayModeActive()) {
            DisplayServerService.requestSpeedometerRefresh(requireContext())
        }
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
            notificationStatusText.text = getString(R.string.settings_notifications_enabled)
            notificationStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_success))
            requestNotificationButton.visibility = View.GONE
        } else {
            notificationStatusText.text = getString(R.string.settings_notifications_disabled)
            notificationStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.aurora_warning))
            requestNotificationButton.visibility = View.VISIBLE
            requestNotificationButton.isEnabled = !notificationRequestInFlight
            requestNotificationButton.text = if (api33Plus) {
                getString(R.string.settings_request_notification)
            } else {
                getString(R.string.settings_open_notification_settings)
            }
        }
    }

    private fun showNotificationSettingsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_enable_notifications_title)
            .setMessage(R.string.settings_enable_notifications_msg)
            .setPositiveButton(R.string.common_open_settings) { _, _ -> openAppNotificationSettings() }
            .setNegativeButton(R.string.cancel, null)
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
        Toast.makeText(requireContext(), getString(R.string.settings_logs_copied), Toast.LENGTH_SHORT).show()
    }

    private fun clearLogs() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_clear_logs_title)
            .setMessage(R.string.settings_clear_logs_msg)
            .setPositiveButton(R.string.common_clear) { _, _ ->
                PhantomLog.clearLogs()
                logText.text = ""
                Toast.makeText(requireContext(), getString(R.string.settings_logs_cleared), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        notifyDisplayServiceSpeedSettingsChanged()
    }
}
