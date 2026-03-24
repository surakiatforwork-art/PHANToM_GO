package com.phantom.carnavrelay.util

import android.net.Uri
import android.util.Log
import org.json.JSONObject

data class PairInfo(
    val ip: String? = null,
    val port: Int? = null,
    val token: String? = null,
    val displayDeviceId: String? = null,
    val pairCode: String? = null
) {
    val isCloudPairing: Boolean
        get() = !displayDeviceId.isNullOrBlank() && !pairCode.isNullOrBlank()

    val hasLocalTransport: Boolean
        get() = !ip.isNullOrBlank() && port != null && port > 0 && !token.isNullOrBlank()
}

fun parsePairInfo(rawInput: String): PairInfo? {
    val raw = rawInput.trim()
    Log.d("PHANTOM_GO", "🔍 Parsing QR RAW: $raw")

    // 1) custom scheme phantomgo://pair?ip=...&port=...&token=...
    if (raw.startsWith("phantomgo://", ignoreCase = true)) {
        Log.d("PHANTOM_GO", "📋 Detected phantomgo:// scheme")
        val u = Uri.parse(raw)
        val host = u.host ?: ""
        if (!host.equals("pair", ignoreCase = true)) {
            Log.w("PHANTOM_GO", "❌ Invalid host: $host")
            return null
        }

        val deviceId = u.getQueryParameter("deviceId")?.trim().orEmpty()
        val pairCode = u.getQueryParameter("pairCode")?.trim().orEmpty()
        val ip = u.getQueryParameter("ip")?.trim().orEmpty()
        val portStr = u.getQueryParameter("port")?.trim().orEmpty()
        val token = u.getQueryParameter("token")?.trim().orEmpty()

        if (deviceId.isNotBlank() && pairCode.isNotBlank()) {
            val port = portStr.toIntOrNull()
            Log.d("PHANTOM_GO", "✅ Parsed cloud phantomgo://: deviceId=$deviceId, pairCode=$pairCode")
            return PairInfo(
                ip = ip.ifBlank { null },
                port = port,
                token = token.ifBlank { null },
                displayDeviceId = deviceId,
                pairCode = pairCode
            )
        }

        val port = portStr.toIntOrNull()
        if (ip.isBlank() || port == null || token.isBlank()) {
            Log.w("PHANTOM_GO", "❌ Missing params: ip=$ip, port=$portStr, token=${token.take(10)}...")
            return null
        }
        
        Log.d("PHANTOM_GO", "✅ Parsed phantomgo://: ip=$ip, port=$port, token=${token.take(10)}...")
        return PairInfo(ip, port, token)
    }

    // 2) JSON {"ip":"...","port":8765,"token":"..."}
    if (raw.startsWith("{") && raw.endsWith("}")) {
        Log.d("PHANTOM_GO", "📋 Detected JSON format")
        return try {
            val obj = JSONObject(raw)
            val deviceId = obj.optString("deviceId", "").trim()
            val pairCode = obj.optString("pairCode", "").trim()
            val ip = obj.optString("ip", "").trim()
            val port = obj.optInt("port", -1)
            val token = obj.optString("token", "").trim()

            if (deviceId.isNotBlank() && pairCode.isNotBlank()) {
                Log.d("PHANTOM_GO", "✅ Parsed cloud JSON: deviceId=$deviceId, pairCode=$pairCode")
                PairInfo(
                    ip = ip.ifBlank { null },
                    port = port.takeIf { it > 0 },
                    token = token.ifBlank { null },
                    displayDeviceId = deviceId,
                    pairCode = pairCode
                )
            } else if (ip.isBlank() || port <= 0 || token.isBlank()) {
                Log.w("PHANTOM_GO", "❌ Invalid JSON: ip=$ip, port=$port, token=${token.take(10)}...")
                null
            } else {
                Log.d("PHANTOM_GO", "✅ Parsed JSON: ip=$ip, port=$port, token=${token.take(10)}...")
                PairInfo(ip, port, token)
            }
        } catch (e: Throwable) {
            Log.e("PHANTOM_GO", "💥 JSON parse error", e)
            null
        }
    }

    // 3) fallback แบบง่าย "ip:port|token" (เผื่อของเก่า)
    // ตัวอย่าง: 192.168.43.120:8765|Abc_123-XYZ
    Log.d("PHANTOM_GO", "📋 Trying fallback format ip:port|token")
    val parts = raw.split("|")
    if (parts.size == 2) {
        val left = parts[0]
        val token = parts[1].trim()
        val hp = left.split(":")
        if (hp.size == 2) {
            val ip = hp[0].trim()
            val port = hp[1].trim().toIntOrNull()
            if (!ip.isBlank() && port != null && port > 0 && token.isNotBlank()) {
                Log.d("PHANTOM_GO", "✅ Parsed fallback: ip=$ip, port=$port, token=${token.take(10)}...")
                return PairInfo(ip, port, token)
            }
        }
    }

    Log.w("PHANTOM_GO", "❌ Unsupported QR format")
    return null
}

fun generateTokenHex(bytesLen: Int = 12): String {
    val bytes = ByteArray(bytesLen)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }  // 24 chars
}

fun buildPairPayload(ip: String, port: Int, token: String): String {
    return Uri.Builder()
        .scheme("phantomgo")
        .authority("pair")
        .appendQueryParameter("ip", ip)
        .appendQueryParameter("port", port.toString())
        .appendQueryParameter("token", token)
        .build()
        .toString()
}

fun buildCloudPairPayload(
    displayDeviceId: String,
    pairCode: String,
    ip: String,
    port: Int,
    token: String
): String {
    return Uri.Builder()
        .scheme("phantomgo")
        .authority("pair")
        .appendQueryParameter("mode", "cloud")
        .appendQueryParameter("deviceId", displayDeviceId)
        .appendQueryParameter("pairCode", pairCode)
        .appendQueryParameter("ip", ip)
        .appendQueryParameter("port", port.toString())
        .appendQueryParameter("token", token)
        .build()
        .toString()
}
