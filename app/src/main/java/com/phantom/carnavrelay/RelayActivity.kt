package com.phantom.carnavrelay

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

class RelayActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val EXTRA_TEXT = "android.intent.extra.TEXT"
    }

    private lateinit var mainSender: MainSender
    private lateinit var prefsManager: PrefsManager
    private lateinit var sendingText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var actionButton: Button

    private var lastRaw: String? = null
    private var requestId: Int = 0
    private var hasOpenedMaps = false
    private var pendingOpenMapsAfterSend: Boolean? = null
    private var lastResolvedUrl: String? = null
    private var lastClassification: MapIntentType = MapIntentType.UNKNOWN
    private var lastIntent: Intent? = null
    private var lastForceSend: Boolean = false

    private enum class MapIntentType {
        NAVIGATION,
        VIEW_MAP,
        UNKNOWN
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay_sending)

        sendingText = findViewById(R.id.sendingText)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        actionButton = findViewById(R.id.actionButton)

        mainSender = MainSender(applicationContext, toastEnabled = false)
        prefsManager = PrefsManager(applicationContext)

        actionButton.visibility = View.GONE
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIntent(intent)
        }
    }

    private fun handleIntent(intent: Intent) {
        requestId += 1
        val currentRequestId = requestId
        resetUiForNewRequest()

        if (isDisplayModeActive()) {
            Log.w(TAG, "🚫 Display mode active - ignoring external intent")
            PhantomLog.w("NAV ignored: device in Display mode")
            showError(getString(R.string.relay_display_mode_active), retryable = false)
            return
        }

        lastIntent = intent
        val isMapView = isMapViewIntent(intent)
        lastForceSend = !isMapView
        val hubEnabled = prefsManager.isMapLinkHubEnabled()

        val raw = extractRawInput(intent)
        if (raw.isNullOrEmpty()) {
            Log.e(TAG, "❌ NO_RAW: No data received in intent")
            PhantomLog.w("NAV no raw input")
            showError(getString(R.string.relay_no_data), retryable = false)
            return
        }

        if (isMapView && !hubEnabled) {
            Log.d(TAG, "↩️ Map Link Hub OFF - forwarding to Google Maps")
            PhantomLog.i("NAV action=OPEN_MAPS (hub off)")
            openMapsDirectAndFinish(raw)
            return
        }

        lastRaw = raw
        Log.d(TAG, "📋 Raw input: $raw")
        PhantomLog.i("NAV rawUrl=$raw")

        if (!isMapView) {
            PhantomLog.i("NAV action=SEND (share)")
            startSendFlow(raw, currentRequestId, intent, forceSend = true)
            return
        }

        startSendFlow(raw, currentRequestId, intent, forceSend = false)
    }

    private fun startSendFlow(
        raw: String,
        currentRequestId: Int,
        originalIntent: Intent,
        forceSend: Boolean
    ) {
        val normalizedUrl = NavLinkUtils.normalizeRawInput(raw)
        if (normalizedUrl.isNullOrEmpty()) {
            Log.w(TAG, "❌ Could not normalize input: $raw")
            PhantomLog.w("NAV normalize failed: $raw")
            showError(getString(R.string.relay_invalid_link), retryable = false)
            return
        }

        updateStatus(getString(R.string.relay_resolving), R.color.aurora_mint)
        PhantomLog.i("NAV normalizedUrl=$normalizedUrl")

        Thread {
            val resolvedUrl = NavLinkUtils.resolveShortLinkIfNeeded(prefsManager, normalizedUrl)
            runOnUiThread {
                if (currentRequestId != requestId) return@runOnUiThread
                lastResolvedUrl = resolvedUrl
                PhantomLog.i("NAV resolvedUrl=$resolvedUrl")
                if (forceSend) {
                    updateStatus(getString(R.string.relay_sending), R.color.aurora_mint)
                    pendingOpenMapsAfterSend = null
                    sendToDisplay(resolvedUrl, currentRequestId)
                } else {
                    val classification = classifyMapIntent(resolvedUrl, originalIntent)
                    lastClassification = classification
                    PhantomLog.i("NAV classify=$classification")
                    decideAction(resolvedUrl, classification, currentRequestId)
                }
            }
        }.start()
    }

    private fun decideAction(resolvedUrl: String, classification: MapIntentType, currentRequestId: Int) {
        val smartEnabled = prefsManager.isSmartModeEnabled()
        val policy = prefsManager.getSmartModePolicy()

        if (!smartEnabled) {
            PhantomLog.i("NAV action=ASK (smart off)")
            showActionSheet(resolvedUrl, classification, currentRequestId)
            return
        }

        when (policy) {
            PrefsManager.SMART_POLICY_AUTO_SEND_NAV_ONLY -> {
                if (classification == MapIntentType.NAVIGATION) {
                    PhantomLog.i("NAV action=SEND (auto nav)")
                    updateStatus(getString(R.string.relay_sending), R.color.aurora_mint)
                    pendingOpenMapsAfterSend = false
                    sendToDisplay(resolvedUrl, currentRequestId)
                } else {
                    PhantomLog.i("NAV action=ASK (auto nav fallback)")
                    showActionSheet(resolvedUrl, classification, currentRequestId)
                }
            }
            PrefsManager.SMART_POLICY_ALWAYS_SEND -> {
                PhantomLog.i("NAV action=SEND (always)")
                updateStatus(getString(R.string.relay_sending), R.color.aurora_mint)
                pendingOpenMapsAfterSend = false
                sendToDisplay(resolvedUrl, currentRequestId)
            }
            PrefsManager.SMART_POLICY_ALWAYS_OPEN_ON_PHONE -> {
                PhantomLog.i("NAV action=OPEN_PHONE (always)")
                openGoogleMaps(resolvedUrl) { success ->
                    if (success) {
                        applyAfterSendBehavior()
                    } else {
                        showError(getString(R.string.relay_maps_not_found), retryable = false)
                    }
                }
            }
            else -> {
                PhantomLog.i("NAV action=ASK (always)")
                showActionSheet(resolvedUrl, classification, currentRequestId)
            }
        }
    }

    private fun sendToDisplay(resolvedUrl: String, currentRequestId: Int) {
        if (!mainSender.isPaired()) {
            Log.w(TAG, "❌ Not paired with any display device")
            showError(getString(R.string.relay_not_paired), retryable = false)
            PhantomLog.w("NAV send failed: not paired")
            return
        }

        val locationOnlyUrl = NavLinkUtils.toLocationOnlyUrl(resolvedUrl)
        if (locationOnlyUrl != resolvedUrl) {
            PhantomLog.i("NAV locationOnlyUrl=$locationOnlyUrl")
        }
        Log.d(TAG, "📤 Sending URL to display: $locationOnlyUrl")
        PhantomLog.i("NAV sendUrl=$locationOnlyUrl")

        mainSender.sendOpenUrl(locationOnlyUrl, object : MainSender.Companion.SendCallback {
            override fun onSuccess() {
                runOnUiThread {
                    if (currentRequestId != requestId) return@runOnUiThread
                    Log.d(TAG, "✅ URL sent successfully to display")
                    PhantomLog.i("NAV send result=success")
                    handleSendSuccess(resolvedUrl)
                }
            }

            override fun onFailure(error: String, queued: Boolean, authFailed: Boolean) {
                runOnUiThread {
                    if (currentRequestId != requestId) return@runOnUiThread
                    Log.e(TAG, "❌ Failed to send URL: $error, queued=$queued, authFailed=$authFailed")
                    PhantomLog.w("NAV send result=failure error=$error queued=$queued authFailed=$authFailed")
                    showError(getString(R.string.relay_send_failed_fmt, error), retryable = true)
                }
            }
        })
    }

    private fun handleSendSuccess(resolvedUrl: String) {
        progressBar.visibility = View.GONE
        val shouldOpenMaps = pendingOpenMapsAfterSend ?: prefsManager.isOpenMapsAfterSendEnabled()
        pendingOpenMapsAfterSend = null

        if (shouldOpenMaps) {
            updateStatus(getString(R.string.relay_sent_opening), R.color.aurora_success)
            openGoogleMaps(resolvedUrl) { success ->
                if (success) {
                    applyAfterSendBehavior()
                } else {
                    showError(getString(R.string.relay_maps_not_found), retryable = false)
                }
            }
        } else {
            updateStatus(getString(R.string.relay_sent), R.color.aurora_success)
            applyAfterSendBehavior()
        }
    }

    private fun openMapsDirectAndFinish(rawUrl: String) {
        if (hasOpenedMaps) return
        updateStatus(getString(R.string.relay_opening_maps), R.color.aurora_mint)

        openGoogleMaps(rawUrl) { success ->
            if (success) {
                closeAndRemoveFromRecents()
            } else {
                hasOpenedMaps = false
                Toast.makeText(this, getString(R.string.relay_maps_not_found), Toast.LENGTH_SHORT).show()
                showError(getString(R.string.relay_maps_not_found), retryable = false)
            }
        }
    }

    private fun openGoogleMaps(url: String, onComplete: (Boolean) -> Unit) {
        if (hasOpenedMaps) {
            onComplete(false)
            return
        }
        hasOpenedMaps = true

        val uri = Uri.parse(url)
        val mapsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }

        try {
            if (mapsIntent.resolveActivity(packageManager) != null) {
                startActivity(mapsIntent)
                onComplete(true)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Google Maps intent resolve failed", e)
        }

        val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
        val hasExternalHandler = try {
            val handlers = packageManager.queryIntentActivities(
                fallbackIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            handlers.any { it.activityInfo.packageName != packageName }
        } catch (e: Exception) {
            false
        }

        if (hasExternalHandler) {
            try {
                startActivity(fallbackIntent)
                onComplete(true)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "⚠️ Fallback maps intent failed", e)
            }
        }

        hasOpenedMaps = false
        onComplete(false)
    }

    private fun closeAndRemoveFromRecents() {
        try {
            if (!isTaskRoot) {
                finishAffinity()
            }
            finishAndRemoveTask()
        } catch (e: Exception) {
            finish()
        }
        overridePendingTransition(0, 0)
    }

    private fun applyAfterSendBehavior() {
        when (prefsManager.getAfterSendBehavior()) {
            PrefsManager.AFTER_SEND_STAY_IN_APP -> {
                // stay in app
            }
            else -> {
                closeAndRemoveFromRecents()
            }
        }
    }

    private fun extractRawInput(intent: Intent): String? {
        val action = intent.action
        val type = intent.type
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val extraSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        return when (action) {
            Intent.ACTION_VIEW -> intent.data?.toString()
            Intent.ACTION_SEND -> {
                val isTextType = type == null || type.startsWith("text/") || type == "text/uri-list"
                if (isTextType) {
                    extraText
                        ?: readClipData(intent.clipData)
                        ?: intent.data?.toString()
                        ?: extraSubject
                } else null
            }
            Intent.ACTION_PROCESS_TEXT -> processText
            else -> {
                Log.w(TAG, "❌ Unsupported action: $action")
                extraText
                    ?: intent.getStringExtra(EXTRA_TEXT)
                    ?: readClipData(intent.clipData)
                    ?: intent.data?.toString()
                    ?: extraSubject
            }
        }
    }

    private fun readClipData(clipData: ClipData?): String? {
        if (clipData == null || clipData.itemCount == 0) return null
        val item = clipData.getItemAt(0)
        return item.text?.toString() ?: item.uri?.toString()
    }

    private fun resetUiForNewRequest() {
        sendingText.text = getString(R.string.relay_sending)
        statusText.text = getString(R.string.relay_preparing)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.aurora_mint))
        progressBar.visibility = View.VISIBLE
        actionButton.visibility = View.GONE
        hasOpenedMaps = false
    }

    private fun updateStatus(message: String, colorRes: Int) {
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun showError(message: String, retryable: Boolean) {
        progressBar.visibility = View.GONE
        updateStatus(message, R.color.aurora_error)

        if (retryable && !lastRaw.isNullOrEmpty()) {
            actionButton.text = getString(R.string.common_retry)
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener {
                val raw = lastRaw ?: return@setOnClickListener
                requestId += 1
                resetUiForNewRequest()
                startSendFlow(raw, requestId, lastIntent ?: intent, lastForceSend)
            }
        } else {
            actionButton.text = getString(R.string.common_close)
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener { finish() }
        }
    }

    private fun isDisplayModeActive(): Boolean {
        val deviceMode = prefsManager.getDeviceMode()
        if (deviceMode == PrefsManager.MODE_DISPLAY) return true

        return try {
            val activityManager = getSystemService(android.app.ActivityManager::class.java)
            val services = activityManager.getRunningServices(Integer.MAX_VALUE)
            services.any { it.service.className == DisplayServerService::class.java.name }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not check DisplayServerService state", e)
            false
        }
    }

    private fun isMapViewIntent(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        val data = intent.data ?: return false
        val scheme = data.scheme?.lowercase() ?: return false
        return when (scheme) {
            "geo", "google.navigation" -> true
            "https" -> {
                val host = data.host?.lowercase() ?: return false
                host == "www.google.com" && (data.path?.startsWith("/maps") == true) ||
                    host == "maps.google.com" ||
                    host == "maps.app.goo.gl"
            }
            else -> false
        }
    }

    private fun classifyMapIntent(url: String, originalIntent: Intent): MapIntentType {
        val originalScheme = originalIntent.data?.scheme?.lowercase()
        if (originalScheme == "google.navigation") {
            return MapIntentType.NAVIGATION
        }

        val lower = url.lowercase()
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            null
        }

        val query = uri?.query ?: ""

        val isNav = lower.contains("/maps/dir") ||
            lower.contains("dir/") ||
            query.contains("destination=") ||
            query.contains("travelmode=") ||
            query.contains("dirflg=") ||
            query.contains("dir_action=navigate") ||
            query.contains("navigation=1") ||
            lower.contains("google.navigation")

        if (isNav) return MapIntentType.NAVIGATION

        val isView = lower.contains("/maps/place") ||
            lower.contains("/maps/search") ||
            query.contains("q=") ||
            lower.contains("maps?q=")

        return if (isView) MapIntentType.VIEW_MAP else MapIntentType.UNKNOWN
    }

    private fun showActionSheet(
        resolvedUrl: String,
        classification: MapIntentType,
        currentRequestId: Int
    ) {
        progressBar.visibility = View.GONE
        updateStatus(getString(R.string.relay_waiting_choice), R.color.aurora_mint)

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_map_action, null)
        dialog.setContentView(view)

        val typeText = view.findViewById<TextView>(R.id.actionSheetType)
        val typeLabel = when (classification) {
            MapIntentType.NAVIGATION -> getString(R.string.relay_type_navigation)
            MapIntentType.VIEW_MAP -> getString(R.string.relay_type_view_map)
            else -> getString(R.string.relay_type_unknown)
        }
        typeText.text = getString(R.string.relay_sheet_type_fmt, typeLabel)

        view.findViewById<Button>(R.id.btnSendToDisplay).setOnClickListener {
            if (currentRequestId != requestId) return@setOnClickListener
            PhantomLog.i("NAV action=SEND (sheet)")
            pendingOpenMapsAfterSend = false
            dialog.dismiss()
            updateStatus(getString(R.string.relay_sending), R.color.aurora_mint)
            sendToDisplay(resolvedUrl, currentRequestId)
        }

        view.findViewById<Button>(R.id.btnSendAndOpenMaps).setOnClickListener {
            if (currentRequestId != requestId) return@setOnClickListener
            PhantomLog.i("NAV action=SEND_OPEN (sheet)")
            pendingOpenMapsAfterSend = true
            dialog.dismiss()
            updateStatus(getString(R.string.relay_sending), R.color.aurora_mint)
            sendToDisplay(resolvedUrl, currentRequestId)
        }

        view.findViewById<Button>(R.id.btnOpenOnPhone).setOnClickListener {
            if (currentRequestId != requestId) return@setOnClickListener
            PhantomLog.i("NAV action=OPEN_PHONE (sheet)")
            dialog.dismiss()
            openGoogleMaps(resolvedUrl) { success ->
                if (success) {
                    applyAfterSendBehavior()
                } else {
                    showError(getString(R.string.relay_maps_not_found), retryable = false)
                }
            }
        }

        view.findViewById<Button>(R.id.btnCopyLink).setOnClickListener {
            if (currentRequestId != requestId) return@setOnClickListener
            PhantomLog.i("NAV action=COPY_LINK (sheet)")
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("PHANTOM_GO_LINK", resolvedUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.relay_link_copied), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            applyAfterSendBehavior()
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            PhantomLog.i("NAV action=CANCEL (sheet)")
            dialog.dismiss()
            applyAfterSendBehavior()
        }

        dialog.show()
    }
}
