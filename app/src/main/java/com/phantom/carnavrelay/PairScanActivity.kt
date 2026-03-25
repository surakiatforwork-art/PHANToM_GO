package com.phantom.carnavrelay

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.phantom.carnavrelay.util.PairInfo
import com.phantom.carnavrelay.util.parsePairInfo
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class PairScanActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val CAMERA_PERMISSION_REQUEST = 1001
        private const val TIMEOUT_SECONDS = 5L
    }

    private lateinit var prefsManager: PrefsManager
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressDialog: AlertDialog? = null
    @Volatile private var isUiActive = false

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            Log.d(TAG, "📱 QR Code scanned: ${result.contents}")
            handleScannedQR(result.contents)
        } else {
            Log.d(TAG, "❌ QR Scan cancelled")
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "▶️ PairScanActivity.onCreate()")
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        
        prefsManager = PrefsManager(this)

        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        } else {
            startScan()
        }
    }

    override fun onStart() {
        super.onStart()
        isUiActive = true
    }

    override fun onStop() {
        isUiActive = false
        super.onStop()
    }

    private fun startScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan QR code from Display device")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setCaptureActivity(PortraitCaptureActivity::class.java)
            setOrientationLocked(true)
        }
        barcodeLauncher.launch(options)
    }

    private fun handleScannedQR(content: String) {
        val raw = content.trim()
        Log.d(TAG, "� QR RAW = $raw")

        // Parse using new parser
        val pairInfo = parsePairInfo(raw)
        
        if (pairInfo == null) {
            Log.e(TAG, "❌ Invalid QR format - showing debug dialog")
            showInvalidQRDialog(raw)
            return
        }

        if (pairInfo.isCloudPairing) {
            Log.d(
                TAG,
                "✅ Parsed cloud QR: displayDeviceId=${pairInfo.displayDeviceId}, pairCode=${pairInfo.pairCode}, local=${pairInfo.ip}:${pairInfo.port}"
            )
            callCloudPairing(pairInfo)
        } else {
            Log.d(TAG, "✅ Parsed local QR: ip=${pairInfo.ip}, port=${pairInfo.port}, token=${pairInfo.token?.take(10)}...")
            callPairEndpoint(pairInfo)
        }
    }
    
    private fun showInvalidQRDialog(raw: String) {
        if (!canShowDialogs()) {
            Log.w(TAG, "⚠️ Skipping invalid QR dialog: activity not active")
            Toast.makeText(this, "Invalid QR format", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        try {
            AlertDialog.Builder(this)
                .setTitle("Invalid QR Format")
                .setMessage("The QR code could not be parsed. Format not recognized.\n\nRaw content:\n${raw.take(200)}${if (raw.length > 200) "..." else ""}")
                .setPositiveButton("Copy RAW") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("QR Raw", raw)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Raw content copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Retry") { _, _ ->
                    startScan()
                }
                .setNeutralButton("Cancel") { _, _ ->
                    setResult(RESULT_CANCELED)
                    finish()
                }
                .setCancelable(false)
                .show()
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "⚠️ Invalid QR dialog skipped (bad token): ${e.message}")
        }
    }
    
    private fun callPairEndpoint(pairInfo: PairInfo) {
        val ip = pairInfo.ip
        val port = pairInfo.port
        val token = pairInfo.token
        if (ip.isNullOrBlank() || port == null || token.isNullOrBlank()) {
            showErrorDialog("QR code is missing local network pairing data.")
            return
        }

        val endpoint = "http://$ip:$port/pair"
        val mainId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        val mainName = android.os.Build.MODEL
        
        val jsonBody = JSONObject().apply {
            put("token", token)
            put("mainId", mainId)
            put("mainName", mainName)
        }.toString()
        
        Log.d(TAG, "✅ Parsed pairing data - IP: $ip, Port: $port, Token: ${prefsManager.getTokenHint(token)}")
        
        // Show progress dialog
        showProgressDialog("Verifying pairing...")
        
        Log.d(TAG, "📤 Calling /pair at $endpoint")
        Log.d(TAG, "📦 Payload: $jsonBody")
        
        val request = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "💥 /pair request failed: ${e.message}", e)
                hideProgressDialog()
                
                val errorMsg = when (e) {
                    is UnknownHostException -> "Cannot reach device. Check IP address."
                    is ConnectException -> "Connection refused. Is Display mode running?"
                    is SocketTimeoutException -> "Connection timed out. Check network."
                    else -> "Network error: ${e.message}"
                }
                
                mainHandler.post {
                    if (!canShowDialogs()) {
                        Log.w(TAG, "⚠️ Pair failure arrived after activity stopped; ignoring dialog")
                        return@post
                    }
                    showErrorDialog(errorMsg)
                }
            }
            
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                Log.d(TAG, "📨 /pair response: ${response.code} - $body")
                hideProgressDialog()
                
                mainHandler.post {
                    when (response.code) {
                        200 -> {
                            // Success - save pairing as verified
                            Log.d(TAG, "✅ /pair success - saving verified pairing")
                            prefsManager.savePairing(ip, port, token, verified = true)
                            Toast.makeText(this@PairScanActivity, "Paired with $ip:$port!", Toast.LENGTH_LONG).show()
                            setResult(RESULT_OK)
                            finish()
                        }
                        401 -> {
                            // Unauthorized - token mismatch
                            Log.w(TAG, "❌ /pair UNAUTHORIZED - token mismatch")
                            showErrorDialog("Authentication failed. Token mismatch.\n\nPlease refresh QR code on Display device and scan again.")
                        }
                        else -> {
                            // Other error
                            val json = try {
                                JSONObject(body)
                            } catch (e: Exception) {
                                null
                            }
                            val reason = json?.optString("reason", "Unknown error")
                            Log.e(TAG, "❌ /pair error: $reason")
                            showErrorDialog("Pairing failed: $reason\n\nPlease try again.")
                        }
                    }
                }
                response.close()
            }
            })
    }

    private fun callCloudPairing(pairInfo: PairInfo) {
        showProgressDialog("Claiming pairing from Firebase...")
        FirebaseManager.claimPairingCode(
            context = this,
            pairInfo = pairInfo,
            onSuccess = { result ->
                hideProgressDialog()
                val localIp = result.localIp
                val localPort = result.localPort
                val localToken = result.localToken
                if (!localIp.isNullOrBlank() && localPort != null && !localToken.isNullOrBlank()) {
                    prefsManager.savePairing(
                        ip = localIp,
                        port = localPort,
                        token = localToken,
                        verified = false,
                        displayDeviceId = result.displayDeviceId,
                        displayName = result.displayName,
                        viaCloud = true
                    )
                } else {
                    prefsManager.saveCloudPairing(
                        displayDeviceId = result.displayDeviceId,
                        displayName = result.displayName
                    )
                }

                mainHandler.post {
                    val displayLabel = result.displayName ?: result.displayDeviceId
                    Toast.makeText(
                        this@PairScanActivity,
                        "Cloud paired with $displayLabel",
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(RESULT_OK)
                    finish()
                }
            },
            onFailure = { error ->
                hideProgressDialog()
                mainHandler.post {
                    showErrorDialog("Cloud pairing failed: $error")
                }
            }
        )
    }
    
    private fun showProgressDialog(message: String) {
        mainHandler.post {
            if (!canShowDialogs()) {
                Log.d(TAG, "ℹ️ Skipping progress dialog: activity not active")
                return@post
            }
            progressDialog = AlertDialog.Builder(this)
                .setTitle("Pairing")
                .setMessage(message)
                .setCancelable(false)
                .create()
            try {
                progressDialog?.show()
            } catch (e: WindowManager.BadTokenException) {
                Log.w(TAG, "⚠️ Progress dialog show failed (bad token): ${e.message}")
                progressDialog = null
            }
        }
    }
    
    private fun hideProgressDialog() {
        mainHandler.post {
            safeDismissDialog()
        }
    }
    
    private fun safeDismissDialog() {
        try {
            // Check if activity is still valid
            if (isFinishing || isDestroyed) {
                Log.d(TAG, "⚠️ Activity finishing/destroyed, skipping dialog dismiss")
                progressDialog = null
                return
            }
            
            // Check if dialog exists and is showing
            progressDialog?.let { dialog ->
                if (dialog.isShowing) {
                    dialog.dismiss()
                    Log.d(TAG, "✅ Progress dialog dismissed safely")
                }
            }
        } catch (e: IllegalArgumentException) {
            // DecorView not attached - already dismissed or activity gone
            Log.w(TAG, "⚠️ Dialog dismiss failed (DecorView not attached): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Unexpected error dismissing dialog: ${e.message}")
        } finally {
            progressDialog = null
        }
    }
    
    private fun showErrorDialog(message: String) {
        if (!canShowDialogs()) {
            Log.w(TAG, "⚠️ Skipping error dialog: activity not active")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }

        try {
            AlertDialog.Builder(this)
                .setTitle("Pairing Failed")
                .setMessage(message)
                .setPositiveButton("Scan Again") { _, _ ->
                    startScan()
                }
                .setNegativeButton("Cancel") { _, _ ->
                    setResult(RESULT_CANCELED)
                    finish()
                }
                .setCancelable(false)
                .show()
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "⚠️ Error dialog show failed (bad token): ${e.message}")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun canShowDialogs(): Boolean {
        return isUiActive && !isFinishing && !isDestroyed
    }

    override fun onDestroy() {
        Log.d(TAG, "💀 PairScanActivity.onDestroy()")
        isUiActive = false
        // Remove all pending callbacks to prevent crashes after activity is destroyed
        mainHandler.removeCallbacksAndMessages(null)
        // Safely dismiss any showing dialog
        safeDismissDialog()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "✅ Camera permission granted")
                    startScan()
                } else {
                    Log.w(TAG, "❌ Camera permission denied")
                    Toast.makeText(this, "Camera permission required to scan QR codes", Toast.LENGTH_LONG).show()
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        }
    }
}
