package com.phantom.carnavrelay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class AntiTheftPinActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FULLSCREEN = "fullscreen"
    }

    private lateinit var rootCard: MaterialCardView
    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var countdownText: TextView
    private lateinit var pinInput: EditText
    private lateinit var confirmButton: Button
    private lateinit var errorText: TextView
    private var fullscreen = false
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DisplayAntiTheftManager.ACTION_STATE_CHANGED) return
            applyState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anti_theft_pin)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        rootCard = findViewById(R.id.antiTheftPinCard)
        titleText = findViewById(R.id.antiTheftPinTitle)
        detailText = findViewById(R.id.antiTheftPinDetail)
        countdownText = findViewById(R.id.antiTheftPinCountdown)
        pinInput = findViewById(R.id.antiTheftPinInput)
        confirmButton = findViewById(R.id.antiTheftPinConfirm)
        errorText = findViewById(R.id.antiTheftPinError)

        pinInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        confirmButton.setOnClickListener {
            val ok = DisplayAntiTheftManager.submitChallengePin(this, pinInput.text?.toString().orEmpty())
            if (ok) {
                finish()
            } else {
                pinInput.text?.clear()
                errorText.text = getString(R.string.at_verify_failed)
                errorText.alpha = 1f
                applyState()
            }
        }

        fullscreen = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)
        applyMode()
        applyState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fullscreen = intent.getBooleanExtra(EXTRA_FULLSCREEN, fullscreen)
        applyMode()
        applyState()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                stateReceiver,
                IntentFilter(DisplayAntiTheftManager.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // Block dismiss while active.
    }

    private fun applyState() {
        val snapshot = DisplayAntiTheftManager.snapshot(this)
        when (snapshot.state) {
            AntiTheftState.CHALLENGE_1,
            AntiTheftState.CHALLENGE_2,
            AntiTheftState.POST_ALERT_COUNTDOWN,
            AntiTheftState.ALARM_ACTIVE,
            AntiTheftState.FULLSCREEN_LOCK -> {
                titleText.text = snapshot.headline
                detailText.text = snapshot.detail
                countdownText.text = if (snapshot.remainingMs > 0L) {
                    getString(R.string.at_time_left_fmt, ((snapshot.remainingMs + 999L) / 1000L))
                } else {
                    if (snapshot.state == AntiTheftState.ALARM_ACTIVE || snapshot.state == AntiTheftState.FULLSCREEN_LOCK) {
                        getString(R.string.at_verification_required)
                    } else {
                        ""
                    }
                }
                if (snapshot.state == AntiTheftState.FULLSCREEN_LOCK) {
                    fullscreen = true
                    applyMode()
                }
            }
            else -> finish()
        }
    }

    private fun applyMode() {
        if (fullscreen) {
            rootCard.layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            rootCard.layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            rootCard.radius = 0f
            rootCard.strokeWidth = 0
            rootCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.aurora_black))
        } else {
            rootCard.layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
            rootCard.layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
            rootCard.radius = resources.displayMetrics.density * 24f
            rootCard.strokeWidth = resources.displayMetrics.density.toInt()
            rootCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.aurora_glass))
        }
        rootCard.requestLayout()
    }
}
