package com.phantom.carnavrelay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.phantom.carnavrelay.bt.DisplayServerService
import com.phantom.carnavrelay.bt.MainClientService

/**
 * DiagnosticsActivity - แสดง crash report และ self-test ระบบ
 */
class DiagnosticsActivity : AppCompatActivity() {

    companion object {
        const val TAG = "PHANTOM_GO"
    }

    private lateinit var tabLayout: TabLayout
    private lateinit var selfTestContent: View
    private lateinit var crashContent: View
    private lateinit var crashReportText: TextView
    private lateinit var checkListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "🔧 DiagnosticsActivity onCreate")
        setContentView(R.layout.activity_diagnostics)

        initViews()
        setupTabs()
        setupButtons()

        // Check if opened from crash
        val showCrash = intent.getBooleanExtra("show_crash", false)
        if (showCrash) {
            tabLayout.selectTab(tabLayout.getTabAt(1))
            loadCrashReport()
        } else {
            runSelfTest()
        }
    }

    private fun initViews() {
        tabLayout = findViewById(R.id.tabLayout)
        selfTestContent = findViewById(R.id.selfTestContent)
        crashContent = findViewById(R.id.crashContent)
        crashReportText = findViewById(R.id.crashReportText)
        checkListContainer = findViewById(R.id.checkListContainer)

        findViewById<View>(R.id.backButton).setOnClickListener {
            finish()
        }
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        selfTestContent.visibility = View.VISIBLE
                        crashContent.visibility = View.GONE
                        runSelfTest()
                    }
                    1 -> {
                        selfTestContent.visibility = View.GONE
                        crashContent.visibility = View.VISIBLE
                        loadCrashReport()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                if (tab?.position == 0) runSelfTest()
            }
        })
    }

    private fun setupButtons() {
        findViewById<MaterialButton>(R.id.runTestButton).setOnClickListener {
            runSelfTest()
        }

        findViewById<MaterialButton>(R.id.copyButton).setOnClickListener {
            val report = crashReportText.text.toString()
            if (report != "No crash report available") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Crash Report", report)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.diag_copied_clipboard), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.shareButton).setOnClickListener {
            val report = crashReportText.text.toString()
            if (report != "No crash report available") {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "PHANToM GO Crash Report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Crash Report"))
            }
        }

        findViewById<MaterialButton>(R.id.clearButton).setOnClickListener {
            CrashReporter.clearCrashReport()
            loadCrashReport()
            Toast.makeText(this, getString(R.string.diag_cleared_report), Toast.LENGTH_SHORT).show()
        }
    }

    private fun runSelfTest() {
        Log.d(TAG, "🔍 Running self-test...")
        checkListContainer.removeAllViews()

        // Run all checks
        val results = mutableListOf<CheckResult>()

        // 1. Bluetooth permission - CONNECT
        results.add(checkPermission(
            Manifest.permission.BLUETOOTH_CONNECT,
            "BLUETOOTH_CONNECT"
        ))

        // 2. Bluetooth permission - SCAN
        results.add(checkPermission(
            Manifest.permission.BLUETOOTH_SCAN,
            "BLUETOOTH_SCAN"
        ))

        // 3. POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            results.add(checkPermission(
                Manifest.permission.POST_NOTIFICATIONS,
                "POST_NOTIFICATIONS"
            ))
        }

        // 4. Bluetooth Adapter available
        results.add(checkBluetoothAdapter())

        // 5. Bluetooth enabled
        results.add(checkBluetoothEnabled())

        // 6. Paired devices
        results.add(checkPairedDevices())

        // 7. Test DisplayServerService
        results.add(testService("DisplayServerService"))

        // 8. Test MainClientService
        results.add(testService("MainClientService"))

        // Display results
        results.forEach { addCheckItem(it) }

        Log.d(TAG, "✅ Self-test complete: ${results.count { it.passed }}/${results.size} passed")
    }

    private fun checkPermission(permission: String, name: String): CheckResult {
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        return CheckResult(
            name = name,
            passed = granted,
            message = if (granted) "Granted" else "NOT granted - Required for Bluetooth"
        )
    }

    private fun checkBluetoothAdapter(): CheckResult {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        return CheckResult(
            name = "Bluetooth Adapter",
            passed = adapter != null,
            message = if (adapter != null) "Available" else "NOT available - Device has no Bluetooth"
        )
    }

    private fun checkBluetoothEnabled(): CheckResult {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        val enabled = adapter?.isEnabled == true
        return CheckResult(
            name = "Bluetooth Enabled",
            passed = enabled,
            message = if (enabled) "ON" else "OFF - Please enable Bluetooth in Settings"
        )
    }

    private fun checkPairedDevices(): CheckResult {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        val devices = try {
            adapter?.bondedDevices
        } catch (e: SecurityException) {
            null
        }
        val count = devices?.size ?: 0
        return CheckResult(
            name = "Paired Devices",
            passed = count > 0,
            message = "$count device(s) paired${if (count == 0) " - Pair a device in Bluetooth Settings first" else ""}"
        )
    }

    private fun testService(serviceName: String): CheckResult {
        return try {
            // Try to start service to see if it crashes
            when (serviceName) {
                "DisplayServerService" -> {
                    val intent = Intent(this, DisplayServerService::class.java)
                    intent.putExtra("code", "TEST000")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
                "MainClientService" -> {
                    val intent = Intent(this, MainClientService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
            CheckResult(
                name = "$serviceName Start",
                passed = true,
                message = "Service started without crash"
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Service test failed: $serviceName", e)
            CheckResult(
                name = "$serviceName Start",
                passed = false,
                message = "FAILED: ${e.message}"
            )
        }
    }

    private fun addCheckItem(result: CheckResult) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_diagnostics_check, checkListContainer, false)

        val statusIcon = view.findViewById<ImageView>(R.id.statusIcon)
        val checkName = view.findViewById<TextView>(R.id.checkName)
        val checkMessage = view.findViewById<TextView>(R.id.checkMessage)

        statusIcon.setImageResource(if (result.passed) R.drawable.ic_check_circle else R.drawable.ic_error)
        statusIcon.setColorFilter(ContextCompat.getColor(this, if (result.passed) R.color.success else R.color.error))
        checkName.text = result.name
        checkMessage.text = result.message
        checkMessage.setTextColor(ContextCompat.getColor(this, if (result.passed) R.color.success else R.color.error))

        checkListContainer.addView(view)
    }

    private fun loadCrashReport() {
        val report = CrashReporter.getCrashReport()
        crashReportText.text = report ?: "No crash report available"
    }

    data class CheckResult(
        val name: String,
        val passed: Boolean,
        val message: String
    )
}
