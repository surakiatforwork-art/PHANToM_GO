package com.phantom.carnavrelay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
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
import android.util.Log
import android.view.View
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.common.BitMatrix
import com.phantom.carnavrelay.util.buildCloudPairPayload
import com.phantom.carnavrelay.util.generateTokenHex
import java.util.EnumMap

class DisplayActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val MAIN_PING_CHECK_INTERVAL_MS = 4000L
        private const val MAIN_PING_TIMEOUT_MS = 12000L
    }

    private lateinit var prefsManager: PrefsManager
    private lateinit var appIcon: ImageView
    private lateinit var titleText: TextView
    private lateinit var tvIpPort: TextView
    private lateinit var tvTokenHint: TextView
    private lateinit var ivQrCode: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var statusDot: View
    private lateinit var statusGhost: TextView
    private lateinit var tvPairedInfo: TextView
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
    private var pairingReceiverRegistered = false
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
                val paired = intent.getBooleanExtra("paired", false)
                Log.d(TAG, "📢 Received PAIRING_CHANGED broadcast: paired=$paired")
                runOnUiThread {
                    updateUI()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "▶️ DisplayActivity.onCreate()")
        setContentView(R.layout.activity_display)

        prefsManager = PrefsManager(this)

        initViews()
        checkPermissions()
        setMapLinkHandlerEnabled(false, "DisplayActivity.onCreate")
        updateUI()
        startHttpServer()
    }

    private fun initViews() {
        appIcon = findViewById(R.id.appIcon)
        titleText = findViewById(R.id.titleText)
        tvIpPort = findViewById(R.id.tvIpPort)
        tvTokenHint = findViewById(R.id.tvTokenHint)
        ivQrCode = findViewById(R.id.ivQrCode)
        tvStatus = findViewById(R.id.tvStatus)
        statusDot = findViewById(R.id.statusDot)
        statusGhost = findViewById(R.id.statusGhost)
        tvPairedInfo = findViewById(R.id.tvPairedInfo)
        btnRefreshToken = findViewById(R.id.btnRefreshToken)
        btnCopyToken = findViewById(R.id.btnCopyToken)
        btnResetPairing = findViewById(R.id.btnResetPairing)
        buttonLayout = findViewById(R.id.buttonLayout)
        layoutQR = findViewById(R.id.layoutQR)
        layoutConnected = findViewById(R.id.layoutConnected)
        layoutConnectedActions = findViewById(R.id.layoutConnectedActions)
        tvConnectedHeadline = findViewById(R.id.tvConnectedHeadline)
        tvConnectedSubtext = findViewById(R.id.tvConnectedSubtext)
        tvConnectedInstruction = findViewById(R.id.tvConnectedInstruction)
        btnOpenLastNav = findViewById(R.id.btnOpenLastNav)
        switchDisplayMode = findViewById(R.id.switchDisplayMode)
        tvDisplayModeStatus = findViewById(R.id.tvDisplayModeStatus)
        tvServerInfo = findViewById(R.id.tvServerInfo)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnBatterySettings = findViewById(R.id.btnBatterySettings)
        tvQrPayload = findViewById(R.id.tvQrPayload)
        btnCopyPayload = findViewById(R.id.btnCopyPayload)

        btnRefreshToken.setOnClickListener {
            Log.d(TAG, "CLICK: btnRefreshToken on DisplayActivity")
            refreshToken()
        }

        btnCopyToken.setOnClickListener {
            Log.d(TAG, "CLICK: btnCopyToken on DisplayActivity")
            copyTokenToClipboard()
        }

        btnOpenLastNav.setOnClickListener {
            Log.d(TAG, "CLICK: btnOpenLastNav on DisplayActivity")
            openLastNavigation()
        }

        btnResetPairing.setOnClickListener {
            Log.d(TAG, "CLICK: btnResetPairing on DisplayActivity")
            confirmResetPairing()
        }

        switchDisplayMode.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "CLICK: switchDisplayMode on DisplayActivity -> $isChecked")
            if (isChecked) {
                startDisplayService()
            } else {
                stopDisplayService()
            }
        }

        btnBatterySettings.setOnClickListener {
            Log.d(TAG, "CLICK: btnBatterySettings on DisplayActivity")
            requestBatteryOptimization()
        }

        btnCopyPayload.setOnClickListener {
            Log.d(TAG, "CLICK: btnCopyPayload on DisplayActivity")
            copyPayloadToClipboard()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "🔕 Notification permission not granted (request in Settings)")
                PhantomLog.w("NOTIF permission missing (DisplayActivity)")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                Log.d(TAG, "🔒 Permission request result - all granted: $allGranted")
                if (allGranted) {
                    Log.d(TAG, "✅ All permissions granted")
                } else {
                    Log.w(TAG, "⚠️ Some permissions denied")
                    Toast.makeText(this, "Some permissions were denied. Features may not work properly.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerPairingReceiverIfNeeded()
    }

    override fun onStop() {
        unregisterPairingReceiverIfNeeded()
        super.onStop()
    }

    private fun startHttpServer() {
        Log.d(TAG, "🚀 Starting DisplayServerService")
        try {
            val intent = Intent(this, DisplayServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to start server", e)
            Toast.makeText(this, "Failed to start server: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUI() {
        val tempServer = HttpServer(this, prefsManager)
        val ip = tempServer.getLocalIpAddress() ?: "0.0.0.0"
        val port = prefsManager.getServerPort()
        val token = prefsManager.getServerToken()
        val tokenHint = prefsManager.getTokenHint(token)
        val isPaired = prefsManager.isDisplayPaired()
        val isMainActive = isMainRecentlyConnected()

        tvIpPort.text = "$ip:$port"
        tvTokenHint.text = tokenHint
        
        // Update Display Mode UI
        val isServiceRunning = isServiceRunning(DisplayServerService::class.java)
        switchDisplayMode.isChecked = isServiceRunning
        tvDisplayModeStatus.text = if (isServiceRunning) "ON" else "OFF"
        tvDisplayModeStatus.setTextColor(if (isServiceRunning) getColor(R.color.success) else getColor(R.color.gray_600))

        setMapLinkHandlerEnabled(!isServiceRunning, "DisplayActivity.updateUI")
        
        if (isServiceRunning) {
            tvServerInfo.text = "Server: $ip:$port"
        } else {
            tvServerInfo.text = "Server not running"
        }

        // Update battery optimization status
        updateBatteryStatus()

        if (isPaired) {
            val pairedName = prefsManager.getDisplayPairedMainName() ?: "Unknown"
            val pairedAt = prefsManager.getDisplayPairedAt()
            val timeAgo = if (pairedAt > 0) {
                val minutes = (System.currentTimeMillis() - pairedAt) / 60000
                if (minutes < 60) "${minutes}m ago" else "${minutes/60}h ago"
            } else "Unknown"

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
            layoutConnected.visibility = View.GONE
            layoutConnectedActions.visibility = View.GONE
        }

        updateConnectionIndicator(isPaired, isMainActive)

        val lastUrl = prefsManager.getPrefs().getString("last_url", null)
        btnOpenLastNav.isEnabled = !lastUrl.isNullOrEmpty()

        val displayDeviceId = prefsManager.getLocalDeviceId()
        val cloudPairCode = prefsManager.getCloudPairCode()

        // Publish the current display pairing ticket to Firebase.
        FirebaseManager.publishPairingCode(this, ip, port, token, "display-update-ui")
        val qrPayload = buildCloudPairPayload(displayDeviceId, cloudPairCode, ip, port, token)
        Log.d(TAG, "📋 QR Payload: $qrPayload")
        
        // Update payload display
        tvQrPayload.text = qrPayload
        tvQrPayload.visibility = if (isPaired) View.GONE else View.VISIBLE
        btnCopyPayload.visibility = if (isPaired) View.GONE else View.VISIBLE
        
        generateQRCode(qrPayload)
    }
    
    private fun startDisplayService() {
        Log.d(TAG, "🚀 Starting DisplayServerService")
        try {
            val intent = Intent(this, DisplayServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            updateUI()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to start server", e)
            switchDisplayMode.isChecked = false
            Toast.makeText(this, "Failed to start server: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun stopDisplayService() {
        Log.d(TAG, "🛑 Stopping DisplayServerService")
        try {
            val intent = Intent(this, DisplayServerService::class.java)
            stopService(intent)
            updateUI()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to stop server", e)
        }
    }

    private fun startBluetoothServer() {
        try {
            val btIntent = Intent(this, com.phantom.carnavrelay.bt.DisplayServerService::class.java).apply {
                putExtra("code", prefsManager.getServerToken())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(btIntent)
            } else {
                startService(btIntent)
            }
            Log.d(TAG, "✅ BT DisplayServerService start requested")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ BT DisplayServerService not started: ${e.message}")
        }
    }

    private fun stopBluetoothServer() {
        try {
            stopService(Intent(this, com.phantom.carnavrelay.bt.DisplayServerService::class.java))
            Log.d(TAG, "🛑 BT DisplayServerService stop requested")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to stop BT DisplayServerService", e)
        }
    }
    
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            for (service in services) {
                if (serviceClass.name == service.service.className) {
                    return service.foreground
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Could not check service status", e)
            false
        }
    }

    private fun setMapLinkHandlerEnabled(enabled: Boolean, reason: String) {
        val component = ComponentName(this, "${packageName}.MapLinkHandlerAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(
            component,
            state,
            PackageManager.DONT_KILL_APP
        )
        Log.d(TAG, "🔧 MapLinkHandlerAlias ${if (enabled) "ENABLED" else "DISABLED"} ($reason)")
        PhantomLog.i("MapLinkHandlerAlias ${if (enabled) "ENABLED" else "DISABLED"} ($reason)")
    }
    
    private fun updateBatteryStatus() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true // Below Android 6, battery optimizations are less aggressive
        }
        
        if (isIgnoringBatteryOptimizations) {
            tvBatteryStatus.text = "Battery optimization: OFF"
            tvBatteryStatus.setTextColor(getColor(R.color.success))
            btnBatterySettings.visibility = View.GONE
        } else {
            tvBatteryStatus.text = "Battery optimization: ON"
            tvBatteryStatus.setTextColor(getColor(R.color.warning))
            btnBatterySettings.visibility = View.VISIBLE
        }
    }
    
    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage("To ensure Display Mode works reliably in the background, please disable battery optimization for PHANToM GO.")
                .setPositiveButton("Settings") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivityForResult(intent, 1002)
                    } catch (e: Exception) {
                        // Fallback to general settings
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
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
            val bitMatrix: BitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            ivQrCode.setImageBitmap(bitmap)
            Log.d(TAG, "✅ QR code generated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to generate QR code", e)
        }
    }
    
    private fun copyPayloadToClipboard() {
        val payload = tvQrPayload.text.toString()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("PHANToM GO QR Payload", payload)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "QR Payload copied to clipboard", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "📋 QR Payload copied to clipboard")
    }

    private fun openLastNavigation() {
        val prefs = prefsManager.getPrefs()
        val lastUrl = prefs.getString("last_url", null)

        if (lastUrl.isNullOrEmpty()) {
            Toast.makeText(this, "No recent navigation found", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "🧭 Opening last navigation URL: $lastUrl")
        val intent = Intent(this, OpenNavigationActivity::class.java).apply {
            putExtra("url", lastUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
    
    private fun refreshToken() {
        Log.d(TAG, "🔄 Refreshing token...")
        val previousPairCode = prefsManager.peekCloudPairCode()
        prefsManager.refreshServerToken()
        prefsManager.refreshCloudPairCode()
        FirebaseManager.resetDisplayPairingSession(this, previousPairCode, "display-refresh-token")
        startHttpServer()
        updateUI()
        Toast.makeText(this, "Token refreshed", Toast.LENGTH_SHORT).show()
    }
    
    private fun copyTokenToClipboard() {
        val token = prefsManager.getServerToken()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("PHANToM GO Token", token)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Token copied to clipboard", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "📋 Token copied to clipboard")
    }
    
    private fun confirmResetPairing() {
        AlertDialog.Builder(this)
            .setTitle("Reset Pairing?")
            .setMessage("This will remove the current pairing and generate a new token. The Main device will need to scan QR again.")
            .setPositiveButton("Reset") { _, _ ->
                Log.d(TAG, "�️ Resetting pairing")
                val previousPairCode = prefsManager.peekCloudPairCode()
                prefsManager.refreshServerToken()
                prefsManager.refreshCloudPairCode()
                FirebaseManager.resetDisplayPairingSession(this, previousPairCode, "display-reset-pairing")
                startHttpServer()
                updateUI()
                Toast.makeText(this, "Pairing reset and token refreshed", Toast.LENGTH_SHORT).show()
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
            tvStatus.setTextColor(getColor(R.color.aurora_cyan))
            startStatusBlink()
        } else {
            stopStatusBlink()
            statusDot.visibility = View.GONE
            statusGhost.visibility = View.VISIBLE
            statusGhost.text = "👻💤"
            tvStatus.text = if (isPaired) "Main Disconnected" else "Disconnected"
            tvStatus.setTextColor(getColor(R.color.ghost_purple_dark))
        }
    }

    private fun isMainRecentlyConnected(): Boolean {
        val lastPing = prefsManager.getLastMainPingAt()
        if (lastPing <= 0L) return false
        return (System.currentTimeMillis() - lastPing) <= MAIN_PING_TIMEOUT_MS
    }

    private fun isBtRecentlyConnected(): Boolean {
        val lastBt = prefsManager.getLastBtClientAt()
        if (lastBt <= 0L) return false
        return (System.currentTimeMillis() - lastBt) <= MAIN_PING_TIMEOUT_MS
    }

    override fun onResume() {
        super.onResume()
        FirebaseManager.startDisplayCommandListener(this, "display-activity-resume")
        updateUI()
        mainPingHandler.post(mainPingRunnable)
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

    override fun onDestroy() {
        unregisterPairingReceiverIfNeeded()
        statusBlinkAnimator?.cancel()
        super.onDestroy()
    }

    private fun registerPairingReceiverIfNeeded() {
        if (pairingReceiverRegistered) return
        val filter = IntentFilter(HttpServer.ACTION_PAIRING_CHANGED)
        ContextCompat.registerReceiver(
            this,
            pairingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        pairingReceiverRegistered = true
    }

    private fun unregisterPairingReceiverIfNeeded() {
        if (!pairingReceiverRegistered) return
        try {
            unregisterReceiver(pairingReceiver)
        } catch (_: Exception) {
            // Ignore stale unregister attempts from activity teardown.
        }
        pairingReceiverRegistered = false
    }
}
