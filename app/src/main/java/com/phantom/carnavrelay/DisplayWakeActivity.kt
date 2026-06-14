package com.phantom.carnavrelay

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent one-shot activity used only to turn the screen on.
 * It immediately finishes so the previously visible app or launcher remains in front.
 */
class DisplayWakeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_REASON = "wake_reason"
        private const val FINISH_DELAY_MS = 650L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguard?.isDeviceSecure == false) {
                keyguard.requestDismissKeyguard(this, null)
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        PhantomLog.i("DISPLAY POWER transparent wake activity reason=${intent.getStringExtra(EXTRA_REASON)}")
        window.decorView.postDelayed({
            finish()
            overridePendingTransition(0, 0)
        }, FINISH_DELAY_MS)
    }
}
