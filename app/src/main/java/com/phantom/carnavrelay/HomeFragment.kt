package com.phantom.carnavrelay

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView

class HomeFragment : Fragment(R.layout.activity_main_drawer), BackPressHandler {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val CAMERA_PERMISSION_REQUEST = 1001
        private const val STATUS_CHECK_INTERVAL_MS = 4000L
    }

    private lateinit var prefsManager: PrefsManager
    private lateinit var mainSender: MainSender
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: MaterialToolbar

    private lateinit var tvPairStatus: TextView
    private lateinit var tvPairInfo: TextView
    private lateinit var tvPendingCount: TextView
    private lateinit var tvConnectionState: TextView
    private lateinit var btnScanQR: Button
    private lateinit var btnSendTest: Button
    private lateinit var btnRetryPending: Button
    private lateinit var btnFindDisplay: Button
    private lateinit var btnClearPairing: Button
    private lateinit var tvMapsAutoShareStatus: TextView
    private lateinit var tvMapsAutoShareDetails: TextView
    private lateinit var btnMapsAutoShareAccessibility: Button
    private lateinit var btnMapsAutoShareNotification: Button
    private lateinit var ghostIcon: TextView
    private var ghostAnimator: ObjectAnimator? = null
    private var antiTheftReceiverRegistered = false
    private var lastSecurityAlertId: String? = null

    private val antiTheftAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != FirebaseManager.ACTION_SECURITY_ALERT_RECEIVED || !isAdded) return
            val alertId = intent.getStringExtra("alertId") ?: return
            if (lastSecurityAlertId == alertId) return
            lastSecurityAlertId = alertId
            showSecurityAlertDialog(intent)
        }
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Log.d(TAG, "✅ QR scan successful")
            updateUI()
        } else {
            Log.d(TAG, "❌ QR scan cancelled or failed")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val statusCheckRunnable = object : Runnable {
        override fun run() {
            if (mainSender.isPaired()) {
                checkServerStatus()
            }
            handler.postDelayed(this, STATUS_CHECK_INTERVAL_MS)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "▶️ HomeFragment.onViewCreated()")
        prefsManager = PrefsManager(requireContext())
        mainSender = MainSender(requireContext())
        initViews(view)
        setupDrawer()
        updateUI()
    }

    private fun initViews(root: View) {
        drawerLayout = root.findViewById(R.id.drawerLayout)
        navigationView = root.findViewById(R.id.navigationView)
        toolbar = root.findViewById(R.id.toolbar)

        tvPairStatus = root.findViewById(R.id.tvPairStatus)
        tvPairInfo = root.findViewById(R.id.tvPairInfo)
        tvPendingCount = root.findViewById(R.id.tvPendingCount)
        tvConnectionState = root.findViewById(R.id.tvConnectionState)
        btnScanQR = root.findViewById(R.id.btnScanQR)
        btnSendTest = root.findViewById(R.id.btnSendTest)
        btnRetryPending = root.findViewById(R.id.btnRetryPending)
        btnFindDisplay = root.findViewById(R.id.btnFindDisplay)
        btnClearPairing = root.findViewById(R.id.btnClearPairing)
        tvMapsAutoShareStatus = root.findViewById(R.id.tvMapsAutoShareStatus)
        tvMapsAutoShareDetails = root.findViewById(R.id.tvMapsAutoShareDetails)
        btnMapsAutoShareAccessibility = root.findViewById(R.id.btnMapsAutoShareAccessibility)
        btnMapsAutoShareNotification = root.findViewById(R.id.btnMapsAutoShareNotification)
        ghostIcon = root.findViewById(R.id.ghostIcon)

        startGhostAnimation()
        setupButtonEffects(btnScanQR)
        setupButtonEffects(btnSendTest)
        setupButtonEffects(btnRetryPending)
        setupButtonEffects(btnFindDisplay)
        setupButtonEffects(btnClearPairing)
        setupButtonEffects(btnMapsAutoShareAccessibility)
        setupButtonEffects(btnMapsAutoShareNotification)

        btnScanQR.setOnClickListener { startQRScan() }
        btnSendTest.setOnClickListener { sendTestLocation() }
        btnRetryPending.setOnClickListener { retryPending() }
        btnFindDisplay.setOnClickListener { findDisplay() }
        btnClearPairing.setOnClickListener { confirmClearPairing() }
        btnMapsAutoShareAccessibility.setOnClickListener {
            startActivity(MapsAutoShareStatus.accessibilitySettingsIntent())
        }
        btnMapsAutoShareNotification.setOnClickListener {
            startActivity(MapsAutoShareStatus.notificationSettingsIntent())
        }
    }

    private fun setupDrawer() {
        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings, R.id.nav_logs -> {
                    (activity as? MainActivity)?.selectTab(BottomNavHelper.Tab.SETTINGS)
                }
                R.id.nav_security -> {
                    (activity as? MainActivity)?.selectTab(BottomNavHelper.Tab.SECURITY)
                }
                R.id.nav_about -> showAboutDialog()
                R.id.nav_switch_mode -> {
                    (activity as? MainActivity)?.selectTab(BottomNavHelper.Tab.DISPLAY)
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun setupButtonEffects(button: Button) {
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(100).start()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
            }
            true
        }
    }

    private fun startGhostAnimation() {
        ghostAnimator?.cancel()
        ghostAnimator = ObjectAnimator.ofFloat(ghostIcon, View.TRANSLATION_Y, 0f, -8f, 0f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun startQRScan() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            return
        }

        val intent = Intent(requireContext(), PairScanActivity::class.java)
        scanLauncher.launch(intent)
    }

    private fun sendTestLocation() {
        if (!mainSender.isPaired()) {
            Toast.makeText(requireContext(), "Not paired with Display device", Toast.LENGTH_SHORT).show()
            return
        }

        val testUrl = "https://www.google.com/maps/search/?api=1&query=13.7563,100.5018"
        mainSender.sendOpenUrl(testUrl, object : MainSender.Companion.SendCallback {
            override fun onSuccess() {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "✅ Test location sent", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(error: String, queued: Boolean, authFailed: Boolean) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "❌ Failed to send test location", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun retryPending() {
        if (!mainSender.isPaired()) {
            Toast.makeText(requireContext(), "Not paired with Display device", Toast.LENGTH_SHORT).show()
            return
        }

        val pending = prefsManager.getPendingQueue()
        if (pending.isEmpty()) {
            Toast.makeText(requireContext(), "No pending URLs", Toast.LENGTH_SHORT).show()
            return
        }

        mainSender.retryPending(object : MainSender.Companion.SendCallback {
            override fun onSuccess() {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "✅ All pending URLs sent", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            }

            override fun onFailure(error: String, queued: Boolean, authFailed: Boolean) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "❌ Some URLs failed: $error", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            }
        })
    }

    private fun findDisplay() {
        if (!mainSender.isPaired()) {
            Toast.makeText(requireContext(), "Not paired with Display device", Toast.LENGTH_SHORT).show()
            return
        }
        (activity as? MainActivity)?.selectTab(BottomNavHelper.Tab.SECURITY)
    }

    private fun confirmClearPairing() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear Pairing")
            .setMessage("Are you sure you want to clear the pairing with the Display device?")
            .setPositiveButton("Clear") { _, _ -> clearPairing() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearPairing() {
        FirebaseManager.clearMainPairing(requireContext(), "main-clear-pairing")
        prefsManager.clearPairing()
        mainSender.clearPending()
        Toast.makeText(requireContext(), "✅ Pairing cleared", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun checkServerStatus() {
        if (!mainSender.isPaired()) {
            return
        }

        mainSender.checkServerStatus { _, _ ->
            activity?.runOnUiThread { updateUI() }
        }
    }

    private fun updateUI() {
        val isPaired = mainSender.isPaired()
        val pendingCount = prefsManager.getPendingCount()
        val state = mainSender.getCurrentState()
        val ctx = requireContext()

        if (isPaired) {
            tvPairStatus.text = "Paired"
            tvPairStatus.setTextColor(ctx.getColor(R.color.aurora_success))
            tvPairInfo.text = mainSender.getPairedInfo()
        } else {
            tvPairStatus.text = "Not Paired"
            tvPairStatus.setTextColor(ctx.getColor(R.color.aurora_warning))
            tvPairInfo.text = "Scan a QR code from your Display device to connect."
        }

        when (state) {
            MainSender.STATE_AUTH_FAILED -> {
                tvConnectionState.text = "Auth failed"
                tvConnectionState.setTextColor(ctx.getColor(R.color.aurora_auth_failed))
                tvConnectionState.setBackgroundResource(R.drawable.badge_error)
            }
            MainSender.STATE_OFFLINE -> {
                tvConnectionState.text = "Disconnected"
                tvConnectionState.setTextColor(ctx.getColor(R.color.aurora_error))
                tvConnectionState.setBackgroundResource(R.drawable.badge_error)
            }
            MainSender.STATE_CONNECTED -> {
                tvConnectionState.text = "Connected"
                tvConnectionState.setTextColor(ctx.getColor(R.color.aurora_success))
                tvConnectionState.setBackgroundResource(R.drawable.badge_success)
            }
            MainSender.STATE_PAIRING, MainSender.STATE_CONNECTING -> {
                tvConnectionState.text = "Connecting"
                tvConnectionState.setTextColor(ctx.getColor(R.color.aurora_warning))
                tvConnectionState.setBackgroundResource(R.drawable.badge_warning)
            }
            else -> {
                tvConnectionState.text = if (isPaired) "Waiting" else "Waiting for Pairing"
                tvConnectionState.setTextColor(ctx.getColor(R.color.aurora_waiting))
                tvConnectionState.setBackgroundResource(R.drawable.badge_neutral)
            }
        }

        tvPendingCount.text = "Pending: $pendingCount"
        if (pendingCount > 0) {
            tvPendingCount.setTextColor(ctx.getColor(R.color.aurora_warning))
            tvPendingCount.setBackgroundResource(R.drawable.badge_warning)
        } else {
            tvPendingCount.setTextColor(ctx.getColor(R.color.aurora_success))
            tvPendingCount.setBackgroundResource(R.drawable.badge_success)
        }

        btnSendTest.isEnabled = isPaired
        btnRetryPending.isEnabled = isPaired && pendingCount > 0
        btnFindDisplay.isEnabled = isPaired
        btnClearPairing.isEnabled = isPaired
        btnSendTest.alpha = if (btnSendTest.isEnabled) 1f else 0.52f
        btnRetryPending.alpha = if (btnRetryPending.isEnabled) 1f else 0.52f
        btnFindDisplay.alpha = if (btnFindDisplay.isEnabled) 1f else 0.52f
        btnClearPairing.alpha = if (btnClearPairing.isEnabled) 1f else 0.52f
        updateMapsAutoShareUI(ctx)
    }

    private fun updateMapsAutoShareUI(context: Context) {
        val accessibilityEnabled = MapsAutoShareStatus.isAccessibilityEnabled(context)
        val notificationEnabled = MapsAutoShareStatus.isNotificationAccessEnabled(context)

        when {
            accessibilityEnabled && notificationEnabled -> {
                tvMapsAutoShareStatus.text = getString(R.string.maps_auto_share_status_active)
                tvMapsAutoShareStatus.setTextColor(context.getColor(R.color.aurora_success))
                tvMapsAutoShareStatus.setBackgroundResource(R.drawable.badge_success)
                tvMapsAutoShareDetails.text = getString(R.string.maps_auto_share_details_active)
            }
            accessibilityEnabled -> {
                tvMapsAutoShareStatus.text = getString(R.string.maps_auto_share_status_limited)
                tvMapsAutoShareStatus.setTextColor(context.getColor(R.color.aurora_warning))
                tvMapsAutoShareStatus.setBackgroundResource(R.drawable.badge_warning)
                tvMapsAutoShareDetails.text = getString(R.string.maps_auto_share_details_limited)
            }
            else -> {
                tvMapsAutoShareStatus.text = getString(R.string.maps_auto_share_status_inactive)
                tvMapsAutoShareStatus.setTextColor(context.getColor(R.color.aurora_waiting))
                tvMapsAutoShareStatus.setBackgroundResource(R.drawable.badge_neutral)
                tvMapsAutoShareDetails.text = getString(R.string.maps_auto_share_details_inactive)
            }
        }

        btnMapsAutoShareAccessibility.text = if (accessibilityEnabled) {
            getString(R.string.maps_auto_share_manage_accessibility)
        } else {
            getString(R.string.maps_auto_share_enable_accessibility)
        }
        btnMapsAutoShareNotification.visibility = if (notificationEnabled) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        setMapLinkHandlerEnabled(!isDisplayServiceRunning(), "HomeFragment.onResume")
        MainSender.triggerAutoReconnect(requireContext(), "main-resume")
        updateUI()
        handler.removeCallbacks(statusCheckRunnable)
        handler.post(statusCheckRunnable)
    }

    override fun onStart() {
        super.onStart()
        registerAntiTheftReceiverIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusCheckRunnable)
    }

    override fun onStop() {
        unregisterAntiTheftReceiverIfNeeded()
        super.onStop()
    }

    private fun isDisplayServiceRunning(): Boolean {
        return try {
            val activityManager = requireContext().getSystemService(android.app.ActivityManager::class.java)
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            services.any { it.service.className == DisplayServerService::class.java.name }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not check DisplayServerService state", e)
            false
        }
    }

    private fun setMapLinkHandlerEnabled(enabled: Boolean, reason: String) {
        val component = ComponentName(requireContext(), "${requireContext().packageName}.MapLinkHandlerAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        requireContext().packageManager.setComponentEnabledSetting(
            component,
            state,
            PackageManager.DONT_KILL_APP
        )
        Log.d(TAG, "🔧 MapLinkHandlerAlias ${if (enabled) "ENABLED" else "DISABLED"} ($reason)")
        PhantomLog.i("MapLinkHandlerAlias ${if (enabled) "ENABLED" else "DISABLED"} ($reason)")
    }

    private fun showAboutDialog() {
        val versionName = try {
            val pi = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pi.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("About PHANToM GO")
            .setMessage("Version: $versionName\n\nSend navigation from your main device to a display device with QR pairing and cloud relay.")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun handleBackPressed(): Boolean {
        return if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        } else {
            false
        }
    }

    override fun onDestroyView() {
        unregisterAntiTheftReceiverIfNeeded()
        ghostAnimator?.cancel()
        super.onDestroyView()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startQRScan()
                } else {
                    Toast.makeText(requireContext(), "Camera permission required for QR scanning", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun registerAntiTheftReceiverIfNeeded() {
        if (antiTheftReceiverRegistered || !isAdded) return
        val filter = IntentFilter(FirebaseManager.ACTION_SECURITY_ALERT_RECEIVED)
        ContextCompat.registerReceiver(
            requireContext(),
            antiTheftAlertReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        antiTheftReceiverRegistered = true
    }

    private fun unregisterAntiTheftReceiverIfNeeded() {
        if (!antiTheftReceiverRegistered || !isAdded) return
        runCatching {
            requireContext().unregisterReceiver(antiTheftAlertReceiver)
        }
        antiTheftReceiverRegistered = false
    }

    private fun showSecurityAlertDialog(intent: Intent) {
        val eventType = intent.getStringExtra("eventType") ?: "unknown"
        val displayName = intent.getStringExtra("displayName") ?: intent.getStringExtra("displayDeviceId") ?: "Display device"
        val locationAvailable = intent.getBooleanExtra("locationAvailable", false)
        val lat = if (intent.hasExtra("lat")) intent.getDoubleExtra("lat", 0.0) else null
        val lng = if (intent.hasExtra("lng")) intent.getDoubleExtra("lng", 0.0) else null
        val speedKmh = if (intent.hasExtra("speedKmh")) intent.getIntExtra("speedKmh", 0) else null

        val message = buildString {
            append(displayName)
            append("\n\n")
            append(securityAlertEventLabel(eventType))
            if (speedKmh != null) {
                append("\nSpeed: ")
                append(speedKmh)
                append(" km/h")
            }
            if (locationAvailable && lat != null && lng != null) {
                append("\nLocation: ")
                append(lat)
                append(", ")
                append(lng)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Anti-Theft Alert")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .apply {
                if (locationAvailable && lat != null && lng != null) {
                    setNeutralButton("Open Map") { _, _ ->
                        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                }
            }
            .show()
    }

    private fun securityAlertEventLabel(eventType: String): String {
        return when (eventType) {
            DisplayAntiTheftManager.ALERT_TIMEOUT_1 -> "No PIN was entered within 15 seconds."
            DisplayAntiTheftManager.ALERT_REPLUG_DURING_CHALLENGE_1 -> "The 3.5 mm jack was replugged during the first challenge."
            DisplayAntiTheftManager.ALERT_WRONG_PIN_1 -> "A wrong PIN was entered once."
            DisplayAntiTheftManager.ALERT_WRONG_PIN_2 -> "A wrong PIN was entered twice."
            DisplayAntiTheftManager.ALERT_ALARM_STARTED -> "The display device alarm has started."
            DisplayAntiTheftManager.ALERT_ALARM_ESCALATED_FULLSCREEN -> "The display device escalated to full-screen PIN lock."
            DisplayAntiTheftManager.ALERT_DISARMED_SUCCESS -> "The display device was disarmed successfully."
            else -> "Event: $eventType"
        }
    }

}
