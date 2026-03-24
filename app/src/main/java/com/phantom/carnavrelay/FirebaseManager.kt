package com.phantom.carnavrelay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.phantom.carnavrelay.util.PairInfo
import java.util.Collections

object FirebaseManager {

    private const val TAG = "PHANTOM_GO"
    private const val DATABASE_URL = "https://phantom-go-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val PATH_DEVICES = "devices"
    private const val PATH_PRESENCE = "presence"
    private const val PATH_PAIRING_CODES = "pairingCodes"
    private const val PATH_PAIRINGS_BY_DISPLAY = "pairings/byDisplay"
    private const val PATH_PAIRINGS_BY_MAIN = "pairings/byMain"
    private const val PATH_COMMANDS = "commands"

    @Volatile
    private var initialized = false

    @Volatile
    private var presenceListenerAttached = false

    @Volatile
    private var displayPairingListenerDeviceId: String? = null

    @Volatile
    private var displayCommandListenerDeviceId: String? = null

    @Volatile
    private var displayCommandListener: ChildEventListener? = null

    private val processedCommandIds = Collections.synchronizedSet(mutableSetOf<String>())

    data class ClaimPairingResult(
        val displayDeviceId: String,
        val displayName: String?,
        val localIp: String?,
        val localPort: Int?,
        val localToken: String?
    )

