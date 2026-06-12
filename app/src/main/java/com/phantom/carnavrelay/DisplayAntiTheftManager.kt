package com.phantom.carnavrelay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.ceil

enum class AntiTheftState {
    DISABLED,
    UNAVAILABLE_NO_JACK,
    READY,
    READY_UNPLUGGED,
    ARMED,
    CHALLENGE_1,
    CHALLENGE_2,
    POST_ALERT_COUNTDOWN,
    ALARM_ACTIVE,
    FULLSCREEN_LOCK
}

data class AntiTheftLocationSnapshot(
    val available: Boolean,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyMeters: Float? = null,
    val provider: String? = null,
    val capturedAt: Long? = null,
    val ageMs: Long? = null
)

data class AntiTheftUiSnapshot(
    val state: AntiTheftState,
    val featureEnabled: Boolean,
    val armed: Boolean,
    val hasPin: Boolean,
    val headsetPlugged: Boolean,
    val remainingMs: Long,
    val wrongAttempts: Int,
    val headline: String,
    val detail: String
)

object DisplayAntiTheftManager {

    private const val TAG = "PHANTOM_GO"
    const val ACTION_STATE_CHANGED = "com.phantom.carnavrelay.ANTI_THEFT_STATE_CHANGED"
    private const val ACTION_OPEN_CHALLENGE = "com.phantom.carnavrelay.ANTI_THEFT_OPEN_CHALLENGE"
    private const val NOTIFICATION_CHANNEL_ID = "phantom_go_anti_theft"
    private const val NOTIFICATION_ID_CHALLENGE = 4101
    private const val NOTIFICATION_ID_ALARM = 4102
    private const val CHALLENGE_TIMEOUT_MS = 15_000L
    private const val POST_ALERT_TIMEOUT_MS = 15_000L
    private const val ALARM_ESCALATION_MS = 30_000L
    private const val VOLUME_REINFORCE_MS = 2_000L
    private const val SPEECH_REPEAT_MS = 4_000L
    private const val HEADSET_DEBOUNCE_MS = 750L
    private const val LOCATION_MAX_AGE_MS = 30_000L

    const val ALERT_TIMEOUT_1 = "timeout_1"
    const val ALERT_REPLUG_DURING_CHALLENGE_1 = "replug_during_challenge_1"
    const val ALERT_WRONG_PIN_1 = "wrong_pin_1"
    const val ALERT_WRONG_PIN_2 = "wrong_pin_2"
    const val ALERT_ALARM_STARTED = "alarm_started"
    const val ALERT_ALARM_ESCALATED_FULLSCREEN = "alarm_escalated_fullscreen"
    const val ALERT_DISARMED_SUCCESS = "disarmed_success"

    private lateinit var appContext: Context
    private lateinit var prefs: PrefsManager
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var receiverRegistered = false
    private var state: AntiTheftState = AntiTheftState.DISABLED
    private var headsetPlugged = false
    private var countdownDeadlineAt = 0L
    private var wrongAttempts = 0
    private var sessionId: String? = null
    private var speechLoopActive = false
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null

    private val countdownTickRunnable = object : Runnable {
        override fun run() {
            when (state) {
                AntiTheftState.CHALLENGE_1,
                AntiTheftState.CHALLENGE_2,
                AntiTheftState.POST_ALERT_COUNTDOWN -> {
                    if (countdownDeadlineAt > 0L && System.currentTimeMillis() >= countdownDeadlineAt) {
                        onCountdownExpired()
                    } else {
                        broadcastState()
                        handler.postDelayed(this, 1000L)
                    }
                }
                else -> Unit
            }
        }
    }

    private val volumeReinforceRunnable = object : Runnable {
        override fun run() {
            if (state != AntiTheftState.ALARM_ACTIVE && state != AntiTheftState.FULLSCREEN_LOCK) {
                return
            }
            reinforceAlarmVolume()
            handler.postDelayed(this, VOLUME_REINFORCE_MS)
        }
    }

