package com.phantom.carnavrelay

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object NavLinkUtils {
    private const val TAG = "PHANTOM_GO"
    private const val RESOLVE_MAX_ATTEMPTS = 3
    private const val RESOLVE_RETRY_DELAY_MS = 200L
    // Google Maps URLs API uses `two-wheeler` (hyphen), not `two_wheeler`.
    private const val TRAVEL_MODE_DRIVING = "driving"
    private const val TRAVEL_MODE_MOTORCYCLE = "two-wheeler"

    private val resolverClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    fun normalizeRawInput(raw: String): String? {
        val uri = try {
            Uri.parse(raw)
        } catch (e: Exception) {
            null
        }

        return when {
            uri?.scheme != null -> normalizeUrl(uri)
            raw.matches(Regex("^-?\\d+\\.?\\d*,-?\\d+\\.?\\d*$")) -> {
                "https://www.google.com/maps/search/?api=1&query=${Uri.encode(raw)}"
            }
            else -> {
                "https://www.google.com/maps/search/?api=1&query=${Uri.encode(raw)}"
            }
        }
    }

    fun normalizeUrl(uri: Uri): String? {
        return when (uri.scheme) {
            "geo" -> {
                val geoData = uri.schemeSpecificPart
                val query = uri.query

                if (query != null && query.startsWith("q=")) {
                    val searchQuery = query.substring(2).replace("+", " ")
                    "https://www.google.com/maps/search/?api=1&query=${Uri.encode(searchQuery)}"
                } else if (geoData.contains(",")) {
                    val parts = geoData.split(",")
                    if (parts.size >= 2) {
                        val lat = parts[0]
                        val lng = parts[1].substringBefore(";")
                        "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
                    } else null
                } else null
            }

            "google.navigation" -> {
                val query = uri.getQueryParameter("q")
                if (query != null) {
                    "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(query)}"
                } else null
            }

            "https" -> {
                when {
                    uri.host?.contains("google.com") == true && uri.path?.contains("/maps") == true -> {
                        uri.toString()
                    }
                    uri.host?.contains("maps.app.goo.gl") == true -> {
                        uri.toString()
                    }
                    else -> uri.toString()
                }
            }

            else -> uri.toString()
        }
    }

    fun isShortMapsLink(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            when {
                host == "maps.app.goo.gl" -> true
                host == "goo.gl" && uri.path?.contains("/app/maps") == true -> true
                host == "goo.gl" && uri.path?.contains("/maps") == true -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun resolveShortLinkIfNeeded(prefsManager: PrefsManager, url: String): String {
        if (!isShortMapsLink(url)) {
            return url
        }

        val cached = prefsManager.getResolvedUrlFor(url)
        if (!cached.isNullOrEmpty()) {
            Log.d(TAG, "🔁 Resolved URL from cache: $cached")
            PhantomLog.i("NAV resolvedCache hit: $url -> $cached")
            return cached
        }

        var attempt = 1
        var resolvedUrl = url
        while (attempt <= RESOLVE_MAX_ATTEMPTS) {
            resolvedUrl = resolveOnce(prefsManager, url)
            if (resolvedUrl != url) {
                return resolvedUrl
            }
            if (attempt < RESOLVE_MAX_ATTEMPTS) {
                Log.w(TAG, "⚠️ Resolve attempt $attempt failed, retrying…")
                try {
                    Thread.sleep(RESOLVE_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    // ignore
                }
            }
            attempt += 1
        }

        Log.w(TAG, "⚠️ Resolve failed after $RESOLVE_MAX_ATTEMPTS attempts, using original")
        PhantomLog.w("NAV resolve failed after retries, fallback: $url")
        return resolvedUrl
    }

    private fun resolveOnce(prefsManager: PrefsManager, url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            resolverClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val hasRedirect = response.priorResponse != null
                val location = response.header("Location")

                if (hasRedirect || !location.isNullOrEmpty()) {
                    Log.d(TAG, "✅ HEAD resolved: $finalUrl")
                    prefsManager.putResolvedUrl(url, finalUrl)
                    finalUrl
                } else if (response.code == 405) {
                    resolveWithGet(prefsManager, url)
                } else {
                    resolveWithGet(prefsManager, url)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ HEAD resolve failed: ${e.message}")
            PhantomLog.w("NAV resolve HEAD failed, fallback to original: $url")
            resolveWithGet(prefsManager, url)
        }
    }

    private fun resolveWithGet(prefsManager: PrefsManager, url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            resolverClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (finalUrl != url) {
                    Log.d(TAG, "✅ GET resolved: $finalUrl")
                    prefsManager.putResolvedUrl(url, finalUrl)
                    finalUrl
                } else {
                    Log.w(TAG, "⚠️ GET resolve returned same URL, using original")
                    PhantomLog.w("NAV resolve GET same URL, fallback: $url")
                    url
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ GET resolve failed: ${e.message}")
            PhantomLog.w("NAV resolve GET failed, fallback to original: $url")
            url
        }
    }

    fun toLocationOnlyUrl(url: String): String {
        val destination = extractDestination(url) ?: return url
        return "https://www.google.com/maps/search/?api=1&query=${Uri.encode(destination)}"
    }

    fun toDirectionsPreviewUrl(url: String): String {
        return toDirectionsPreviewUrl(url, null)
    }

    fun toDirectionsPreviewUrl(url: String, navMode: String?): String {
        val destination = extractDestination(url) ?: return url
        val travelMode = when (navMode) {
            PrefsManager.DISPLAY_NAV_MODE_MOTORCYCLE -> TRAVEL_MODE_MOTORCYCLE
            else -> TRAVEL_MODE_DRIVING
        }
        return "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=$travelMode"
    }

    fun toDisplayOpenUrl(url: String, navMode: String?, openBehavior: String?): String {
        if (openBehavior == PrefsManager.DISPLAY_OPEN_BEHAVIOR_START_NAVIGATION) {
            val destination = extractDestination(url)
            if (!destination.isNullOrBlank()) {
                if (navMode == PrefsManager.DISPLAY_NAV_MODE_MOTORCYCLE) {
                    // google.navigation doesn't support motorcycle; using "b" maps to bicycle.
                    // Use directions URL with travelmode=two-wheeler and dir_action=navigate instead.
                    val previewUrl = toDirectionsPreviewUrl(url, navMode)
                    return appendQueryParam(previewUrl, "dir_action", "navigate")
                }

                val builder = Uri.Builder()
                    .scheme("google.navigation")
                    .appendQueryParameter("q", destination)
                if (navMode == PrefsManager.DISPLAY_NAV_MODE_DRIVING) {
                    builder.appendQueryParameter("mode", "d")
                }
                return builder.build().toString()
            }
        }

        val baseUrl = toDirectionsPreviewUrl(url, navMode)
        return if (openBehavior == PrefsManager.DISPLAY_OPEN_BEHAVIOR_START_NAVIGATION) {
            appendQueryParam(baseUrl, "dir_action", "navigate")
        } else {
            baseUrl
        }
    }

    private fun extractDestination(url: String): String? {
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            null
        } ?: return null

        val scheme = uri.scheme?.lowercase() ?: return null
        return when (scheme) {
            "google.navigation" -> uri.getQueryParameter("q")
            "geo" -> {
                val query = uri.getQueryParameter("q")
                if (!query.isNullOrBlank()) {
                    query
                } else {
                    val ssp = uri.schemeSpecificPart ?: return null
                    val latLng = ssp.substringBefore("?").trim()
                    if (latLng.contains(",")) latLng else null
                }
            }
            "http", "https" -> extractFromHttp(uri)
            else -> null
        }
    }

    private fun extractFromHttp(uri: Uri): String? {
        val host = uri.host?.lowercase() ?: return null
        val queryDestination = uri.getQueryParameter("destination")
            ?: uri.getQueryParameter("daddr")
            ?: uri.getQueryParameter("q")
            ?: uri.getQueryParameter("query")

        if (!queryDestination.isNullOrBlank()) {
            return queryDestination
        }

        if (host.contains("google.com") || host.contains("maps.google.com")) {
            val segments = uri.pathSegments ?: emptyList()
            val dirIndex = segments.indexOf("dir")
            if (dirIndex >= 0 && dirIndex + 1 < segments.size) {
                val candidate = segments
                    .subList(dirIndex + 1, segments.size)
                    .asReversed()
                    .firstOrNull { segment ->
                        segment.isNotBlank() &&
                            !segment.startsWith("@") &&
                            !segment.startsWith("data=") &&
                            !segment.contains("!")
                    }
                val decoded = candidate?.let { Uri.decode(it) }
                if (!decoded.isNullOrBlank()) {
                    return decoded
                }
            }
            val placeIndex = segments.indexOf("place")
            if (placeIndex >= 0 && placeIndex + 1 < segments.size) {
                val place = segments[placeIndex + 1]
                val decoded = Uri.decode(place)
                if (decoded.isNotBlank()) {
                    return decoded
                }
            }
        }

        return null
    }

    private fun appendQueryParam(url: String, name: String, value: String): String {
        return try {
            val uri = Uri.parse(url)
            if (uri.getQueryParameter(name) != null) {
                url
            } else {
                uri.buildUpon()
                    .appendQueryParameter(name, value)
                    .build()
                    .toString()
            }
        } catch (e: Exception) {
            url
        }
    }
}
