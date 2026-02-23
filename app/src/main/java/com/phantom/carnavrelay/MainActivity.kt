package com.phantom.carnavrelay

import android.Manifest
import android.content.ComponentName
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val CAMERA_PERMISSION_REQUEST = 1001
        private const val STATUS_CHECK_INTERVAL_MS = 4000L  // Check status every 4 seconds
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
    private lateinit var btnClearPairing: Button
    private lateinit var tvA11yToggle: TextView
    private lateinit var tvA11yStatus: TextView
    private lateinit var cardDisplay: MaterialCardView
    private lateinit var ghostIcon: TextView
    private var ghostAnimator: ObjectAnimator? = null

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "▶️ MainActivity.onCreate()")
        setContentView(R.layout.activity_main_drawer)

        prefsManager = PrefsManager(this)
        mainSender = MainSender(this)

        initViews()
        setupDrawer()
        updateUI()
        updateA11yUI()
        
        // Check for crash reports
        CrashReporter.checkAndShowCrashReport(this)
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        
        tvPairStatus = findViewById(R.id.tvPairStatus)
        tvPairInfo = findViewById(R.id.tvPairInfo)
        tvPendingCount = findViewById(R.id.tvPendingCount)
        tvConnectionState = findViewById(R.id.tvConnectionState)
        btnScanQR = findViewById(R.id.btnScanQR)
        btnSendTest = findViewById(R.id.btnSendTest)
        btnRetryPending = findViewById(R.id.btnRetryPending)
        btnClearPairing = findViewById(R.id.btnClearPairing)
        tvA11yToggle = findViewById(R.id.tvA11yToggle)
        tvA11yStatus = findViewById(R.id.tvA11yStatus)
        cardDisplay = findViewById(R.id.cardDisplay)
        ghostIcon = findViewById(R.id.ghostIcon)

        startGhostAnimation()

        // Setup button press animations and haptic feedback
        setupButtonEffects(btnScanQR)
        setupButtonEffects(btnSendTest)
        setupButtonEffects(btnRetryPending)
        setupButtonEffects(btnClearPairing)

        btnScanQR.setOnClickListener {
            Log.d(TAG, "CLICK: btnScanQR on MainActivity")
            startQRScan()
        }

        btnSendTest.setOnClickListener {
            Log.d(TAG, "CLICK: btnSendTest on MainActivity")
            sendTestLocation()
        }

        btnRetryPending.setOnClickListener {
            Log.d(TAG, "CLICK: btnRetryPending on MainActivity")
            retryPending()
        }

        btnClearPairing.setOnClickListener {
            Log.d(TAG, "CLICK: btnClearPairing on MainActivity")
            confirmClearPairing()
        }

        // Display Mode Card Click
        cardDisplay.setOnClickListener {
            Log.d(TAG, "CLICK: cardDisplay on MainActivity")
            openDisplayMode()
        }
    }

    private fun setupDrawer() {
        toolbar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    Log.d(TAG, "CLICK: nav_settings on MainActivity")
                    openSettings()
                }
                R.id.nav_logs -> {
                    Log.d(TAG, "CLICK: nav_logs on MainActivity")
                    openSettings()
                }
                R.id.nav_about -> {
                    Log.d(TAG, "CLICK: nav_about on MainActivity")
                    showAboutDialog()
                }
                R.id.nav_switch_mode -> {
                    Log.d(TAG, "CLICK: nav_switch_mode on MainActivity")
                    openDisplayMode()
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
                    v.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(100)
                        .start()
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_UP -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
            }
            false // Don't consume; allow click handling
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

    private fun openSettings() {
        Log.d(TAG, "⚙️ Opening Settings")
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun openDisplayMode() {
        Log.d(TAG, "🚀 Opening Display Mode")
        val intent = Intent(this, DisplayActivity::class.java)
        startActivity(intent)
    }

    private fun startQRScan() {
        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }
            return
        }

        Log.d(TAG, "📷 Launching in-app QR scanner")
        val intent = Intent(this, PairScanActivity::class.java)
        scanLauncher.launch(intent)
    }

    private fun sendTestLocation() {
        if (!mainSender.isPaired()) {
            Toast.makeText(this, "Not paired with Display device", Toast.LENGTH_SHORT).show()
            return
        }

        val testUrl = "https://www.google.com/maps/search/?api=1&query=13.7563,100.5018"
        mainSender.sendOpenUrl(testUrl, object : MainSender.Companion.SendCallback {
            override fun onSuccess() {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "✅ Test location sent", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(error: String, queued: Boolean, authFailed: Boolean) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ Failed to send test location", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun retryPending() {
        if (!mainSender.isPaired()) {
            Toast.makeText(this, "Not paired with Display device", Toast.LENGTH_SHORT).show()
            return
        }

        val pending = prefsManager.getPendingQueue()
        if (pending.isEmpty()) {
            Toast.makeText(this, "No pending URLs", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "🔄 Retrying ${pending.size} pending URLs")
        
        mainSender.retryPending(object : MainSender.Companion.SendCallback {
            override fun onSuccess() {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "✅ All pending URLs sent", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            }
            override fun onFailure(error: String, queued: Boolean, authFailed: Boolean) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "❌ Some URLs failed: $error", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            }
        })
    }

    private fun confirmClearPairing() {
        AlertDialog.Builder(this)
            .setTitle("Clear Pairing")
            .setMessage("Are you sure you want to clear the pairing with the Display device?")
            .setPositiveButton("Clear") { _, _ ->
                clearPairing()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearPairing() {
        prefsManager.clearPairing()
        mainSender.clearPending()
        Toast.makeText(this, "✅ Pairing cleared", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun checkServerStatus() {
        if (!mainSender.isPaired()) {
            Log.w(TAG, "❌ Not paired, cannot check server status")
            return
        }

        mainSender.checkServerStatus { success, error ->
            runOnUiThread {
                if (success) {
                    Log.d(TAG, "✅ Server status check passed")
                } else {
                    Log.w(TAG, "❌ Server status check failed: $error")
                }
                updateUI()
            }
        }
    }
    
    private fun updateA11yUI() {
        // Accessibility service has been removed
        // Hide or disable accessibility-related UI elements
        tvA11yToggle?.text = "REMOVED"
        tvA11yToggle?.setTextColor(getColor(R.color.aurora_disconnected))
        tvA11yStatus?.text = "Accessibility features removed"
    }

    private fun updateUI() {
        val isPaired = mainSender.isPaired()
        val pendingCount = prefsManager.getPendingCount()
        val state = mainSender.getCurrentState()

        // Update pairing status
        if (isPaired) {
            tvPairStatus.text = "✅ Paired"
            tvPairStatus.setTextColor(getColor(R.color.aurora_success))
            tvPairInfo.text = mainSender.getPairedInfo()
        } else {
            tvPairStatus.text = "Not Paired"
            tvPairStatus.setTextColor(getColor(R.color.aurora_waiting))
            tvPairInfo.text = "Scan QR code from Display device"
        }

        when (state) {
            MainSender.STATE_AUTH_FAILED -> {
                tvConnectionState.text = "🔴 Auth failed"
                tvConnectionState.setTextColor(getColor(R.color.aurora_auth_failed))
                tvConnectionState.setBackgroundResource(R.drawable.badge_error)
            }
            MainSender.STATE_OFFLINE -> {
                tvConnectionState.text = "🔴 Disconnected"
                tvConnectionState.setTextColor(getColor(R.color.aurora_error))
                tvConnectionState.setBackgroundResource(R.drawable.badge_error)
            }
            MainSender.STATE_CONNECTED -> {
                tvConnectionState.text = "🟢 Connected"
                tvConnectionState.setTextColor(getColor(R.color.aurora_success))
                tvConnectionState.setBackgroundResource(R.drawable.badge_success)
            }
            MainSender.STATE_PAIRING, MainSender.STATE_CONNECTING -> {
                tvConnectionState.text = "🟡 Connecting"
                tvConnectionState.setTextColor(getColor(R.color.aurora_warning))
                tvConnectionState.setBackgroundResource(R.drawable.badge_warning)
            }
            else -> {
                tvConnectionState.text = if (isPaired) "🟡 Waiting" else "🟡 Waiting for Pairing"
                tvConnectionState.setTextColor(getColor(R.color.aurora_waiting))
                tvConnectionState.setBackgroundResource(R.drawable.badge_neutral)
            }
        }

        // Update pending count
        tvPendingCount.text = "Pending: $pendingCount"
        if (pendingCount > 0) {
            tvPendingCount.setTextColor(getColor(R.color.aurora_warning))
            tvPendingCount.setBackgroundResource(R.drawable.badge_warning)
        } else {
            tvPendingCount.setTextColor(getColor(R.color.aurora_success))
            tvPendingCount.setBackgroundResource(R.drawable.badge_success)
        }

        // Update button states
        btnSendTest.isEnabled = isPaired
        btnRetryPending.isEnabled = isPaired && pendingCount > 0
        btnClearPairing.isEnabled = isPaired
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "▶️ MainActivity.onResume()")
        setMapLinkHandlerEnabled(!isDisplayServiceRunning(), "MainActivity.onResume")
        MainSender.triggerAutoReconnect(this, "main-resume")
        updateUI()
        handler.post(statusCheckRunnable)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "⏸️ MainActivity.onPause()")
        handler.removeCallbacks(statusCheckRunnable)
    }

    private fun isDisplayServiceRunning(): Boolean {
        return try {
            val activityManager = getSystemService(android.app.ActivityManager::class.java)
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            services.any { it.service.className == DisplayServerService::class.java.name }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not check DisplayServerService state", e)
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

    override fun onBackPressed() {
        if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        ghostAnimator?.cancel()
        super.onDestroy()
    }

    private fun showAboutDialog() {
        val versionName = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            pi.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        AlertDialog.Builder(this)
            .setTitle("About PHANToM GO")
            .setMessage("Version: $versionName\n\nSend navigation from main device to display device over local Wi‑Fi.")
            .setPositiveButton("OK", null)
            .show()
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
                    Toast.makeText(this, "Camera permission required for QR scanning", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
