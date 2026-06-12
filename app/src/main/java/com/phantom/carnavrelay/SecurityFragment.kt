package com.phantom.carnavrelay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SecurityFragment : Fragment(R.layout.fragment_security) {

    private lateinit var prefsManager: PrefsManager
    private lateinit var statusCard: View
    private lateinit var headline: TextView
    private lateinit var stateBadge: TextView
    private lateinit var detail: TextView
    private lateinit var locationSummary: TextView
    private lateinit var locationPlaceholder: TextView
    private lateinit var mapView: WebView
    private lateinit var primaryAction: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var openExternalMapButton: MaterialButton

    private var receiverRegistered = false
    private var latestLocation: FirebaseManager.DisplayLocationInfo? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isAdded) return
            updateSecurityState()
            if (intent?.action == FirebaseManager.ACTION_SECURITY_ALERT_RECEIVED) {
                refreshDisplayLocation()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefsManager = PrefsManager(requireContext())
        statusCard = view.findViewById(R.id.securityStatusCard)
        headline = view.findViewById(R.id.securityHeadline)
        stateBadge = view.findViewById(R.id.securityStateBadge)
        detail = view.findViewById(R.id.securityDetail)
        locationSummary = view.findViewById(R.id.displayLocationSummary)
        locationPlaceholder = view.findViewById(R.id.displayLocationPlaceholder)
        mapView = view.findViewById(R.id.displayLocationMap)
        primaryAction = view.findViewById(R.id.btnSecurityPrimaryAction)
        refreshButton = view.findViewById(R.id.btnSecurityRefresh)
        openExternalMapButton = view.findViewById(R.id.btnSecurityOpenExternalMap)

        configureMap()
        primaryAction.setOnClickListener {
            (activity as? MainActivity)?.selectTab(BottomNavHelper.Tab.SETTINGS)
        }
        refreshButton.setOnClickListener { refreshDisplayLocation() }
        openExternalMapButton.setOnClickListener { openLatestLocationExternally() }

        updateSecurityState()
        refreshDisplayLocation()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(DisplayAntiTheftManager.ACTION_STATE_CHANGED)
                addAction(FirebaseManager.ACTION_SECURITY_ALERT_RECEIVED)
            }
            ContextCompat.registerReceiver(
                requireContext(),
                stateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { requireContext().unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroyView() {
        mapView.stopLoading()
        mapView.destroy()
        super.onDestroyView()
    }

    private fun updateSecurityState() {
        val isDisplay = prefsManager.getDeviceMode() == PrefsManager.MODE_DISPLAY
        if (isDisplay) {
            val snapshot = DisplayAntiTheftManager.snapshot(requireContext())
            val alarmActive = snapshot.state == AntiTheftState.ALARM_ACTIVE ||
                snapshot.state == AntiTheftState.FULLSCREEN_LOCK
            headline.text = snapshot.headline
            detail.text = snapshot.detail
            stateBadge.text = when {
                alarmActive -> "ALARM"
                snapshot.armed -> "ARMED"
                snapshot.featureEnabled -> "READY"
                else -> "OFF"
            }
            val color = when {
                alarmActive -> R.color.ui_danger
                snapshot.armed || snapshot.featureEnabled -> R.color.ui_success
                else -> R.color.ui_warning
            }
            stateBadge.setTextColor(requireContext().getColor(color))
            stateBadge.setBackgroundResource(
                when {
                    alarmActive -> R.drawable.badge_error
                    snapshot.armed || snapshot.featureEnabled -> R.drawable.badge_success
                    else -> R.drawable.badge_warning
                }
            )
            statusCard.setBackgroundResource(
                if (alarmActive) R.drawable.security_alert_card else R.drawable.security_safe_card
            )
            primaryAction.text = "Manage Display security"
        } else {
            val paired = prefsManager.getPairedDisplayDeviceId() != null
            headline.text = if (paired) "Display security link ready" else "No Display paired"
            detail.text = if (paired) {
                "Security alerts and the Display's latest location are available on this page."
            } else {
                "Pair a Display device from Home before using remote security monitoring."
            }
            stateBadge.text = if (paired) "MONITORING" else "OFFLINE"
            stateBadge.setTextColor(
                requireContext().getColor(if (paired) R.color.ui_success else R.color.ui_warning)
            )
            stateBadge.setBackgroundResource(
                if (paired) R.drawable.badge_success else R.drawable.badge_warning
            )
            statusCard.setBackgroundResource(
                if (paired) R.drawable.security_safe_card else R.drawable.soft_card_background
            )
            primaryAction.text = "Open security settings"
        }
    }

    private fun refreshDisplayLocation() {
        if (prefsManager.getPairedDisplayDeviceId().isNullOrBlank()) {
            latestLocation = null
            showNoLocation("Pair a Display device to see its last known location.")
            return
        }

        refreshButton.isEnabled = false
        locationSummary.text = "Loading the Display's latest location..."
        FirebaseManager.getPairedDisplayLocation(requireContext()) { info, error ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                refreshButton.isEnabled = true
                latestLocation = info
                if (info == null) {
                    showNoLocation(error ?: "No location record is available yet.")
                } else {
                    showLocation(info)
                }
            }
        }
    }

    private fun showNoLocation(message: String) {
        locationSummary.text = message
        mapView.visibility = View.GONE
        locationPlaceholder.visibility = View.VISIBLE
        locationPlaceholder.text = "No location available yet"
        openExternalMapButton.visibility = View.GONE
    }

    private fun showLocation(info: FirebaseManager.DisplayLocationInfo) {
        val lat = info.lat
        val lng = info.lng
        val timestamp = info.locationUpdatedAt ?: info.lastOnlineAt ?: info.presenceUpdatedAt
        locationSummary.text = buildString {
            append(if (info.connected) "Online now" else "Offline - showing latest recorded location")
            append("\n")
            append(info.displayName ?: info.displayDeviceId)
            if (lat != null && lng != null) {
                append("\n")
                append(String.format(Locale.US, "%.6f, %.6f", lat, lng))
            }
            info.speedKmh?.let { append("  •  $it km/h") }
            timestamp?.let {
                append("\nUpdated ")
                append(DateFormat.getDateTimeInstance().format(Date(it)))
            }
        }

        if (lat == null || lng == null) {
            mapView.visibility = View.GONE
            locationPlaceholder.visibility = View.VISIBLE
            locationPlaceholder.text = "The Display is connected, but has not reported coordinates yet."
            openExternalMapButton.visibility = View.GONE
            return
        }

        locationPlaceholder.visibility = View.GONE
        mapView.visibility = View.VISIBLE
        openExternalMapButton.visibility = View.VISIBLE
        mapView.loadDataWithBaseURL(
            "https://www.openstreetmap.org/",
            mapHtml(lat, lng, info.displayName ?: "Display"),
            "text/html",
            "UTF-8",
            null
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureMap() {
        mapView.webViewClient = WebViewClient()
        mapView.settings.javaScriptEnabled = true
        mapView.settings.domStorageEnabled = false
        mapView.settings.allowFileAccess = false
        mapView.settings.allowContentAccess = false
        mapView.settings.setSupportZoom(true)
        mapView.settings.builtInZoomControls = true
        mapView.settings.displayZoomControls = false
    }

    private fun mapHtml(lat: Double, lng: Double, label: String): String {
        val safeLabel = label
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
              <style>html,body,#map{height:100%;margin:0;background:#e6e8ea}.leaflet-control-attribution{font-size:9px}</style>
            </head>
            <body>
              <div id="map"></div>
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
              <script>
                const map=L.map('map',{zoomControl:true}).setView([$lat,$lng],16);
                L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
                L.marker([$lat,$lng]).addTo(map).bindPopup('$safeLabel').openPopup();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun openLatestLocationExternally() {
        val info = latestLocation ?: return
        val lat = info.lat ?: return
        val lng = info.lng ?: return
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
