package com.phantom.carnavrelay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.random.Random

object DisplayModeFlow {

  private const val TAG = "PHANTOM_GO"

  fun start(ctx: Context): Boolean {
    Log.d(TAG, "📺 DisplayModeFlow.start() called")
    
    // ตรวจสอบสิทธิ์ Bluetooth ก่อน
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val hasConnect = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
      val hasScan = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
      Log.d(TAG, "🔒 BT permissions - CONNECT: $hasConnect, SCAN: $hasScan")
      
      if (!hasConnect || !hasScan) {
        Log.w(TAG, "❌ Missing Bluetooth permissions for DisplayMode")
        MaterialAlertDialogBuilder(ctx)
          .setTitle(R.string.bt_permission_title)
          .setMessage(R.string.bt_permission_message)
          .setPositiveButton(R.string.ok, null)
          .show()
        return false
      }
    }
    
    // ตรวจสอบ Bluetooth Adapter
    val adapter = BluetoothAdapter.getDefaultAdapter()
    if (adapter == null) {
      Log.e(TAG, "❌ No Bluetooth adapter found")
      MaterialAlertDialogBuilder(ctx)
        .setTitle(R.string.bt_not_supported_title)
        .setMessage(R.string.bt_not_supported_message)
        .setPositiveButton(R.string.ok, null)
        .show()
      return false
    }
    
    if (!adapter.isEnabled) {
      Log.w(TAG, "⚠️ Bluetooth is disabled")
      MaterialAlertDialogBuilder(ctx)
        .setTitle(R.string.bt_disabled_title)
        .setMessage(R.string.bt_disabled_message)
        .setPositiveButton(R.string.ok, null)
        .show()
      return false
    }
    
    val code = Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
    Log.d(TAG, "🔢 Generated pairing code: $code")
    
    val i = Intent(ctx, DisplayActivity::class.java).apply {
      putExtra("code", code)
      // No FLAG_ACTIVITY_NEW_TASK needed - called from Activity context
    }
    
    try {
      ctx.startActivity(i)
      Log.d(TAG, "✅ DisplayActivity started successfully")
      return true
    } catch (e: Exception) {
      Log.e(TAG, "💥 Failed to start DisplayActivity", e)
      throw e
    }
  }
}
