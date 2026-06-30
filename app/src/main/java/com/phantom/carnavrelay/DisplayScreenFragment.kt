package com.phantom.carnavrelay

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.phantom.carnavrelay.util.buildCloudPairPayload
import java.util.EnumMap

class DisplayScreenFragment : Fragment(R.layout.activity_display) {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val MAIN_PING_CHECK_INTERVAL_MS = 4000L
        private const val MAIN_PING_TIMEOUT_MS = 12000L
        // After the user flips the Display-mode toggle, keep showing their intended state for this
        // long while the foreground service is still transitioning; then trust the real state.
        private const val DISPLAY_MODE_RECONCILE_DELAY_MS = 4000L
    }

    private lateinit var prefsManager: PrefsManager
    private lateinit var appIcon: ImageView
    private lateinit var speedometerCard: MaterialCardView
    private lateinit var speedometerContent: LinearLayout
    private lateinit var tvSpeedLabel: TextView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvSpeedUnit: TextView
    private lateinit var tvSpeedStatus: TextView
    private lateinit var titleText: TextView
    private lateinit var tvIpPort: TextView
    private lateinit var tvTokenHint: TextView
    private lateinit var ivQrCode: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var statusDot: View
    private lateinit var statusGhost: TextView
    private lateinit var tvPairedInfo: TextView
    private lateinit var connectedStateCard: MaterialCardView
    private lateinit var connectedStateContent: LinearLayout
    private lateinit var ivConnectedIcon: ImageView
    private lateinit var btnRefreshToken: Button
    private lateinit var btnCopyToken: Button
    private lateinit var btnResetPairing: Button
    private lateinit var buttonLayout: LinearLayout
    private lateinit var layoutQR: LinearLayout
    private lateinit var layoutConnected: LinearLayout
    private lateinit var layoutConnectedActions: LinearLayout
    private lateinit var tvConnectedHeadline: TextView
    private lateinit var tvConnectedSubtext: TextView
    private lateinit var tvConnectedInstruction: TextView
    private lateinit var btnOpenLastNav: Button
    private lateinit var switchDisplayMode: MaterialSwitch
    private lateinit var tvDisplayModeStatus: TextView
    private lateinit var tvServerInfo: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnBatterySettings: Button
    private lateinit var tvQrPayload: TextView
    private lateinit var btnCopyPayload: Button
    private var statusBlinkAnimator: ObjectAnimator? = null
    private var suppressDisplayModeToggleCallback = false
    // The user's just-made toggle choice, shown optimistically until the service state catches up
    // (null = no pending choice, trust the real service state).
    private var desiredDisplayMode: Boolean? = null
    private val reconcileDisplayModeRunnable = Runnable {
        desiredDisplayMode = null
        if (isAdded) updateUI()
    }
    private var pairingReceiverRegistered = false
    private var speedReceiverRegistered = false
    private val mainPingHandler = Handler(Looper.getMainLooper())
    private val mainPingRunnable = object : Runnable {
        override fun run() {
            val isPaired = prefsManager.isDisplayPaired()
            val mainActive = isMainRecentlyConnected()
            updateConnectionIndicator(isPaired, mainActive)
            mainPingHandler.postDelayed(this, MAIN_PING_CHECK_INTERVAL_MS)
        }
    }

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HttpServer.ACTION_PAIRING_CHANGED) {
                Log.d(TAG, "📢 Received PAIRING_CHANGED broadcast")
                activity?.runOnUiThread { updateUI() }
            }
        }
    }

    private val speedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DisplayServerService.ACTION_SPEED_UPDATED) {
                activity?.runOnUiThread { updateSpeedUi() }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "▶️ DisplayScreenFragment.onViewCreated()")
        prefsManager = PrefsManager(requireContext())
        initViews(view)
        checkPermissions()
        setMapLinkHandlerEnabled(false, "DisplayFragment.onViewCreated")
        updateUI()
    }

    private fun initViews(root: View) {
        appIcon = root.findViewById(R.id.appIcon)
        speedometerCard = root.findViewById(R.id.speedometerCard)
        speedometerContent = root.findViewById(R.id.speedometerContent)
        tvSpeedLabel = root.findViewById(R.id.tvSpeedLabel)
        tvSpeedValue = root.findViewById(R.id.tvSpeedValue)
        tvSpeedUnit = root.findViewById(R.id.tvSpeedUnit)
        tvSpeedStatus = root.findViewById(R.id.tvSpeedStatus)
        titleText = root.findViewById(R.id.titleText)
        tvIpPort = root.findViewById(R.id.tvIpPort)
        tvTokenHint = root.findViewById(R.id.tvTokenHint)
        ivQrCode = root.findViewById(R.id.ivQrCode)
        tvStatus = root.findViewById(R.id.tvStatus)
        statusDot = root.findViewById(R.id.statusDot)
        statusGhost = root.findViewById(R.id.statusGhost)
        tvPairedInfo = root.findViewById(R.id.tvPairedInfo)
        connectedStateCard = root.findViewById(R.id.connectedStateCard)
        connectedStateContent = root.findViewById(R.id.connectedStateContent)
        ivConnectedIcon = root.findViewById(R.id.ivConnectedIcon)
        btnRefreshToken = root.findViewById(R.id.btnRefreshToken)
        btnCopyToken = root.findViewById(R.id.btnCopyToken)
        btnResetPairing = root.findViewById(R.id.btnResetPairing)
        buttonLayout = root.findViewById(R.id.buttonLayout)
        layoutQR = root.findViewById(R.id.layoutQR)
        layoutConnected = root.findViewById(R.id.layoutConnected)
        layoutConnectedActions = root.findViewById(R.id.layoutConnectedActions)
        tvConnectedHeadline = root.findViewById(R.id.tvConnectedHeadline)
        tvConnectedSubtext = root.findViewById(R.id.tvConnectedSubtext)
        tvConnectedInstruction = root.findViewById(R.id.tvConnectedInstruction)
        btnOpenLastNav = root.findViewById(R.id.btnOpenLastNav)
        switchDisplayMode = root.findViewById(R.id.switchDisplayMode)
        tvDisplayModeStatus = root.findViewById(R.id.tvDisplayModeStatus)
        tvServerInfo = root.findViewById(R.id.tvServerInfo)
        tvBatteryStatus = root.findViewById(R.id.tvBatteryStatus)
        btnBatterySettings = root.findViewById(R.id.btnBatterySettings)
        tvQrPayload = root.findViewById(R.id.tvQrPayload)
        btnCopyPayload = root.findViewById(R.id.btnCopyPayload)

        btnRefreshToken.setOnClickListener { refreshToken() }
        btnCopyToken.setOnClickListener { copyTokenToClipboard() }
        btnOpenLastNav.setOnClickListener { openLastNavigation() }
        btnResetPairing.setOnClickListener { confirmResetPairing() }
        btnBatterySettings.setOnClickListener { requestBatteryOptimization() }
        btnCopyPayload.setOnClickListener { copyPayloadToClipboard() }

        switchDisplayMode.setOnCheckedChangeListener { _, isChecked ->
            if (suppressDisplayModeToggleCallback) return@setOnCheckedChangeListener
            // Reflect the user's choice immediately. The freshly (re)started foreground service is
            // not reported as "running" yet by the time updateUI() re-reads it, so without this the
            // switch would bounce back and need a second tap. The reconcile runnable drops the
            // optimistic override after a few seconds so a failed start can't leave it stuck.
            desiredDisplayMode = isChecked
            mainPingHandler.removeCallbacks(reconcileDisplayModeRunnable)
            mainPingHandler.postDelayed(reconcileDisplayModeRunnable, DISPLAY_MODE_RECONCILE_DELAY_MS)
            if (isChecked) {
                ensureDisplayServiceRunning("display-mode-toggle")
            } else {
                stopDisplayService()
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                PhantomLog.w("NOTIF permission missing (DisplayFragment)")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(requireContext(), getString(R.string.display_some_permissions_denied), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerPairingReceiverIfNeeded()
        registerSpeedReceiverIfNeeded()
    }

    override fun onStop() {
        unregisterPairingReceiverIfNeeded()
        unregisterSpeedReceiverIfNeeded()
        super.onStop()
    }

    private fun updateUI() {
        val tempServer = HttpServer(requireContext(), prefsManager)
        val ip = tempServer.getLocalIpAddress() ?: "0.0.0.0"
        val port = prefsManager.getServerPort()
        val token = prefsManager.getServerToken()
        val tokenHint = prefsManager.getTokenHint(token)
        val isPaired = prefsManager.isDisplayPaired()
        val isMainActive = isMainRecentlyConnected()

        tvIpPort.text = "$ip:$port"
        tvTokenHint.text = tokenHint

        val isServiceRunning = isServiceRunning(DisplayServerService::class.java)
        // Once the real service state has caught up with the user's last toggle, drop the override.
        val desired = desiredDisplayMode
        if (desired != null && desired == isServiceRunning) {
            desiredDisplayMode = null
            mainPingHandler.removeCallbacks(reconcileDisplayModeRunnable)
        }
        val displayModeOn = desiredDisplayMode ?: isServiceRunning
        suppressDisplayModeToggleCallback = true
        switchDisplayMode.isChecked = displayModeOn
        suppressDisplayModeToggleCallback = false
        tvDisplayModeStatus.text = if (displayModeOn) getString(R.string.display_mode_on) else getString(R.string.display_mode_off)
        tvDisplayModeStatus.setTextColor(requireContext().getColor(if (displayModeOn) R.color.success else R.color.gray_600))

        setMapLinkHandlerEnabled(!displayModeOn, "DisplayFragment.updateUI")

        tvServerInfo.text = if (displayModeOn) getString(R.string.display_server_fmt, "$ip:$port") else getString(R.string.display_server_not_running)
        updateBatteryStatus()
        speedometerCard.visibility = if (isPaired) View.VISIBLE else View.GONE
        updateSpeedUi()
        updateDisplayPriorityLayout(isPaired)

        if (isPaired) {
            val pairedName = prefsManager.getDisplayPairedMainName() ?: getString(R.string.display_unknown)
            val pairedAt = prefsManager.getDisplayPairedAt()
            val timeAgo = if (pairedAt > 0) {
                val minutes = (System.currentTimeMillis() - pairedAt) / 60000
                if (minutes < 60) getString(R.string.display_minutes_ago_fmt, minutes.toInt())
                else getString(R.string.display_hours_ago_fmt, (minutes / 60).toInt())
            } else {
                getString(R.string.display_unknown)
            }

            appIcon.visibility = View.GONE
            titleText.text = getString(R.string.display_connected)
            tvIpPort.visibility = View.GONE
            tvTokenHint.visibility = View.GONE
            buttonLayout.visibility = View.GONE
            tvPairedInfo.text = getString(R.string.display_main_device_fmt, pairedName, timeAgo)
            tvPairedInfo.visibility = View.GONE
            tvConnectedHeadline.text = if (isMainActive) getString(R.string.connected) else getString(R.string.display_paired_state)
            tvConnectedSubtext.text = if (isMainActive) {
                getString(R.string.display_ready_receive_fmt, pairedName)
            } else {
                getString(R.string.display_still_paired_fmt, pairedName)
            }
            tvConnectedInstruction.text = getString(R.string.display_tap_disconnect_hint)
            btnResetPairing.text = getString(R.string.display_disconnect_main)
            layoutQR.visibility = View.GONE
            layoutConnected.visibility = View.VISIBLE
            ivConnectedIcon.visibility = View.GONE
            layoutConnectedActions.visibility = View.VISIBLE
            tvConnectedInstruction.visibility = View.GONE
        } else {
            appIcon.visibility = View.VISIBLE
            titleText.text = getString(R.string.app_name_display)
            tvIpPort.visibility = View.VISIBLE
            tvTokenHint.visibility = View.VISIBLE
            buttonLayout.visibility = View.VISIBLE
            tvPairedInfo.text = getString(R.string.display_scan_this_qr)
            tvPairedInfo.visibility = View.VISIBLE
            tvConnectedHeadline.text = getString(R.string.display_waiting_pairing)
            tvConnectedSubtext.text = getString(R.string.display_show_on_car_hint)
            tvConnectedInstruction.text = getString(R.string.display_auto_switch_hint)
            btnResetPairing.text = getString(R.string.display_disconnect_reset)
            layoutQR.visibility = View.VISIBLE
            layoutConnected.visibility = View.VISIBLE
            ivConnectedIcon.visibility = View.GONE
            layoutConnectedActions.visibility = View.GONE
            tvConnectedInstruction.visibility = View.VISIBLE
        }

        updateConnectionIndicator(isPaired, isMainActive)

        val lastUrl = prefsManager.getPrefs().getString("last_url", null)
        btnOpenLastNav.isEnabled = !lastUrl.isNullOrEmpty()

        val displayDeviceId = prefsManager.getLocalDeviceId()
        val cloudPairCode = prefsManager.getCloudPairCode()

        FirebaseManager.publishPairingCode(requireContext(), ip, port, token, "display-update-ui")
        val qrPayload = buildCloudPairPayload(displayDeviceId, cloudPairCode, ip, port, token)

        // Keep the raw payload (which embeds the pairing token) out of plain sight.
        // The QR code is the intended pairing channel; the Copy button still allows
        // a manual fallback without showing the token on the car display.
        tvQrPayload.text = qrPayload
        tvQrPayload.visibility = View.GONE
        btnCopyPayload.visibility = if (isPaired) View.GONE else View.VISIBLE
        generateQRCode(qrPayload)
    }

    private fun ensureDisplayServiceRunning(reason: String) {
        if (isServiceRunning(DisplayServerService::class.java)) {
            updateUI()
            return
        }

        try {
            val intent = Intent(requireContext(), DisplayServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
            DisplayServerService.requestSpeedometerRefresh(requireContext())
            updateUI()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to start server [$reason]", e)
            desiredDisplayMode = false
            mainPingHandler.removeCallbacks(reconcileDisplayModeRunnable)
            updateUI()
            Toast.makeText(
                requireContext(),
                getString(R.string.display_failed_start_server_fmt, e.message ?: getString(R.string.common_unknown)),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopDisplayService() {
        try {
            DisplayServerService.requestStop(requireContext(), "display-mode-toggle")
            prefsManager.clearSpeedSnapshot()
            updateUI()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to stop server", e)
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            services.any { serviceClass.name == it.service.className && it.foreground }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check service status", e)
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
        requireContext().packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        Log.d(TAG, "MapLinkHandlerAlias ${if (enabled) "ENABLED" else "DISABLED"} ($reason)")
    }

    private fun updateBatteryStatus() {
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
        } else {
            true
        }

        if (isIgnoringBatteryOptimizations) {
            tvBatteryStatus.text = getString(R.string.display_battery_opt_off)
            tvBatteryStatus.setTextColor(requireContext().getColor(R.color.success))
            btnBatterySettings.visibility = View.GONE
        } else {
            tvBatteryStatus.text = getString(R.string.display_battery_opt_on)
            tvBatteryStatus.setTextColor(requireContext().getColor(R.color.warning))
            btnBatterySettings.visibility = View.VISIBLE
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.display_battery_title)
                .setMessage(R.string.display_battery_msg)
                .setPositiveButton(R.string.display_settings) { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${requireContext().packageName}")
                        }
                        startActivityForResult(intent, 1002)
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002) {
            updateUI()
        }
    }

    private fun generateQRCode(data: String) {
        try {
            val writer = QRCodeWriter()
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            val bitMatrix: BitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 512, 512, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to generate QR code", e)
        }
    }

    private fun copyPayloadToClipboard() {
        val payload = tvQrPayload.text.toString()
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PHANToM GO QR Payload", payload))
        Toast.makeText(requireContext(), getString(R.string.display_payload_copied), Toast.LENGTH_SHORT).show()
    }

    private fun openLastNavigation() {
        val lastUrl = prefsManager.getPrefs().getString("last_url", null)
        if (lastUrl.isNullOrEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.display_no_recent_nav), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(requireContext(), OpenNavigationActivity::class.java).apply {
            putExtra("url", lastUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun refreshToken() {
        val previousPairCode = prefsManager.peekCloudPairCode()
        prefsManager.refreshServerToken()
        prefsManager.refreshCloudPairCode()
        FirebaseManager.resetDisplayPairingSession(requireContext(), previousPairCode, "display-refresh-token")
        updateUI()
        Toast.makeText(requireContext(), getString(R.string.display_token_refreshed), Toast.LENGTH_SHORT).show()
    }

    private fun copyTokenToClipboard() {
        val token = prefsManager.getServerToken()
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PHANToM GO Token", token))
        Toast.makeText(requireContext(), getString(R.string.display_token_copied), Toast.LENGTH_SHORT).show()
    }

    private fun confirmResetPairing() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.display_reset_pairing_title)
            .setMessage(R.string.display_reset_pairing_msg)
            .setPositiveButton(R.string.common_reset) { _, _ ->
                val previousPairCode = prefsManager.peekCloudPairCode()
                prefsManager.refreshServerToken()
                prefsManager.refreshCloudPairCode()
                FirebaseManager.resetDisplayPairingSession(requireContext(), previousPairCode, "display-reset-pairing")
                updateUI()
                Toast.makeText(requireContext(), getString(R.string.display_pairing_reset), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateConnectionIndicator(isPaired: Boolean, isMainActive: Boolean) {
        if (isPaired) {
            stopStatusBlink()
            statusDot.visibility = View.GONE
            statusGhost.visibility = View.GONE
            tvStatus.visibility = View.GONE
            val pairedName = prefsManager.getDisplayPairedMainName() ?: getString(R.string.display_unknown)
            tvConnectedHeadline.text = if (isMainActive) getString(R.string.connected) else getString(R.string.display_paired_state)
            tvConnectedSubtext.text = if (isMainActive) {
                getString(R.string.display_ready_receive_fmt, pairedName)
            } else {
                getString(R.string.display_still_paired_fmt, pairedName)
            }
            return
        }

        tvStatus.visibility = View.VISIBLE
        stopStatusBlink()
        statusDot.visibility = View.GONE
        statusGhost.visibility = View.VISIBLE
        statusGhost.text = "👻💤"
        tvStatus.text = getString(R.string.display_disconnected)
        tvStatus.setTextColor(requireContext().getColor(R.color.ghost_purple_dark))
    }

    private fun isMainRecentlyConnected(): Boolean {
        val lastPing = prefsManager.getLastMainPingAt()
        if (lastPing <= 0L) return false
        return (System.currentTimeMillis() - lastPing) <= MAIN_PING_TIMEOUT_MS
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        mainPingHandler.post(mainPingRunnable)
        if (prefsManager.isSpeedometerEnabled() && isServiceRunning(DisplayServerService::class.java)) {
            DisplayServerService.requestSpeedometerRefresh(requireContext())
        }
    }

    override fun onPause() {
        super.onPause()
        mainPingHandler.removeCallbacks(mainPingRunnable)
        mainPingHandler.removeCallbacks(reconcileDisplayModeRunnable)
    }

    private fun startStatusBlink() {
        if (statusBlinkAnimator?.isRunning == true) return
        statusBlinkAnimator = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 0.3f, 1f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopStatusBlink() {
        statusBlinkAnimator?.cancel()
        statusBlinkAnimator = null
        statusDot.alpha = 1f
    }

    override fun onDestroyView() {
        unregisterPairingReceiverIfNeeded()
        unregisterSpeedReceiverIfNeeded()
        statusBlinkAnimator?.cancel()
        super.onDestroyView()
    }

    private fun registerPairingReceiverIfNeeded() {
        if (pairingReceiverRegistered) return
        val filter = IntentFilter(HttpServer.ACTION_PAIRING_CHANGED)
        ContextCompat.registerReceiver(
            requireContext(),
            pairingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        pairingReceiverRegistered = true
    }

    private fun unregisterPairingReceiverIfNeeded() {
        if (!pairingReceiverRegistered) return
        try {
            requireContext().unregisterReceiver(pairingReceiver)
        } catch (_: Exception) {
        }
        pairingReceiverRegistered = false
    }

    private fun registerSpeedReceiverIfNeeded() {
        if (speedReceiverRegistered) return
        val filter = IntentFilter(DisplayServerService.ACTION_SPEED_UPDATED)
        ContextCompat.registerReceiver(
            requireContext(),
            speedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        speedReceiverRegistered = true
    }

    private fun unregisterSpeedReceiverIfNeeded() {
        if (!speedReceiverRegistered) return
        try {
            requireContext().unregisterReceiver(speedReceiver)
        } catch (_: Exception) {
        }
        speedReceiverRegistered = false
    }

    private fun updateSpeedUi() {
        val enabled = prefsManager.isSpeedometerEnabled()
        val hasPermission = DisplaySpeedMonitor.hasLocationPermission(requireContext())
        val hasBackground = DisplaySpeedMonitor.hasBackgroundLocationPermission(requireContext())
        val locationEnabled = DisplaySpeedMonitor.isLocationEnabled(requireContext())
        val serviceRunning = isServiceRunning(DisplayServerService::class.java)
        val speed = prefsManager.getLastSpeedKmh()
        val hasFix = prefsManager.hasSpeedFix()
        val provider = prefsManager.getLastSpeedProvider()
        val updatedAt = prefsManager.getLastSpeedUpdatedAt()

        tvSpeedValue.text = if (enabled && serviceRunning && hasPermission && locationEnabled && speed != null) speed.coerceIn(0, 999).toString() else "--"
        tvSpeedStatus.text = when {
            !enabled -> getString(R.string.display_speed_enable_hint)
            !serviceRunning -> getString(R.string.display_speed_turn_on_mode)
            !hasPermission -> getString(R.string.display_speed_location_required)
            !locationEnabled -> getString(R.string.display_speed_turn_on_location)
            !hasBackground -> getString(R.string.display_speed_alltime)
            speed == null || !hasFix -> getString(R.string.display_speed_waiting_gps)
            else -> {
                val ageSec = ((System.currentTimeMillis() - updatedAt) / 1000L).coerceAtLeast(0L)
                getString(R.string.display_speed_live_fmt, provider ?: getString(R.string.display_speed_provider_location), ageSec.toInt())
            }
        }
        tvSpeedStatus.setTextColor(
            requireContext().getColor(
                when {
                    !enabled || !serviceRunning || !hasPermission || !locationEnabled || !hasBackground -> R.color.aurora_warning
                    speed == null || !hasFix -> R.color.text_secondary
                    else -> R.color.aurora_success
                }
            )
        )
    }

    private fun updateDisplayPriorityLayout(isPaired: Boolean) {
        val parent = connectedStateCard.parent as? ViewGroup
        if (parent != null && isPaired) {
            val speedIndex = parent.indexOfChild(speedometerCard)
            val connectionIndex = parent.indexOfChild(connectedStateCard)
            if (speedIndex > connectionIndex) {
                parent.removeView(speedometerCard)
                parent.addView(speedometerCard, connectionIndex)
            }
        }
        updateSpeedometerLayout(isPaired)
        updatePairingCardLayout(isPaired)
    }

    private fun updateSpeedometerLayout(isPaired: Boolean) {
        val density = resources.displayMetrics.density

        speedometerCard.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = (if (isPaired) 10 else 18).dp(density)
        }

        speedometerContent.minimumHeight = (if (isPaired) 330 else 128).dp(density)
        speedometerContent.updatePadding(
            left = speedometerContent.paddingLeft,
            top = (if (isPaired) 30 else 18).dp(density),
            right = speedometerContent.paddingRight,
            bottom = (if (isPaired) 34 else 18).dp(density)
        )

        tvSpeedLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 18f else 14f)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            tvSpeedValue,
            if (isPaired) 72 else 40,
            if (isPaired) 150 else 54,
            2,
            TypedValue.COMPLEX_UNIT_SP
        )
        tvSpeedUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 24f else 15f)
        tvSpeedStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 16f else 12f)
    }

    private fun updatePairingCardLayout(isPaired: Boolean) {
        val density = resources.displayMetrics.density

        // The QR image is hosted by a FrameLayout in the current design.
        // Use the shared base params so resizing remains valid if its parent changes again.
        ivQrCode.updateLayoutParams<ViewGroup.LayoutParams> {
            width = (if (isPaired) 180 else 280).dp(density)
            height = (if (isPaired) 180 else 280).dp(density)
        }
        tvQrPayload.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 10f else 12f)

        layoutConnected.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = (if (isPaired) 12 else 20).dp(density)
        }
        layoutConnectedActions.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = (if (isPaired) 10 else 16).dp(density)
        }

        connectedStateContent.minimumHeight = (if (isPaired) 128 else 320).dp(density)
        connectedStateContent.updatePadding(
            left = connectedStateContent.paddingLeft,
            top = (if (isPaired) 14 else 28).dp(density),
            right = connectedStateContent.paddingRight,
            bottom = (if (isPaired) 14 else 28).dp(density)
        )

        ivConnectedIcon.updateLayoutParams<LinearLayout.LayoutParams> {
            width = (if (isPaired) 64 else 92).dp(density)
            height = (if (isPaired) 64 else 92).dp(density)
        }
        tvConnectedHeadline.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 18f else 30f)
        tvConnectedSubtext.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 13f else 18f)
        tvConnectedInstruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 13f else 14f)
    }

    private fun Int.dp(density: Float): Int = (this * density).toInt()
}
