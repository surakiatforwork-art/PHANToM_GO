package com.phantom.carnavrelay

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

class HttpServer(private val context: Context, private val prefsManager: PrefsManager) : NanoHTTPD(prefsManager.getServerPort()) {
    
    companion object {
        private const val TAG = "PHANTOM_GO"
        const val DEFAULT_PORT = 8765
        const val ACTION_PAIRING_CHANGED = "com.phantom.carnavrelay.PAIRING_CHANGED"
    }

    private data class LocalIpCandidate(
        val address: String,
        val interfaceName: String,
        val isPrivate: Boolean,
        val isActiveNetwork: Boolean
    )
    
    private var isRunning = false
    
    override fun start() {
        try {
            super.start()
            isRunning = true
            Log.d(TAG, "✅ HTTP Server started on port $listeningPort")
        } catch (e: IOException) {
            Log.e(TAG, "💥 Failed to start HTTP Server", e)
            throw e
        }
    }
    
    override fun stop() {
        super.stop()
        isRunning = false
        Log.d(TAG, "🛑 HTTP Server stopped")
    }
    
    fun isServerRunning(): Boolean {
        return isRunning
    }
    
    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri
        
        Log.d(TAG, "📡 HTTP ${method.name} $uri from ${session.remoteIpAddress}")
        
        return when {
            uri == "/status" && method == Method.GET -> handleStatus()
            uri == "/pair" && method == Method.POST -> handlePair(session)
            uri == "/open-url" && method == Method.POST -> handleOpenUrl(session)
            uri == "/refresh-token" && method == Method.POST -> handleRefreshToken(session)
            uri == "/unpair" && method == Method.POST -> handleUnpair(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }
    
    private fun handleStatus(): Response {
        val ip = getLocalIpAddress() ?: "0.0.0.0"
        val port = listeningPort
        val token = prefsManager.getServerToken()
        val tokenHint = prefsManager.getTokenHint(token)
        val paired = prefsManager.isDisplayPaired()
        val pairedMainId = prefsManager.getDisplayPairedMainId()
        prefsManager.setLastMainPingAt(System.currentTimeMillis())
        
        Log.d(TAG, "📊 /status request - paired=$paired, tokenHint=$tokenHint")
        
        val response = JsonUtils.createStatusResponse(ip, port, paired, tokenHint, "1.0", pairedMainId)
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }
    
    private fun handlePair(session: IHTTPSession): Response {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        
        val postData = body["postData"] ?: ""
        Log.d(TAG, "📨 /pair request received: $postData")
        
        val request = JsonUtils.parsePairRequest(postData)
        
        if (request == null) {
            Log.w(TAG, "❌ /pair invalid request format")
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                JsonUtils.createPairResponse(false, "INVALID_FORMAT")
            )
        }
        
        val serverToken = prefsManager.getServerToken()
        
        // Verify token
        if (request.token != serverToken) {
            Log.w(TAG, "❌ /pair UNAUTHORIZED - token mismatch")
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                JsonUtils.createPairResponse(false, "UNAUTHORIZED")
            )
        }
        
        // Token valid - set paired state
        Log.d(TAG, "✅ /pair success - token valid, mainId=${request.mainId}, mainName=${request.mainName}")
        prefsManager.setDisplayPaired(true, request.mainId, request.mainName)
        
