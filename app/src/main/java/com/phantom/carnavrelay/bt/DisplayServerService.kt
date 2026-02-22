package com.phantom.carnavrelay.bt

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phantom.carnavrelay.BtConst
import com.phantom.carnavrelay.CrashReporter
import com.phantom.carnavrelay.PrefsManager
import com.phantom.carnavrelay.R
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class DisplayServerService : Service() {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_NAVIGATION_RECEIVED = "com.phantom.carnavrelay.NAVIGATION_RECEIVED"
    }

    private lateinit var prefsManager: PrefsManager
    private var tokenCode: String = "000000"
    private var server: BluetoothServerSocket? = null
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        prefsManager = PrefsManager(applicationContext)
        prefsManager.setBtClientConnected(false)
        Log.d(TAG, "🔧 BT DisplayServerService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tokenCode = intent?.getStringExtra("code")
            ?.takeIf { it.isNotBlank() }
            ?: prefsManager.getServerToken()
        Log.d(TAG, "▶️ BT DisplayServerService start, tokenHint=${prefsManager.getTokenHint(tokenCode)}")

        try {
            startForeground(
                NOTIFICATION_ID,
                notif("${getString(R.string.notification_listening)} (BT)")
            )
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to start BT foreground service", e)
            CrashReporter.recordException(applicationContext, "BtDisplayServerService:startForeground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (worker == null) {
            worker = Thread { runServerLoop() }.also { it.start() }
        }
        return START_STICKY
    }

    private fun runServerLoop() {
        Log.d(TAG, "🔄 BT server loop started")
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                Log.e(TAG, "❌ No Bluetooth adapter available")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasConnect = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasConnect) {
                    Log.e(TAG, "❌ BLUETOOTH_CONNECT permission not granted")
                    return
                }
            }

            try {
                server = adapter.listenUsingRfcommWithServiceRecord(BtConst.SERVICE_NAME, BtConst.APP_UUID)
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ Bluetooth permission denied", e)
                return
            }
            Log.d(TAG, "✅ BT server socket ready")

            while (true) {
                val socket = server?.accept() ?: break
                Log.d(TAG, "📱 BT client connected: ${socket.remoteDevice.address}")
                handleClient(socket)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "💥 BT server loop error", e)
            CrashReporter.recordException(applicationContext, "BtDisplayServerService:runServerLoop", e)
        } finally {
            prefsManager.setBtClientConnected(false)
            Log.d(TAG, "🛑 BT server loop ended")
        }
    }

    private fun handleClient(socket: BluetoothSocket) {
        val clientAddr = runCatching { socket.remoteDevice.address }.getOrNull() ?: "unknown"
        var paired = false
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            while (true) {
                val line = reader.readLine() ?: break
                val obj = try {
                    JSONObject(line)
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Invalid JSON from $clientAddr: $line", e)
                    CrashReporter.recordException(applicationContext, "BtDisplayServerService:parseJSON", e)
                    continue
                }

                when (obj.optString("type")) {
                    "HELLO" -> {
                        val got = obj.optString("code")
                        val expectedToken = prefsManager.getServerToken()
                        tokenCode = expectedToken
                        paired = got == expectedToken
                        Log.d(
                            TAG,
                            "🔑 BT HELLO from $clientAddr matched=$paired tokenHint=${prefsManager.getTokenHint(got)}"
                        )
                        if (!paired) {
                            break
                        }
                        prefsManager.setBtClientConnected(true)
                        prefsManager.setLastBtClientAt(System.currentTimeMillis())
                        sendBroadcast(Intent(BtConst.ACTION_CONNECTED))
                    }

                    "OPEN_URL" -> {
                        if (!paired) {
                            Log.w(TAG, "⚠️ OPEN_URL from unpaired BT client")
                            continue
                        }
                        val url = obj.optString("url")
                        if (url.isBlank()) continue

                        prefsManager.setLastBtClientAt(System.currentTimeMillis())
                        Log.d(TAG, "🗺️ BT OPEN_URL received: $url")

                        // Keep legacy broadcast for diagnostics/compat
                        sendBroadcast(Intent(BtConst.ACTION_OPEN_URL).putExtra(BtConst.EXTRA_URL, url))

                        // Forward into the active display flow used by HTTP transport.
                        val serviceIntent = Intent(applicationContext, com.phantom.carnavrelay.DisplayServerService::class.java).apply {
                            action = ACTION_NAVIGATION_RECEIVED
                            putExtra("url", url)
                        }
                        try {
                            ContextCompat.startForegroundService(applicationContext, serviceIntent)
                        } catch (_: Exception) {
                            applicationContext.startService(serviceIntent)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "💥 BT client handler error for $clientAddr", e)
            CrashReporter.recordException(applicationContext, "BtDisplayServerService:handleClient", e)
        } finally {
            try {
                socket.close()
            } catch (e: Throwable) {
                Log.e(TAG, "💥 Error closing BT client socket", e)
            }
            prefsManager.setBtClientConnected(false)
            try {
                sendBroadcast(Intent(BtConst.ACTION_DISCONNECTED))
            } catch (e: Exception) {
                Log.e(TAG, "💥 Failed to broadcast BT DISCONNECTED", e)
            }
        }
    }

    private fun notif(text: String): Notification {
        val chId = "car_nav_display_bt"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(chId, getString(R.string.channel_display_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, chId)
            .setContentTitle(getString(R.string.app_name_display))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_display_mode)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "💀 BT DisplayServerService onDestroy")
        prefsManager.setBtClientConnected(false)
        try {
            server?.close()
        } catch (e: Throwable) {
            Log.e(TAG, "Error closing BT server socket", e)
            CrashReporter.recordException(applicationContext, "BtDisplayServerService:onDestroy", e)
        }
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
