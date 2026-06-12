package com.phantom.carnavrelay.bt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phantom.carnavrelay.BtConst
import com.phantom.carnavrelay.CrashReporter
import com.phantom.carnavrelay.R
import org.json.JSONObject
import java.io.PrintWriter

class MainClientService : Service() {

  companion object {
    private const val TAG = "PHANTOM_GO"
    private const val NOTIFICATION_ID = 2002
  }

  private var address: String? = null
  private var code: String? = null

  @Volatile private var socket: BluetoothSocket? = null
  @Volatile private var writer: PrintWriter? = null

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "🔧 MainClientService onCreate")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(TAG, "▶️ MainClientService onStartCommand, startId=$startId")
    val mode = intent?.getStringExtra("mode")
    Log.d(TAG, "📋 Mode: $mode")

    // ส่งอย่างเดียวจาก RelayActivity
    val sendUrl = intent?.getStringExtra("send_url")
    if (mode == "SEND_ONLY" && sendUrl != null) {
      Log.d(TAG, "📤 SEND_ONLY mode with URL: $sendUrl")
      sendOpenUrl(sendUrl)
      return START_NOT_STICKY
    }

    // เริ่มเชื่อมต่อค้างไว้
    address = intent?.getStringExtra("address") ?: address
    code = intent?.getStringExtra("code") ?: code
    Log.d(TAG, "🔗 Device: $address, Code: $code")

    try {
      startForeground(NOTIFICATION_ID, notif(getString(R.string.main_connecting)))
      Log.d(TAG, "✅ Foreground service started")
    } catch (e: Exception) {
      Log.e(TAG, "💥 Failed to start foreground service", e)
      CrashReporter.recordException(this, "MainClientService:startForeground", e)
      stopSelf()
      return START_NOT_STICKY
    }

    connectIfNeeded()

    return START_STICKY
  }

  private fun connectIfNeeded() {
    if (socket?.isConnected == true) {
      Log.d(TAG, "✅ Already connected")
      return
    }

    Log.d(TAG, "🏃 Starting connection thread...")
    Thread {
      try {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
          Log.e(TAG, "❌ No Bluetooth adapter available")
          return@Thread
        }
        
        Log.d(TAG, "📱 Getting remote device: $address")
        val dev = adapter.getRemoteDevice(address)

        Log.d(TAG, "📡 Creating RFCOMM socket...")
        // Check BLUETOOTH_CONNECT permission for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this, 
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                Log.e(TAG, "❌ Missing BLUETOOTH_CONNECT permission")
                throw SecurityException("BLUETOOTH_CONNECT permission required")
            }
        }
        
        @Suppress("MissingPermission")
        val s = dev.createRfcommSocketToServiceRecord(BtConst.APP_UUID)
        adapter.cancelDiscovery()
        
        Log.d(TAG, "⏳ Connecting to server...")
        s.connect()
        Log.d(TAG, "✅ Connected to server!")

        socket = s
        writer = PrintWriter(s.outputStream, true)

        // ส่ง HELLO + code
        val hello = JSONObject().apply {
          put("type", "HELLO")
          put("code", code ?: "")
        }.toString()
        Log.d(TAG, "📤 Sending HELLO: $hello")
        writer?.println(hello)
        Log.d(TAG, "✅ HELLO sent, pairing complete")

      } catch (e: Throwable) {
        Log.e(TAG, "💥 Connection error", e)
        CrashReporter.recordException(this, "MainClientService:connectIfNeeded", e)
        closeNow()
      }
    }.start()
  }

  private fun sendOpenUrl(url: String) {
    Log.d(TAG, "🗺️ sendOpenUrl called: $url")
    connectIfNeeded()
    
    Thread {
      try {
        val w = writer
        if (w != null) {
          val msg = JSONObject().apply {
            put("type", "OPEN_URL")
            put("url", url)
          }.toString()
          Log.d(TAG, "📤 Sending OPEN_URL: $msg")
          w.println(msg)
          Log.d(TAG, "✅ OPEN_URL sent successfully")
        } else {
          Log.w(TAG, "⚠️ Writer is null, cannot send URL")
        }
      } catch (e: Throwable) {
        Log.e(TAG, "💥 Error sending URL", e)
        CrashReporter.recordException(this, "MainClientService:sendOpenUrl", e)
        closeNow()
      }
    }.start()
  }

  private fun closeNow() {
    Log.d(TAG, "🔌 Closing connection")
    try { 
      socket?.close() 
      Log.d(TAG, "✅ Socket closed")
    } catch (e: Throwable) {
      Log.e(TAG, "Error closing socket", e)
    }
    socket = null
    writer = null
  }

  private fun notif(text: String): Notification {
    val chId = "car_nav_main"
    if (Build.VERSION.SDK_INT >= 26) {
      val nm = getSystemService(NotificationManager::class.java)
      nm.createNotificationChannel(
        NotificationChannel(chId, getString(R.string.channel_main_name), NotificationManager.IMPORTANCE_LOW)
      )
    }
    return NotificationCompat.Builder(this, chId)
      .setContentTitle(getString(R.string.app_name_main))
      .setContentText(text)
      .setSmallIcon(R.drawable.ic_main_mode)
      .setOngoing(true)
      .build()
  }

  override fun onDestroy() {
    Log.d(TAG, "💀 MainClientService onDestroy")
    closeNow()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
