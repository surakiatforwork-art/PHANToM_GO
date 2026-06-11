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
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
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
    private lateinit var antiTheftCard: MaterialCardView
    private lateinit var tvAntiTheftStatus: TextView
    private lateinit var tvAntiTheftDetail: TextView
    private lateinit var btnAntiTheftPrimary: Button
    private lateinit var btnAntiTheftSecondary: Button
    private var statusBlinkAnimator: ObjectAnimator? = null
    private var suppressDisplayModeToggleCallback = false
    private var pairingReceiverRegistered = false
    private var speedReceiverRegistered = false
    private var antiTheftReceiverRegistered = false
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

    private val antiTheftReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DisplayAntiTheftManager.ACTION_STATE_CHANGED) {
                activity?.runOnUiThread { updateAntiTheftUi() }
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
        antiTheftCard = root.findViewById(R.id.antiTheftCard)
        tvAntiTheftStatus = root.findViewById(R.id.tvAntiTheftStatus)
        tvAntiTheftDetail = root.findViewById(R.id.tvAntiTheftDetail)
        btnAntiTheftPrimary = root.findViewById(R.id.btnAntiTheftPrimary)
        btnAntiTheftSecondary = root.findViewById(R.id.btnAntiTheftSecondary)

        btnRefreshToken.setOnClickListener { refreshToken() }
        btnCopyToken.setOnClickListener { copyTokenToClipboard() }
        btnOpenLastNav.setOnClickListener { openLastNavigation() }
        btnResetPairing.setOnClickListener { confirmResetPairing() }
        btnBatterySettings.setOnClickListener { requestBatteryOptimization() }
        btnCopyPayload.setOnClickListener { copyPayloadToClipboard() }
        btnAntiTheftPrimary.setOnClickListener { handleAntiTheftPrimaryAction() }
        btnAntiTheftSecondary.setOnClickListener { handleAntiTheftSecondaryAction() }

        switchDisplayMode.setOnCheckedChangeListener { _, isChecked ->
            if (suppressDisplayModeToggleCallback) return@setOnCheckedChangeListener
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
                Toast.makeText(requireContext(), "Some permissions were denied. Features may not work properly.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerPairingReceiverIfNeeded()
        registerSpeedReceiverIfNeeded()
        registerAntiTheftReceiverIfNeeded()
    }

    override fun onStop() {
        unregisterPairingReceiverIfNeeded()
        unregisterSpeedReceiverIfNeeded()
        unregisterAntiTheftReceiverIfNeeded()
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
        suppressDisplayModeToggleCallback = true
        switchDisplayMode.isChecked = isServiceRunning
        suppressDisplayModeToggleCallback = false
        tvDisplayModeStatus.text = if (isServiceRunning) "ON" else "OFF"
        tvDisplayModeStatus.setTextColor(requireContext().getColor(if (isServiceRunning) R.color.success else R.color.gray_600))

        setMapLinkHandlerEnabled(!isServiceRunning, "DisplayFragment.updateUI")

        tvServerInfo.text = if (isServiceRunning) "Server: $ip:$port" else "Server not running"
        updateBatteryStatus()
        updateSpeedUi()
        updateDisplayPriorityLayout(isPaired)
        updateAntiTheftUi()

        if (isPaired) {
            val pairedName = prefsManager.getDisplayPairedMainName() ?: "Unknown"
            val pairedAt = prefsManager.getDisplayPairedAt()
            val timeAgo = if (pairedAt > 0) {
                val minutes = (System.currentTimeMillis() - pairedAt) / 60000
                if (minutes < 60) "${minutes}m ago" else "${minutes / 60}h ago"
            } else {
                "Unknown"
            }

            appIcon.visibility = View.GONE
            titleText.text = "Display Connected"
            tvIpPort.visibility = View.GONE
            tvTokenHint.visibility = View.GONE
            buttonLayout.visibility = View.GONE
            tvPairedInfo.text = "Main device: $pairedName  •  Paired $timeAgo"
            tvConnectedHeadline.text = if (isMainActive) "Connected" else "Paired"
            tvConnectedSubtext.text = if (isMainActive) {
                "Ready to receive the latest route from $pairedName"
            } else {
                "Still paired with $pairedName"
            }
            tvConnectedInstruction.text = "Tap disconnect below if you want to pair a different Main device."
            btnResetPairing.text = "Disconnect Main Device"
            layoutQR.visibility = View.GONE
            layoutConnected.visibility = View.VISIBLE
            ivConnectedIcon.visibility = View.VISIBLE
            layoutConnectedActions.visibility = View.VISIBLE
        } else {
            appIcon.visibility = View.VISIBLE
            titleText.text = getString(R.string.app_name_display)
            tvIpPort.visibility = View.VISIBLE
            tvTokenHint.visibility = View.VISIBLE
            buttonLayout.visibility = View.VISIBLE
            tvPairedInfo.text = "Scan this QR code with Main device"
            tvConnectedHeadline.text = "Waiting for Pairing"
            tvConnectedSubtext.text = "Show this screen on the car display, then scan from the Main device."
            tvConnectedInstruction.text = "Once paired, this screen will switch to connected mode automatically."
            btnResetPairing.text = "Disconnect / Reset"
            layoutQR.visibility = View.VISIBLE
            layoutConnected.visibility = View.VISIBLE
            ivConnectedIcon.visibility = View.GONE
            layoutConnectedActions.visibility = View.GONE
        }

        updateConnectionIndicator(isPaired, isMainActive)

        val lastUrl = prefsManager.getPrefs().getString("last_url", null)
        btnOpenLastNav.isEnabled = !lastUrl.isNullOrEmpty()

        val displayDeviceId = prefsManager.getLocalDeviceId()
        val cloudPairCode = prefsManager.getCloudPairCode()

        FirebaseManager.publishPairingCode(requireContext(), ip, port, token, "display-update-ui")
        val qrPayload = buildCloudPairPayload(displayDeviceId, cloudPairCode, ip, port, token)

        tvQrPayload.text = qrPayload
        tvQrPayload.visibility = if (isPaired) View.GONE else View.VISIBLE
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
            switchDisplayMode.isChecked = false
            Toast.makeText(requireContext(), "Failed to start server: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopDisplayService() {
        try {
            val intent = Intent(requireContext(), DisplayServerService::class.java)
            requireContext().stopService(intent)
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
            tvBatteryStatus.text = "Battery optimization: OFF"
            tvBatteryStatus.setTextColor(requireContext().getColor(R.color.success))
            btnBatterySettings.visibility = View.GONE
        } else {
            tvBatteryStatus.text = "Battery optimization: ON"
            tvBatteryStatus.setTextColor(requireContext().getColor(R.color.warning))
            btnBatterySettings.visibility = View.VISIBLE
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AlertDialog.Builder(requireContext())
                .setTitle("Battery Optimization")
                .setMessage("To ensure Display Mode works reliably in the background, please disable battery optimization for PHANToM GO.")
                .setPositiveButton("Settings") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${requireContext().packageName}")
                        }
                        startActivityForResult(intent, 1002)
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                .setNegativeButton("Cancel", null)
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
        Toast.makeText(requireContext(), "QR Payload copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun openLastNavigation() {
        val lastUrl = prefsManager.getPrefs().getString("last_url", null)
        if (lastUrl.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No recent navigation found", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(requireContext(), "Token refreshed", Toast.LENGTH_SHORT).show()
    }

    private fun copyTokenToClipboard() {
        val token = prefsManager.getServerToken()
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PHANToM GO Token", token))
        Toast.makeText(requireContext(), "Token copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun confirmResetPairing() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset Pairing?")
            .setMessage("This will remove the current pairing and generate a new token. The Main device will need to scan QR again.")
            .setPositiveButton("Reset") { _, _ ->
                val previousPairCode = prefsManager.peekCloudPairCode()
                prefsManager.refreshServerToken()
                prefsManager.refreshCloudPairCode()
                FirebaseManager.resetDisplayPairingSession(requireContext(), previousPairCode, "display-reset-pairing")
                updateUI()
                Toast.makeText(requireContext(), "Pairing reset and token refreshed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateConnectionIndicator(isPaired: Boolean, isMainActive: Boolean) {
        if (isPaired && isMainActive) {
            statusDot.visibility = View.VISIBLE
            statusDot.setBackgroundResource(R.drawable.status_dot_cyan)
            statusGhost.visibility = View.GONE
            tvStatus.text = "Connected (Wi‑Fi)"
            tvStatus.setTextColor(requireContext().getColor(R.color.aurora_cyan))
            startStatusBlink()
        } else {
            stopStatusBlink()
            statusDot.visibility = View.GONE
            statusGhost.visibility = View.VISIBLE
            statusGhost.text = "👻💤"
            tvStatus.text = if (isPaired) "Main Disconnected" else "Disconnected"
            tvStatus.setTextColor(requireContext().getColor(R.color.ghost_purple_dark))
        }
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
        updateAntiTheftUi()
    }

    override fun onPause() {
        super.onPause()
        mainPingHandler.removeCallbacks(mainPingRunnable)
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
        unregisterAntiTheftReceiverIfNeeded()
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

    private fun registerAntiTheftReceiverIfNeeded() {
        if (antiTheftReceiverRegistered) return
        val filter = IntentFilter(DisplayAntiTheftManager.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            requireContext(),
            antiTheftReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        antiTheftReceiverRegistered = true
    }

    private fun unregisterAntiTheftReceiverIfNeeded() {
        if (!antiTheftReceiverRegistered) return
        try {
            requireContext().unregisterReceiver(antiTheftReceiver)
        } catch (_: Exception) {
        }
        antiTheftReceiverRegistered = false
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

        tvSpeedValue.text = if (enabled && serviceRunning && hasPermission && locationEnabled && speed != null) speed.toString() else "--"
        tvSpeedStatus.text = when {
            !enabled -> "Enable speedometer in Settings"
            !serviceRunning -> "Turn on Display Mode to start speedometer"
            !hasPermission -> "Location permission required"
            !locationEnabled -> "Turn on device location"
            !hasBackground -> "Allow all-time location in Settings"
            speed == null || !hasFix -> "Waiting for GPS speed..."
            else -> {
                val ageSec = ((System.currentTimeMillis() - updatedAt) / 1000L).coerceAtLeast(0L)
                "Live via ${provider ?: "location"} • updated ${ageSec}s ago"
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
        updateSpeedometerLayout(isPaired)
        updatePairingCardLayout(isPaired)
    }

    private fun updateSpeedometerLayout(isPaired: Boolean) {
        val density = resources.displayMetrics.density

        speedometerCard.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = (if (isPaired) 10 else 18).dp(density)
        }

        speedometerContent.minimumHeight = (if (isPaired) 360 else 128).dp(density)
        speedometerContent.updatePadding(
            left = speedometerContent.paddingLeft,
            top = (if (isPaired) 30 else 18).dp(density),
            right = speedometerContent.paddingRight,
            bottom = (if (isPaired) 34 else 18).dp(density)
        )

        tvSpeedLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 18f else 14f)
        tvSpeedValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 128f else 46f)
        tvSpeedUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 28f else 15f)
        tvSpeedStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 16f else 12f)

        tvSpeedUnit.updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = (if (isPaired) 14 else 10).dp(density)
            bottomMargin = (if (isPaired) 22 else 9).dp(density)
        }
    }

    private fun updatePairingCardLayout(isPaired: Boolean) {
        val density = resources.displayMetrics.density

        ivQrCode.updateLayoutParams<LinearLayout.LayoutParams> {
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

        connectedStateContent.minimumHeight = (if (isPaired) 220 else 320).dp(density)
        connectedStateContent.updatePadding(
            left = connectedStateContent.paddingLeft,
            top = (if (isPaired) 20 else 28).dp(density),
            right = connectedStateContent.paddingRight,
            bottom = (if (isPaired) 20 else 28).dp(density)
        )

        ivConnectedIcon.updateLayoutParams<LinearLayout.LayoutParams> {
            width = (if (isPaired) 64 else 92).dp(density)
            height = (if (isPaired) 64 else 92).dp(density)
        }
        tvConnectedHeadline.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 24f else 30f)
        tvConnectedSubtext.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 16f else 18f)
        tvConnectedInstruction.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPaired) 13f else 14f)
    }

    private fun updateAntiTheftUi() {
        val snapshot = DisplayAntiTheftManager.snapshot(requireContext())
        val visible = DisplayAntiTheftManager.isVisibleOnDisplay(requireContext())
        antiTheftCard.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        tvAntiTheftStatus.text = snapshot.headline
        tvAntiTheftDetail.text = snapshot.detail

        when {
            !snapshot.featureEnabled -> {
                btnAntiTheftPrimary.text = "Open Settings"
                btnAntiTheftSecondary.text = if (snapshot.hasPin) "Change PIN" else "Set PIN"
                btnAntiTheftPrimary.isEnabled = true
                btnAntiTheftSecondary.isEnabled = true
            }
            !snapshot.hasPin -> {
                btnAntiTheftPrimary.text = "Set PIN"
                btnAntiTheftSecondary.text = "Open Settings"
                btnAntiTheftPrimary.isEnabled = true
                btnAntiTheftSecondary.isEnabled = true
            }
            snapshot.armed -> {
                btnAntiTheftPrimary.text = "Disarm"
                btnAntiTheftSecondary.text = "Change PIN"
                btnAntiTheftPrimary.isEnabled = true
                btnAntiTheftSecondary.isEnabled = true
            }
            else -> {
                btnAntiTheftPrimary.text = "Arm Anti-Theft"
                btnAntiTheftSecondary.text = "Change PIN"
                btnAntiTheftPrimary.isEnabled = snapshot.headsetPlugged
                btnAntiTheftSecondary.isEnabled = true
            }
        }

        btnAntiTheftPrimary.alpha = if (btnAntiTheftPrimary.isEnabled) 1f else 0.55f
        btnAntiTheftSecondary.alpha = if (btnAntiTheftSecondary.isEnabled) 1f else 0.55f
    }

    private fun handleAntiTheftPrimaryAction() {
        val snapshot = DisplayAntiTheftManager.snapshot(requireContext())
        when {
            !snapshot.featureEnabled -> {
                (activity as? MainActivity)?.selectTab(BottomNavHelper.Tab.SETTINGS)
            }
            !snapshot.hasPin -> {
                promptAntiTheftPinChange()
            }
            snapshot.armed -> {
                promptAntiTheftDisarm()
            }
            else -> {
                val error = DisplayAntiTheftManager.arm(requireContext())
                if (error != null) {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                }
                updateAntiTheftUi()
            }
        }
    }

    private fun handleAntiTheftSecondaryAction() {
        val snapshot = DisplayAntiTheftManager.snapshot(requireContext())
        if (!snapshot.featureEnabled && !snapshot.hasPin) {
            (activity as? MainActivity)?.selectTab(BottomNavHelper.Tab.SETTINGS)
        } else {
            promptAntiTheftPinChange()
        }
    }

    private fun promptAntiTheftPinChange() {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN (4-6 digits)"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Set Anti-Theft PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text?.toString().orEmpty().trim()
                val ok = DisplayAntiTheftManager.savePin(requireContext(), pin)
                Toast.makeText(
                    requireContext(),
                    if (ok) "Anti-Theft PIN saved" else "PIN must be 4-6 digits",
                    Toast.LENGTH_SHORT
                ).show()
                updateAntiTheftUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptAntiTheftDisarm() {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter PIN"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Disarm Anti-Theft")
            .setMessage("Enter your PIN to turn off anti-theft protection.")
            .setView(input)
            .setPositiveButton("Disarm") { _, _ ->
                val ok = DisplayAntiTheftManager.disarmWithPin(requireContext(), input.text?.toString().orEmpty())
                Toast.makeText(
                    requireContext(),
                    if (ok) "Anti-Theft disarmed" else "Wrong PIN",
                    Toast.LENGTH_SHORT
                ).show()
                updateAntiTheftUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun Int.dp(density: Float): Int = (this * density).toInt()
}
