package com.phantom.carnavrelay

import android.content.Context
import android.content.SharedPreferences
import com.phantom.carnavrelay.util.generateTokenHex

class PrefsManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun getPrefs(): SharedPreferences = prefs
    
    companion object {
        private const val PREFS_NAME = "phantom_go_prefs"
        
        // Display device settings
        private const val KEY_SERVER_TOKEN = "server_token"
        private const val KEY_SERVER_PORT = "server_port"
        
        // Display device: paired state
        private const val KEY_DISPLAY_PAIRED = "display_paired"
        private const val KEY_DISPLAY_PAIRED_MAIN_ID = "display_paired_main_id"
        private const val KEY_DISPLAY_PAIRED_MAIN_NAME = "display_paired_main_name"
        private const val KEY_DISPLAY_PAIRED_AT = "display_paired_at"
        
        // Main device paired config
        private const val KEY_PAIRED_IP = "paired_ip"
        private const val KEY_PAIRED_PORT = "paired_port"
        private const val KEY_PAIRED_TOKEN = "paired_token"
        private const val KEY_IS_PAIRED = "is_paired"
        private const val KEY_PAIRED_VERIFIED = "paired_verified"  // Server-verified status
        
        // Pending queue
        private const val KEY_PENDING_QUEUE = "pending_queue"

        // Device mode
        private const val KEY_DEVICE_MODE = "device_mode"
        const val MODE_MAIN = "MAIN"
        const val MODE_DISPLAY = "DISPLAY"

        // Main -> Display transport selection
        private const val KEY_CONNECTION_TRANSPORT = "connection_transport"
        const val TRANSPORT_AUTO = "AUTO"
        const val TRANSPORT_WIFI = "WIFI"
        const val TRANSPORT_BLUETOOTH = "BLUETOOTH"

        // Main-side stored Bluetooth target (system-paired device)
        private const val KEY_BT_TARGET_ADDRESS = "bt_target_address"
        private const val KEY_BT_TARGET_NAME = "bt_target_name"

        // Display-side observed Bluetooth connection state
        private const val KEY_BT_CONNECTED = "bt_connected"
        private const val KEY_LAST_BT_CLIENT_AT = "last_bt_client_at"

        // Map link hub toggle
        private const val KEY_MAP_LINK_HUB_ENABLED = "map_link_hub_enabled"

        // Smart mode toggle + policy
        private const val KEY_SMART_MODE_ENABLED = "smart_mode_enabled"
        private const val KEY_SMART_MODE_POLICY = "smart_mode_policy"

        const val SMART_POLICY_AUTO_SEND_NAV_ONLY = "AUTO_SEND_NAV_ONLY"
        const val SMART_POLICY_ALWAYS_ASK = "ALWAYS_ASK"
        const val SMART_POLICY_ALWAYS_SEND = "ALWAYS_SEND"
        const val SMART_POLICY_ALWAYS_OPEN_ON_PHONE = "ALWAYS_OPEN_ON_PHONE"

        // After send behavior
        private const val KEY_AFTER_SEND_BEHAVIOR = "after_send_behavior"
        const val AFTER_SEND_EXIT_AND_REMOVE_TASK = "EXIT_AND_REMOVE_TASK"
        const val AFTER_SEND_STAY_IN_APP = "STAY_IN_APP"

        // Open Maps after send (optional)
        private const val KEY_OPEN_MAPS_AFTER_SEND = "open_maps_after_send"

        // Short link resolve cache
        private const val KEY_URL_RESOLVE_CACHE = "url_resolve_cache"

        // Display navigation mode (driving / motorcycle)
        private const val KEY_DISPLAY_NAV_MODE = "display_nav_mode"
        const val DISPLAY_NAV_MODE_DRIVING = "DRIVING"
        const val DISPLAY_NAV_MODE_MOTORCYCLE = "MOTORCYCLE"

        // Display open behavior (preview route / start navigation)
        private const val KEY_DISPLAY_OPEN_BEHAVIOR = "display_open_behavior"
        const val DISPLAY_OPEN_BEHAVIOR_PREVIEW_ROUTE = "PREVIEW_ROUTE"
        const val DISPLAY_OPEN_BEHAVIOR_START_NAVIGATION = "START_NAVIGATION"

        // Display: last time main device pinged /status
        private const val KEY_LAST_MAIN_PING_AT = "last_main_ping_at"

        // Main-side cache for auto-reconnect (recent working IPs)
        private const val KEY_KNOWN_DISPLAY_IPS = "known_display_ips"
    }
    
    // Display device: Server token
    fun getServerToken(): String {
        val existing = prefs.getString(KEY_SERVER_TOKEN, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val newToken = generateNewToken()
        setServerToken(newToken)
        return newToken
    }
    
    fun setServerToken(token: String) {
        prefs.edit().putString(KEY_SERVER_TOKEN, token).apply()
    }
    
    fun refreshServerToken(): String {
        val newToken = generateNewToken()
        setServerToken(newToken)
        // Reset paired state when token refreshed
        setDisplayPaired(false)
        return newToken
    }
    
    // Display device: Server port (default 8765)
    fun getServerPort(): Int {
        return prefs.getInt(KEY_SERVER_PORT, 8765)
    }
    
    fun setServerPort(port: Int) {
        prefs.edit().putInt(KEY_SERVER_PORT, port).apply()
    }
    
    // Display device: Paired state
    fun isDisplayPaired(): Boolean {
        return prefs.getBoolean(KEY_DISPLAY_PAIRED, false)
    }
    
    fun setDisplayPaired(paired: Boolean, mainId: String? = null, mainName: String? = null) {
        prefs.edit().apply {
            putBoolean(KEY_DISPLAY_PAIRED, paired)
            if (paired) {
                putString(KEY_DISPLAY_PAIRED_MAIN_ID, mainId)
                putString(KEY_DISPLAY_PAIRED_MAIN_NAME, mainName)
                putLong(KEY_DISPLAY_PAIRED_AT, System.currentTimeMillis())
            } else {
                remove(KEY_DISPLAY_PAIRED_MAIN_ID)
                remove(KEY_DISPLAY_PAIRED_MAIN_NAME)
                remove(KEY_DISPLAY_PAIRED_AT)
            }
            apply()
        }
    }
    
    fun getDisplayPairedMainId(): String? {
        return prefs.getString(KEY_DISPLAY_PAIRED_MAIN_ID, null)
    }
    
    fun getDisplayPairedMainName(): String? {
        return prefs.getString(KEY_DISPLAY_PAIRED_MAIN_NAME, null)
    }
    
    fun getDisplayPairedAt(): Long {
        return prefs.getLong(KEY_DISPLAY_PAIRED_AT, 0)
    }
    
    fun clearDisplayPairing() {
        setDisplayPaired(false)
    }
    
    // Main device: Pairing config
    fun getPairedIp(): String? {
        return prefs.getString(KEY_PAIRED_IP, null)
    }
    
    fun getPairedPort(): Int {
        return prefs.getInt(KEY_PAIRED_PORT, 8765)
    }
    
    fun getPairedToken(): String? {
        return prefs.getString(KEY_PAIRED_TOKEN, null)
    }
    
    fun isPaired(): Boolean {
        return prefs.getBoolean(KEY_IS_PAIRED, false) && 
               !getPairedIp().isNullOrEmpty() && 
               !getPairedToken().isNullOrEmpty()
    }
    
    fun isPairedVerified(): Boolean {
        return isPaired() && prefs.getBoolean(KEY_PAIRED_VERIFIED, false)
    }
    
    fun setPairedVerified(verified: Boolean) {
        prefs.edit().putBoolean(KEY_PAIRED_VERIFIED, verified).apply()
    }
    
    fun savePairing(ip: String, port: Int, token: String, verified: Boolean = false) {
        prefs.edit().apply {
            putString(KEY_PAIRED_IP, ip)
            putInt(KEY_PAIRED_PORT, port)
            putString(KEY_PAIRED_TOKEN, token)
            putBoolean(KEY_IS_PAIRED, true)
            putBoolean(KEY_PAIRED_VERIFIED, verified)
            apply()
        }
    }
    
    fun clearPairing() {
        prefs.edit().apply {
            remove(KEY_PAIRED_IP)
            remove(KEY_PAIRED_PORT)
            remove(KEY_PAIRED_TOKEN)
            putBoolean(KEY_IS_PAIRED, false)
            putBoolean(KEY_PAIRED_VERIFIED, false)
            apply()
        }
    }
    
    // Pending queue for failed sends
    fun getPendingQueue(): List<String> {
        val json = prefs.getString(KEY_PENDING_QUEUE, "[]") ?: "[]"
        return JsonUtils.parsePendingQueueJson(json)
    }
    
    fun addToPendingQueue(url: String) {
        val queue = getPendingQueue().toMutableList()
        queue.add(url)
        savePendingQueue(queue)
    }
    
    fun removeFromPendingQueue(url: String) {
        val queue = getPendingQueue().toMutableList()
        queue.remove(url)
        savePendingQueue(queue)
    }
    
    fun savePendingQueue(queue: List<String>) {
        val json = JsonUtils.createPendingQueueJson(queue)
        prefs.edit().putString(KEY_PENDING_QUEUE, json).apply()
    }
    
    fun clearPendingQueue() {
        prefs.edit().remove(KEY_PENDING_QUEUE).apply()
    }
    
    fun getPendingCount(): Int {
        return getPendingQueue().size
    }

    fun getDeviceMode(): String {
        return prefs.getString(KEY_DEVICE_MODE, MODE_MAIN) ?: MODE_MAIN
    }

    fun setDeviceMode(mode: String) {
        prefs.edit().putString(KEY_DEVICE_MODE, mode).apply()
    }

    fun getConnectionTransport(): String {
        return prefs.getString(KEY_CONNECTION_TRANSPORT, TRANSPORT_AUTO) ?: TRANSPORT_AUTO
    }

    fun setConnectionTransport(transport: String) {
        prefs.edit().putString(KEY_CONNECTION_TRANSPORT, transport).apply()
    }

    fun getBluetoothTargetAddress(): String? {
        return prefs.getString(KEY_BT_TARGET_ADDRESS, null)
    }

    fun getBluetoothTargetName(): String? {
        return prefs.getString(KEY_BT_TARGET_NAME, null)
    }

    fun hasBluetoothTarget(): Boolean {
        return !getBluetoothTargetAddress().isNullOrBlank()
    }

    fun saveBluetoothTarget(address: String, name: String?) {
        prefs.edit().apply {
            putString(KEY_BT_TARGET_ADDRESS, address)
            putString(KEY_BT_TARGET_NAME, name)
            apply()
        }
    }

    fun clearBluetoothTarget() {
        prefs.edit().apply {
            remove(KEY_BT_TARGET_ADDRESS)
            remove(KEY_BT_TARGET_NAME)
            apply()
        }
    }

    fun isBtClientConnected(): Boolean {
        return prefs.getBoolean(KEY_BT_CONNECTED, false)
    }

    fun setBtClientConnected(connected: Boolean) {
        prefs.edit().putBoolean(KEY_BT_CONNECTED, connected).apply()
        if (connected) {
            setLastBtClientAt(System.currentTimeMillis())
        }
    }

    fun getLastBtClientAt(): Long {
        return prefs.getLong(KEY_LAST_BT_CLIENT_AT, 0L)
    }

    fun setLastBtClientAt(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_BT_CLIENT_AT, timestampMs).apply()
    }

    fun isMapLinkHubEnabled(): Boolean {
        return prefs.getBoolean(KEY_MAP_LINK_HUB_ENABLED, false)
    }

    fun setMapLinkHubEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MAP_LINK_HUB_ENABLED, enabled).apply()
    }

    fun isSmartModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_SMART_MODE_ENABLED, false)
    }

    fun setSmartModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_MODE_ENABLED, enabled).apply()
    }

    fun getSmartModePolicy(): String {
        return prefs.getString(KEY_SMART_MODE_POLICY, SMART_POLICY_AUTO_SEND_NAV_ONLY)
            ?: SMART_POLICY_AUTO_SEND_NAV_ONLY
    }

    fun setSmartModePolicy(policy: String) {
        prefs.edit().putString(KEY_SMART_MODE_POLICY, policy).apply()
    }

    fun getAfterSendBehavior(): String {
        return prefs.getString(KEY_AFTER_SEND_BEHAVIOR, AFTER_SEND_EXIT_AND_REMOVE_TASK)
            ?: AFTER_SEND_EXIT_AND_REMOVE_TASK
    }

    fun setAfterSendBehavior(behavior: String) {
        prefs.edit().putString(KEY_AFTER_SEND_BEHAVIOR, behavior).apply()
    }

    fun isOpenMapsAfterSendEnabled(): Boolean {
        return prefs.getBoolean(KEY_OPEN_MAPS_AFTER_SEND, false)
    }

    fun setOpenMapsAfterSendEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OPEN_MAPS_AFTER_SEND, enabled).apply()
    }

    fun getDisplayNavMode(): String {
        return prefs.getString(KEY_DISPLAY_NAV_MODE, DISPLAY_NAV_MODE_DRIVING)
            ?: DISPLAY_NAV_MODE_DRIVING
    }

    fun setDisplayNavMode(mode: String) {
        prefs.edit().putString(KEY_DISPLAY_NAV_MODE, mode).apply()
    }

    fun getDisplayOpenBehavior(): String {
        return prefs.getString(KEY_DISPLAY_OPEN_BEHAVIOR, DISPLAY_OPEN_BEHAVIOR_PREVIEW_ROUTE)
            ?: DISPLAY_OPEN_BEHAVIOR_PREVIEW_ROUTE
    }

    fun setDisplayOpenBehavior(behavior: String) {
        prefs.edit().putString(KEY_DISPLAY_OPEN_BEHAVIOR, behavior).apply()
    }

    fun getLastMainPingAt(): Long {
        return prefs.getLong(KEY_LAST_MAIN_PING_AT, 0L)
    }

    fun setLastMainPingAt(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_MAIN_PING_AT, timestampMs).apply()
    }

    fun getKnownDisplayIps(): List<String> {
        val csv = prefs.getString(KEY_KNOWN_DISPLAY_IPS, "") ?: ""
        return csv.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun rememberDisplayIp(ip: String) {
        val clean = ip.trim()
        if (clean.isEmpty()) return
        val updated = (listOf(clean) + getKnownDisplayIps())
            .distinct()
            .take(8)
        prefs.edit().putString(KEY_KNOWN_DISPLAY_IPS, updated.joinToString(",")).apply()
    }

    fun clearKnownDisplayIps() {
        prefs.edit().remove(KEY_KNOWN_DISPLAY_IPS).apply()
    }

    fun getResolvedUrlFor(shortUrl: String): String? {
        val json = prefs.getString(KEY_URL_RESOLVE_CACHE, "{}") ?: "{}"
        return try {
            val obj = org.json.JSONObject(json)
            obj.optString(shortUrl, null)
        } catch (e: Exception) {
            null
        }
    }

    fun putResolvedUrl(shortUrl: String, resolvedUrl: String) {
        val json = prefs.getString(KEY_URL_RESOLVE_CACHE, "{}") ?: "{}"
        val obj = try {
            org.json.JSONObject(json)
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        obj.put(shortUrl, resolvedUrl)
        prefs.edit().putString(KEY_URL_RESOLVE_CACHE, obj.toString()).apply()
    }
    
    private fun generateNewToken(): String {
        return generateTokenHex(12) // 24 hex chars, URL-safe
    }
    
    // Get token hint (first 4 + last 4 chars)
    fun getTokenHint(token: String): String {
        if (token.length <= 8) return token
        return "${token.take(4)}...${token.takeLast(4)}"
    }
}
