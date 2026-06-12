package com.phantom.carnavrelay

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object MainModeFlow {

  private const val TAG = "PHANTOM_GO"

  fun start(activity: Activity): Boolean {
    Log.d(TAG, "▶️ MainModeFlow.start() called from ${activity.javaClass.simpleName}")
    
    // Check Bluetooth permissions first
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val hasConnect = ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
      val hasScan = ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
      Log.d(TAG, "🔒 BT permissions - CONNECT: $hasConnect, SCAN: $hasScan")
      
      if (!hasConnect || !hasScan) {
        Log.w(TAG, "❌ Missing Bluetooth permissions for MainMode")
        showPermissionDialog(activity)
        return false
      }
    }

    val adapter = BluetoothAdapter.getDefaultAdapter()
    
    if (adapter == null) {
      Log.e(TAG, "❌ No Bluetooth adapter found")
      MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.bt_not_supported_title)
        .setMessage(R.string.bt_not_supported_message)
        .setPositiveButton(R.string.ok, null)
        .show()
      return false
    }
    
    Log.d(TAG, "📡 Bluetooth adapter found, checking enabled state")
    
    if (!adapter.isEnabled) {
      Log.w(TAG, "⚠️ Bluetooth is disabled")
      MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.bt_disabled_title)
        .setMessage(R.string.bt_disabled_message)
        .setPositiveButton(R.string.ok, null)
        .show()
      return false
    }
    
    Log.d(TAG, "✅ Bluetooth is enabled")
    
    val bonded = try {
      adapter.bondedDevices?.toList().orEmpty()
    } catch (e: SecurityException) {
      Log.e(TAG, "💥 SecurityException accessing bonded devices", e)
      CrashReporter.recordException(activity, "MainModeFlow:getBondedDevices", e)
      emptyList()
    }
    
    Log.d(TAG, "🔗 Found ${bonded.size} paired device(s)")

    if (bonded.isEmpty()) {
      Log.w(TAG, "⚠️ No paired Bluetooth devices")
      MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.paired_devices)
        .setMessage(R.string.no_paired_devices)
        .setPositiveButton(R.string.ok, null)
        .show()
      return false
    }

    val names = bonded.map { "${it.name ?: activity.getString(R.string.bt_unknown_device)}\n${it.address}" }.toTypedArray()
    
    Log.d(TAG, "📋 Showing device picker dialog with ${names.size} devices")

    MaterialAlertDialogBuilder(activity)
      .setTitle(R.string.paired_devices)
      .setItems(names) { _, which ->
        val device = bonded[which]
        Log.d(TAG, "� User selected device: ${device.name} (${device.address})")
        
        // Start MainPairActivity and then finish MainActivity
        val intent = Intent(activity, MainPairActivity::class.java).apply {
          putExtra("address", device.address)
          putExtra("device_name", device.name ?: activity.getString(R.string.bt_unknown_device))
          // No FLAG_ACTIVITY_NEW_TASK needed when using Activity context
        }
        
        try {
          Log.d(TAG, "🚀 Starting MainPairActivity for ${device.address}")
          activity.startActivity(intent)
          Log.d(TAG, "✅ MainPairActivity started, finishing MainActivity")
          Toast.makeText(
            activity,
            activity.getString(R.string.bt_connecting_device_fmt, device.name ?: activity.getString(R.string.bt_unknown_device)),
            Toast.LENGTH_SHORT
          ).show()
          // Now finish the calling activity after successfully starting MainPairActivity
          activity.finish()
        } catch (e: Exception) {
          Log.e(TAG, "💥 Failed to start MainPairActivity", e)
          CrashReporter.recordException(activity, "MainModeFlow:startMainPairActivity", e)
          Toast.makeText(
            activity,
            activity.getString(R.string.bt_cannot_open_pair_fmt, e.message ?: activity.getString(R.string.common_unknown)),
            Toast.LENGTH_LONG
          ).show()
        }
      }
      .setNegativeButton(R.string.cancel) { _, _ ->
        Log.d(TAG, "❌ User cancelled device selection dialog")
        // Don't finish activity - just let dialog dismiss and stay on MainActivity
      }
      .setOnCancelListener {
        Log.d(TAG, "❌ Device picker dialog cancelled (back button)")
        // Don't finish activity - stay on MainActivity
      }
      .show()
    
    Log.d(TAG, "⏳ Device picker dialog shown, returning false (mode not yet started)")
    // Return false because we haven't actually started the mode yet - we're waiting for user selection
    return false
  }

  private fun showPermissionDialog(activity: Activity) {
    Log.d(TAG, "⚠️ Showing permission dialog")
    MaterialAlertDialogBuilder(activity)
      .setTitle(R.string.bt_permission_title)
      .setMessage(R.string.bt_permission_message)
      .setPositiveButton(R.string.ok, null)
      .show()
  }
}