    private val speechRunnable = object : Runnable {
        override fun run() {
            if (!speechLoopActive || (state != AntiTheftState.ALARM_ACTIVE && state != AntiTheftState.FULLSCREEN_LOCK)) {
                return
            }
            speakTheftWarning()
            handler.postDelayed(this, SPEECH_REPEAT_MS)
        }
    }

    private val fullscreenEscalationRunnable = Runnable {
        if (state == AntiTheftState.ALARM_ACTIVE) {
            state = AntiTheftState.FULLSCREEN_LOCK
            prefs.setAntiTheftLastState(state.name)
            sendSecurityAlert(ALERT_ALARM_ESCALATED_FULLSCREEN)
            showPinChallengeUi(fullscreen = true)
            broadcastState()
        }
    }

    private var pendingHeadsetApply: Runnable? = null

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_HEADSET_PLUG) return
            val plugged = intent.getIntExtra("state", -1) == 1
            scheduleHeadsetStateApply(plugged)
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = PrefsManager(appContext)
        headsetPlugged = isHeadsetPluggedNow(appContext)
        wrongAttempts = prefs.getAntiTheftWrongAttempts()
        sessionId = prefs.getAntiTheftSessionId()
        state = prefs.getAntiTheftLastState()?.let {
            runCatching { AntiTheftState.valueOf(it) }.getOrNull()
        } ?: idleStateForCurrentInputs()
        initialized = true
        createNotificationChannel()
    }

    fun onDisplayServiceStarted(context: Context) {
        initialize(context)
        registerHeadsetReceiverIfNeeded()
        headsetPlugged = isHeadsetPluggedNow(appContext)
        prefs.setAntiTheftHeadsetPlugged(headsetPlugged)
        state = when (prefs.getAntiTheftLastState()) {
            AntiTheftState.ALARM_ACTIVE.name,
            AntiTheftState.FULLSCREEN_LOCK.name -> {
                prefs.setAntiTheftLastState(AntiTheftState.ALARM_ACTIVE.name)
                startAlarmInternal(resumeExisting = true, sendAlert = false)
                return
            }
            else -> idleStateForCurrentInputs()
        }
        prefs.setAntiTheftLastState(state.name)
        broadcastState()
    }

    fun onDisplayServiceStopped(explicitStop: Boolean) {
        if (!initialized) return
        if (!explicitStop) {
            PhantomLog.w("Anti-theft kept armed after unexpected Display service stop")
            return
        }
        unregisterHeadsetReceiverIfNeeded()
        stopAlarmOutputs()
        cancelAllTimers()
        prefs.setAntiTheftArmed(false)
        state = AntiTheftState.DISABLED
        prefs.setAntiTheftLastState(state.name)
        broadcastState()
    }

    fun snapshot(context: Context): AntiTheftUiSnapshot {
        initialize(context)
        val currentState = state
        return AntiTheftUiSnapshot(
            state = currentState,
            featureEnabled = prefs.isAntiTheftEnabled(),
            armed = prefs.isAntiTheftArmed(),
            hasPin = prefs.hasAntiTheftPin(),
            headsetPlugged = headsetPlugged,
            remainingMs = remainingMs(),
            wrongAttempts = wrongAttempts,
            headline = stateHeadline(currentState),
            detail = stateDetail(currentState)
        )
    }

    fun isVisibleOnDisplay(context: Context): Boolean {
        initialize(context)
        return headsetPlugged || prefs.isAntiTheftEnabled() || prefs.isAntiTheftArmed() ||
            state != AntiTheftState.DISABLED && state != AntiTheftState.UNAVAILABLE_NO_JACK
    }

    fun setFeatureEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        prefs.setAntiTheftEnabled(enabled)
        if (!enabled) {
            cancelAllTimers()
            stopAlarmOutputs()
            prefs.setAntiTheftArmed(false)
            sessionId = null
            wrongAttempts = 0
            prefs.setAntiTheftWrongAttempts(0)
            prefs.setAntiTheftSessionId(null)
            state = AntiTheftState.DISABLED
        } else {
            state = idleStateForCurrentInputs()
        }
        prefs.setAntiTheftLastState(state.name)
        broadcastState()
    }

    fun savePin(context: Context, pin: String): Boolean {
        initialize(context)
        val normalized = pin.trim()
        if (!isValidPin(normalized)) return false
        prefs.setAntiTheftPin(normalized)
        broadcastState()
        return true
    }

    fun hasPin(context: Context): Boolean {
        initialize(context)
        return prefs.hasAntiTheftPin()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        initialize(context)
        return prefs.verifyAntiTheftPin(pin.trim())
    }

    fun arm(context: Context): String? {
        initialize(context)
        if (!prefs.isAntiTheftEnabled()) return context.getString(R.string.at_enable_first)
        if (!prefs.hasAntiTheftPin()) return context.getString(R.string.at_set_pin_first)
        if (!headsetPlugged) return context.getString(R.string.at_plug_trigger_first)
        prefs.setAntiTheftArmed(true)
        state = AntiTheftState.ARMED
        prefs.setAntiTheftLastState(state.name)
        broadcastState()
        return null
    }

    fun disarmWithPin(context: Context, pin: String): Boolean {
        initialize(context)
        val ok = prefs.verifyAntiTheftPin(pin.trim())
        if (ok) {
            cancelAllTimers()
            stopAlarmOutputs()
            prefs.setAntiTheftArmed(false)
            state = if (headsetPlugged) AntiTheftState.READY else AntiTheftState.UNAVAILABLE_NO_JACK
            sessionId = null
            wrongAttempts = 0
            prefs.setAntiTheftSessionId(null)
            prefs.setAntiTheftWrongAttempts(0)
            prefs.setAntiTheftAlarmStartedAt(0L)
            prefs.setAntiTheftLastState(state.name)
            broadcastState()
        }
        return ok
    }

    fun submitChallengePin(context: Context, pin: String): Boolean {
        initialize(context)
        val normalized = pin.trim()
        if (prefs.verifyAntiTheftPin(normalized)) {
            onCorrectPin()
            return true
        }

        when (state) {
            AntiTheftState.CHALLENGE_1 -> {
                wrongAttempts = 1
                prefs.setAntiTheftWrongAttempts(wrongAttempts)
                sendSecurityAlert(ALERT_WRONG_PIN_1)
                vibrateOnce()
                state = AntiTheftState.CHALLENGE_2
                prefs.setAntiTheftLastState(state.name)
                scheduleCountdown(CHALLENGE_TIMEOUT_MS)
                broadcastState()
                return false
            }
            AntiTheftState.CHALLENGE_2 -> {
                wrongAttempts = 2
                prefs.setAntiTheftWrongAttempts(wrongAttempts)
                sendSecurityAlert(ALERT_WRONG_PIN_2)
                startAlarmInternal(resumeExisting = false, sendAlert = true)
                return false
            }
            AntiTheftState.POST_ALERT_COUNTDOWN -> {
                startAlarmInternal(resumeExisting = false, sendAlert = true)
                return false
            }
            AntiTheftState.ALARM_ACTIVE,
            AntiTheftState.FULLSCREEN_LOCK -> {
                vibrateOnce()
                broadcastState()
                return false
            }
            else -> return false
        }
    }

    fun setAttachLocationEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        prefs.setAntiTheftAttachLocationEnabled(enabled)
        broadcastState()
    }

    fun isAttachLocationEnabled(context: Context): Boolean {
        initialize(context)
        return prefs.isAntiTheftAttachLocationEnabled()
    }

    fun setAlarmSoundUri(context: Context, uri: Uri?) {
        initialize(context)
        prefs.setAntiTheftAlarmSoundUri(uri?.toString())
    }

    fun getAlarmSoundUri(context: Context): Uri? {
        initialize(context)
        return prefs.getAntiTheftAlarmSoundUri()?.let(Uri::parse)
    }

    fun getCurrentState(context: Context): AntiTheftState {
        initialize(context)
        return state
    }

    fun testAlarm(context: Context) {
        initialize(context)
        startAlarmInternal(resumeExisting = false, sendAlert = false)
    }

    private fun scheduleHeadsetStateApply(plugged: Boolean) {
        pendingHeadsetApply?.let(handler::removeCallbacks)
        val applyRunnable = Runnable {
            applyStableHeadsetState(plugged)
        }
        pendingHeadsetApply = applyRunnable
        handler.postDelayed(applyRunnable, HEADSET_DEBOUNCE_MS)
    }

    private fun applyStableHeadsetState(plugged: Boolean) {
        headsetPlugged = plugged
        prefs.setAntiTheftHeadsetPlugged(plugged)
        if (!prefs.isAntiTheftEnabled()) {
            state = AntiTheftState.DISABLED
            prefs.setAntiTheftLastState(state.name)
            broadcastState()
            return
        }

        if (state == AntiTheftState.CHALLENGE_1 && plugged) {
            state = AntiTheftState.POST_ALERT_COUNTDOWN
            prefs.setAntiTheftLastState(state.name)
            sendSecurityAlert(ALERT_REPLUG_DURING_CHALLENGE_1)
            scheduleCountdown(POST_ALERT_TIMEOUT_MS)
            broadcastState()
            return
        }

        if (prefs.isAntiTheftArmed() && !plugged && state == AntiTheftState.ARMED) {
            startChallenge1()
            return
        }

        if (!prefs.isAntiTheftArmed() || state == AntiTheftState.READY_UNPLUGGED || state == AntiTheftState.READY || state == AntiTheftState.UNAVAILABLE_NO_JACK) {
            state = idleStateForCurrentInputs()
            prefs.setAntiTheftLastState(state.name)
            broadcastState()
        }
    }

    private fun startChallenge1() {
        sessionId = "ats_${System.currentTimeMillis()}"
        prefs.setAntiTheftSessionId(sessionId)
        wrongAttempts = 0
        prefs.setAntiTheftWrongAttempts(0)
        state = AntiTheftState.CHALLENGE_1
        prefs.setAntiTheftLastState(state.name)
        scheduleCountdown(CHALLENGE_TIMEOUT_MS)
        showPinChallengeUi(fullscreen = false)
        broadcastState()
    }

    private fun onCountdownExpired() {
        when (state) {
            AntiTheftState.CHALLENGE_1 -> {
                state = AntiTheftState.POST_ALERT_COUNTDOWN
                prefs.setAntiTheftLastState(state.name)
                sendSecurityAlert(ALERT_TIMEOUT_1)
                scheduleCountdown(POST_ALERT_TIMEOUT_MS)
                showPinChallengeUi(fullscreen = false)
                broadcastState()
            }
            AntiTheftState.CHALLENGE_2,
            AntiTheftState.POST_ALERT_COUNTDOWN -> {
                startAlarmInternal(resumeExisting = false, sendAlert = true)
            }
            else -> Unit
        }
    }

    private fun startAlarmInternal(resumeExisting: Boolean, sendAlert: Boolean) {
        cancelCountdown()
        state = AntiTheftState.ALARM_ACTIVE
        prefs.setAntiTheftLastState(state.name)
        if (!resumeExisting) {
            prefs.setAntiTheftAlarmStartedAt(System.currentTimeMillis())
        }
        if (sendAlert) {
            sendSecurityAlert(ALERT_ALARM_STARTED)
        }
        startAlarmOutputs()
        showPinChallengeUi(fullscreen = false)
        scheduleFullscreenEscalation()
        broadcastState()
    }

    private fun onCorrectPin() {
        val previousState = state
        cancelAllTimers()
        stopAlarmOutputs()
        wrongAttempts = 0
        prefs.setAntiTheftWrongAttempts(0)
        prefs.setAntiTheftAlarmStartedAt(0L)
        val nextState = if (prefs.isAntiTheftArmed()) {
            if (headsetPlugged) AntiTheftState.ARMED else AntiTheftState.READY_UNPLUGGED
        } else {
            if (headsetPlugged) AntiTheftState.READY else AntiTheftState.UNAVAILABLE_NO_JACK
        }
        state = nextState
        prefs.setAntiTheftLastState(state.name)
        if (sessionId != null && previousState != AntiTheftState.ARMED && previousState != AntiTheftState.READY) {
            sendSecurityAlert(ALERT_DISARMED_SUCCESS)
        }
        sessionId = null
        prefs.setAntiTheftSessionId(null)
        broadcastState()
    }

    private fun idleStateForCurrentInputs(): AntiTheftState {
        return when {
            !prefs.isAntiTheftEnabled() -> AntiTheftState.DISABLED
            prefs.isAntiTheftArmed() && headsetPlugged -> AntiTheftState.ARMED
            prefs.isAntiTheftArmed() && !headsetPlugged -> AntiTheftState.READY_UNPLUGGED
            headsetPlugged -> AntiTheftState.READY
            else -> AntiTheftState.UNAVAILABLE_NO_JACK
        }
    }

    private fun scheduleCountdown(durationMs: Long) {
        cancelCountdown()
        countdownDeadlineAt = System.currentTimeMillis() + durationMs
        handler.post(countdownTickRunnable)
    }

    private fun cancelCountdown() {
        countdownDeadlineAt = 0L
        handler.removeCallbacks(countdownTickRunnable)
    }

    private fun scheduleFullscreenEscalation() {
        handler.removeCallbacks(fullscreenEscalationRunnable)
        handler.postDelayed(fullscreenEscalationRunnable, ALARM_ESCALATION_MS)
    }

    private fun cancelAllTimers() {
        pendingHeadsetApply?.let(handler::removeCallbacks)
        pendingHeadsetApply = null
        cancelCountdown()
        handler.removeCallbacks(volumeReinforceRunnable)
        handler.removeCallbacks(speechRunnable)
        handler.removeCallbacks(fullscreenEscalationRunnable)
    }

    private fun remainingMs(): Long {
        if (countdownDeadlineAt <= 0L) return 0L
        return (countdownDeadlineAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun registerHeadsetReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_HEADSET_PLUG)
        ContextCompat.registerReceiver(
            appContext,
            headsetReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterHeadsetReceiverIfNeeded() {
        if (!receiverRegistered) return
        runCatching {
            appContext.unregisterReceiver(headsetReceiver)
        }
        receiverRegistered = false
    }

    private fun isHeadsetPluggedNow(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_LINE_ANALOG
        }
    }

    private fun startAlarmOutputs() {
        startMediaAlarm()
        startSpeechLoop()
        vibrateOnce()
        reinforceAlarmVolume()
        handler.post(volumeReinforceRunnable)
    }

    private fun stopAlarmOutputs() {
        speechLoopActive = false
        handler.removeCallbacks(volumeReinforceRunnable)
        handler.removeCallbacks(speechRunnable)
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { tts?.stop() }
        cancelNotifications()
    }

    private fun startMediaAlarm() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null

        val soundUri = prefs.getAntiTheftAlarmSoundUri()?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        mediaPlayer = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                setDataSource(appContext, soundUri)
                prepare()
                start()
            }
        }.getOrElse {
            Log.e(TAG, "💥 Failed to start anti-theft alarm sound", it)
            PhantomLog.e("Failed to start anti-theft alarm sound", it)
            null
        }
    }

    private fun startSpeechLoop() {
        val existing = tts
        if (existing != null) {
            speechLoopActive = true
            existing.language = Locale("th", "TH")
            existing.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            handler.post(speechRunnable)
            return
        }

        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("th", "TH")
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                speechLoopActive = true
                handler.post(speechRunnable)
            } else {
                Log.w(TAG, "⚠️ TextToSpeech init failed for anti-theft")
            }
        }
    }

    private fun speakTheftWarning() {
        val engine = tts ?: return
        if (engine.isSpeaking) return
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }
        runCatching {
            engine.speak("ขโมย", TextToSpeech.QUEUE_FLUSH, params, "anti-theft-${System.currentTimeMillis()}")
        }
    }

    private fun reinforceAlarmVolume() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }.onFailure {
            Log.w(TAG, "⚠️ Failed to reinforce alarm volume", it)
        }
    }

    private fun vibrateOnce() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = manager?.defaultVibrator ?: return@runCatching
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@runCatching
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 250, 120, 250), -1)
                }
            }
        }
    }

    private fun showPinChallengeUi(fullscreen: Boolean) {
        showChallengeNotification(fullscreen)
        val intent = Intent(appContext, AntiTheftPinActivity::class.java).apply {
            action = ACTION_OPEN_CHALLENGE
            putExtra(AntiTheftPinActivity.EXTRA_FULLSCREEN, fullscreen)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        runCatching { appContext.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "⚠️ Could not start AntiTheftPinActivity directly", it)
            }
    }

    private fun showChallengeNotification(fullscreen: Boolean) {
        createNotificationChannel()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Security verification notification blocked: notification permission missing")
            return
        }
        val launchIntent = Intent(appContext, AntiTheftPinActivity::class.java).apply {
            action = ACTION_OPEN_CHALLENGE
            putExtra(AntiTheftPinActivity.EXTRA_FULLSCREEN, fullscreen)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            4100,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_display_mode)
            .setContentTitle(
                string(
                    if (fullscreen) {
                        R.string.at_notification_lock_active
                    } else {
                        R.string.at_notification_verification_required
                    }
                )
            )
            .setContentText(stateDetail(state))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(state == AntiTheftState.ALARM_ACTIVE || state == AntiTheftState.FULLSCREEN_LOCK)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(
                if (fullscreen) NOTIFICATION_ID_ALARM else NOTIFICATION_ID_CHALLENGE,
                notification
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Security verification notification blocked", e)
        }
    }

    private fun cancelNotifications() {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID_CHALLENGE)
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID_ALARM)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            string(R.string.at_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = string(R.string.at_channel_desc)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun captureLocationSnapshot(): AntiTheftLocationSnapshot {
        if (!prefs.isAntiTheftAttachLocationEnabled()) {
            return AntiTheftLocationSnapshot(available = false)
        }
        val hasFineLocation = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) {
            return AntiTheftLocationSnapshot(available = false)
        }
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return AntiTheftLocationSnapshot(available = false)
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER
        )
        val best = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
            ?: return AntiTheftLocationSnapshot(available = false)

        val ageMs = (System.currentTimeMillis() - best.time).coerceAtLeast(0L)
        if (ageMs > LOCATION_MAX_AGE_MS) {
            return AntiTheftLocationSnapshot(available = false, ageMs = ageMs)
        }
        return AntiTheftLocationSnapshot(
            available = true,
            lat = best.latitude,
            lng = best.longitude,
            accuracyMeters = if (best.hasAccuracy()) best.accuracy else null,
            provider = best.provider,
            capturedAt = best.time,
            ageMs = ageMs
        )
    }

    private fun sendSecurityAlert(eventType: String) {
        if (sessionId.isNullOrBlank()) {
            sessionId = "ats_${System.currentTimeMillis()}"
            prefs.setAntiTheftSessionId(sessionId)
        }
        val location = captureLocationSnapshot()
        val alertSessionId = sessionId ?: return
        FirebaseManager.sendSecurityAlert(
            context = appContext,
            eventType = eventType,
            sessionId = alertSessionId,
            wrongAttemptCount = wrongAttempts,
            jackPlugged = headsetPlugged,
            location = location,
            speedKmh = prefs.getLastSpeedKmh(),
            alarmState = state.name
        )
    }

    private fun stateHeadline(currentState: AntiTheftState): String {
        return when (currentState) {
            AntiTheftState.DISABLED -> string(R.string.at_state_disabled_headline)
            AntiTheftState.UNAVAILABLE_NO_JACK -> string(R.string.at_state_unavailable_headline)
            AntiTheftState.READY -> string(R.string.at_state_ready_headline)
            AntiTheftState.READY_UNPLUGGED -> string(R.string.at_state_missing_headline)
            AntiTheftState.ARMED -> string(R.string.at_state_armed_headline)
            AntiTheftState.CHALLENGE_1 -> string(R.string.at_state_challenge_headline)
            AntiTheftState.CHALLENGE_2 -> string(R.string.at_state_challenge_required_headline)
            AntiTheftState.POST_ALERT_COUNTDOWN -> string(R.string.at_state_challenge_headline)
            AntiTheftState.ALARM_ACTIVE -> string(R.string.at_state_lock_headline)
            AntiTheftState.FULLSCREEN_LOCK -> string(R.string.at_state_lock_headline)
        }
    }

    private fun stateDetail(currentState: AntiTheftState): String {
        return when (currentState) {
            AntiTheftState.DISABLED -> string(R.string.at_state_disabled_detail)
            AntiTheftState.UNAVAILABLE_NO_JACK -> string(R.string.at_state_unavailable_detail)
            AntiTheftState.READY -> string(R.string.at_state_ready_detail)
            AntiTheftState.READY_UNPLUGGED -> string(R.string.at_state_reconnect_detail)
            AntiTheftState.ARMED -> string(R.string.at_state_armed_detail)
            AntiTheftState.CHALLENGE_1 -> string(R.string.at_state_challenge_detail_fmt, remainingSeconds())
            AntiTheftState.CHALLENGE_2 -> string(R.string.at_state_challenge_failed_detail_fmt, remainingSeconds())
            AntiTheftState.POST_ALERT_COUNTDOWN -> string(R.string.at_state_post_alert_detail_fmt, remainingSeconds())
            AntiTheftState.ALARM_ACTIVE -> string(R.string.at_state_alarm_detail)
            AntiTheftState.FULLSCREEN_LOCK -> string(R.string.at_state_fullscreen_detail)
        }
    }

    private fun string(@StringRes resId: Int, vararg args: Any): String {
        return if (args.isEmpty()) {
            appContext.getString(resId)
        } else {
            appContext.getString(resId, *args)
        }
    }

    private fun remainingSeconds(): Int {
        return ceil(remainingMs() / 1000.0).toInt().coerceAtLeast(0)
    }

    private fun isValidPin(pin: String): Boolean {
        return pin.length >= 4 && pin.length <= 6 && pin.all { it.isDigit() }
    }

    private fun broadcastState() {
        if (!initialized) return
        val snapshot = snapshot(appContext)
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            setPackage(appContext.packageName)
            putExtra("state", snapshot.state.name)
            putExtra("featureEnabled", snapshot.featureEnabled)
            putExtra("armed", snapshot.armed)
            putExtra("hasPin", snapshot.hasPin)
            putExtra("headsetPlugged", snapshot.headsetPlugged)
            putExtra("remainingMs", snapshot.remainingMs)
            putExtra("wrongAttempts", snapshot.wrongAttempts)
            putExtra("headline", snapshot.headline)
            putExtra("detail", snapshot.detail)
        }
        appContext.sendBroadcast(intent)
        PhantomLog.i("Anti-theft state -> ${snapshot.state.name} (armed=${snapshot.armed}, headset=${snapshot.headsetPlugged}, wrong=$wrongAttempts)")
    }
}
