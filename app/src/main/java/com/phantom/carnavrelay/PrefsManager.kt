package com.phantom.carnavrelay

import android.content.Context
import android.content.SharedPreferences
import com.phantom.carnavrelay.util.generateTokenHex
import java.security.MessageDigest
import java.security.SecureRandom

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
        private const val MAX_PENDING_ROUTES = 25

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

        // Stable local device identity for Firebase-backed pairing
        private const val KEY_LOCAL_DEVICE_ID = "local_device_id"

        // Firebase cloud pairing
        private const val KEY_CLOUD_PAIR_CODE = "cloud_pair_code"
        private const val KEY_PAIRED_DISPLAY_DEVICE_ID = "paired_display_device_id"
        private const val KEY_PAIRED_DISPLAY_NAME = "paired_display_name"
        private const val KEY_PAIRED_VIA_CLOUD = "paired_via_cloud"

        // One-time onboarding / permission setup
        private const val KEY_INITIAL_PERMISSION_SETUP_SHOWN = "initial_permission_setup_shown"

        // Speedometer / location
        private const val KEY_SPEEDOMETER_ENABLED = "speedometer_enabled"
        private const val KEY_SPEED_LAST_KMH = "speed_last_kmh"
        private const val KEY_SPEED_LAST_UPDATED_AT = "speed_last_updated_at"
        private const val KEY_SPEED_LAST_ACCURACY_METERS = "speed_last_accuracy_meters"
        private const val KEY_SPEED_LAST_PROVIDER = "speed_last_provider"
        private const val KEY_SPEED_LAST_HAS_FIX = "speed_last_has_fix"

        // Display power automation
        private const val KEY_DISPLAY_POWER_AUTOMATION_ENABLED = "display_power_automation_enabled"
        private const val KEY_DISPLAY_WAKE_BEFORE_MAPS_ENABLED = "display_wake_before_maps_enabled"
        private const val KEY_DISPLAY_LOCK_ON_POWER_DISCONNECT = "display_lock_on_power_disconnect"
        private const val KEY_DISPLAY_POWER_STATE_KNOWN = "display_power_state_known"
        private const val KEY_DISPLAY_POWER_PLUGGED = "display_power_plugged"
        private const val KEY_DISPLAY_POWER_STATE_UPDATED_AT = "display_power_state_updated_at"
        private const val KEY_DISPLAY_POWER_DISCONNECT_LOCK_PENDING = "display_power_disconnect_lock_pending"

        // Display anti-theft
        private const val KEY_ANTI_THEFT_ENABLED = "anti_theft_enabled"
        private const val KEY_ANTI_THEFT_ARMED = "anti_theft_armed"
        private const val KEY_ANTI_THEFT_PIN_HASH = "anti_theft_pin_hash"
        private const val KEY_ANTI_THEFT_PIN_SALT = "anti_theft_pin_salt"
        private const val KEY_ANTI_THEFT_PIN_ALGO_VERSION = "anti_theft_pin_algo_version"
        private const val KEY_ANTI_THEFT_ATTACH_LOCATION = "anti_theft_attach_location"
        private const val KEY_ANTI_THEFT_ALARM_SOUND_URI = "anti_theft_alarm_sound_uri"
        private const val KEY_ANTI_THEFT_LAST_STATE = "anti_theft_last_state"
        private const val KEY_ANTI_THEFT_LAST_HEADSET_PLUGGED = "anti_theft_last_headset_plugged"
        private const val KEY_ANTI_THEFT_COUNTDOWN_DEADLINE_AT = "anti_theft_countdown_deadline_at"
        private const val KEY_ANTI_THEFT_SESSION_ID = "anti_theft_session_id"
        private const val KEY_ANTI_THEFT_WRONG_ATTEMPTS = "anti_theft_wrong_attempts"
        private const val KEY_ANTI_THEFT_ALARM_STARTED_AT = "anti_theft_alarm_started_at"
        private const val PIN_ALGO_V1 = "SHA-256:PIN-V1"
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

    fun hasLocalPairingTransport(): Boolean {
        return !getPairedIp().isNullOrBlank() && !getPairedToken().isNullOrBlank()
    }

    fun hasCloudPairingTarget(): Boolean {
        return prefs.getBoolean(KEY_PAIRED_VIA_CLOUD, false) &&
            !getPairedDisplayDeviceId().isNullOrBlank()
    }
    
    fun isPaired(): Boolean {
        if (!prefs.getBoolean(KEY_IS_PAIRED, false)) return false
        return hasLocalPairingTransport() || hasCloudPairingTarget()
    }
    
    fun isPairedVerified(): Boolean {
        return isPaired() && prefs.getBoolean(KEY_PAIRED_VERIFIED, false)
    }
    
    fun setPairedVerified(verified: Boolean) {
        prefs.edit().putBoolean(KEY_PAIRED_VERIFIED, verified).apply()
    }
    
    fun savePairing(
        ip: String,
        port: Int,
        token: String,
        verified: Boolean = false,
        displayDeviceId: String? = null,
        displayName: String? = null,
        viaCloud: Boolean = false
    ) {
        prefs.edit().apply {
            putString(KEY_PAIRED_IP, ip)
            putInt(KEY_PAIRED_PORT, port)
            putString(KEY_PAIRED_TOKEN, token)
            putBoolean(KEY_IS_PAIRED, true)
            putBoolean(KEY_PAIRED_VERIFIED, verified)
            putString(KEY_PAIRED_DISPLAY_DEVICE_ID, displayDeviceId)
            putString(KEY_PAIRED_DISPLAY_NAME, displayName)
            putBoolean(KEY_PAIRED_VIA_CLOUD, viaCloud)
            apply()
        }
    }

    fun saveCloudPairing(
        displayDeviceId: String,
        displayName: String? = null
    ) {
        prefs.edit().apply {
            remove(KEY_PAIRED_IP)
            remove(KEY_PAIRED_PORT)
            remove(KEY_PAIRED_TOKEN)
            putBoolean(KEY_IS_PAIRED, true)
            putBoolean(KEY_PAIRED_VERIFIED, false)
            putString(KEY_PAIRED_DISPLAY_DEVICE_ID, displayDeviceId)
            putString(KEY_PAIRED_DISPLAY_NAME, displayName)
            putBoolean(KEY_PAIRED_VIA_CLOUD, true)
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
            remove(KEY_PAIRED_DISPLAY_DEVICE_ID)
            remove(KEY_PAIRED_DISPLAY_NAME)
            putBoolean(KEY_PAIRED_VIA_CLOUD, false)
            apply()
        }
    }

    fun getPairedDisplayDeviceId(): String? {
        return prefs.getString(KEY_PAIRED_DISPLAY_DEVICE_ID, null)
    }

    fun getPairedDisplayName(): String? {
        return prefs.getString(KEY_PAIRED_DISPLAY_NAME, null)
    }

    fun isCloudPaired(): Boolean {
        return prefs.getBoolean(KEY_IS_PAIRED, false) && hasCloudPairingTarget()
    }
    
    // Pending queue for failed sends
    fun getPendingQueue(): List<String> {
        val json = prefs.getString(KEY_PENDING_QUEUE, "[]") ?: "[]"
        return JsonUtils.parsePendingQueueJson(json)
    }
    
    @Synchronized
    fun addToPendingQueue(url: String) {
        val queue = getPendingQueue().toMutableList()
        queue.remove(url)
        queue.add(url)
        while (queue.size > MAX_PENDING_ROUTES) {
            queue.removeAt(0)
        }
        savePendingQueue(queue)
    }
    
    @Synchronized
    fun removeFromPendingQueue(url: String) {
        val queue = getPendingQueue().toMutableList()
        queue.removeAll { it == url }
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
        val stored = prefs.getString(KEY_CONNECTION_TRANSPORT, TRANSPORT_WIFI) ?: TRANSPORT_WIFI
        // Wi‑Fi only mode: migrate legacy AUTO/BLUETOOTH selections to WIFI.
        if (stored != TRANSPORT_WIFI) {
            prefs.edit().putString(KEY_CONNECTION_TRANSPORT, TRANSPORT_WIFI).apply()
            return TRANSPORT_WIFI
        }
        return stored
    }

    fun setConnectionTransport(transport: String) {
        // Bluetooth transport is disabled; persist WIFI regardless of caller input.
        prefs.edit().putString(KEY_CONNECTION_TRANSPORT, TRANSPORT_WIFI).apply()
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

    fun getLocalDeviceId(): String {
        val existing = prefs.getString(KEY_LOCAL_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = "device_${generateTokenHex(8)}"
        prefs.edit().putString(KEY_LOCAL_DEVICE_ID, newId).apply()
        return newId
    }

    fun getCloudPairCode(): String {
        val existing = prefs.getString(KEY_CLOUD_PAIR_CODE, null)
        if (!existing.isNullOrBlank()) return existing
        val newCode = generateTokenHex(5)
        prefs.edit().putString(KEY_CLOUD_PAIR_CODE, newCode).apply()
        return newCode
    }

    fun peekCloudPairCode(): String? {
        return prefs.getString(KEY_CLOUD_PAIR_CODE, null)
    }

    fun refreshCloudPairCode(): String {
        val newCode = generateTokenHex(5)
        prefs.edit().putString(KEY_CLOUD_PAIR_CODE, newCode).apply()
        return newCode
    }

    fun clearCloudPairCode() {
        prefs.edit().remove(KEY_CLOUD_PAIR_CODE).apply()
    }

    fun getResolvedUrlFor(shortUrl: String): String? {
        val json = prefs.getString(KEY_URL_RESOLVE_CACHE, "{}") ?: "{}"
        return try {
            val obj = org.json.JSONObject(json)
            if (obj.has(shortUrl)) {
                obj.optString(shortUrl).takeIf { it.isNotBlank() }
            } else {
                null
            }
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

    fun hasShownInitialPermissionSetup(): Boolean {
        return prefs.getBoolean(KEY_INITIAL_PERMISSION_SETUP_SHOWN, false)
    }

    fun markInitialPermissionSetupShown() {
        prefs.edit().putBoolean(KEY_INITIAL_PERMISSION_SETUP_SHOWN, true).apply()
    }

    fun isSpeedometerEnabled(): Boolean {
        return prefs.getBoolean(KEY_SPEEDOMETER_ENABLED, false)
    }

    fun setSpeedometerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEEDOMETER_ENABLED, enabled).apply()
    }

    fun saveSpeedSnapshot(
        kmh: Int?,
        hasFix: Boolean,
        updatedAt: Long,
        accuracyMeters: Float? = null,
        provider: String? = null
    ) {
        prefs.edit().apply {
            if (kmh == null) {
                remove(KEY_SPEED_LAST_KMH)
            } else {
                putInt(KEY_SPEED_LAST_KMH, kmh)
            }
            putBoolean(KEY_SPEED_LAST_HAS_FIX, hasFix)
            putLong(KEY_SPEED_LAST_UPDATED_AT, updatedAt)
            if (accuracyMeters == null) {
                remove(KEY_SPEED_LAST_ACCURACY_METERS)
            } else {
                putFloat(KEY_SPEED_LAST_ACCURACY_METERS, accuracyMeters)
            }
            if (provider.isNullOrBlank()) {
                remove(KEY_SPEED_LAST_PROVIDER)
            } else {
                putString(KEY_SPEED_LAST_PROVIDER, provider)
            }
            apply()
        }
    }

    fun clearSpeedSnapshot() {
        prefs.edit().apply {
            remove(KEY_SPEED_LAST_KMH)
            remove(KEY_SPEED_LAST_UPDATED_AT)
            remove(KEY_SPEED_LAST_ACCURACY_METERS)
            remove(KEY_SPEED_LAST_PROVIDER)
            putBoolean(KEY_SPEED_LAST_HAS_FIX, false)
            apply()
        }
    }

    fun getLastSpeedKmh(): Int? {
        return if (!prefs.contains(KEY_SPEED_LAST_KMH)) null else prefs.getInt(KEY_SPEED_LAST_KMH, 0)
    }

    fun getLastSpeedUpdatedAt(): Long {
        return prefs.getLong(KEY_SPEED_LAST_UPDATED_AT, 0L)
    }

    fun getLastSpeedAccuracyMeters(): Float? {
        return if (!prefs.contains(KEY_SPEED_LAST_ACCURACY_METERS)) null
        else prefs.getFloat(KEY_SPEED_LAST_ACCURACY_METERS, 0f)
    }

    fun getLastSpeedProvider(): String? {
        return prefs.getString(KEY_SPEED_LAST_PROVIDER, null)
    }

    fun hasSpeedFix(): Boolean {
        return prefs.getBoolean(KEY_SPEED_LAST_HAS_FIX, false)
    }

    fun isDisplayPowerAutomationEnabled(): Boolean {
        return prefs.getBoolean(KEY_DISPLAY_POWER_AUTOMATION_ENABLED, false)
    }

    fun setDisplayPowerAutomationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DISPLAY_POWER_AUTOMATION_ENABLED, enabled).apply()
    }

    fun isDisplayWakeBeforeMapsEnabled(): Boolean {
        return prefs.getBoolean(KEY_DISPLAY_WAKE_BEFORE_MAPS_ENABLED, true)
    }

    fun setDisplayWakeBeforeMapsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DISPLAY_WAKE_BEFORE_MAPS_ENABLED, enabled).apply()
    }

    fun isDisplayLockOnPowerDisconnectEnabled(): Boolean {
        return prefs.getBoolean(KEY_DISPLAY_LOCK_ON_POWER_DISCONNECT, false)
    }

    fun setDisplayLockOnPowerDisconnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DISPLAY_LOCK_ON_POWER_DISCONNECT, enabled).apply()
    }

    fun hasDisplayPowerState(): Boolean {
        return prefs.getBoolean(KEY_DISPLAY_POWER_STATE_KNOWN, false)
    }

    fun isDisplayPowerPlugged(): Boolean {
        return prefs.getBoolean(KEY_DISPLAY_POWER_PLUGGED, false)
    }

    fun setDisplayPowerState(plugged: Boolean) {
        prefs.edit()
            .putBoolean(KEY_DISPLAY_POWER_STATE_KNOWN, true)
            .putBoolean(KEY_DISPLAY_POWER_PLUGGED, plugged)
            .putLong(KEY_DISPLAY_POWER_STATE_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun setDisplayDisconnectLockPending(pending: Boolean) {
        prefs.edit().putBoolean(KEY_DISPLAY_POWER_DISCONNECT_LOCK_PENDING, pending).apply()
    }

    fun isDisplayDisconnectLockPending(): Boolean {
        return prefs.getBoolean(KEY_DISPLAY_POWER_DISCONNECT_LOCK_PENDING, false)
    }

    fun isAntiTheftEnabled(): Boolean {
        return prefs.getBoolean(KEY_ANTI_THEFT_ENABLED, false)
    }

    fun setAntiTheftEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ANTI_THEFT_ENABLED, enabled).apply()
    }

    fun isAntiTheftArmed(): Boolean {
        return prefs.getBoolean(KEY_ANTI_THEFT_ARMED, false)
    }

    fun setAntiTheftArmed(armed: Boolean) {
        prefs.edit().putBoolean(KEY_ANTI_THEFT_ARMED, armed).apply()
    }

    fun hasAntiTheftPin(): Boolean {
        return !prefs.getString(KEY_ANTI_THEFT_PIN_HASH, null).isNullOrBlank() &&
            !prefs.getString(KEY_ANTI_THEFT_PIN_SALT, null).isNullOrBlank()
    }

    fun setAntiTheftPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { b -> "%02x".format(b) }
        val hash = hashPin(pin, saltHex)
        prefs.edit().apply {
            putString(KEY_ANTI_THEFT_PIN_HASH, hash)
            putString(KEY_ANTI_THEFT_PIN_SALT, saltHex)
            putString(KEY_ANTI_THEFT_PIN_ALGO_VERSION, PIN_ALGO_V1)
            apply()
        }
    }

    fun verifyAntiTheftPin(pin: String): Boolean {
        val saltHex = prefs.getString(KEY_ANTI_THEFT_PIN_SALT, null) ?: return false
        val storedHash = prefs.getString(KEY_ANTI_THEFT_PIN_HASH, null) ?: return false
        return hashPin(pin, saltHex) == storedHash
    }

    fun isAntiTheftAttachLocationEnabled(): Boolean {
        return prefs.getBoolean(KEY_ANTI_THEFT_ATTACH_LOCATION, true)
    }

    fun setAntiTheftAttachLocationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ANTI_THEFT_ATTACH_LOCATION, enabled).apply()
    }

    fun getAntiTheftAlarmSoundUri(): String? {
        return prefs.getString(KEY_ANTI_THEFT_ALARM_SOUND_URI, null)
    }

    fun setAntiTheftAlarmSoundUri(uri: String?) {
        prefs.edit().apply {
            if (uri.isNullOrBlank()) {
                remove(KEY_ANTI_THEFT_ALARM_SOUND_URI)
            } else {
                putString(KEY_ANTI_THEFT_ALARM_SOUND_URI, uri)
            }
            apply()
        }
    }

    fun getAntiTheftLastState(): String? {
        return prefs.getString(KEY_ANTI_THEFT_LAST_STATE, null)
    }

    fun setAntiTheftLastState(state: String?) {
        prefs.edit().apply {
            if (state.isNullOrBlank()) {
                remove(KEY_ANTI_THEFT_LAST_STATE)
            } else {
                putString(KEY_ANTI_THEFT_LAST_STATE, state)
            }
            apply()
        }
    }

    fun wasAntiTheftHeadsetPlugged(): Boolean {
        return prefs.getBoolean(KEY_ANTI_THEFT_LAST_HEADSET_PLUGGED, false)
    }

    fun setAntiTheftHeadsetPlugged(plugged: Boolean) {
        prefs.edit().putBoolean(KEY_ANTI_THEFT_LAST_HEADSET_PLUGGED, plugged).apply()
    }

    fun getAntiTheftCountdownDeadlineAt(): Long {
        return prefs.getLong(KEY_ANTI_THEFT_COUNTDOWN_DEADLINE_AT, 0L)
    }

    fun setAntiTheftCountdownDeadlineAt(deadlineAt: Long) {
        prefs.edit().putLong(KEY_ANTI_THEFT_COUNTDOWN_DEADLINE_AT, deadlineAt).apply()
    }

    fun getAntiTheftSessionId(): String? {
        return prefs.getString(KEY_ANTI_THEFT_SESSION_ID, null)
    }

    fun setAntiTheftSessionId(sessionId: String?) {
        prefs.edit().apply {
            if (sessionId.isNullOrBlank()) {
                remove(KEY_ANTI_THEFT_SESSION_ID)
            } else {
                putString(KEY_ANTI_THEFT_SESSION_ID, sessionId)
            }
            apply()
        }
    }

    fun getAntiTheftWrongAttempts(): Int {
        return prefs.getInt(KEY_ANTI_THEFT_WRONG_ATTEMPTS, 0)
    }

    fun setAntiTheftWrongAttempts(count: Int) {
        prefs.edit().putInt(KEY_ANTI_THEFT_WRONG_ATTEMPTS, count.coerceAtLeast(0)).apply()
    }

    fun getAntiTheftAlarmStartedAt(): Long {
        return prefs.getLong(KEY_ANTI_THEFT_ALARM_STARTED_AT, 0L)
    }

    fun setAntiTheftAlarmStartedAt(timestampMs: Long) {
        prefs.edit().putLong(KEY_ANTI_THEFT_ALARM_STARTED_AT, timestampMs).apply()
    }

    fun clearAntiTheftSession() {
        prefs.edit().apply {
            putBoolean(KEY_ANTI_THEFT_ARMED, false)
            remove(KEY_ANTI_THEFT_LAST_STATE)
            remove(KEY_ANTI_THEFT_SESSION_ID)
            putInt(KEY_ANTI_THEFT_WRONG_ATTEMPTS, 0)
            putLong(KEY_ANTI_THEFT_ALARM_STARTED_AT, 0L)
            apply()
        }
    }

    private fun hashPin(pin: String, saltHex: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$PIN_ALGO_V1|$saltHex|$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
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
