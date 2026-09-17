package com.example.webrtc

import android.util.Log
import com.example.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection

/**
 * Centralized provider for WebRTC ICE servers.
 *
 * Automatically parses complete ICE server arrays (STUN, TURN UDP/TCP, TURNS TLS)
 * from BuildConfig.TURN_ICE_SERVERS_JSON or custom runtime configuration.
 *
 * Strictly adheres to security rules: never logs credentials or dumps raw configuration.
 */
object IceServerProvider {
    private const val TAG = "IceServerProvider"

    data class IceSummary(
        val stunServers: Int,
        val turnUdpServers: Int,
        val turnTcpServers: Int,
        val turnsServers: Int,
        val totalServers: Int
    )

    /**
     * Builds the complete list of [PeerConnection.IceServer] entries.
     *
     * @param customJson Optional runtime JSON string overriding BuildConfig.
     * @param fallbackConfig Optional legacy TurnConfig fallback.
     */
    fun getIceServers(
        customJson: String? = null,
        fallbackConfig: WebRtcManager.TurnConfig? = null
    ): List<PeerConnection.IceServer> {
        val jsonSource = (customJson ?: BuildConfig.TURN_ICE_SERVERS_JSON).trim()

        if (jsonSource.isNotEmpty()) {
            val parsedServers = parseIceServersFromJson(jsonSource)
            if (parsedServers.isNotEmpty()) {
                val result = mutableListOf<PeerConnection.IceServer>()
                result.addAll(parsedServers)
                ensureDefaultStunPresent(result)
                logSafeSummary(result)
                return result
            }
        }

        // Fallback: Legacy 3-variable config
        return buildFallbackIceServers(fallbackConfig)
    }

    private fun parseIceServersFromJson(json: String): List<PeerConnection.IceServer> {
        val result = mutableListOf<PeerConnection.IceServer>()
        try {
            val trimmed = json.trim()
            val array: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    when {
                        obj.has("iceServers") -> obj.getJSONArray("iceServers")
                        obj.has("ice_servers") -> obj.getJSONArray("ice_servers")
                        else -> JSONArray().apply { put(obj) }
                    }
                }
                else -> return emptyList()
            }

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue

                // Extract URLs: support String, JSONArray of Strings, or "url"
                val urls = mutableListOf<String>()
                if (item.has("urls")) {
                    val urlsVal = item.get("urls")
                    when (urlsVal) {
                        is JSONArray -> {
                            for (u in 0 until urlsVal.length()) {
                                val s = urlsVal.optString(u).trim()
                                if (s.isNotEmpty()) urls.add(s)
                            }
                        }
                        is String -> {
                            val s = urlsVal.trim()
                            if (s.isNotEmpty()) urls.add(s)
                        }
                        else -> {
                            val s = urlsVal.toString().trim()
                            if (s.isNotEmpty()) urls.add(s)
                        }
                    }
                } else if (item.has("url")) {
                    val s = item.optString("url").trim()
                    if (s.isNotEmpty()) urls.add(s)
                }

                if (urls.isEmpty()) continue

                val username = item.optString("username").trim()
                val credential = when {
                    item.has("credential") -> item.optString("credential").trim()
                    item.has("password") -> item.optString("password").trim()
                    else -> ""
                }

                try {
                    val builder = PeerConnection.IceServer.builder(urls)
                    if (username.isNotEmpty() && credential.isNotEmpty()) {
                        builder.setUsername(username)
                        builder.setPassword(credential)
                    }
                    result.add(builder.createIceServer())
                } catch (e: Exception) {
                    Log.w(TAG, "Error adding ICE server entry: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ICE servers JSON: ${e.message}")
        }
        return result
    }

    private fun ensureDefaultStunPresent(servers: MutableList<PeerConnection.IceServer>) {
        val hasGoogleStun = servers.any { server ->
            server.urls.any { it.contains("stun.l.google.com") }
        }
        if (!hasGoogleStun) {
            servers.add(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        }
    }

    private fun sanitizeTurnUrl(url: String): String {
        var clean = url.trim().removeSurrounding("\"").trim()
        if (clean.contains(" ")) {
            clean = clean.substringBefore(" ").trim()
        }
        if (clean.contains("global.relay.metered.ca") && clean.contains(":8003")) {
            clean = clean.replace(Regex(":8003.*"), ":80")
        }
        return clean
    }

    private fun buildFallbackIceServers(customTurn: WebRtcManager.TurnConfig?): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        servers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
        servers.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer())

        val rawUrl = sanitizeTurnUrl(customTurn?.serverUrl ?: BuildConfig.TURN_SERVER_URL)
        val username = (customTurn?.username ?: BuildConfig.TURN_USERNAME).trim()
        val password = (customTurn?.credential ?: BuildConfig.TURN_PASSWORD).trim()

        if (rawUrl.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) {
            val turnUrls = mutableListOf<String>()
            turnUrls.add(rawUrl)

            if (rawUrl.contains("global.relay.metered.ca")) {
                turnUrls.add("turn:global.relay.metered.ca:80")
                turnUrls.add("turn:global.relay.metered.ca:80?transport=tcp")
                turnUrls.add("turn:global.relay.metered.ca:443?transport=tcp")
                turnUrls.add("turns:global.relay.metered.ca:443?transport=tcp")
            } else if (!rawUrl.contains("transport=")) {
                val separator = if (rawUrl.contains("?")) "&" else "?"
                turnUrls.add("$rawUrl${separator}transport=tcp")
            }

            for (url in turnUrls.distinct()) {
                try {
                    servers.add(
                        PeerConnection.IceServer.builder(url)
                            .setUsername(username)
                            .setPassword(password)
                            .createIceServer()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback TURN server build notice for $url: ${e.message}")
                }
            }
        }
        logSafeSummary(servers)
        return servers
    }

    private fun logSafeSummary(servers: List<PeerConnection.IceServer>) {
        var stun = 0
        var turnUdp = 0
        var turnTcp = 0
        var turns = 0

        for (server in servers) {
            for (url in server.urls) {
                when {
                    url.startsWith("turns:", ignoreCase = true) -> turns++
                    url.startsWith("turn:", ignoreCase = true) && url.contains("transport=tcp", ignoreCase = true) -> turnTcp++
                    url.startsWith("turn:", ignoreCase = true) -> turnUdp++
                    url.startsWith("stun:", ignoreCase = true) -> stun++
                }
            }
        }
        Log.i(
            TAG,
            "Configured WebRTC ICE infrastructure: ${servers.size} servers total (STUN: $stun, TURN UDP: $turnUdp, TURN TCP: $turnTcp, TURNS TLS: $turns)"
        )
    }

    fun getSafeSummary(customJson: String? = null): IceSummary {
        val servers = getIceServers(customJson)
        var stun = 0
        var turnUdp = 0
        var turnTcp = 0
        var turns = 0

        for (server in servers) {
            for (url in server.urls) {
                when {
                    url.startsWith("turns:", ignoreCase = true) -> turns++
                    url.startsWith("turn:", ignoreCase = true) && url.contains("transport=tcp", ignoreCase = true) -> turnTcp++
                    url.startsWith("turn:", ignoreCase = true) -> turnUdp++
                    url.startsWith("stun:", ignoreCase = true) -> stun++
                }
            }
        }
        return IceSummary(
            stunServers = stun,
            turnUdpServers = turnUdp,
            turnTcpServers = turnTcp,
            turnsServers = turns,
            totalServers = servers.size
        )
    }
}
