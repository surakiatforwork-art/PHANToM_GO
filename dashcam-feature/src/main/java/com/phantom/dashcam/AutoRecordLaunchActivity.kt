package com.phantom.dashcam

import android.os.Build
import android.os.Bundle
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class AutoRecordLaunchActivity : AppCompatActivity() {

    private var hasRequestedStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DashcamStateStore.bootstrap(this)
        val rotation = intent.getIntExtra(EXTRA_ROTATION, currentRotation())
        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: DashcamRecordingService.TRIGGER_POWER_CONNECTED
        SurfaceRotationStore.update(rotation)
        DashcamStateStore.update {
            it.copy(
                detailText = getString(R.string.auto_record_launch_detail),
                backgroundStartPending = false,
            )
        }

        window.decorView.post {
            requestRecordingStartIfNeeded(trigger, rotation)
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        val rotation = intent.getIntExtra(EXTRA_ROTATION, currentRotation())
        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: DashcamRecordingService.TRIGGER_POWER_CONNECTED
        requestRecordingStartIfNeeded(trigger, rotation)
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }
    }

    private fun requestRecordingStartIfNeeded(trigger: String, rotation: Int) {
        if (hasRequestedStart) {
            return
        }
        hasRequestedStart = true
        window.decorView.postDelayed({
            ContextCompat.startForegroundService(
                this,
                DashcamRecordingService.createStartIntent(
                    this,
                    trigger,
                    rotation,
                ),
            )
        }, START_DELAY_MS)
        window.decorView.postDelayed({
            finish()
            overridePendingTransition(0, 0)
        }, FINISH_DELAY_MS)
    }

    companion object {
        private const val EXTRA_TRIGGER = "auto_trigger"
        private const val EXTRA_ROTATION = "auto_rotation"
        private const val START_DELAY_MS = 250L
        private const val FINISH_DELAY_MS = 1_200L

        fun createIntent(context: android.content.Context, trigger: String, rotation: Int): android.content.Intent {
            return android.content.Intent(context, AutoRecordLaunchActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
                putExtra(EXTRA_TRIGGER, trigger)
                putExtra(EXTRA_ROTATION, rotation)
            }
        }
    }
}
