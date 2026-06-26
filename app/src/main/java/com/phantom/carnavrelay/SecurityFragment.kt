package com.phantom.carnavrelay

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.phantom.dashcam.AutoRecordPowerManager
import com.phantom.dashcam.DashcamClipsActivity
import com.phantom.dashcam.DashcamPreferences
import com.phantom.dashcam.DashcamStateStore
import com.phantom.dashcam.PowerStateUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SecurityFragment : Fragment(R.layout.fragment_security) {

    private lateinit var prefsManager: PrefsManager
    private lateinit var statusCard: View
    private lateinit var headline: TextView
    private lateinit var stateBadge: TextView
    private lateinit var detail: TextView
    private lateinit var locationSummary: TextView
    private lateinit var locationPlaceholder: TextView
    private lateinit var mapView: WebView
    private lateinit var primaryAction: MaterialButton
    private lateinit var displaySecurityControls: View
    private lateinit var findDisplayCard: View
    private lateinit var jackStatus: TextView
    private lateinit var pinAction: MaterialButton
    private lateinit var advancedAction: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var openExternalMapButton: MaterialButton
    private lateinit var dashcamPreferences: DashcamPreferences
    private lateinit var dashcamEnableSwitch: MaterialSwitch
    private lateinit var dashcamStatusText: TextView
    private lateinit var dashcamAutoRecordSwitch: MaterialSwitch
    private lateinit var dashcamDetailText: TextView
    private lateinit var dashcamGrantButton: MaterialButton
    private lateinit var dashcamSecurityViewClipsButton: MaterialButton
    private var updatingDashcamUi = false

    private val requestDashcamPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!isAdded) return@registerForActivityResult
        DashcamStateStore.bootstrap(requireContext())
        updateDashcamState()
    }

    private var receiverRegistered = false
    private var latestLocation: FirebaseManager.DisplayLocationInfo? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isAdded) return
            updateSecurityState()
            if (intent?.action == FirebaseManager.ACTION_SECURITY_ALERT_RECEIVED) {
                refreshDisplayLocation()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefsManager = PrefsManager(requireContext())
        dashcamPreferences = DashcamPreferences(requireContext())
        statusCard = view.findViewById(R.id.securityStatusCard)
        headline = view.findViewById(R.id.securityHeadline)
        stateBadge = view.findViewById(R.id.securityStateBadge)
        detail = view.findViewById(R.id.securityDetail)
        locationSummary = view.findViewById(R.id.displayLocationSummary)
        locationPlaceholder = view.findViewById(R.id.displayLocationPlaceholder)
        mapView = view.findViewById(R.id.displayLocationMap)
        primaryAction = view.findViewById(R.id.btnSecurityPrimaryAction)
        displaySecurityControls = view.findViewById(R.id.displaySecurityControls)
        findDisplayCard = view.findViewById(R.id.findDisplayCard)
        jackStatus = view.findViewById(R.id.securityJackStatus)
        pinAction = view.findViewById(R.id.btnSecurityPinAction)
        advancedAction = view.findViewById(R.id.btnSecurityAdvancedAction)
        refreshButton = view.findViewById(R.id.btnSecurityRefresh)
        openExternalMapButton = view.findViewById(R.id.btnSecurityOpenExternalMap)
        dashcamEnableSwitch = view.findViewById(R.id.dashcamEnableSwitch)
        dashcamStatusText = view.findViewById(R.id.dashcamStatusText)
        dashcamAutoRecordSwitch = view.findViewById(R.id.dashcamAutoRecordSwitch)
        dashcamDetailText = view.findViewById(R.id.dashcamDetailText)
        dashcamGrantButton = view.findViewById(R.id.dashcamGrantButton)
        dashcamSecurityViewClipsButton = view.findViewById(R.id.dashcamSecurityViewClipsButton)
        setupDashcamControls()

        configureMap()
        primaryAction.setOnClickListener {
            if (prefsManager.getDeviceMode() == PrefsManager.MODE_DISPLAY) {
                handleDisplaySecurityPrimaryAction()
            } else {
                openSettings()
            }
        }
        pinAction.setOnClickListener { promptPinChange() }
        advancedAction.setOnClickListener { openSettings() }
        refreshButton.setOnClickListener { refreshDisplayLocation() }
        openExternalMapButton.setOnClickListener { openLatestLocationExternally() }

        updateSecurityState()
        refreshDisplayLocation()
        DashcamStateStore.bootstrap(requireContext())
        updateDashcamState()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(DisplayAntiTheftManager.ACTION_STATE_CHANGED)
                addAction(FirebaseManager.ACTION_SECURITY_ALERT_RECEIVED)
            }
            ContextCompat.registerReceiver(
                requireContext(),
                stateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        DashcamStateStore.bootstrap(requireContext())
        updateDashcamState()
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { requireContext().unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroyView() {
        mapView.stopLoading()
        mapView.destroy()
        super.onDestroyView()
    }

    private fun updateSecurityState() {
        val isDisplay = prefsManager.getDeviceMode() == PrefsManager.MODE_DISPLAY
        if (isDisplay) {
            val snapshot = DisplayAntiTheftManager.snapshot(requireContext())
            val alarmActive = snapshot.state == AntiTheftState.ALARM_ACTIVE ||
                snapshot.state == AntiTheftState.FULLSCREEN_LOCK
            headline.text = snapshot.headline
            detail.text = snapshot.detail
            stateBadge.text = when {
                alarmActive -> getString(R.string.security_badge_alarm)
                snapshot.armed -> getString(R.string.security_badge_armed)
                snapshot.featureEnabled -> getString(R.string.security_badge_ready)
                else -> getString(R.string.security_badge_off)
            }
            val color = when {
                alarmActive -> R.color.ui_danger
                snapshot.armed || snapshot.featureEnabled -> R.color.ui_success
                else -> R.color.ui_warning
            }
            stateBadge.setTextColor(requireContext().getColor(color))
            stateBadge.setBackgroundResource(
                when {
                    alarmActive -> R.drawable.badge_error
                    snapshot.armed || snapshot.featureEnabled -> R.drawable.badge_success
                    else -> R.drawable.badge_warning
                }
            )
            statusCard.setBackgroundResource(
                if (alarmActive) R.drawable.security_alert_card else R.drawable.security_safe_card
            )
            displaySecurityControls.visibility = View.VISIBLE
            findDisplayCard.visibility = View.GONE
            jackStatus.text = getString(
                if (snapshot.headsetPlugged) R.string.security_jack_connected else R.string.security_jack_disconnected
            )
            pinAction.text = getString(if (snapshot.hasPin) R.string.at_change_pin else R.string.at_set_pin)
            primaryAction.text = when {
                !snapshot.featureEnabled -> getString(R.string.common_enable)
                !snapshot.hasPin -> getString(R.string.at_set_pin)
                snapshot.armed -> getString(R.string.at_disarm)
                else -> getString(R.string.at_arm)
            }
            primaryAction.isEnabled = !snapshot.featureEnabled || !snapshot.hasPin || snapshot.armed || snapshot.headsetPlugged
            primaryAction.alpha = if (primaryAction.isEnabled) 1f else 0.55f
        } else {
            val paired = prefsManager.getPairedDisplayDeviceId() != null
            headline.text = if (paired) getString(R.string.security_link_ready) else getString(R.string.security_no_display)
            detail.text = if (paired) {
                getString(R.string.security_alerts_available)
            } else {
                getString(R.string.security_pair_first)
            }
            stateBadge.text = if (paired) getString(R.string.security_badge_monitoring) else getString(R.string.security_badge_offline)
            stateBadge.setTextColor(
                requireContext().getColor(if (paired) R.color.ui_success else R.color.ui_warning)
            )
            stateBadge.setBackgroundResource(
                if (paired) R.drawable.badge_success else R.drawable.badge_warning
            )
            statusCard.setBackgroundResource(
                if (paired) R.drawable.security_safe_card else R.drawable.soft_card_background
            )
            displaySecurityControls.visibility = View.GONE
            findDisplayCard.visibility = View.VISIBLE
            primaryAction.text = getString(R.string.security_open_settings)
            primaryAction.isEnabled = true
            primaryAction.alpha = 1f
        }
    }

    private fun handleDisplaySecurityPrimaryAction() {
        val snapshot = DisplayAntiTheftManager.snapshot(requireContext())
        when {
            !snapshot.featureEnabled -> {
                DisplayAntiTheftManager.setFeatureEnabled(requireContext(), true)
                if (!snapshot.hasPin) promptPinChange()
            }
            !snapshot.hasPin -> promptPinChange()
            snapshot.armed -> promptDisarm()
            else -> {
                val error = DisplayAntiTheftManager.arm(requireContext())
                if (error != null) {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                }
            }
        }
        updateSecurityState()
    }

    private fun promptPinChange() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.at_pin_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.at_set_pin_title)
            .setView(input)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val saved = DisplayAntiTheftManager.savePin(requireContext(), input.text?.toString().orEmpty())
                Toast.makeText(
                    requireContext(),
                    if (saved) getString(R.string.at_pin_saved) else getString(R.string.at_pin_invalid),
                    Toast.LENGTH_SHORT
                ).show()
                updateSecurityState()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptDisarm() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.at_enter_pin_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.at_disarm_title)
            .setMessage(R.string.at_disarm_msg)
            .setView(input)
            .setPositiveButton(R.string.at_disarm) { _, _ ->
                val disarmed = DisplayAntiTheftManager.disarmWithPin(requireContext(), input.text?.toString().orEmpty())
                Toast.makeText(
                    requireContext(),
                    if (disarmed) getString(R.string.at_disarmed) else getString(R.string.at_wrong_pin),
                    Toast.LENGTH_SHORT
                ).show()
                updateSecurityState()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openSettings() {
        (activity as? MainActivity)?.selectTab(BottomNavHelper.Tab.SETTINGS)
    }

    private fun setupDashcamControls() {
        dashcamEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingDashcamUi) return@setOnCheckedChangeListener
            dashcamPreferences.setFeatureEnabled(isChecked)
            if (isChecked) {
                if (!hasDashcamPermissions()) requestDashcamPermissions()
            } else {
                AutoRecordPowerManager.setAutoRecordEnabled(requireContext(), false)
            }
            DashcamStateStore.bootstrap(requireContext())
            updateDashcamState()
        }

        dashcamAutoRecordSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingDashcamUi) return@setOnCheckedChangeListener
            AutoRecordPowerManager.setAutoRecordEnabled(requireContext(), isChecked)
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(requireContext())
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            }
            updateDashcamState()
        }

        dashcamGrantButton.setOnClickListener { requestDashcamPermissions() }
        dashcamSecurityViewClipsButton.setOnClickListener {
            startActivity(Intent(requireContext(), DashcamClipsActivity::class.java))
        }
    }

    private fun updateDashcamState() {
        if (!isAdded) return
        val featureEnabled = dashcamPreferences.isFeatureEnabled()
        val autoRecord = dashcamPreferences.isAutoRecordOnPowerEnabled()
        val state = DashcamStateStore.state.value
        val hasPermission = hasDashcamPermissions()
        val charging = PowerStateUtils.isCharging(requireContext())

        updatingDashcamUi = true
        dashcamEnableSwitch.isChecked = featureEnabled
        dashcamAutoRecordSwitch.isChecked = autoRecord && featureEnabled
        dashcamAutoRecordSwitch.isEnabled = featureEnabled
        updatingDashcamUi = false

        val statusRes = when {
            !featureEnabled -> R.string.security_dashcam_state_off
            !hasPermission -> R.string.security_dashcam_need_permission
            state.isRecording -> R.string.security_dashcam_state_recording
            autoRecord && !charging -> R.string.security_dashcam_state_waiting
            else -> R.string.security_dashcam_state_ready
        }
        dashcamStatusText.text = getString(statusRes)
        dashcamStatusText.setTextColor(
            requireContext().getColor(
                if (!featureEnabled || !hasPermission) R.color.ui_warning else R.color.ui_success
            )
        )

        dashcamDetailText.text = getString(
            R.string.security_dashcam_detail_fmt,
            if (hasPermission) getString(R.string.settings_granted) else getString(R.string.settings_not_granted),
            if (charging) getString(R.string.common_on) else getString(R.string.common_off),
            if (autoRecord && featureEnabled) getString(R.string.common_on) else getString(R.string.common_off),
        )

        dashcamGrantButton.visibility = if (featureEnabled && !hasPermission) View.VISIBLE else View.GONE
    }

    private fun hasDashcamPermissions(): Boolean {
        val ctx = requireContext()
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestDashcamPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestDashcamPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun refreshDisplayLocation() {
        if (prefsManager.getPairedDisplayDeviceId().isNullOrBlank()) {
            latestLocation = null
            showNoLocation(getString(R.string.security_pair_to_see_location))
            return
        }

        refreshButton.isEnabled = false
        locationSummary.text = getString(R.string.security_loading_location)
        FirebaseManager.getPairedDisplayLocation(requireContext()) { info, error ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                refreshButton.isEnabled = true
                latestLocation = info
                if (info == null) {
                    showNoLocation(error ?: getString(R.string.security_no_location_record))
                } else {
                    showLocation(info)
                }
            }
        }
    }

    private fun showNoLocation(message: String) {
        locationSummary.text = message
        mapView.visibility = View.GONE
        locationPlaceholder.visibility = View.VISIBLE
        locationPlaceholder.text = getString(R.string.security_no_location)
        openExternalMapButton.visibility = View.GONE
    }

    private fun showLocation(info: FirebaseManager.DisplayLocationInfo) {
        val lat = info.lat
        val lng = info.lng
        val timestamp = info.locationUpdatedAt ?: info.lastOnlineAt ?: info.presenceUpdatedAt
        locationSummary.text = buildString {
            append(if (info.connected) getString(R.string.security_online_now) else getString(R.string.security_offline_latest))
            append("\n")
            append(info.displayName ?: info.displayDeviceId)
            if (lat != null && lng != null) {
                append("\n")
                append(String.format(Locale.US, "%.6f, %.6f", lat, lng))
            }
            info.speedKmh?.let {
                append("  •  ")
                append(getString(R.string.security_speed_fmt, it))
            }
            timestamp?.let {
                append("\n")
                append(getString(R.string.security_updated_fmt, DateFormat.getDateTimeInstance().format(Date(it))))
            }
        }

        if (lat == null || lng == null) {
            mapView.visibility = View.GONE
            locationPlaceholder.visibility = View.VISIBLE
            locationPlaceholder.text = getString(R.string.security_no_coords)
            openExternalMapButton.visibility = View.GONE
            return
        }

        locationPlaceholder.visibility = View.GONE
        mapView.visibility = View.VISIBLE
        openExternalMapButton.visibility = View.VISIBLE
        mapView.loadDataWithBaseURL(
            "https://www.openstreetmap.org/",
            mapHtml(lat, lng, info.displayName ?: getString(R.string.security_display_fallback)),
            "text/html",
            "UTF-8",
            null
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureMap() {
        mapView.webViewClient = WebViewClient()
        mapView.settings.javaScriptEnabled = true
        mapView.settings.domStorageEnabled = false
        mapView.settings.allowFileAccess = false
        mapView.settings.allowContentAccess = false
        mapView.settings.setSupportZoom(true)
        mapView.settings.builtInZoomControls = true
        mapView.settings.displayZoomControls = false
    }

    private fun mapHtml(lat: Double, lng: Double, label: String): String {
        val safeLabel = label
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
              <style>html,body,#map{height:100%;margin:0;background:#e6e8ea}.leaflet-control-attribution{font-size:9px}</style>
            </head>
            <body>
              <div id="map"></div>
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
              <script>
                const map=L.map('map',{zoomControl:true}).setView([$lat,$lng],16);
                L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
                L.marker([$lat,$lng]).addTo(map).bindPopup('$safeLabel').openPopup();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun openLatestLocationExternally() {
        val info = latestLocation ?: return
        val lat = info.lat ?: return
        val lng = info.lng ?: return
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