        // Broadcast pairing state change
        broadcastPairingChanged(true)
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            JsonUtils.createPairResponse(true)
        )
    }
    
    private fun handleOpenUrl(session: IHTTPSession): Response {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        
        val postData = body["postData"] ?: ""
        Log.d(TAG, "📨 /open-url request: $postData")
        
        val request = JsonUtils.parseOpenUrlRequest(postData)
        
        if (request == null) {
            Log.w(TAG, "❌ /open-url invalid request format")
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                JsonUtils.createOpenUrlResponse(false, reason = "INVALID_FORMAT")
            )
        }
        
        val (token, url) = request
        val serverToken = prefsManager.getServerToken()
        
        // Check if paired first
        if (!prefsManager.isDisplayPaired()) {
            Log.w(TAG, "❌ /open-url NOT_PAIRED - device not paired")
            return newFixedLengthResponse(
                Response.Status.CONFLICT,
                "application/json",
                JsonUtils.createOpenUrlResponse(false, reason = "NOT_PAIRED")
            )
        }
        
        // Verify token
        if (token != serverToken) {
            Log.w(TAG, "❌ /open-url UNAUTHORIZED - token mismatch")
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                JsonUtils.createOpenUrlResponse(false, reason = "UNAUTHORIZED")
            )
        }
        
        // Token valid and paired - hand off to DisplayServerService
        Log.d(TAG, "✅ /open-url authorized, received URL: $url")
        
        // Notify DisplayServerService for background handling
        try {
            val serviceIntent = Intent(context, DisplayServerService::class.java).apply {
                action = "com.phantom.carnavrelay.NAVIGATION_RECEIVED"
                putExtra("url", url)
            }
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to notify DisplayServerService", e)
        }
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            JsonUtils.createOpenUrlResponse(true, "URL received")
        )
    }
    
    private fun handleUnpair(session: IHTTPSession): Response {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        
        val postData = body["postData"] ?: ""
        Log.d(TAG, "📨 /unpair request: $postData")
        
        // Parse to verify token if provided
        val json = try {
            org.json.JSONObject(postData)
        } catch (e: Exception) {
            null
        }
        
        val token = json?.optString("token", "")
        val serverToken = prefsManager.getServerToken()
        
        // If token provided, verify it
        if (!token.isNullOrEmpty() && token != serverToken) {
            Log.w(TAG, "❌ /unpair UNAUTHORIZED - token mismatch")
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                JsonUtils.createErrorResponse("Invalid token", "UNAUTHORIZED")
            )
        }
        
        // Reset paired state
        Log.d(TAG, "✅ /unpair - resetting paired state")
        prefsManager.setDisplayPaired(false)
        
        // Broadcast pairing state change
        broadcastPairingChanged(false)
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            JsonUtils.createOpenUrlResponse(true, "Unpaired successfully")
        )
    }
    
    private fun handleRefreshToken(session: IHTTPSession): Response {
        val remoteIp = session.remoteIpAddress
        if (!isLocalNetworkAddress(remoteIp)) {
            Log.w(TAG, "❌ Refresh token attempted from external IP: $remoteIp")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "application/json",
                JsonUtils.createErrorResponse("Token refresh only allowed from local network")
            )
        }
        
        // Reset paired state when token refreshed
        prefsManager.setDisplayPaired(false)
        broadcastPairingChanged(false)
        
        val newToken = prefsManager.refreshServerToken()
        Log.d(TAG, "🔄 Token refreshed, new hint: ${prefsManager.getTokenHint(newToken)}")
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            JsonUtils.createRefreshTokenResponse(newToken)
        )
    }
    
    private fun broadcastPairingChanged(paired: Boolean) {
        try {
            val intent = Intent(ACTION_PAIRING_CHANGED).apply {
                putExtra("paired", paired)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "📢 Broadcasted PAIRING_CHANGED: paired=$paired")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to broadcast pairing change", e)
        }
    }
    
    fun getLocalIpAddress(): String? {
        try {
            val activeNetworkIps = resolveActiveNetworkIpv4s()
            val candidates = mutableListOf<LocalIpCandidate>()
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                try {
                    if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                } catch (_: Exception) {
                }
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    val inet4 = addr as? Inet4Address ?: continue
                    val hostAddress = inet4.hostAddress ?: continue
                    if (inet4.isLoopbackAddress || inet4.isLinkLocalAddress) continue
                    candidates += LocalIpCandidate(
                        address = hostAddress,
                        interfaceName = intf.name ?: "",
                        isPrivate = inet4.isSiteLocalAddress,
                        isActiveNetwork = activeNetworkIps.contains(hostAddress)
                    )
                }
            }
            val best = candidates.maxByOrNull { scoreLocalIpCandidate(it) }
            if (best != null) {
                Log.d(
                    TAG,
                    "🌐 Selected local IP ${best.address} on ${best.interfaceName} " +
                        "(private=${best.isPrivate}, active=${best.isActiveNetwork})"
                )
                return best.address
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error getting IP address", e)
        }
        return null
    }
    
    fun getPairingPayload(): String {
        val ip = getLocalIpAddress() ?: "0.0.0.0"
        val port = listeningPort
        val token = prefsManager.getServerToken()
        return JsonUtils.createPairingPayload(ip, port, token)
    }

    private fun resolveActiveNetworkIpv4s(): Set<String> {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
            val network = cm.activeNetwork ?: return emptySet()
            val linkProperties = cm.getLinkProperties(network) ?: return emptySet()
            linkProperties.linkAddresses.mapNotNull { linkAddress ->
                val inet = linkAddress.address as? Inet4Address ?: return@mapNotNull null
                if (inet.isLoopbackAddress || inet.isLinkLocalAddress) return@mapNotNull null
                inet.hostAddress
            }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not resolve active network IPs: ${e.message}")
            emptySet()
        }
    }

    private fun scoreLocalIpCandidate(candidate: LocalIpCandidate): Int {
        var score = 0
        if (candidate.isPrivate) score += 100
        if (candidate.isActiveNetwork) score += 60

        val interfaceName = candidate.interfaceName.lowercase()
        if (isLikelyWifiInterface(interfaceName)) score += 30
        if (interfaceName.contains("tun") || interfaceName.contains("rmnet") || interfaceName.contains("ccmni")) {
            score -= 40
        }

        if (candidate.address.startsWith("192.168.")) score += 8
        if (candidate.address.startsWith("172.20.")) score += 8
        if (candidate.address.startsWith("192.168.43.") || candidate.address.startsWith("192.168.137.")) score += 12

        return score
    }

    private fun isLikelyWifiInterface(interfaceName: String): Boolean {
        return interfaceName.contains("wlan") ||
            interfaceName.contains("wifi") ||
            interfaceName.contains("softap") ||
            interfaceName.contains("ap")
    }

    private fun isLocalNetworkAddress(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        if (ip == "127.0.0.1" || ip == "::1" || ip.equals("localhost", ignoreCase = true)) return true
        return try {
            val address = InetAddress.getByName(ip)
            address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress
        } catch (_: Exception) {
            false
        }
    }
}
