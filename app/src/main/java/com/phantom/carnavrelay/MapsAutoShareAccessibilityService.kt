package com.phantom.carnavrelay

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MapsAutoShareAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MapsAutoShareA11y"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        MapsAutoShareCoordinator.onServiceConnected(this)
        Log.i(TAG, "Scoped Maps accessibility service connected")
        PhantomLog.i("Maps Auto Share accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        MapsAutoShareCoordinator.onAccessibilityEvent(this, event)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Scoped Maps accessibility feedback interrupted")
        PhantomLog.w("Maps Auto Share accessibility interrupted")
    }

    override fun onDestroy() {
        MapsAutoShareCoordinator.onServiceDisconnected(this)
        super.onDestroy()
    }
}
