package com.phantom.carnavrelay

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.phantom.carnavrelay.bt.MainClientService

class MainPairActivity : AppCompatActivity() {

    private val TAG = "PHANTOM_GO"
    
    private lateinit var codeInput: TextInputEditText
    private lateinit var codeLayout: TextInputLayout
    private lateinit var connectButton: MaterialButton
    private lateinit var prefsManager: PrefsManager
    private var deviceAddress: String = ""
    private var deviceName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "▶️ MainPairActivity.onCreate() called")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_pair)
        prefsManager = PrefsManager(this)

        deviceAddress = intent.getStringExtra("address") ?: ""
        deviceName = intent.getStringExtra("device_name") ?: getString(R.string.main_pair_unknown_device)
        
        Log.d(TAG, "📱 MainPairActivity - Address: $deviceAddress, Name: $deviceName")

        if (deviceAddress.isEmpty()) {
            Log.e(TAG, "❌ MainPairActivity: Empty device address")
            Toast.makeText(this, getString(R.string.main_pair_device_missing), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            initViews()
            setupCodeInput()
            setupConnectButton()
            Log.d(TAG, "✅ MainPairActivity views initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "💥 MainPairActivity view initialization failed", e)
            CrashReporter.recordException(this, "MainPairActivity:onCreate", e)
            Toast.makeText(this, getString(R.string.main_pair_error_fmt, e.message ?: getString(R.string.common_unknown)), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initViews() {
        Log.d(TAG, "🔧 MainPairActivity.initViews() called")
        codeInput = findViewById<TextInputEditText>(R.id.codeInput)
            ?: error("codeInput not found in activity_main_pair")
        codeLayout = findViewById<TextInputLayout>(R.id.codeLayout)
            ?: error("codeLayout not found in activity_main_pair")
        connectButton = findViewById<MaterialButton>(R.id.connectButton)
            ?: error("connectButton not found in activity_main_pair")

        // Set device name in subtitle
        findViewById<TextView>(R.id.deviceNameText)?.text = deviceName
        Log.d(TAG, "✅ Views initialized: codeInput=$codeInput, codeLayout=$codeLayout, connectButton=$connectButton")
    }

    private fun setupCodeInput() {
        Log.d(TAG, "🔧 MainPairActivity.setupCodeInput() called")
        codeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val code = s?.toString()?.replace(" ", "") ?: ""
                // Format with spaces every 2 digits
                if (code.length <= 6) {
                    val formatted = formatCode(code)
                    if (formatted != s?.toString()) {
                        codeInput.setText(formatted)
                        codeInput.setSelection(formatted.length)
                    }
                }

                // Enable button when code is complete
                connectButton.isEnabled = code.length == 6
                codeLayout.error = if (code.length == 6) null else getString(R.string.main_pair_code_required)
            }
        })
    }

    private fun formatCode(code: String): String {
        return code.chunked(2).joinToString(" ")
    }

    private fun setupConnectButton() {
        Log.d(TAG, "🔧 MainPairActivity.setupConnectButton() called")
        connectButton.setOnClickListener {
            val code = codeInput.text?.toString()?.replace(" ", "") ?: ""
            Log.d(TAG, "👆 Connect button clicked with code length: ${code.length}")
            if (code.length == 6) {
                startMainClientService(code)
            } else {
                codeLayout.error = getString(R.string.main_pair_code_required)
            }
        }
    }

    private fun startMainClientService(code: String) {
        Log.d(TAG, "🚀 MainPairActivity.startMainClientService() - Starting service for $deviceAddress")
        prefsManager.saveBluetoothTarget(deviceAddress, deviceName)
        Log.d(TAG, "💾 Saved BT target for reconnect: $deviceName ($deviceAddress)")
        val intent = Intent(this, MainClientService::class.java).apply {
            putExtra("address", deviceAddress)
            putExtra("code", code)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "▶️ Starting foreground service (Android O+)")
                startForegroundService(intent)
            } else {
                Log.d(TAG, "▶️ Starting service (pre-Android O)")
                startService(intent)
            }
            Log.d(TAG, "✅ MainClientService started successfully")
            Toast.makeText(this, getString(R.string.main_pair_connecting), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to start MainClientService", e)
            CrashReporter.recordException(this, "MainPairActivity:startMainClientService", e)
            Toast.makeText(this, getString(R.string.main_pair_cannot_start_service_fmt, e.message ?: getString(R.string.common_unknown)), Toast.LENGTH_LONG).show()
        }
        
        finish()
    }
    
    override fun onDestroy() {
        Log.d(TAG, "💀 MainPairActivity.onDestroy() called")
        super.onDestroy()
    }
}