    fun initialize(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
            try {
                database().setPersistenceEnabled(true)
                Log.d(TAG, "🔥 Firebase Realtime Database offline persistence enabled")
            } catch (e: Exception) {
                Log.d(TAG, "ℹ️ Firebase persistence already configured: ${e.message}")
            }
            initialized = true
        }
        ensureSignedInAndSync(context)
    }

    fun ensureSignedInAndSync(context: Context, reason: String = "manual") {
        val appContext = context.applicationContext
        withSignedInUser(
            context = appContext,
            reason = reason,
            onReady = {
                syncDeviceRecord(appContext, reason)
                attachPresenceListener(appContext)
                attachDisplayPairingListener(appContext)
            },
            onFailure = { e ->
                PhantomLog.w("Firebase auth failed ($reason): ${e.message}")
            }
        )
    }

    fun syncDeviceRecord(context: Context, reason: String = "manual") {
        val appContext = context.applicationContext
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (authUid.isNullOrBlank()) {
            ensureSignedInAndSync(appContext, "sync-deferred:$reason")
            return
        }

        val prefs = PrefsManager(appContext)
        val deviceId = prefs.getLocalDeviceId()
        val role = prefs.getDeviceMode().lowercase()
        val deviceRef = database()
            .getReference(PATH_DEVICES)
            .child(deviceId)

        val payload = hashMapOf<String, Any?>(
            "deviceId" to deviceId,
            "authUid" to authUid,
            "role" to role,
            "platform" to "android",
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "appVersion" to getAppVersion(appContext),
            "updatedAt" to ServerValue.TIMESTAMP,
            "lastSeenAt" to ServerValue.TIMESTAMP
        )

        deviceRef.updateChildren(payload)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Firebase device sync complete ($deviceId/$role) [$reason]")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "💥 Firebase device sync failed [$reason]", e)
                PhantomLog.w("Firebase device sync failed ($reason): ${e.message}")
            }
    }

    fun publishPairingCode(
        context: Context,
        localIp: String,
        localPort: Int,
        localToken: String,
        reason: String = "display-ui"
    ) {
        val appContext = context.applicationContext
        withSignedInUser(
            context = appContext,
            reason = "publish-pairing-code:$reason",
            onReady = { authUid ->
                val prefs = PrefsManager(appContext)
                val deviceId = prefs.getLocalDeviceId()
                val pairCode = prefs.getCloudPairCode()
                val payload = hashMapOf<String, Any?>(
                    "pairCode" to pairCode,
                    "displayDeviceId" to deviceId,
                    "displayAuthUid" to authUid,
                    "displayName" to Build.MODEL,
                    "localIp" to localIp,
                    "localPort" to localPort,
                    "localToken" to localToken,
                    "platform" to "android",
                    "updatedAt" to ServerValue.TIMESTAMP
                )

                database()
                    .getReference(PATH_PAIRING_CODES)
                    .child(pairCode)
                    .setValue(payload)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ Firebase pairing code published ($pairCode/$deviceId) [$reason]")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "💥 Firebase pairing code publish failed [$reason]", e)
                        PhantomLog.w("Firebase pairing code publish failed ($reason): ${e.message}")
                    }
            }
        )
    }

    fun claimPairingCode(
        context: Context,
        pairInfo: PairInfo,
        onSuccess: (ClaimPairingResult) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val appContext = context.applicationContext
        val displayDeviceId = pairInfo.displayDeviceId
        val pairCode = pairInfo.pairCode
        if (displayDeviceId.isNullOrBlank() || pairCode.isNullOrBlank()) {
            onFailure("Invalid cloud pairing payload")
            return
        }

        withSignedInUser(
            context = appContext,
            reason = "claim-pairing-code",
            onReady = { authUid ->
                val prefs = PrefsManager(appContext)
                val mainDeviceId = prefs.getLocalDeviceId()
                val codeRef = database()
                    .getReference(PATH_PAIRING_CODES)
                    .child(pairCode)

                codeRef.get()
                    .addOnSuccessListener { codeSnapshot ->
                        if (!codeSnapshot.exists()) {
                            onFailure("Pairing code not found")
                            return@addOnSuccessListener
                        }

                        val codeDisplayId = codeSnapshot.child("displayDeviceId").getValue(String::class.java)
                        if (codeDisplayId != displayDeviceId) {
                            onFailure("QR code does not match display device")
                            return@addOnSuccessListener
                        }

                        val displayName = codeSnapshot.child("displayName").getValue(String::class.java)
                        val displayAuthUid = codeSnapshot.child("displayAuthUid").getValue(String::class.java)
                        val localIp = codeSnapshot.child("localIp").getValue(String::class.java)
                        val localPort = codeSnapshot.child("localPort").getValue(Long::class.java)?.toInt()
                        val localToken = codeSnapshot.child("localToken").getValue(String::class.java)
                        if (displayAuthUid.isNullOrBlank()) {
                            onFailure("Display auth session is missing")
                            return@addOnSuccessListener
                        }
                        val displayPairRef = database()
                            .getReference(PATH_PAIRINGS_BY_DISPLAY)
                            .child(displayDeviceId)
                        val mainPairRef = database()
                            .getReference(PATH_PAIRINGS_BY_MAIN)
                            .child(mainDeviceId)

                        displayPairRef.get()
                            .addOnSuccessListener { displayPairSnapshot ->
                                mainPairRef.get()
                                    .addOnSuccessListener { mainPairSnapshot ->
                                        val previousMainId = displayPairSnapshot.child("mainDeviceId").getValue(String::class.java)
                                        val previousDisplayIdForMain = mainPairSnapshot.child("displayDeviceId").getValue(String::class.java)
                                        val pairPayload = hashMapOf<String, Any?>(
                                            "displayDeviceId" to displayDeviceId,
                                            "displayAuthUid" to displayAuthUid,
                                            "displayName" to displayName,
                                            "mainDeviceId" to mainDeviceId,
                                            "mainAuthUid" to authUid,
                                            "mainName" to Build.MODEL,
                                            "pairedAt" to ServerValue.TIMESTAMP,
                                            "updatedAt" to ServerValue.TIMESTAMP,
                                            "transportHint" to if (!localIp.isNullOrBlank() && !localToken.isNullOrBlank()) "wifi_local" else "cloud_pending"
                                        )

                                        val updates = hashMapOf<String, Any?>(
                                            "$PATH_PAIRINGS_BY_DISPLAY/$displayDeviceId" to pairPayload,
                                            "$PATH_PAIRINGS_BY_MAIN/$mainDeviceId" to pairPayload,
                                            "$PATH_PAIRING_CODES/$pairCode/lastClaimedBy" to mainDeviceId,
                                            "$PATH_PAIRING_CODES/$pairCode/lastClaimedAt" to ServerValue.TIMESTAMP
                                        )
                                        if (!previousMainId.isNullOrBlank() && previousMainId != mainDeviceId) {
                                            updates["$PATH_PAIRINGS_BY_MAIN/$previousMainId"] = null
                                        }
                                        if (!previousDisplayIdForMain.isNullOrBlank() && previousDisplayIdForMain != displayDeviceId) {
                                            updates["$PATH_PAIRINGS_BY_DISPLAY/$previousDisplayIdForMain"] = null
                                        }

                                        database()
                                            .reference
                                            .updateChildren(updates)
                                            .addOnSuccessListener {
                                                Log.d(TAG, "✅ Firebase cloud pairing claimed ($mainDeviceId -> $displayDeviceId)")
                                                onSuccess(
                                                    ClaimPairingResult(
                                                        displayDeviceId = displayDeviceId,
                                                        displayName = displayName,
                                                        localIp = localIp,
                                                        localPort = localPort,
                                                        localToken = localToken
                                                    )
                                                )
                                            }
                                            .addOnFailureListener { e ->
                                                Log.e(TAG, "💥 Firebase cloud pairing update failed", e)
                                                onFailure(e.message ?: "Failed to save pairing")
                                            }
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e(TAG, "💥 Firebase main pairing read failed", e)
                                        onFailure(e.message ?: "Failed to verify main device pairing state")
                                    }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "💥 Firebase display pairing read failed", e)
                                onFailure(e.message ?: "Failed to verify display pairing state")
                            }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "💥 Firebase pairing code lookup failed", e)
                        onFailure(e.message ?: "Failed to load pairing code")
                    }
            },
            onFailure = { e ->
                onFailure(e.message ?: "Firebase sign-in failed")
            }
        )
    }

    fun clearMainPairing(context: Context, reason: String = "main-clear") {
        val appContext = context.applicationContext
        withSignedInUser(
            context = appContext,
            reason = reason,
            onReady = {
                val prefs = PrefsManager(appContext)
                val mainDeviceId = prefs.getLocalDeviceId()
                val displayDeviceId = prefs.getPairedDisplayDeviceId()
                val updates = hashMapOf<String, Any?>(
                    "$PATH_PAIRINGS_BY_MAIN/$mainDeviceId" to null
                )
                if (!displayDeviceId.isNullOrBlank()) {
                    updates["$PATH_PAIRINGS_BY_DISPLAY/$displayDeviceId"] = null
                }
                database().reference.updateChildren(updates)
            }
        )
    }

    fun resetDisplayPairingSession(
        context: Context,
        previousPairCode: String?,
        reason: String = "display-reset"
    ) {
        val appContext = context.applicationContext
        withSignedInUser(
            context = appContext,
            reason = reason,
            onReady = {
                val prefs = PrefsManager(appContext)
                val displayDeviceId = prefs.getLocalDeviceId()
                val displayPairRef = database()
                    .getReference(PATH_PAIRINGS_BY_DISPLAY)
                    .child(displayDeviceId)

                displayPairRef.get()
                    .addOnSuccessListener { snapshot ->
                        val previousMainId = snapshot.child("mainDeviceId").getValue(String::class.java)
                        val updates = hashMapOf<String, Any?>(
                            "$PATH_PAIRINGS_BY_DISPLAY/$displayDeviceId" to null
                        )
                        if (!previousMainId.isNullOrBlank()) {
                            updates["$PATH_PAIRINGS_BY_MAIN/$previousMainId"] = null
                        }
                        if (!previousPairCode.isNullOrBlank()) {
                            updates["$PATH_PAIRING_CODES/$previousPairCode"] = null
                        }
                        database().reference.updateChildren(updates)
                    }
            }
        )
    }

    fun sendCloudOpenUrl(
        context: Context,
        url: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val appContext = context.applicationContext
        withSignedInUser(
            context = appContext,
            reason = "send-cloud-open-url",
            onReady = { authUid ->
                val prefs = PrefsManager(appContext)
                val mainDeviceId = prefs.getLocalDeviceId()
                val displayDeviceId = prefs.getPairedDisplayDeviceId()
                if (displayDeviceId.isNullOrBlank()) {
                    onFailure("No cloud-paired display selected")
                    return@withSignedInUser
                }

                val commandRef = database()
                    .getReference(PATH_COMMANDS)
                    .child(displayDeviceId)
                    .push()
                val commandId = commandRef.key
                if (commandId.isNullOrBlank()) {
                    onFailure("Failed to allocate command id")
                    return@withSignedInUser
                }

                val payload = hashMapOf<String, Any?>(
                    "commandId" to commandId,
                    "fromMainDeviceId" to mainDeviceId,
                    "fromMainAuthUid" to authUid,
                    "url" to url,
                    "transport" to "firebase_rtdb",
                    "status" to "pending",
                    "createdAt" to ServerValue.TIMESTAMP
                )

                val updates = hashMapOf<String, Any?>(
                    "$PATH_COMMANDS/$displayDeviceId/$commandId" to payload,
                    "$PATH_PAIRINGS_BY_MAIN/$mainDeviceId/lastActivityAt" to ServerValue.TIMESTAMP,
                    "$PATH_PAIRINGS_BY_DISPLAY/$displayDeviceId/lastActivityAt" to ServerValue.TIMESTAMP
                )

                database().reference.updateChildren(updates)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ Cloud open-url queued ($mainDeviceId -> $displayDeviceId / $commandId)")
                        onSuccess(commandId)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "💥 Cloud open-url write failed", e)
                        onFailure(e.message ?: "Failed to write cloud command")
                    }
            },
            onFailure = { e ->
                onFailure(e.message ?: "Firebase sign-in failed")
            }
        )
    }

    fun checkCloudDisplayStatus(
        context: Context,
        onResult: (Boolean, String?) -> Unit
    ) {
        val appContext = context.applicationContext
        withSignedInUser(
            context = appContext,
            reason = "check-cloud-display-status",
            onReady = {
                val prefs = PrefsManager(appContext)
                val mainDeviceId = prefs.getLocalDeviceId()
                val displayDeviceId = prefs.getPairedDisplayDeviceId()
                if (displayDeviceId.isNullOrBlank()) {
                    onResult(false, "No cloud-paired display selected")
                    return@withSignedInUser
                }

                val pairRef = database()
                    .getReference(PATH_PAIRINGS_BY_MAIN)
                    .child(mainDeviceId)
                val presenceRef = database()
                    .getReference(PATH_PRESENCE)
                    .child(displayDeviceId)

                pairRef.get()
                    .addOnSuccessListener { pairSnapshot ->
                        if (!pairSnapshot.exists()) {
                            onResult(false, "Cloud pairing not found")
                            return@addOnSuccessListener
                        }

                        presenceRef.get()
                            .addOnSuccessListener { presenceSnapshot ->
                                val connected = presenceSnapshot.child("connected").getValue(Boolean::class.java) == true
                                if (connected) {
                                    onResult(true, null)
                                } else {
                                    onResult(false, "Display offline")
                                }
                            }
                            .addOnFailureListener { e ->
                                onResult(false, e.message ?: "Failed to read display presence")
                            }
                    }
                    .addOnFailureListener { e ->
                        onResult(false, e.message ?: "Failed to read cloud pairing")
                    }
            },
            onFailure = { e ->
                onResult(false, e.message ?: "Firebase sign-in failed")
            }
        )
    }

    fun startDisplayCommandListener(context: Context, reason: String = "display-service") {
        val appContext = context.applicationContext
        withSignedInUser(
            context = appContext,
            reason = "start-display-command-listener:$reason",
            onReady = {
                val deviceId = PrefsManager(appContext).getLocalDeviceId()
                synchronized(this) {
                    if (displayCommandListenerDeviceId == deviceId && displayCommandListener != null) {
                        return@withSignedInUser
                    }
                    stopDisplayCommandListener()
                    val ref = database()
                        .getReference(PATH_COMMANDS)
                        .child(deviceId)
                    val listener = object : ChildEventListener {
                        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                            handleCloudCommandSnapshot(appContext, snapshot)
                        }

                        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                            handleCloudCommandSnapshot(appContext, snapshot)
                        }

                        override fun onChildRemoved(snapshot: DataSnapshot) = Unit
                        override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

                        override fun onCancelled(error: DatabaseError) {
                            Log.w(TAG, "⚠️ Cloud command listener cancelled: ${error.message}")
                        }
                    }
                    ref.addChildEventListener(listener)
                    displayCommandListenerDeviceId = deviceId
                    displayCommandListener = listener
                    Log.d(TAG, "✅ Cloud command listener attached for $deviceId [$reason]")
                    PhantomLog.i("Cloud command listener attached for $deviceId [$reason]")
                }
            }
        )
    }

    fun stopDisplayCommandListener() {
        synchronized(this) {
            val deviceId = displayCommandListenerDeviceId
            val listener = displayCommandListener
            if (!deviceId.isNullOrBlank() && listener != null) {
                database()
                    .getReference(PATH_COMMANDS)
                    .child(deviceId)
                    .removeEventListener(listener)
            }
            if (!deviceId.isNullOrBlank()) {
                PhantomLog.i("Cloud command listener stopped for $deviceId")
            }
            displayCommandListenerDeviceId = null
            displayCommandListener = null
            processedCommandIds.clear()
        }
    }

    fun markCloudCommandOpened(
        context: Context,
        commandId: String,
        finalUrl: String
    ) {
        val appContext = context.applicationContext
        val prefs = PrefsManager(appContext)
        val displayDeviceId = prefs.getLocalDeviceId()
        database()
            .getReference(PATH_COMMANDS)
            .child(displayDeviceId)
            .child(commandId)
            .updateChildren(
                hashMapOf(
                    "status" to "opened",
                    "finalUrl" to finalUrl,
                    "openedAt" to ServerValue.TIMESTAMP
                )
            )
    }

    private fun attachPresenceListener(context: Context) {
        if (presenceListenerAttached) return
        synchronized(this) {
            if (presenceListenerAttached) return
            presenceListenerAttached = true
        }

        val appContext = context.applicationContext
        val connectedRef = database().getReference(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isConnected = snapshot.getValue(Boolean::class.java) == true
                updatePresence(appContext, isConnected)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "⚠️ Firebase presence listener cancelled: ${error.message}")
            }
        })
    }

    private fun updatePresence(context: Context, connected: Boolean) {
        val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val prefs = PrefsManager(context)
        val deviceId = prefs.getLocalDeviceId()
        val role = prefs.getDeviceMode().lowercase()
        val presenceRef = database()
            .getReference(PATH_PRESENCE)
            .child(deviceId)

        val onlinePayload = hashMapOf<String, Any?>(
            "deviceId" to deviceId,
            "authUid" to authUid,
            "role" to role,
            "connected" to connected,
            "updatedAt" to ServerValue.TIMESTAMP
        )

        presenceRef.updateChildren(onlinePayload)
        if (connected) {
            presenceRef.child("connected").onDisconnect().setValue(false)
            presenceRef.child("updatedAt").onDisconnect().setValue(ServerValue.TIMESTAMP)
            presenceRef.child("lastOnlineAt").onDisconnect().setValue(ServerValue.TIMESTAMP)
        }
    }

    private fun attachDisplayPairingListener(context: Context) {
        val appContext = context.applicationContext
        val prefs = PrefsManager(appContext)
        val deviceId = prefs.getLocalDeviceId()
        if (displayPairingListenerDeviceId == deviceId) return
        synchronized(this) {
            if (displayPairingListenerDeviceId == deviceId) return
            displayPairingListenerDeviceId = deviceId
        }

        database()
            .getReference(PATH_PAIRINGS_BY_DISPLAY)
            .child(deviceId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val paired = snapshot.exists() && !snapshot.child("mainDeviceId").getValue(String::class.java).isNullOrBlank()
                    val mainId = snapshot.child("mainDeviceId").getValue(String::class.java)
                    val mainName = snapshot.child("mainName").getValue(String::class.java)
                    val previousPaired = prefs.isDisplayPaired()
                    val previousMainId = prefs.getDisplayPairedMainId()
                    val previousMainName = prefs.getDisplayPairedMainName()

                    if (paired) {
                        prefs.setDisplayPaired(true, mainId, mainName)
                    } else {
                        prefs.setDisplayPaired(false)
                    }

                    if (paired != previousPaired || mainId != previousMainId || mainName != previousMainName) {
                        broadcastPairingChanged(appContext, paired)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "⚠️ Firebase display pairing listener cancelled: ${error.message}")
                }
            })
    }

    private fun handleCloudCommandSnapshot(context: Context, snapshot: DataSnapshot) {
        val commandId = snapshot.key ?: return
        val status = snapshot.child("status").getValue(String::class.java) ?: "pending"
        if (status != "pending") return
        if (!processedCommandIds.add(commandId)) return

        val url = snapshot.child("url").getValue(String::class.java)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "⚠️ Cloud command $commandId missing URL")
            PhantomLog.w("Cloud command $commandId missing URL")
            return
        }
        PhantomLog.i("Cloud command received ($commandId)")

        val prefs = PrefsManager(context)
        val displayDeviceId = prefs.getLocalDeviceId()
        database()
            .getReference(PATH_COMMANDS)
            .child(displayDeviceId)
            .child(commandId)
            .updateChildren(
                hashMapOf(
                    "status" to "received",
                    "receivedAt" to ServerValue.TIMESTAMP
                )
            )

        val serviceIntent = Intent(context, DisplayServerService::class.java).apply {
            action = DisplayServerService.ACTION_NAVIGATION_RECEIVED
            putExtra("url", url)
            putExtra("commandId", commandId)
            putExtra("source", "firebase")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "📥 Cloud command delivered to DisplayServerService ($commandId)")
            PhantomLog.i("Cloud command delivered to DisplayServerService ($commandId)")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to start DisplayServerService for cloud command $commandId", e)
            PhantomLog.e("Failed to start DisplayServerService for cloud command $commandId", e)
        }
    }

    private fun broadcastPairingChanged(context: Context, paired: Boolean) {
        try {
            val intent = Intent(HttpServer.ACTION_PAIRING_CHANGED).apply {
                putExtra("paired", paired)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to broadcast cloud pairing change: ${e.message}")
        }
    }

    private fun database(): FirebaseDatabase {
        return FirebaseDatabase.getInstance(DATABASE_URL)
    }

    private fun withSignedInUser(
        context: Context,
        reason: String,
        onReady: (String) -> Unit,
        onFailure: (Exception) -> Unit = {}
    ) {
        val appContext = context.applicationContext
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            Log.d(TAG, "🔥 Firebase anonymous session ready (${currentUser.uid}) [$reason]")
            onReady(currentUser.uid)
            return
        }

        Log.d(TAG, "🔥 Signing in to Firebase anonymously [$reason]")
        auth.signInAnonymously()
            .addOnSuccessListener {
                val uid = it.user?.uid
                if (uid.isNullOrBlank()) {
                    onFailure(IllegalStateException("Missing Firebase user uid"))
                    return@addOnSuccessListener
                }
                Log.d(TAG, "✅ Firebase anonymous sign-in success: $uid [$reason]")
                syncDeviceRecord(appContext, "auth-success:$reason")
                attachPresenceListener(appContext)
                attachDisplayPairingListener(appContext)
                onReady(uid)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "💥 Firebase anonymous sign-in failed [$reason]", e)
                onFailure(e)
            }
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
