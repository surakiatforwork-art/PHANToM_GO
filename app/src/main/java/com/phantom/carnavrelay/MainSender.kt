package com.phantom.carnavrelay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import java.io.PrintWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MainSender(
    private val context: Context,
    private val toastEnabled: Boolean = true,
    private val queueOnFailure: Boolean = true
) {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val TIMEOUT_SECONDS = 3L
        private const val MAX_RETRIES = 2
        private const val DISCOVERY_COOLDOWN_MS = 30000L
        private const val DISCOVERY_TIMEOUT_MS = 300L
        private const val DISCOVERY_MAX_HOSTS = 254
        
        // Connection states
        const val STATE_UNPAIRED = "UNPAIRED"
        const val STATE_PAIRING = "PAIRING"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_AUTH_FAILED = "AUTH_FAILED"
        const val STATE_OFFLINE = "OFFLINE"
        
        // Callback interface for send results
        interface SendCallback {
            fun onSuccess()
            fun onFailure(error: String, queued: Boolean, authFailed: Boolean = false)
        }
        
        // Static method for accessibility service
        fun sendOpenUrlAsync(context: Context, mapsUrl: String, source: String = "a11y") {
            val sender = MainSender(context)
            Log.d(TAG, "[$source] Sending URL: $mapsUrl")
            sender.sendOpenUrl(mapsUrl, null) // No callback needed for async capture
        }

        fun triggerAutoReconnect(context: Context, reason: String = "manual") {
            try {
                val sender = MainSender(context.applicationContext, toastEnabled = false, queueOnFailure = false)
                sender.triggerAutoReconnect(reason)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ triggerAutoReconnect failed: ${e.message}")
            }
        }
    }

    private val prefsManager = PrefsManager(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var currentState = STATE_UNPAIRED
    @Volatile private var discoveryInProgress = false
    @Volatile private var lastDiscoveryAt = 0L
    @Volatile private var lastBtStatusCheckAt = 0L

    fun isPaired(): Boolean {
        return prefsManager.isPaired()
    }
    
    fun isVerified(): Boolean {
        return prefsManager.isPairedVerified()
    }
    
    fun getCurrentState(): String {
        return currentState
    }
    
    fun setState(state: String) {
        Log.d(TAG, "🔄 State change: $currentState → $state")
        currentState = state
    }

    fun getPairedInfo(): String {
        if (!isPaired()) return "Not paired"
        val ip = prefsManager.getPairedIp() ?: "unknown"
        val port = prefsManager.getPairedPort()
        val tokenHint = prefsManager.getPairedToken()?.let { prefsManager.getTokenHint(it) } ?: "****"
        val verified = if (prefsManager.isPairedVerified()) "✅" else "⏳"
        return "$verified $ip:$port (Token: $tokenHint)"
    }

    fun sendOpenUrl(url: String, callback: SendCallback? = null) {
        when (prefsManager.getConnectionTransport()) {
            PrefsManager.TRANSPORT_BLUETOOTH -> {
                Log.d(TAG, "📡 Transport=BLUETOOTH")
                sendOpenUrlViaBluetooth(url, callback)
            }
            PrefsManager.TRANSPORT_AUTO -> {
                Log.d(TAG, "📡 Transport=AUTO")
                sendOpenUrlAuto(url, callback)
            }
            else -> {
                Log.d(TAG, "📡 Transport=WIFI")
                sendOpenUrlWifi(url, callback)
            }
        }
    }

    private fun sendOpenUrlWifi(
        url: String,
        callback: SendCallback? = null,
        queueOnFailureForCall: Boolean = queueOnFailure,
        toastEnabledForCall: Boolean = toastEnabled
    ) {
        Log.d(TAG, "📤 sendOpenUrl() called - state: $currentState")
        
        // Guard: Check if paired
        if (!isPaired()) {
            Log.w(TAG, "❌ Cannot send - not paired with any display device")
            setState(STATE_UNPAIRED)
            showToast("Please pair with a Display device first", toastEnabledForCall)
            callback?.onFailure("Not paired with any display device", false)
            return
        }

        val ip = prefsManager.getPairedIp() ?: ""
        val port = prefsManager.getPairedPort()
        val token = prefsManager.getPairedToken() ?: ""
        
        // Guard: Validate config
        if (ip.isEmpty() || token.isEmpty()) {
            Log.e(TAG, "❌ Invalid pairing config - IP or token is empty")
            setState(STATE_AUTH_FAILED)
            showToast("Invalid pairing configuration", toastEnabledForCall)
            callback?.onFailure("Invalid pairing configuration", false, true)
            return
        }

        val endpoint = "http://$ip:$port/open-url"
        
        val jsonBody = try {
            JSONObject().apply {
                put("token", token)
                put("url", url)
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to create JSON body", e)
            showToast("Internal error: ${e.message}", toastEnabledForCall)
            callback?.onFailure("Internal error: ${e.message}", false)
            return
        }

        Log.d(TAG, "📤 Sending URL to $endpoint")
        Log.d(TAG, "📦 Payload: ${jsonBody.replace(token, "****TOKEN****")}")
        
        setState(STATE_CONNECTING)

        val request = try {
            Request.Builder()
                .url(endpoint)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to build request", e)
            setState(STATE_OFFLINE)
            showToast("Failed to create request: ${e.message}", toastEnabledForCall)
            callback?.onFailure("Failed to create request: ${e.message}", false)
            return
        }

        sendWithRetry(
            request = request,
            url = url,
            callback = callback,
            retryCount = 0,
            queueOnFailureForCall = queueOnFailureForCall,
            toastEnabledForCall = toastEnabledForCall
        )
    }

    private fun sendWithRetry(
        request: Request,
        url: String,
        callback: SendCallback?,
        retryCount: Int,
        queueOnFailureForCall: Boolean,
        toastEnabledForCall: Boolean
    ) {
        try {
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "💥 Send failed (attempt ${retryCount + 1}): ${e.message}", e)
                    
                    val errorMsg = when (e) {
                        is UnknownHostException -> "Cannot reach device. Check network."
                        is ConnectException -> "Connection refused. Is Display running?"
                        is SocketTimeoutException -> "Connection timed out."
                        else -> "Network error: ${e.message}"
                    }
                    
                    if (retryCount < MAX_RETRIES) {
                        Log.d(TAG, "🔄 Retrying... (${retryCount + 1}/$MAX_RETRIES)")
                        mainHandler.postDelayed({
                            sendWithRetry(
                                request = request,
                                url = url,
                                callback = callback,
                                retryCount = retryCount + 1,
                                queueOnFailureForCall = queueOnFailureForCall,
                                toastEnabledForCall = toastEnabledForCall
                            )
                        }, 500)
                    } else {
                        // All retries failed - queue for later
                        Log.w(TAG, "❌ All retries failed${if (queueOnFailureForCall) ", queuing URL" else ""}: $url")
                        setState(STATE_OFFLINE)
                        if (queueOnFailureForCall) {
                            prefsManager.addToPendingQueue(url)
                            showToast("Failed to send. Queued for retry.", toastEnabledForCall)
                        }
                        maybeDiscoverDisplay("send-failure")
                        callback?.onFailure(errorMsg, queueOnFailureForCall)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = try {
                        response.body?.string() ?: ""
                    } catch (e: Exception) {
                        Log.e(TAG, "💥 Failed to read response body", e)
                        ""
                    }
                    
                    Log.d(TAG, "📨 Response: ${response.code} - $body")
                    
                    when (response.code) {
                        200 -> {
                            Log.d(TAG, "✅ URL sent successfully")
                            setState(STATE_CONNECTED)
                            prefsManager.setPairedVerified(true)
                            val pairedIp = prefsManager.getPairedIp()
                            if (!pairedIp.isNullOrBlank()) {
                                prefsManager.rememberDisplayIp(pairedIp)
                            }
                            showToast("Sent to display device!", toastEnabledForCall)
                            callback?.onSuccess()
                        }
                        401 -> {
                            // Unauthorized - token mismatch
                            Log.e(TAG, "💥 Authentication failed (401) - token mismatch")
                            setState(STATE_AUTH_FAILED)
                            prefsManager.setPairedVerified(false)
                            showToast("Authentication failed. Please pair again.", toastEnabledForCall)
                            callback?.onFailure("Authentication failed. Token mismatch.", false, true)
                        }
                        409 -> {
                            // Conflict - not paired
                            Log.e(TAG, "💥 Not paired (409) - device requires pairing")
                            setState(STATE_AUTH_FAILED)
                            prefsManager.setPairedVerified(false)
                            showToast("Device not paired. Please scan QR again.", toastEnabledForCall)
                            callback?.onFailure("Device not paired. Please scan QR again.", false, true)
                        }
                        else -> {
                            val errorMsg = "Server error: ${response.code}"
                            Log.e(TAG, "💥 $errorMsg")
                            
                            // Try to parse error reason
                            val reason = try {
                                JSONObject(body).optString("reason", "Unknown")
                            } catch (e: Exception) {
                                "Unknown"
                            }
                            
                            // Queue for retry unless it's an auth error
                            if (response.code == 403) {
                                setState(STATE_AUTH_FAILED)
                                showToast("Access forbidden: $reason", toastEnabledForCall)
                                callback?.onFailure("Access forbidden: $reason", false)
                            } else {
                                if (queueOnFailureForCall) {
                                    prefsManager.addToPendingQueue(url)
                                    showToast("Failed ($reason). Queued for retry.", toastEnabledForCall)
                                }
                                callback?.onFailure("$errorMsg - $reason", queueOnFailureForCall)
                            }
                        }
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "💥 Exception in sendWithRetry", e)
            setState(STATE_OFFLINE)
            showToast("Error: ${e.message}", toastEnabledForCall)
            callback?.onFailure("Error: ${e.message}", false)
        }
    }

    private fun sendOpenUrlAuto(url: String, callback: SendCallback?) {
        val hasBtTarget = prefsManager.hasBluetoothTarget()
        sendOpenUrlWifi(
            url = url,
            callback = object : SendCallback {
                override fun onSuccess() {
                    callback?.onSuccess()
                }

                override fun onFailure(error: String, queued: Boolean, authFailed: Boolean) {
                    if (authFailed || !hasBtTarget) {
                        callback?.onFailure(error, queued, authFailed)
                        return
                    }
                    Log.w(TAG, "↪️ Wi-Fi send failed in AUTO mode, trying Bluetooth fallback: $error")
                    PhantomLog.w("AUTO transport fallback WIFI->BT: $error")
                    sendOpenUrlViaBluetooth(url, object : SendCallback {
                        override fun onSuccess() {
                            callback?.onSuccess()
                        }

                        override fun onFailure(btError: String, btQueued: Boolean, btAuthFailed: Boolean) {
                            if (queueOnFailure && !btQueued) {
                                prefsManager.addToPendingQueue(url)
                                showToast("Failed to send. Queued for retry.")
                            }
                            val combined = "Wi-Fi: $error | BT: $btError"
                            callback?.onFailure(combined, btQueued || queueOnFailure, btAuthFailed)
                        }
                    })
                }
            },
            queueOnFailureForCall = false,
            toastEnabledForCall = false
        )
    }

    private fun sendOpenUrlViaBluetooth(url: String, callback: SendCallback?) {
        Log.d(TAG, "📤 sendOpenUrlViaBluetooth() called - state: $currentState")

        if (!isPaired()) {
            setState(STATE_UNPAIRED)
            showToast("Please pair with a Display device first")
            callback?.onFailure("Not paired with any display device", false)
            return
        }

        val address = prefsManager.getBluetoothTargetAddress()
        if (address.isNullOrBlank()) {
            showToast("No Bluetooth display selected")
            callback?.onFailure("No Bluetooth display selected", false)
            return
        }

        val token = prefsManager.getPairedToken()
        if (token.isNullOrBlank()) {
            setState(STATE_AUTH_FAILED)
            callback?.onFailure("Missing pairing token", false, true)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasConnect) {
                showToast("Bluetooth permission required")
                callback?.onFailure("BLUETOOTH_CONNECT permission required", false)
                return
            }
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            callback?.onFailure("Bluetooth not supported", false)
            return
        }
        if (!adapter.isEnabled) {
            callback?.onFailure("Bluetooth is disabled", false)
            return
        }

        setState(STATE_CONNECTING)
        Thread {
            var socket: android.bluetooth.BluetoothSocket? = null
            try {
                val device = adapter.getRemoteDevice(address)
                @Suppress("MissingPermission")
                socket = device.createRfcommSocketToServiceRecord(BtConst.APP_UUID)
                adapter.cancelDiscovery()
                socket.connect()

                val writer = PrintWriter(socket.outputStream, true)
                val hello = JSONObject().apply {
                    put("type", "HELLO")
                    put("code", token)
                }.toString()
                writer.println(hello)

                val openUrl = JSONObject().apply {
                    put("type", "OPEN_URL")
                    put("url", url)
                }.toString()
                writer.println(openUrl)
                writer.flush()

                prefsManager.setLastBtClientAt(System.currentTimeMillis())
                setState(STATE_CONNECTED)
                mainHandler.post {
                    showToast("Sent to display via Bluetooth")
                    callback?.onSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 Bluetooth send failed: ${e.message}", e)
                setState(STATE_OFFLINE)
                mainHandler.post {
                    maybeDiscoverDisplay("bt-send-failure")
                    if (queueOnFailure) {
                        prefsManager.addToPendingQueue(url)
                        showToast("Bluetooth send failed. Queued for retry.")
                    }
                    callback?.onFailure("Bluetooth error: ${e.message}", queueOnFailure)
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    fun triggerAutoReconnect(reason: String = "manual") {
        when (prefsManager.getConnectionTransport()) {
            PrefsManager.TRANSPORT_BLUETOOTH -> {
                checkBluetoothStatus(reason = "auto-$reason", callback = null)
            }
            PrefsManager.TRANSPORT_AUTO -> {
                // Prefer keeping Wi‑Fi pairing healthy, but also refresh BT reachability when configured.
                maybeDiscoverDisplay(reason)
                if (prefsManager.hasBluetoothTarget()) {
                    checkBluetoothStatus(reason = "auto-$reason", callback = null)
                }
            }
            else -> {
                maybeDiscoverDisplay(reason)
            }
        }
    }

    fun checkBluetoothStatus(
        reason: String = "manual",
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        if (!isPaired()) {
            setState(STATE_UNPAIRED)
            callback?.invoke(false, "Not paired")
            return
        }

        val address = prefsManager.getBluetoothTargetAddress()
        if (address.isNullOrBlank()) {
            callback?.invoke(false, "No Bluetooth target selected")
            return
        }
        val token = prefsManager.getPairedToken()
        if (token.isNullOrBlank()) {
            setState(STATE_AUTH_FAILED)
            callback?.invoke(false, "Missing pairing token")
            return
        }

        val now = System.currentTimeMillis()
        if ((now - lastBtStatusCheckAt) < 8000L && currentState == STATE_CONNECTED) {
            callback?.invoke(true, null)
            return
        }
        lastBtStatusCheckAt = now

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasConnect) {
                setState(STATE_OFFLINE)
                callback?.invoke(false, "BLUETOOTH_CONNECT permission required")
                return
            }
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            setState(STATE_OFFLINE)
            callback?.invoke(false, "Bluetooth not supported")
            return
        }
        if (!adapter.isEnabled) {
            setState(STATE_OFFLINE)
            callback?.invoke(false, "Bluetooth disabled")
            return
        }

        Thread {
            var socket: android.bluetooth.BluetoothSocket? = null
            try {
                val device = adapter.getRemoteDevice(address)
                @Suppress("MissingPermission")
                socket = device.createRfcommSocketToServiceRecord(BtConst.APP_UUID)
                adapter.cancelDiscovery()
                socket.connect()

                val writer = PrintWriter(socket.outputStream, true)
                val hello = JSONObject().apply {
                    put("type", "HELLO")
                    put("code", token)
                }.toString()
                writer.println(hello)
                writer.flush()

                prefsManager.setLastBtClientAt(System.currentTimeMillis())
                setState(STATE_CONNECTED)
                Log.d(TAG, "✅ Bluetooth status check connected ($reason) -> $address")
                mainHandler.post { callback?.invoke(true, null) }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Bluetooth status check failed ($reason): ${e.message}")
                setState(STATE_OFFLINE)
                mainHandler.post { callback?.invoke(false, e.message ?: "Bluetooth error") }
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    fun retryPending(callback: SendCallback? = null) {
        val pending = prefsManager.getPendingQueue()
        
        if (pending.isEmpty()) {
            showToast("No pending commands")
            callback?.onSuccess()
            return
        }

        Log.d(TAG, "🔄 Retrying ${pending.size} pending commands")
        showToast("Retrying ${pending.size} pending commands...")

        var successCount = 0
        var failCount = 0
        val total = pending.size

        pending.forEach { url ->
            sendOpenUrl(url, object : SendCallback {
                override fun onSuccess() {
                    prefsManager.removeFromPendingQueue(url)
                    successCount++
                    checkComplete()
                }

                override fun onFailure(error: String, queued: Boolean, authFailed: Boolean) {
                    if (!queued || authFailed) {
                        prefsManager.removeFromPendingQueue(url)
                    }
                    failCount++
                    checkComplete()
                }

                private fun checkComplete() {
                    if (successCount + failCount == total) {
                        val msg = "Retry complete: $successCount success, $failCount failed"
                        Log.d(TAG, msg)
                        showToast(msg)
                        if (failCount == 0) {
                            callback?.onSuccess()
                        } else {
                            callback?.onFailure("$failCount commands still failed", pending.size > 0)
                        }
                    }
                }
            })
        }
    }

    fun getPendingCount(): Int {
        return prefsManager.getPendingCount()
    }

    fun clearPending() {
        prefsManager.clearPendingQueue()
        showToast("Pending queue cleared")
    }

    private fun showToast(message: String, enabled: Boolean = toastEnabled) {
        if (!enabled) return
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Test with Bangkok location
    fun sendTestLocation(callback: SendCallback? = null) {
        val testUrl = "https://www.google.com/maps/search/?api=1&query=13.7563,100.5018"
        Log.d(TAG, "🧪 Sending test location (Bangkok): $testUrl")
        sendOpenUrl(testUrl, callback)
    }
    
    // Check server status via /status endpoint
    fun checkServerStatus(callback: ((Boolean, String?) -> Unit)? = null) {
        if (!isPaired()) {
            callback?.invoke(false, "Not paired")
            return
        }
        
        val ip = prefsManager.getPairedIp() ?: ""
        val port = prefsManager.getPairedPort()
        val token = prefsManager.getPairedToken() ?: ""
        
        if (ip.isEmpty()) {
            callback?.invoke(false, "No IP configured")
            return
        }
        
        val endpoint = "http://$ip:$port/status"
        Log.d(TAG, "🔍 Checking server status at $endpoint")
        
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()
        
        try {
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "💥 Status check failed: ${e.message}")
                    setState(STATE_OFFLINE)
                    maybeDiscoverDisplay("status-failure")
                    mainHandler.post {
                        callback?.invoke(false, e.message)
                    }
                }
                
                override fun onResponse(call: Call, response: Response) {
                    val body = try {
                        response.body?.string() ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    
                    Log.d(TAG, "📨 Status response: ${response.code}")
                    
                    if (response.isSuccessful) {
                        try {
                            val json = JSONObject(body)
                            val serverPaired = json.optBoolean("paired", false)
                            val serverTokenHint = json.optString("tokenHint", "")
                            val localTokenHint = prefsManager.getTokenHint(token)
                            
                            // Check if token hint matches
                            val tokenMatches = serverTokenHint == localTokenHint
                            
                            if (serverPaired && tokenMatches) {
                                Log.d(TAG, "✅ Server verified - paired and token matches")
                                setState(STATE_CONNECTED)
                                prefsManager.setPairedVerified(true)
                                prefsManager.rememberDisplayIp(ip)
                                mainHandler.post {
                                    callback?.invoke(true, null)
                                }
                            } else if (!tokenMatches) {
                                Log.w(TAG, "⚠️ Token mismatch detected")
                                setState(STATE_AUTH_FAILED)
                                prefsManager.setPairedVerified(false)
                                mainHandler.post {
                                    callback?.invoke(false, "Token mismatch - please re-pair")
                                }
                            } else {
                                Log.d(TAG, "ℹ️ Server not paired yet")
                                setState(STATE_PAIRING)
                                mainHandler.post {
                                    callback?.invoke(false, "Display not paired yet")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "💥 Failed to parse status response", e)
                            mainHandler.post {
                                callback?.invoke(false, "Invalid response")
                            }
                        }
                    } else {
                        Log.w(TAG, "❌ Status check returned ${response.code}")
                        setState(STATE_OFFLINE)
                        mainHandler.post {
                            callback?.invoke(false, "HTTP ${response.code}")
                        }
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "💥 Exception in checkServerStatus", e)
            setState(STATE_OFFLINE)
            callback?.invoke(false, e.message)
        }
    }

    private fun maybeDiscoverDisplay(reason: String) {
        if (!isPaired()) return
        val now = System.currentTimeMillis()
        if (discoveryInProgress || now - lastDiscoveryAt < DISCOVERY_COOLDOWN_MS) {
            return
        }
        discoveryInProgress = true
        lastDiscoveryAt = now

        Thread {
            try {
                discoverDisplayOnLocalNetwork(reason)
            } catch (e: Exception) {
                Log.e(TAG, "💥 Auto-reconnect discovery failed", e)
            } finally {
                discoveryInProgress = false
            }
        }.start()
    }

    private fun discoverDisplayOnLocalNetwork(reason: String) {
        val token = prefsManager.getPairedToken() ?: return
        val tokenHint = prefsManager.getTokenHint(token)
        val port = prefsManager.getPairedPort()
        val localMainId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

        val discoveryClient = OkHttpClient.Builder()
            .connectTimeout(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        val knownCandidates = mutableListOf<String>()
        prefsManager.getPairedIp()?.let { if (it.isNotBlank()) knownCandidates += it }
        knownCandidates += prefsManager.getKnownDisplayIps()
        knownCandidates.distinct().forEach { candidateIp ->
            val foundPort = probeDisplayStatus(
                client = discoveryClient,
                targetIp = candidateIp,
                port = port,
                localTokenHint = tokenHint,
                localMainId = localMainId
            )
            if (foundPort != null) {
                applyDiscoveredDisplay(candidateIp, foundPort, token, reason, "known-ip")
                return
            }
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
        val maskInt = wifiManager?.dhcpInfo?.netmask ?: 0
        if (ipInt == 0 || maskInt == 0) {
            Log.w(TAG, "⚠️ Auto-reconnect skipped: no Wi-Fi IP")
            return
        }

        val ip = ipInt.toUInt()
        val mask = maskInt.toUInt()
        val network = ip and mask
        val broadcast = network or mask.inv()
        var start = network + 1u
        var end = if (broadcast > 0u) broadcast - 1u else 0u
        var hostCount = if (end >= start) (end - start + 1u) else 0u

        if (hostCount == 0u) {
            Log.w(TAG, "⚠️ Auto-reconnect skipped: invalid subnet range")
            return
        }

        if (hostCount > DISCOVERY_MAX_HOSTS.toUInt()) {
            val subnetBase = ip and 0xFFFFFF00u
            start = subnetBase + 1u
            end = subnetBase + 254u
            hostCount = 254u
        }

        val localIpString = uintToIp(ip)
        val localTokenHint = tokenHint

        val found = AtomicBoolean(false)
        val foundIp = AtomicReference<String?>(null)
        val foundPort = AtomicInteger(port)

        val executor = Executors.newFixedThreadPool(12)

        var host = start
        while (host <= end) {
            val targetIp = uintToIp(host)
            host++
            if (targetIp == localIpString) {
                continue
            }
            executor.execute {
                if (found.get()) return@execute
                try {
                    val probedPort = probeDisplayStatus(
                        client = discoveryClient,
                        targetIp = targetIp,
                        port = port,
                        localTokenHint = localTokenHint,
                        localMainId = localMainId
                    )
                    if (probedPort != null && found.compareAndSet(false, true)) {
                        foundIp.set(targetIp)
                        foundPort.set(probedPort)
                    }
                } catch (e: Exception) {
                    // Ignore individual host failures
                }
            }
        }

        executor.shutdown()
        val deadline = System.currentTimeMillis() + 4000L
        while (!executor.isTerminated && System.currentTimeMillis() < deadline && !found.get()) {
            executor.awaitTermination(200, TimeUnit.MILLISECONDS)
        }
        if (found.get()) {
            executor.shutdownNow()
        }

        val ipFound = foundIp.get()
        if (!ipFound.isNullOrEmpty()) {
            applyDiscoveredDisplay(ipFound, foundPort.get(), token, reason, "subnet-scan")
        } else {
            Log.d(TAG, "ℹ️ Auto-reconnect: display not found ($reason)")
        }
    }

    private fun probeDisplayStatus(
        client: OkHttpClient,
        targetIp: String,
        port: Int,
        localTokenHint: String,
        localMainId: String
    ): Int? {
        val request = Request.Builder()
            .url("http://$targetIp:$port/status")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val serverTokenHint = json.optString("tokenHint", "")
            val paired = json.optBoolean("paired", false)
            val pairedMainId = json.optString("pairedMainId", "")
            val matches = paired &&
                serverTokenHint == localTokenHint &&
                (pairedMainId.isEmpty() || pairedMainId == localMainId)
            if (!matches) return null
            return json.optInt("port", port)
        }
    }

    private fun applyDiscoveredDisplay(
        ip: String,
        port: Int,
        token: String,
        reason: String,
        source: String
    ) {
        prefsManager.savePairing(ip, port, token, verified = true)
        prefsManager.rememberDisplayIp(ip)
        setState(STATE_CONNECTED)
        val message = "🔁 Auto-reconnect: Display found at $ip:$port ($reason/$source)"
        Log.d(TAG, message)
        PhantomLog.i(message)
        showToast("Reconnected to Display")

        if (prefsManager.getPendingCount() > 0) {
            Log.d(TAG, "🔄 Auto-retry pending queue after reconnect")
            retryPending(null)
        }
    }

    private fun uintToIp(value: UInt): String {
        val b1 = (value and 0xFFu).toInt()
        val b2 = ((value shr 8) and 0xFFu).toInt()
        val b3 = ((value shr 16) and 0xFFu).toInt()
        val b4 = ((value shr 24) and 0xFFu).toInt()
        return "$b1.$b2.$b3.$b4"
    }
}
