package com.example.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.example.BuildConfig
import com.example.model.SignalingMessage
import com.example.model.SignalingType
import com.example.signaling.SignalingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Production-grade WebRTC Manager for Parental Control.
 *
 * Strictly adheres to architectural mandates:
 * - Child is the ONLY media source (captures screen via MediaProjection and mic via RECORD_AUDIO).
 * - Parent is purely the viewer/receiver (renders remote VideoTrack, plays remote AudioTrack).
 * - Parent NEVER captures its own screen or microphone.
 * - Real SDP offers/answers, real ICE candidate gathering, and real STUN server traversal.
 */
class WebRtcManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"

        @Volatile
        private var instance: WebRtcManager? = null

        fun getInstance(context: Context): WebRtcManager {
            return instance ?: synchronized(this) {
                instance ?: WebRtcManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val signaling = SignalingEngine.getInstance()

    val rootEglBase: EglBase by lazy {
        try {
            EglBase.create()
        } catch (t: Throwable) {
            try {
                EglBase.createEgl10(EglBase.CONFIG_PLAIN)
            } catch (_: Throwable) {
                EglBase.create()
            }
        }
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var parentPeerConnection: PeerConnection? = null
    private var childPeerConnection: PeerConnection? = null

    // Child source components (Screen & Mic)
    @Volatile
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    /**
     * A SurfaceTextureHelper can only ever drive ONE capturer/VideoSource pair.
     * Re-using the helper from a previous MediaProjection session leaves it bound to the old,
     * already-disposed VideoSource, so the new ScreenCapturerAndroid delivers ZERO frames.
     * The parent still negotiates a video m-line and fires onTrack (UI says "LIVE SCREEN"),
     * audio keeps working, but the video surface stays permanently BLACK.
     * So: always hand out a brand new helper for every capture session.
     */
    @Synchronized
    private fun createFreshSurfaceTextureHelper(): SurfaceTextureHelper {
        releaseSurfaceTextureHelper()
        val helper = SurfaceTextureHelper.create(
            "ScreenCaptureThread-" + System.currentTimeMillis(),
            rootEglBase.eglBaseContext
        )
        surfaceTextureHelper = helper
        return helper
    }

    private fun releaseSurfaceTextureHelper() {
        try {
            surfaceTextureHelper?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceTextureHelper stopListening notice: ${e.message}")
        }
        try {
            surfaceTextureHelper?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceTextureHelper dispose notice: ${e.message}")
        }
        surfaceTextureHelper = null
    }
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null

    // Frame-level diagnostics + capture watchdog state. These let us prove whether a black
    // screen is caused by the child not capturing or by the parent not receiving.
    private var capturedFrameCount = 0L
    private var remoteFrameCount = 0L
    private var isCapturingFrames = false
    private var lastCaptureWidth = 720
    private var lastCaptureHeight = 1280
    private var localFrameProbeTrack: VideoTrack? = null
    private var remoteFrameProbeTrack: VideoTrack? = null

    /** Counts frames actually produced by the child screen capturer. */
    private fun attachLocalFrameProbe(track: VideoTrack?) {
        if (track == null || localFrameProbeTrack === track) return
        localFrameProbeTrack = track
        try {
            track.addSink { frame ->
                capturedFrameCount++
                if (capturedFrameCount == 1L || capturedFrameCount % 150L == 0L) {
                    Log.d(TAG, "CHILD CAPTURE OK - frames produced: $capturedFrameCount (" + frame.rotatedWidth + "x" + frame.rotatedHeight + ")")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach local frame probe: ${e.message}")
        }
    }

    /** Counts frames actually decoded and delivered on the parent viewer. */
    private fun attachRemoteFrameProbe(track: VideoTrack, force: Boolean = false) {
        if (!force && remoteFrameProbeTrack === track) return
        remoteFrameProbeTrack = track
        if (force) {
            // Keep remoteFrameCount continuous or restart probe sink cleanly
        } else {
            remoteFrameCount = 0L
        }
        try {
            track.addSink { frame ->
                remoteFrameCount++
                if (remoteFrameCount == 1L || remoteFrameCount % 30L == 0L) {
                    _remoteFrameCounter.value = remoteFrameCount
                    _remoteFrameSize.value = "${frame.rotatedWidth}x${frame.rotatedHeight}"
                }
                if (remoteFrameCount == 1L) {
                    Log.i(TAG, "PARENT FIRST FRAME RECEIVED (${frame.rotatedWidth}x${frame.rotatedHeight}) - Live screen streaming confirmed active")
                    scope.launch(Dispatchers.Main) {
                        _isScreenMirroringActive.value = true
                        _connectionStateDescription.value = "Live Screen Streaming"
                    }
                } else if (remoteFrameCount % 150L == 0L) {
                    Log.d(TAG, "PARENT RENDER OK - remote frames received: $remoteFrameCount (" + frame.rotatedWidth + "x" + frame.rotatedHeight + ")")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach remote frame probe: ${e.message}")
        }
    }

    /**
     * Re-arms MediaProjection capture if it never actually delivered a frame.
     * Without this, a stalled VirtualDisplay keeps the video track alive but empty.
     */
    private fun ensureCaptureRunning() {
        val capturer = videoCapturer ?: return
        if (isCapturingFrames && capturedFrameCount > 0L) return
        try {
            capturer.startCapture(lastCaptureWidth, lastCaptureHeight, 30)
            isCapturingFrames = true
            Log.d(TAG, "Re-armed screen capture at " + lastCaptureWidth + "x" + lastCaptureHeight)
        } catch (e: Exception) {
            Log.w(TAG, "Capture re-arm notice: ${e.message}")
        }
    }

    /** Detects a silently dead VirtualDisplay (zero frames) and restarts capture once. */
    private fun startCaptureWatchdog() {
        scope.launch {
            delay(4000)
            if (capturedFrameCount == 0L && videoCapturer != null) {
                Log.w(TAG, "No screen frames captured after 4s - restarting MediaProjection capture")
                try {
                    videoCapturer?.stopCapture()
                } catch (e: Exception) {
                    Log.w(TAG, "Watchdog stopCapture notice: ${e.message}")
                }
                try {
                    videoCapturer?.startCapture(lastCaptureWidth, lastCaptureHeight, 30)
                    isCapturingFrames = true
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog restart failed: ${e.message}")
                }
            }
        }
    }
    private var localVideoTrack: VideoTrack? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    // Parent receiver states
    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    // Live, on-screen diagnostics so a black screen can be explained without Logcat.
    private val _remoteFrameCounter = MutableStateFlow(0L)
    val remoteFrameCounter: StateFlow<Long> = _remoteFrameCounter.asStateFlow()

    private val _remoteFrameSize = MutableStateFlow("-")
    val remoteFrameSize: StateFlow<String> = _remoteFrameSize.asStateFlow()

    // Bytes actually received on the wire. This separates "no media arriving at all"
    // (DTLS/SRTP never established -> 0 B) from "media arriving but not decoding"
    // (bytes climbing while the frame counter stays at 0).
    private val _mediaBytesText = MutableStateFlow("0 B")
    val mediaBytesText: StateFlow<String> = _mediaBytesText.asStateFlow()

    /**
     * Transport truth line: ICE state, DTLS state and the selected candidate pair.
     *
     * Deliberately a SEPARATE flow from `connectionStateDescription`, because onTrack()
     * overwrites that text with an optimistic message and hides the fact that DTLS
     * never completed.
     */
    private val _transportText = MutableStateFlow("ICE:- DTLS:- PAIR:-")
    val transportText: StateFlow<String> = _transportText.asStateFlow()

    @Volatile
    private var iceStateLabel: String = "-"

    @Volatile
    private var dtlsStateLabel: String = "-"

    @Volatile
    private var candidatePairLabel: String = "-"

    // Distinct diagnostics flows for separate UI inspection
    data class SafeWebRtcDiagnostics(
        val iceGatheringState: String = "-",
        val iceConnectionState: String = "-",
        val peerConnectionState: String = "-",
        val dtlsState: String = "-",
        val localCandidateType: String = "-",
        val remoteCandidateType: String = "-",
        val selectedProtocol: String = "-",
        val relaySelected: Boolean = false,
        val packetsReceived: Long = 0L,
        val bytesReceived: Long = 0L,
        val bytesReceivedFormatted: String = "0 B",
        val audioBytesReceived: Long = 0L,
        val audioPacketsReceived: Long = 0L,
        val framesReceived: Long = 0L,
        val framesDecoded: Long = 0L,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val lastFrameTime: String = "-"
    )

    private val _safeDiagnostics = MutableStateFlow(SafeWebRtcDiagnostics())
    val safeDiagnostics: StateFlow<SafeWebRtcDiagnostics> = _safeDiagnostics.asStateFlow()

    // Debug toggle: Force TURN Relay (iceTransportsType = RELAY)
    private val _forceTurnRelay = MutableStateFlow(false)
    val forceTurnRelay: StateFlow<Boolean> = _forceTurnRelay.asStateFlow()

    fun setForceTurnRelay(enabled: Boolean) {
        _forceTurnRelay.value = enabled
        Log.i(TAG, "Force TURN Relay debug mode set to: $enabled")
        if (childPeerConnection != null || parentPeerConnection != null) {
            triggerControlledIceRestart("Force TURN relay toggle: $enabled")
        }
    }

    private val _iceStateLabelFlow = MutableStateFlow("-")
    val iceStateLabelFlow: StateFlow<String> = _iceStateLabelFlow.asStateFlow()

    private val _dtlsStateLabelFlow = MutableStateFlow("-")
    val dtlsStateLabelFlow: StateFlow<String> = _dtlsStateLabelFlow.asStateFlow()

    private val _candidatePairLabelFlow = MutableStateFlow("-")
    val candidatePairLabelFlow: StateFlow<String> = _candidatePairLabelFlow.asStateFlow()

    private val _candidateTypeDetailFlow = MutableStateFlow("Local: - | Remote: -")
    val candidateTypeDetailFlow: StateFlow<String> = _candidateTypeDetailFlow.asStateFlow()

    private val _crossNetworkStatusFlow = MutableStateFlow("P2P Direct / Local (No Relay)")
    val crossNetworkStatusFlow: StateFlow<String> = _crossNetworkStatusFlow.asStateFlow()

    private val _isRelayActiveFlow = MutableStateFlow(false)
    val isRelayActiveFlow: StateFlow<Boolean> = _isRelayActiveFlow.asStateFlow()

    private val _isCrossNetworkSuccessFlow = MutableStateFlow(false)
    val isCrossNetworkSuccessFlow: StateFlow<Boolean> = _isCrossNetworkSuccessFlow.asStateFlow()

    // 8-second frame stall renderer sink detach & reattach trigger
    private val _rendererReattachTrigger = MutableStateFlow(0)
    val rendererReattachTrigger: StateFlow<Int> = _rendererReattachTrigger.asStateFlow()

    fun reattachRendererSink() {
        _rendererReattachTrigger.value++
        Log.w(TAG, "reattachRendererSink called (epoch=${_rendererReattachTrigger.value}) - detaching/reattaching sink without restarting MediaProjection")
        val track = _remoteVideoTrack.value ?: return
        try {
            track.setEnabled(false)
            track.setEnabled(true)
            attachRemoteFrameProbe(track, force = true)
        } catch (e: Exception) {
            Log.w(TAG, "Notice in reattachRendererSink: ${e.message}")
        }
    }

    private fun publishTransportText() {
        _transportText.value = "ICE:$iceStateLabel DTLS:$dtlsStateLabel PAIR:$candidatePairLabel"
        _iceStateLabelFlow.value = iceStateLabel
        _dtlsStateLabelFlow.value = dtlsStateLabel
        _candidatePairLabelFlow.value = candidatePairLabel
    }

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrackFlow: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    val activeVideoTrack: StateFlow<VideoTrack?> = combine(
        _remoteVideoTrack,
        _localVideoTrack
    ) { remote, local ->
        remote ?: local
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _isScreenMirroringActive = MutableStateFlow(false)
    val isScreenMirroringActive: StateFlow<Boolean> = _isScreenMirroringActive.asStateFlow()

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    private val _isScreenCaptureAuthorized = MutableStateFlow(false)
    val isScreenCaptureAuthorized: StateFlow<Boolean> = _isScreenCaptureAuthorized.asStateFlow()

    private val _isMicrophoneMonitoringActive = MutableStateFlow(false)
    val isMicrophoneMonitoringActive: StateFlow<Boolean> = _isMicrophoneMonitoringActive.asStateFlow()

    private val _streamError = MutableStateFlow<String?>(null)
    val streamError: StateFlow<String?> = _streamError.asStateFlow()

    fun clearStreamError() {
        _streamError.value = null
    }

    fun setStreamError(error: String) {
        _streamError.value = error
    }

    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraVideoSource: VideoSource? = null
    private var cameraVideoTrack: VideoTrack? = null
    private var cameraSurfaceTextureHelper: SurfaceTextureHelper? = null
    private var isFrontCamera = false

    private val _connectionStateDescription = MutableStateFlow("Idle")
    val connectionStateDescription: StateFlow<String> = _connectionStateDescription.asStateFlow()

    @Volatile
    var pendingProjectionResultData: android.content.Intent? = null

    private var currentSessionId: String = ""
    private var remoteDeviceId: String = ""
    private var localDeviceId: String = ""

    private enum class LocalRole { NONE, PARENT, CHILD }
    @Volatile private var localRole: LocalRole = LocalRole.NONE

    private val pendingIceCandidates = java.util.Collections.synchronizedList(mutableListOf<IceCandidate>())
    private val processedCandidateSdpSet = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var iceRestartAttempts: Int = 0
    private var lastSuccessfulConnectionAtMs: Long = 0L

    init {
        try {
            connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                private var lastNetworkHandle: Long? = null
                override fun onAvailable(network: Network) {
                    val handle = network.networkHandle
                    if (lastNetworkHandle != null && lastNetworkHandle != handle) {
                        Log.i(TAG, "Network handoff detected - triggering controlled ICE restart")
                        if (localRole != LocalRole.NONE) {
                            triggerControlledIceRestart("Network handoff")
                        }
                    }
                    lastNetworkHandle = handle
                }
            }
            networkCallback?.let {
                connectivityManager?.registerDefaultNetworkCallback(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register default network callback: ${e.message}")
        }
    }

    fun triggerControlledIceRestart(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastIceRestartAtMs < 6_000L) {
            Log.d(TAG, "Controlled ICE restart throttled by cooldown ($reason)")
            return
        }

        if (iceRestartAttempts >= 4) {
            val timeSinceLastSuccess = now - lastSuccessfulConnectionAtMs
            if (timeSinceLastSuccess < 30_000L) {
                Log.w(TAG, "Exceeded maximum consecutive ICE restart retries ($iceRestartAttempts attempts)")
                return
            } else {
                iceRestartAttempts = 0
            }
        }

        iceRestartAttempts++
        lastIceRestartAtMs = now
        Log.w(TAG, "Triggering controlled ICE restart (attempt $iceRestartAttempts, reason: $reason)")
        triggerIceRestart(reason)
    }

    // Standalone TURN relay verification
    fun testTurnRelay(onResult: (success: Boolean, message: String) -> Unit) {
        scope.launch {
            var testPc: PeerConnection? = null
            var relayGathered = false
            val timeoutJob = launch {
                delay(10000)
                if (!relayGathered) {
                    try { testPc?.close() } catch (_: Exception) {}
                    testPc = null
                    withContext(Dispatchers.Main) {
                        onResult(false, "TURN Relay Test Timed Out (No relay candidate gathered in 10s)")
                    }
                }
            }

            try {
                if (peerConnectionFactory == null) {
                    initPeerConnectionFactory()
                }
                val factory = peerConnectionFactory
                if (factory == null) {
                    timeoutJob.cancel()
                    onResult(false, "PeerConnectionFactory not initialized")
                    return@launch
                }
                val servers = buildIceServers()
                val rtcConfig = PeerConnection.RTCConfiguration(servers).apply {
                    iceTransportsType = PeerConnection.IceTransportsType.RELAY
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                }

                testPc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        val sdp = candidate.sdp.lowercase()
                        if (sdp.contains("typ relay")) {
                            relayGathered = true
                            timeoutJob.cancel()
                            val proto = when {
                                sdp.contains("transport=tcp") || sdp.contains(" tcp ") -> "TCP"
                                else -> "UDP"
                            }
                            Log.i(TAG, "TURN RELAY TEST SUCCESS: Gathered relay candidate ($proto)")
                            scope.launch(Dispatchers.Main) {
                                try { testPc?.close() } catch (_: Exception) {}
                                testPc = null
                                onResult(true, "SUCCESS: TURN Relay candidate gathered ($proto)")
                            }
                        }
                    }
                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
                    override fun onIceConnectionReceivingChange(p0: Boolean) {}
                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                        if (newState == PeerConnection.IceGatheringState.COMPLETE && !relayGathered) {
                            timeoutJob.cancel()
                            scope.launch(Dispatchers.Main) {
                                try { testPc?.close() } catch (_: Exception) {}
                                testPc = null
                                onResult(false, "Gathering finished without relay candidate")
                            }
                        }
                    }
                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
                    override fun onAddStream(p0: MediaStream?) {}
                    override fun onRemoveStream(p0: MediaStream?) {}
                    override fun onDataChannel(p0: DataChannel?) {}
                })

                val dcInit = DataChannel.Init()
                testPc?.createDataChannel("turn_probe", dcInit)
                testPc?.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        sdp?.let {
                            testPc?.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {}
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(p0: String?) {}
                            }, it)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, MediaConstraints())

            } catch (e: Exception) {
                timeoutJob.cancel()
                withContext(Dispatchers.Main) {
                    onResult(false, "TURN Test failed: ${e.message}")
                }
            }
        }
    }

    // ---- Negotiation guards (prevents offer/answer storm that killed the stream) ----
    @Volatile
    private var lastOfferBuiltAtMs: Long = 0L

    @Volatile
    private var childNegotiationInFlight: Boolean = false

    @Volatile
    private var lastAppliedRemoteOfferSdp: String = ""

    /**
     * True once the parent has successfully applied an offer for this session.
     */
    @Volatile
    private var parentHasAppliedOffer: Boolean = false

    /** Minimum gap between two full PeerConnection rebuilds on the child. */
    private val OFFER_REBUILD_COOLDOWN_MS = 9_000L

    // =========================================================================
    // CONFIGURABLE AUTHENTICATED TURN CREDENTIALS
    // =========================================================================
    data class TurnConfig(
        val serverUrl: String = "",
        val username: String = "",
        val credential: String = ""
    )

    private val _turnConfigFlow = MutableStateFlow(TurnConfig())
    val turnConfigFlow: StateFlow<TurnConfig> = _turnConfigFlow.asStateFlow()

    fun getActiveTurnConfig(): TurnConfig {
        val prefs = context.getSharedPreferences("turn_config_prefs", Context.MODE_PRIVATE)
        val prefUrl = prefs.getString("turn_url", null)
        val prefUser = prefs.getString("turn_user", null)
        val prefPass = prefs.getString("turn_pass", null)

        val url = if (!prefUrl.isNullOrBlank()) prefUrl else BuildConfig.TURN_SERVER_URL
        val user = if (!prefUser.isNullOrBlank()) prefUser else BuildConfig.TURN_USERNAME
        val pass = if (!prefPass.isNullOrBlank()) prefPass else BuildConfig.TURN_PASSWORD

        val config = TurnConfig(url.trim(), user.trim(), pass.trim())
        _turnConfigFlow.value = config
        return config
    }

    fun updateTurnCredentials(url: String, username: String, password: String) {
        val prefs = context.getSharedPreferences("turn_config_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("turn_url", url.trim())
            .putString("turn_user", username.trim())
            .putString("turn_pass", password.trim())
            .apply()
        _turnConfigFlow.value = TurnConfig(url.trim(), username.trim(), password.trim())
        Log.d(TAG, "Updated in-app TURN credentials for server: ${url.take(25)}")
    }

    private data class ParsedTurnHost(val host: String, val port: Int)
    private fun parseTurnHost(raw: String): ParsedTurnHost {
        var s = raw.trim()
        if (s.contains("?")) s = s.substringBefore("?")
        if (s.startsWith("turns:", ignoreCase = true)) {
            s = s.substring(6)
        } else if (s.startsWith("turn:", ignoreCase = true)) {
            s = s.substring(5)
        }
        return if (s.contains(":")) {
            val parts = s.split(":")
            val h = parts[0].trim()
            val p = parts[1].toIntOrNull() ?: 3478
            ParsedTurnHost(h, p)
        } else {
            ParsedTurnHost(s.trim(), 3478)
        }
    }

    fun buildIceServers(customTurn: TurnConfig? = null): List<PeerConnection.IceServer> {
        val config = customTurn ?: getActiveTurnConfig()
        return IceServerProvider.getIceServers(fallbackConfig = config)
    }

    // ---- ICE Failure & Prolonged Disconnection Recovery ----
    private var disconnectedTimerJob: kotlinx.coroutines.Job? = null
    private var lastIceRestartAtMs: Long = 0L

    private fun scheduleDisconnectedIceRestart() {
        disconnectedTimerJob?.cancel()
        disconnectedTimerJob = scope.launch {
            delay(5000)
            Log.w(TAG, "ICE remained DISCONNECTED for > 5s -> initiating ICE restart")
            triggerIceRestart("Prolonged ICE DISCONNECTED (> 5s)")
        }
    }

    private fun cancelDisconnectedIceRestart() {
        disconnectedTimerJob?.cancel()
        disconnectedTimerJob = null
    }

    fun triggerIceRestart(reason: String) {
        if (localRole == LocalRole.PARENT) {
            Log.d(TAG, "Parent requesting ICE restart from child ($reason)")
            signaling.sendMessage(
                SignalingMessage(
                    sessionId = currentSessionId,
                    senderDeviceId = localDeviceId,
                    targetDeviceId = remoteDeviceId,
                    messageType = SignalingType.RESTART_ICE,
                    payload = reason
                )
            )
            return
        }

        val pc = childPeerConnection ?: return
        val now = System.currentTimeMillis()
        if (now - lastIceRestartAtMs < 5_000L) {
            Log.d(TAG, "ICE restart throttled (reason=$reason, lastRestart=${now - lastIceRestartAtMs}ms ago)")
            return
        }
        lastIceRestartAtMs = now

        scope.launch {
            Log.w(TAG, "Executing restartIce() on child peer connection ($reason)...")
            var waitCount = 0
            while (childNegotiationInFlight && waitCount < 15) {
                delay(200)
                waitCount++
            }
            val state = try { pc.signalingState() } catch (_: Exception) { null }
            if (state == PeerConnection.SignalingState.CLOSED) {
                Log.w(TAG, "Child PeerConnection is CLOSED - cannot restart ICE")
                return@launch
            }

            try {
                pc.restartIce()
                Log.d(TAG, "restartIce() called on child, creating fresh SDP offer...")
                childNegotiationInFlight = false
                createAndSendOffer()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute restartIce(): ${e.message}", e)
            }
        }
    }

    // ---- Child Outbound Video Bytes Watchdog ----
    private var childStatsJob: kotlinx.coroutines.Job? = null
    private var lastOutboundVideoBytes: Long = -1L
    private var lastOutboundBytesChangeTimeMs: Long = 0L

    private fun startChildStatsMonitor() {
        childStatsJob?.cancel()
        childStatsJob = scope.launch {
            lastOutboundBytesChangeTimeMs = System.currentTimeMillis()
            lastOutboundVideoBytes = -1L
            while (isActive) {
                delay(2000)
                if (localRole != LocalRole.CHILD || !_isScreenMirroringActive.value) continue
                val pc = childPeerConnection ?: continue
                val capturer = videoCapturer ?: continue

                try {
                    pc.getStats { report ->
                        var outboundVideoBytes = 0L
                        var hasVideoSender = false
                        for (stat in report.statsMap.values) {
                            if (stat.type == "outbound-rtp") {
                                val mediaType = stat.members["mediaType"]?.toString()
                                    ?: stat.members["kind"]?.toString()
                                if (mediaType == "video") {
                                    hasVideoSender = true
                                    val bytes = stat.members["bytesSent"]
                                    if (bytes is Number) {
                                        outboundVideoBytes += bytes.toLong()
                                    }
                                }
                            }
                        }

                        if (hasVideoSender) {
                            val now = System.currentTimeMillis()
                            if (lastOutboundVideoBytes == -1L) {
                                lastOutboundVideoBytes = outboundVideoBytes
                                lastOutboundBytesChangeTimeMs = now
                            } else if (outboundVideoBytes > lastOutboundVideoBytes) {
                                lastOutboundVideoBytes = outboundVideoBytes
                                lastOutboundBytesChangeTimeMs = now
                            } else {
                                val stalledMs = now - lastOutboundBytesChangeTimeMs
                                if (stalledMs >= 6_000L) {
                                    Log.w(TAG, "Outbound video bytes stopped increasing on child for ${stalledMs / 1000}s ($outboundVideoBytes bytes). Restarting screen capturer only...")
                                    lastOutboundBytesChangeTimeMs = now
                                    restartChildScreenCapturerOnly()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Child stats poll error: ${e.message}")
                }
            }
        }
    }

    fun restartChildScreenCapturerOnly() {
        val capturer = videoCapturer ?: return
        Log.d(TAG, "Restarting screen capturer only (preserving MediaProjection & PeerConnection)...")
        try {
            capturer.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Notice during capturer stopCapture: ${e.message}")
        }
        try {
            capturer.startCapture(lastCaptureWidth, lastCaptureHeight, 30)
            isCapturingFrames = true
            Log.d(TAG, "Screen capturer successfully restarted at ${lastCaptureWidth}x${lastCaptureHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart screen capturer: ${e.message}", e)
        }
    }

    init {
        initPeerConnectionFactory()
        setupSignalingListener()
    }

    fun setLocalDeviceId(deviceId: String) {
        if (deviceId.isNotEmpty()) {
            localDeviceId = deviceId
            Log.d(TAG, "WebRtcManager localDeviceId set: $deviceId")
        }
    }

    fun setRemoteDeviceId(deviceId: String) {
        if (deviceId.isNotEmpty()) {
            remoteDeviceId = deviceId
            Log.d(TAG, "WebRtcManager remoteDeviceId set: $deviceId")
        }
    }

    fun getRemoteDeviceId(): String = remoteDeviceId

    fun getLocalDeviceId(): String = localDeviceId

    fun getCurrentSessionId(): String = currentSessionId

    fun setCurrentSessionId(sessionId: String) {
        if (sessionId.isNotEmpty()) {
            currentSessionId = sessionId
        }
    }

    private fun initPeerConnectionFactory() {
        try {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val useHwAec = org.webrtc.audio.JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
            val useHwNs = org.webrtc.audio.JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
            Log.d(TAG, "Audio hardware support - HW AEC: $useHwAec, HW NS: $useHwNs")

            val audioDeviceModule = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(useHwAec)
                .setUseHardwareNoiseSuppressor(useHwNs)
                .createAudioDeviceModule()

            val encoderFactory = DefaultVideoEncoderFactory(
                rootEglBase.eglBaseContext,
                true,
                true
            )
            val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            Log.d(TAG, "PeerConnectionFactory initialized successfully with JavaAudioDeviceModule")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize PeerConnectionFactory", e)
        }
    }

    private fun setupSignalingListener() {
        signaling.addMessageListener { message ->
            scope.launch {
                handleSignalingMessage(message)
            }
        }
    }

    private fun handleSignalingMessage(message: SignalingMessage) {
        // Validate targeting
        if (localDeviceId.isNotEmpty() && message.targetDeviceId.isNotEmpty() && message.targetDeviceId != localDeviceId) {
            return
        }

        when (message.messageType) {
            SignalingType.START_SCREEN -> {
                // ChildActiveScreen owns the user-consent UI and starts/resumes capture.
                // Do not also resend from here: the same SharedFlow message is observed by UI.
                currentSessionId = message.sessionId
                remoteDeviceId = message.senderDeviceId
                if (localDeviceId.isEmpty() && message.targetDeviceId.isNotEmpty()) {
                    localDeviceId = message.targetDeviceId
                }
                localRole = LocalRole.CHILD
                _connectionStateDescription.value = "Parent requested screen mirror"
            }

            SignalingType.SCREEN_PERMISSION_REQUIRED -> {
                Log.d(TAG, "Child device requires user permission on device")
                _connectionStateDescription.value = "Waiting for child approval on device..."
            }

            SignalingType.SCREEN_PERMISSION_GRANTED -> {
                Log.d(TAG, "Child approved screen permission, establishing video stream...")
                _connectionStateDescription.value = "Child Approved! Connecting Stream..."
            }

            SignalingType.SCREEN_PERMISSION_DENIED -> {
                Log.d(TAG, "Child denied screen permission request")
                _streamError.value = "Child declined screen request"
                _connectionStateDescription.value = "Child declined screen request"
            }

            SignalingType.START_CAMERA -> {
                Log.d(TAG, "Child received START_CAMERA request for session: ${message.sessionId}")
                currentSessionId = message.sessionId
                remoteDeviceId = message.senderDeviceId
                val childId = localDeviceId.ifEmpty { message.targetDeviceId }
                startChildCameraStreaming(message.sessionId, message.senderDeviceId, childId)
            }

            SignalingType.STOP_CAMERA -> {
                Log.d(TAG, "Received STOP_CAMERA")
                stopChildCameraStreaming()
            }

            SignalingType.SWITCH_CAMERA -> {
                Log.d(TAG, "Received SWITCH_CAMERA")
                switchChildCamera()
            }

            SignalingType.CAMERA_PERMISSION_REQUIRED -> {
                Log.d(TAG, "Child device requires camera permission on device")
                _connectionStateDescription.value = "Waiting for child camera approval..."
            }

            SignalingType.CAMERA_PERMISSION_GRANTED -> {
                Log.d(TAG, "Child approved camera permission, establishing camera stream...")
                _connectionStateDescription.value = "Child Approved Camera! Connecting..."
            }

            SignalingType.CAMERA_PERMISSION_DENIED -> {
                Log.d(TAG, "Child denied camera permission request")
                _streamError.value = "Child declined camera permission request"
                _connectionStateDescription.value = "Child declined camera request"
            }

            SignalingType.START_AUDIO -> {
                Log.d(TAG, "Child received START_AUDIO request for session: ${message.sessionId}")
                currentSessionId = message.sessionId
                remoteDeviceId = message.senderDeviceId
                val childId = localDeviceId.ifEmpty { message.targetDeviceId }
                startChildMicrophoneStreaming(message.sessionId, message.senderDeviceId, childId)
            }

            SignalingType.STOP_SCREEN -> {
                Log.d(TAG, "Received STOP_SCREEN; UI/service will pause capture")
            }

            SignalingType.STOP_AUDIO -> {
                Log.d(TAG, "Received STOP_AUDIO")
                stopChildMicrophoneStreaming()
            }

            SignalingType.AUDIO_PERMISSION_REQUIRED -> {
                Log.d(TAG, "Child device requires microphone permission on device")
                _connectionStateDescription.value = "Waiting for child microphone approval..."
            }

            SignalingType.AUDIO_PERMISSION_GRANTED -> {
                Log.d(TAG, "Child approved audio permission")
                _connectionStateDescription.value = "Microphone Connected"
            }

            SignalingType.AUDIO_PERMISSION_DENIED -> {
                Log.d(TAG, "Child denied microphone permission request")
                _streamError.value = "Child declined microphone permission request"
                _connectionStateDescription.value = "Child declined audio request"
            }

            SignalingType.WEBRTC_OFFER -> {
                Log.d(TAG, "Received WEBRTC_OFFER")
                handleRemoteOffer(message)
            }

            SignalingType.WEBRTC_ANSWER -> {
                Log.d(TAG, "Received WEBRTC_ANSWER")
                handleRemoteAnswer(message)
            }

            SignalingType.ICE_CANDIDATE -> {
                Log.d(TAG, "Received ICE_CANDIDATE")
                handleRemoteIceCandidate(message)
            }

            SignalingType.SESSION_ENDED -> {
                Log.d(TAG, "Session ended")
                cleanupSession()
            }

            SignalingType.RESTART_ICE -> {
                Log.d(TAG, "Received RESTART_ICE signaling message from ${message.senderDeviceId}: ${message.payload}")
                triggerIceRestart("Remote peer requested ICE restart: ${message.payload}")
            }

            else -> {}
        }
    }

    // =========================================================================
    // CHILD ROLE: MEDIA SOURCE (MediaProjection & Microphone)
    // =========================================================================

    fun hasActiveScreenCapturer(): Boolean {
        return videoCapturer != null && localVideoTrack != null
    }

    private fun hasSender(pc: PeerConnection?, track: org.webrtc.MediaStreamTrack?): Boolean {
        if (pc == null || track == null) return false
        return pc.senders.any { sender -> sender.track()?.id() == track.id() }
    }

    private fun ensureTrackPublished(pc: PeerConnection?, track: org.webrtc.MediaStreamTrack?) {
        if (pc == null || track == null || hasSender(pc, track)) return
        pc.addTrack(track, listOf("child_media_stream"))
    }

    fun resumeChildScreenMirroring(
        sessionId: String,
        targetParentDeviceId: String,
        childDeviceId: String
    ) {
        currentSessionId = sessionId
        remoteDeviceId = targetParentDeviceId
        localDeviceId = childDeviceId
        localRole = LocalRole.CHILD
        val factory = peerConnectionFactory ?: return
        try {
            Log.d(TAG, "Resuming screen mirroring using persistent MediaProjection for parent $targetParentDeviceId")
            _isScreenMirroringActive.value = true
            _connectionStateDescription.value = "Streaming (One-Time Auth Active)"

            // ROOT CAUSE FIX (v10): reuse the live PeerConnection instead of destroying it.
            // Rebuilding here restarted ICE gathering from zero every time the stream was
            // resumed, so the handshake never had time to complete.
            val resumeState = try {
                childPeerConnection?.signalingState()
            } catch (e: Exception) {
                null
            }
            if (childPeerConnection == null ||
                resumeState == PeerConnection.SignalingState.CLOSED
            ) {
                Log.d(TAG, "No usable child PeerConnection (state=$resumeState); creating one to resume streaming")
                createChildPeerConnection(factory)
            } else {
                Log.d(TAG, "Reusing the live child PeerConnection to resume streaming (state=$resumeState)")
            }

            // A null video track here used to be a SILENT no-op: the offer was still sent,
            // but it contained audio only. The parent then negotiated fine, reached ICE
            // Connected and displayed LIVE SCREEN while receiving zero video frames.
            // Fail loudly and ask for screen authorization again instead.
            val videoTrack = localVideoTrack
            if (videoTrack == null || videoCapturer == null) {
                Log.e(TAG, "RESUME ABORTED - no screen capturer/video track available. " +
                    "MediaProjection authorization was lost, so no video can be published.")
                _isScreenMirroringActive.value = false
                _isScreenCaptureAuthorized.value = false
                _connectionStateDescription.value = "Screen authorization lost - re-approve on child"
                return
            }

            videoTrack.setEnabled(true)
            ensureCaptureRunning()
            ensureTrackPublished(childPeerConnection, videoTrack)
            // Always publish audio together with video so the parent never needs a
            // second renegotiation (which used to tear down the video connection).
            ensureLocalAudioTrack()?.let { track ->
                ensureTrackPublished(childPeerConnection, track)
            }

            createAndSendOffer()
            startChildStatsMonitor()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming screen mirroring", e)
            _isScreenMirroringActive.value = false
            _connectionStateDescription.value = "Resume Error: ${e.message}"
        }
    }

    fun startChildScreenMirroring(
        mediaProjectionResultData: Intent,
        sessionId: String,
        targetParentDeviceId: String,
        childDeviceId: String
    ) {
        if (hasActiveScreenCapturer()) {
            resumeChildScreenMirroring(sessionId, targetParentDeviceId, childDeviceId)
            return
        }

        currentSessionId = sessionId
        remoteDeviceId = targetParentDeviceId
        localDeviceId = childDeviceId
        localRole = LocalRole.CHILD
        _isScreenMirroringActive.value = true
        _connectionStateDescription.value = "Creating Screen Stream..."

        try {
            val factory = peerConnectionFactory ?: return

            // 1. Create ScreenCapturerAndroid from MediaProjection intent
            videoCapturer = ScreenCapturerAndroid(mediaProjectionResultData, object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaProjection revoked by Android system")
                    stopChildScreenMirroring(permanent = true)
                }
            })

            // Fresh helper per session - a recycled one silently produces zero frames.
            val helper = createFreshSurfaceTextureHelper()
            videoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
            videoCapturer?.initialize(helper, context, videoSource?.capturerObserver)
            val metrics = context.resources.displayMetrics
            val isPortrait = metrics.heightPixels >= metrics.widthPixels
            val captureWidth = if (isPortrait) 720 else 1280
            val captureHeight = if (isPortrait) 1280 else 720
            lastCaptureWidth = captureWidth
            lastCaptureHeight = captureHeight
            capturedFrameCount = 0L
            videoCapturer?.startCapture(captureWidth, captureHeight, 30)
            isCapturingFrames = true

            localVideoTrack = factory.createVideoTrack("child_screen_track", videoSource)
            localVideoTrack?.setEnabled(true)
            attachLocalFrameProbe(localVideoTrack)
            startCaptureWatchdog()
            _localVideoTrack.value = localVideoTrack
            _isScreenCaptureAuthorized.value = true

            // If Parent UI is active on this same device in loopback mode, mirror local video track
            if (localDeviceId.isNotEmpty() && localDeviceId == remoteDeviceId && _remoteVideoTrack.value == null) {
                _remoteVideoTrack.value = localVideoTrack
                _connectionStateDescription.value = "Connected (Live Screen Streaming)"
            }

            // 2. Setup PeerConnection
            createChildPeerConnection(factory)

            // 3. Add VideoTrack + AudioTrack (single negotiation for both media kinds)
            localVideoTrack?.let { track ->
                ensureTrackPublished(childPeerConnection, track)
            }
            ensureLocalAudioTrack()?.let { track ->
                ensureTrackPublished(childPeerConnection, track)
            }

            // 4. Create and Send SDP Offer
            createAndSendOffer()
            startChildStatsMonitor()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting child screen mirroring", e)
            _connectionStateDescription.value = "Capture Error: ${e.message}"
            _isScreenMirroringActive.value = false
            _isScreenCaptureAuthorized.value = false
        }
    }

    /**
     * Resends a fresh SDP offer for a new or reconnected parent session using the existing live video/audio tracks.
     */
    fun resendOfferForParent(
        sessionId: String,
        targetParentDeviceId: String,
        childDeviceId: String
    ) {
        currentSessionId = sessionId
        remoteDeviceId = targetParentDeviceId
        localDeviceId = childDeviceId
        localRole = LocalRole.CHILD
        val factory = peerConnectionFactory ?: return
        try {
            // ROOT CAUSE FIX (v10):
            // This path used to close the child PeerConnection and build a new one on
            // EVERY resend request from the parent. The parent re-requests the stream
            // periodically until it sees frames, so the child kept destroying the very
            // transport that was still in the middle of its ICE/DTLS handshake. Neither
            // side ever got the few seconds it needs to finish connecting.
            // Reuse the live PeerConnection and just renegotiate on it.
            val childState = try {
                childPeerConnection?.signalingState()
            } catch (e: Exception) {
                null
            }
            if (childPeerConnection == null ||
                childState == PeerConnection.SignalingState.CLOSED
            ) {
                Log.d(TAG, "No usable child PeerConnection (state=$childState); creating one to resend the offer")
                createChildPeerConnection(factory)
            } else {
                Log.d(TAG, "Reusing the live child PeerConnection to resend the offer (state=$childState); " +
                    "gathered ICE candidates are preserved")
            }
            // The track may still be disabled from a previous pause. A disabled track is
            // negotiated normally but transmits nothing -> parent shows LIVE + black screen.
            val videoTrack = localVideoTrack
            if (videoTrack == null || videoCapturer == null) {
                Log.e(TAG, "RESEND ABORTED - no screen capturer/video track available; " +
                    "an audio-only offer would show LIVE SCREEN with a black video area.")
                _isScreenCaptureAuthorized.value = false
                _connectionStateDescription.value = "Screen authorization lost - re-approve on child"
                childNegotiationInFlight = false
                return
            }
            videoTrack.setEnabled(true)
            ensureCaptureRunning()
            ensureTrackPublished(childPeerConnection, videoTrack)
            ensureLocalAudioTrack()?.let { track ->
                ensureTrackPublished(childPeerConnection, track)
            }
            createAndSendOffer()
        } catch (e: Exception) {
            Log.e(TAG, "Error resending offer for parent", e)
            childNegotiationInFlight = false
        }
    }

    /**
     * Creates the child microphone AudioTrack once (if RECORD_AUDIO is granted) and reuses it.
     * Returns null when the runtime permission is missing, so screen sharing still works.
     */
    private fun ensureLocalAudioTrack(): AudioTrack? {
        val factory = peerConnectionFactory ?: return null
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "RECORD_AUDIO not granted on child - audio monitoring unavailable")
            return null
        }
        localAudioTrack?.let { return it }
        return try {
            val audioConstraints = MediaConstraints().apply {
                optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            val source = audioSource ?: factory.createAudioSource(audioConstraints).also { audioSource = it }
            val track = factory.createAudioTrack("child_audio_track", source)
            track.setEnabled(true)
            localAudioTrack = track
            _isMicrophoneMonitoringActive.value = true
            Log.d(TAG, "Child microphone track created and attached")
            track
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create child audio track", e)
            null
        }
    }

    fun startChildMicrophoneStreaming(
        sessionId: String,
        targetParentDeviceId: String,
        childDeviceId: String
    ) {
        currentSessionId = sessionId
        remoteDeviceId = targetParentDeviceId
        localDeviceId = childDeviceId
        localRole = LocalRole.CHILD
        try {
            val factory = peerConnectionFactory ?: return

            val track = ensureLocalAudioTrack()
            if (track == null) {
                _isMicrophoneMonitoringActive.value = false
                _connectionStateDescription.value = "Mic permission missing on child device"
                return
            }
            track.setEnabled(true)
            _isMicrophoneMonitoringActive.value = true

            // If an audio sender is already attached to the live connection, DO NOT rebuild it.
            // Rebuilding used to drop the video track and killed the screen stream.
            val hasAudioSender = childPeerConnection?.senders?.any {
                it.track()?.kind() == "audio"
            } == true

            if (childPeerConnection != null && hasAudioSender) {
                Log.d(TAG, "Audio already published on the live connection - nothing to renegotiate")
                return
            }

            val cooldownActive = childNegotiationInFlight &&
                (System.currentTimeMillis() - lastOfferBuiltAtMs) < OFFER_REBUILD_COOLDOWN_MS
            if (cooldownActive) {
                Log.d(TAG, "Skipping mic renegotiation - an offer is already in flight")
                return
            }

            // ROOT CAUSE FIX (v10):
            // Starting the microphone used to close the child PeerConnection and build a
            // brand new one. That destroyed the already-negotiated screen transport, so the
            // parent was forced to rebuild too and both sides restarted ICE from scratch.
            // Adding a track to the LIVE connection and renegotiating is the correct way.
            val livePc = childPeerConnection
            if (livePc != null) {
                Log.d(TAG, "Adding microphone track to the LIVE child PeerConnection (no rebuild)")
                localVideoTrack?.let { v ->
                    v.setEnabled(true)
                    val hasVideoSender = livePc.senders.any { it.track()?.kind() == "video" }
                    if (!hasVideoSender) {
                        ensureTrackPublished(livePc, v)
                    }
                }
                ensureTrackPublished(livePc, track)
            } else {
                Log.d(TAG, "No child PeerConnection exists yet; creating one for the microphone stream")
                createChildPeerConnection(factory)
                localVideoTrack?.let { v ->
                    v.setEnabled(true)
                    ensureTrackPublished(childPeerConnection, v)
                }
                ensureTrackPublished(childPeerConnection, track)
            }

            createAndSendOffer()
            Log.d(TAG, "Child microphone capture active")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting child microphone streaming", e)
            _isMicrophoneMonitoringActive.value = false
        }
    }

    private fun createChildPeerConnection(factory: PeerConnectionFactory) {
        try {
            childPeerConnection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing existing child peer connection: ${e.message}")
        }
        childPeerConnection = null

        val isRelayOnly = _forceTurnRelay.value
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            iceTransportsType = if (isRelayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        childPeerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendIceCandidate(candidate)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "Child ICE Connection State: $newState")
                _connectionStateDescription.value = "Child WebRTC: $newState"
                iceStateLabel = newState.name
                publishTransportText()
                when (newState) {
                    PeerConnection.IceConnectionState.FAILED -> {
                        Log.w(TAG, "Child ICE state FAILED - initiating ICE restart")
                        triggerIceRestart("Child ICE FAILED")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "Child ICE state DISCONNECTED - scheduling restart if prolonged (>5s)")
                        scheduleDisconnectedIceRestart()
                    }
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        iceRestartAttempts = 0
                        lastSuccessfulConnectionAtMs = System.currentTimeMillis()
                        cancelDisconnectedIceRestart()
                    }
                    else -> {}
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d(TAG, "CHILD signaling state: $state")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "CHILD PeerConnection (DTLS) State: $newState")
                dtlsStateLabel = newState?.name ?: "-"
                publishTransportText()
                when (newState) {
                    PeerConnection.PeerConnectionState.FAILED -> {
                        Log.e(TAG, "CHILD transport FAILED - initiating ICE restart")
                        triggerIceRestart("Child DTLS/Transport FAILED")
                    }
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        cancelDisconnectedIceRestart()
                    }
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
        })
    }

    private fun createAndSendOffer() {
        val pc = childPeerConnection ?: return
        val state = try { pc.signalingState() } catch (_: Exception) { null }
        val now = System.currentTimeMillis()
        if (childNegotiationInFlight && (now - lastOfferBuiltAtMs) > 10_000L) {
            Log.w(TAG, "Previous offer negotiation timed out after 10s. Resetting inFlight flag.")
            childNegotiationInFlight = false
        }
        if (childNegotiationInFlight || state != PeerConnection.SignalingState.STABLE) {
            Log.d(TAG, "Offer skipped: negotiation already active (state=$state, inFlight=$childNegotiationInFlight)")
            return
        }
        val mediaConstraints = MediaConstraints()
        childNegotiationInFlight = true
        lastOfferBuiltAtMs = System.currentTimeMillis()

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Child Local SDP Offer set successfully, transmitting to Parent")
                        signaling.sendMessage(
                            SignalingMessage(
                                sessionId = currentSessionId,
                                senderDeviceId = localDeviceId,
                                targetDeviceId = remoteDeviceId,
                                messageType = SignalingType.WEBRTC_OFFER,
                                payload = desc.description
                            )
                        )
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set child local description: $error")
                        childNegotiationInFlight = false
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
                childNegotiationInFlight = false
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, mediaConstraints)
    }

    fun pauseChildScreenMirroring() {
        try {
            Log.d(TAG, "Pausing screen mirroring - keeping MediaProjection alive for instant one-time access")
            localVideoTrack?.setEnabled(false)
            // Keep the negotiated PeerConnection alive. Closing it here destroys ICE/DTLS
            // and makes one-time resume depend on a complete new signaling round-trip.
            _isScreenMirroringActive.value = false
            _connectionStateDescription.value = "Standby (Authorized)"
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing screen mirroring: ${e.message}")
        }
    }

    fun stopChildScreenMirroring(permanent: Boolean = false) {
        if (!permanent) {
            pauseChildScreenMirroring()
            return
        }
        try {
            Log.d(TAG, "Permanently stopping child screen mirroring & releasing MediaProjection")
            // Cleanly detach Surface from VirtualDisplay first to prevent BufferQueue abandonment
            try {
                videoCapturer?.let { capturer ->
                    val vdField = ScreenCapturerAndroid::class.java.getDeclaredField("virtualDisplay")
                    vdField.isAccessible = true
                    val vd = vdField.get(capturer) as? android.hardware.display.VirtualDisplay
                    vd?.setSurface(null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "VirtualDisplay surface detachment notice: ${e.message}")
            }

            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            videoSource?.dispose()
            videoSource = null

            // The helper MUST die together with the capturer it was bound to. Keeping it alive
            // was the reason the next capture session produced a permanently black stream.
            releaseSurfaceTextureHelper()
            isCapturingFrames = false
            capturedFrameCount = 0L
            localFrameProbeTrack = null

            localVideoTrack?.setEnabled(false)
            localVideoTrack?.dispose()
            localVideoTrack = null
            _localVideoTrack.value = null

            childPeerConnection?.close()
            childPeerConnection = null

            _isScreenCaptureAuthorized.value = false
            _isScreenMirroringActive.value = false
            _connectionStateDescription.value = "Screen Mirroring Stopped"
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen mirroring", e)
        }
    }

    fun stopChildMicrophoneStreaming() {
        try {
            localAudioTrack?.setEnabled(false)
            val audioSender = childPeerConnection?.senders?.firstOrNull { it.track()?.kind() == "audio" }
            try {
                audioSender?.setTrack(null, true)
            } catch (_: Exception) {}
            localAudioTrack?.dispose()
            localAudioTrack = null

            audioSource?.dispose()
            audioSource = null

            _isMicrophoneMonitoringActive.value = false
            Log.d(TAG, "Child microphone capture stopped cleanly")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping microphone streaming", e)
        }
    }

    fun startChildCameraStreaming(
        sessionId: String,
        targetParentDeviceId: String,
        childDeviceId: String
    ) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.e(TAG, "CAMERA permission not granted on child")
            _streamError.value = "Camera permission not granted on child"
            _connectionStateDescription.value = "Camera Permission Required"
            return
        }

        currentSessionId = sessionId
        remoteDeviceId = targetParentDeviceId
        localDeviceId = childDeviceId
        localRole = LocalRole.CHILD
        _isCameraActive.value = true
        _connectionStateDescription.value = "Starting Remote Camera..."

        try {
            val factory = peerConnectionFactory ?: return

            // Stop previous camera capture session cleanly if any
            stopChildCameraInternal()

            val enumerator = Camera2Enumerator(context)
            val deviceNames = enumerator.deviceNames
            var chosenCamera: String? = null
            for (name in deviceNames) {
                if (if (isFrontCamera) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)) {
                    chosenCamera = name
                    break
                }
            }
            if (chosenCamera == null && deviceNames.isNotEmpty()) {
                chosenCamera = deviceNames[0]
            }

            if (chosenCamera == null) {
                Log.e(TAG, "No camera found on child device")
                _streamError.value = "No camera found on device"
                _connectionStateDescription.value = "No Camera Found"
                return
            }

            val eventsHandler = object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(errorDescription: String?) {
                    Log.e(TAG, "Camera error: $errorDescription")
                    _streamError.value = "Camera error: $errorDescription"
                    _connectionStateDescription.value = "Camera Error: $errorDescription"
                }

                override fun onCameraDisconnected() {
                    Log.w(TAG, "Camera disconnected")
                }

                override fun onCameraFreezed(errorDescription: String?) {
                    Log.w(TAG, "Camera freezed: $errorDescription")
                }

                override fun onCameraOpening(cameraName: String?) {
                    Log.d(TAG, "Camera opening: $cameraName")
                }

                override fun onFirstFrameAvailable() {
                    Log.i(TAG, "CHILD CAMERA FIRST FRAME AVAILABLE")
                    isCapturingFrames = true
                }

                override fun onCameraClosed() {
                    Log.d(TAG, "Camera closed")
                }
            }

            val capturer = enumerator.createCapturer(chosenCamera, eventsHandler)
            cameraCapturer = capturer

            val helper = SurfaceTextureHelper.create(
                "CameraCaptureThread-" + System.currentTimeMillis(),
                rootEglBase.eglBaseContext
            )
            cameraSurfaceTextureHelper = helper

            val videoSrc = factory.createVideoSource(false)
            cameraVideoSource = videoSrc

            capturer.initialize(helper, context, videoSrc.capturerObserver)
            capturer.startCapture(1280, 720, 30)

            val track = factory.createVideoTrack("child_camera_track", videoSrc)
            track.setEnabled(true)
            cameraVideoTrack = track
            localVideoTrack = track
            _localVideoTrack.value = track
            attachLocalFrameProbe(track)

            // Loopback display
            if (localDeviceId.isNotEmpty() && localDeviceId == remoteDeviceId) {
                _remoteVideoTrack.value = track
                _connectionStateDescription.value = "Connected (Live Camera Streaming)"
            }

            val livePc = childPeerConnection
            val resumeState = try { livePc?.signalingState() } catch (_: Exception) { null }
            if (livePc == null || resumeState == PeerConnection.SignalingState.CLOSED) {
                createChildPeerConnection(factory)
                ensureTrackPublished(childPeerConnection, track)
                ensureLocalAudioTrack()?.let { ensureTrackPublished(childPeerConnection, it) }
            } else {
                val videoSender = livePc.senders.firstOrNull { it.track()?.kind() == "video" }
                if (videoSender != null) {
                    videoSender.setTrack(track, true)
                    Log.d(TAG, "Swapped video track on existing PeerConnection to camera")
                } else {
                    ensureTrackPublished(livePc, track)
                }
                ensureLocalAudioTrack()?.let { ensureTrackPublished(livePc, it) }
            }

            createAndSendOffer()
            startChildStatsMonitor()
            _connectionStateDescription.value = "Camera Live Streaming"
        } catch (e: Exception) {
            Log.e(TAG, "Error starting child camera", e)
            _streamError.value = "Camera error: ${e.message}"
            _connectionStateDescription.value = "Camera Error: ${e.message}"
        }
    }

    fun switchChildCamera() {
        val capturer = cameraCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                isFrontCamera = isFront
                Log.d(TAG, "Child camera switched. isFront: $isFront")
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.e(TAG, "Child camera switch error: $errorDescription")
            }
        })
    }

    fun stopChildCameraStreaming() {
        try {
            _isCameraActive.value = false
            stopChildCameraInternal()

            val livePc = childPeerConnection
            val videoSender = livePc?.senders?.firstOrNull { it.track()?.kind() == "video" }
            if (_isScreenMirroringActive.value && localVideoTrack != null && localVideoTrack != cameraVideoTrack) {
                videoSender?.setTrack(localVideoTrack, true)
            } else {
                try {
                    videoSender?.setTrack(null, true)
                } catch (_: Exception) {}
                _localVideoTrack.value = null
            }

            if (!_isScreenMirroringActive.value && !_isMicrophoneMonitoringActive.value) {
                livePc?.close()
                childPeerConnection = null
            }
            Log.d(TAG, "Child camera stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping child camera", e)
        }
    }

    private fun stopChildCameraInternal() {
        try {
            cameraCapturer?.stopCapture()
        } catch (_: Exception) {}
        try {
            cameraCapturer?.dispose()
        } catch (_: Exception) {}
        cameraCapturer = null

        try {
            cameraVideoSource?.dispose()
        } catch (_: Exception) {}
        cameraVideoSource = null

        try {
            cameraSurfaceTextureHelper?.stopListening()
            cameraSurfaceTextureHelper?.dispose()
        } catch (_: Exception) {}
        cameraSurfaceTextureHelper = null

        try {
            cameraVideoTrack?.setEnabled(false)
            cameraVideoTrack?.dispose()
        } catch (_: Exception) {}
        cameraVideoTrack = null
    }

    // =========================================================================
    // PARENT ROLE: VIEWER / RECEIVER (Remote Video & Remote Audio)
    // =========================================================================

    fun startParentScreenSession(
        sessionId: String,
        targetChildDeviceId: String,
        parentDeviceId: String
    ) {
        currentSessionId = sessionId
        remoteDeviceId = targetChildDeviceId
        localDeviceId = parentDeviceId
        localRole = LocalRole.PARENT
        _isScreenMirroringActive.value = true
        _connectionStateDescription.value = "Requesting Child Screen..."
        parentHasAppliedOffer = false

        // If this device is also capturing locally in loopback test, bind track immediately
        if (targetChildDeviceId == parentDeviceId && localVideoTrack != null) {
            Log.d(TAG, "Local screen capturer active on this device in loopback - attaching directly to preview")
            _remoteVideoTrack.value = localVideoTrack
            _connectionStateDescription.value = "Connected (Live Screen Streaming)"
        } else {
            _remoteVideoTrack.value = null
        }

        signaling.registerDevice(parentDeviceId)
        signaling.joinSession(sessionId)

        if (parentPeerConnection == null || parentPeerConnection?.signalingState() == PeerConnection.SignalingState.CLOSED) {
            createParentPeerConnection()
        }

        // Send START_SCREEN request to Child device
        signaling.sendMessage(
            SignalingMessage(
                sessionId = sessionId,
                senderDeviceId = parentDeviceId,
                targetDeviceId = targetChildDeviceId,
                messageType = SignalingType.START_SCREEN,
                payload = "REQUEST_SCREEN"
            )
        )

        // Resend request periodically while waiting for remote video track
        scope.launch {
            var retry = 0
            while (_isScreenMirroringActive.value &&
                _remoteVideoTrack.value == null &&
                !parentHasAppliedOffer &&
                retry < 12
            ) {
                delay(5000)
                if (_isScreenMirroringActive.value &&
                    _remoteVideoTrack.value == null &&
                    !parentHasAppliedOffer
                ) {
                    retry++
                    Log.d(TAG, "Re-notifying child device of screen request (retry $retry)")
                    signaling.sendMessage(
                        SignalingMessage(
                            sessionId = sessionId,
                            senderDeviceId = parentDeviceId,
                            targetDeviceId = targetChildDeviceId,
                            messageType = SignalingType.START_SCREEN,
                            payload = "REQUEST_SCREEN"
                        )
                    )
                }
            }
        }
    }

    fun startParentCameraSession(
        sessionId: String,
        targetChildDeviceId: String,
        parentDeviceId: String
    ) {
        clearStreamError()
        currentSessionId = sessionId
        remoteDeviceId = targetChildDeviceId
        localDeviceId = parentDeviceId
        localRole = LocalRole.PARENT
        _isCameraActive.value = true
        _connectionStateDescription.value = "Requesting Remote Camera..."
        parentHasAppliedOffer = false

        signaling.registerDevice(parentDeviceId)
        signaling.joinSession(sessionId)

        if (parentPeerConnection == null || parentPeerConnection?.signalingState() == PeerConnection.SignalingState.CLOSED) {
            createParentPeerConnection()
        }

        // Send START_CAMERA request to Child device
        signaling.sendMessage(
            SignalingMessage(
                sessionId = sessionId,
                senderDeviceId = parentDeviceId,
                targetDeviceId = targetChildDeviceId,
                messageType = SignalingType.START_CAMERA,
                payload = "REQUEST_CAMERA"
            )
        )

        scope.launch {
            var retry = 0
            while (_isCameraActive.value &&
                _remoteVideoTrack.value == null &&
                !parentHasAppliedOffer &&
                retry < 8
            ) {
                delay(4000)
                if (_isCameraActive.value &&
                    _remoteVideoTrack.value == null &&
                    !parentHasAppliedOffer
                ) {
                    retry++
                    Log.d(TAG, "Re-notifying child device of camera request (retry $retry)")
                    signaling.sendMessage(
                        SignalingMessage(
                            sessionId = sessionId,
                            senderDeviceId = parentDeviceId,
                            targetDeviceId = targetChildDeviceId,
                            messageType = SignalingType.START_CAMERA,
                            payload = "REQUEST_CAMERA"
                        )
                    )
                }
            }
        }
    }

    fun switchParentCamera() {
        signaling.sendMessage(
            SignalingMessage(
                sessionId = currentSessionId,
                senderDeviceId = localDeviceId,
                targetDeviceId = remoteDeviceId,
                messageType = SignalingType.SWITCH_CAMERA,
                payload = "SWITCH"
            )
        )
    }

    fun stopParentCameraSession() {
        _isCameraActive.value = false
        signaling.sendMessage(
            SignalingMessage(
                sessionId = currentSessionId,
                senderDeviceId = localDeviceId,
                targetDeviceId = remoteDeviceId,
                messageType = SignalingType.STOP_CAMERA
            )
        )
        if (!_isScreenMirroringActive.value) {
            _remoteVideoTrack.value = null
        }
        if (!_isScreenMirroringActive.value && !_isMicrophoneMonitoringActive.value) {
            cleanupSession()
        }
    }

    fun stopParentScreenSession() {
        _isScreenMirroringActive.value = false
        signaling.sendMessage(
            SignalingMessage(
                sessionId = currentSessionId,
                senderDeviceId = localDeviceId,
                targetDeviceId = remoteDeviceId,
                messageType = SignalingType.STOP_SCREEN
            )
        )
        if (!_isCameraActive.value) {
            _remoteVideoTrack.value = null
        }
        if (!_isCameraActive.value && !_isMicrophoneMonitoringActive.value) {
            cleanupSession()
        }
    }

    fun stopParentAudioSession() {
        _isMicrophoneMonitoringActive.value = false
        signaling.sendMessage(
            SignalingMessage(
                sessionId = currentSessionId,
                senderDeviceId = localDeviceId,
                targetDeviceId = remoteDeviceId,
                messageType = SignalingType.STOP_AUDIO
            )
        )
        if (!_isScreenMirroringActive.value && !_isCameraActive.value) {
            cleanupSession()
        }
    }

    fun startParentAudioSession(
        sessionId: String,
        targetChildDeviceId: String,
        parentDeviceId: String
    ) {
        currentSessionId = sessionId
        remoteDeviceId = targetChildDeviceId
        localDeviceId = parentDeviceId
        localRole = LocalRole.PARENT
        _isMicrophoneMonitoringActive.value = true

        signaling.registerDevice(parentDeviceId)
        signaling.joinSession(sessionId)

        if (parentPeerConnection == null || parentPeerConnection?.signalingState() == PeerConnection.SignalingState.CLOSED) {
            createParentPeerConnection()
        }

        // Send START_AUDIO request to Child device
        signaling.sendMessage(
            SignalingMessage(
                sessionId = sessionId,
                senderDeviceId = parentDeviceId,
                targetDeviceId = targetChildDeviceId,
                messageType = SignalingType.START_AUDIO,
                payload = "REQUEST_AUDIO"
            )
        )
    }

    private fun createParentPeerConnection() {
        val factory = peerConnectionFactory ?: return

        try {
            parentPeerConnection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing existing parent peer connection: ${e.message}")
        }
        parentPeerConnection = null
        lastAppliedRemoteOfferSdp = ""
        iceStateLabel = "-"
        dtlsStateLabel = "-"
        candidatePairLabel = "-"
        publishTransportText()
        // Start polling immediately, not only once DTLS reports CONNECTED. If DTLS never
        // completes we still need to see the ICE state and the selected candidate pair.
        startReceiveStatsMonitor()

        val isRelayOnly = _forceTurnRelay.value
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            iceTransportsType = if (isRelayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }

        parentPeerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendIceCandidate(candidate)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "Parent WebRTC ICE State: $newState")
                iceStateLabel = newState.name
                publishTransportText()
                scope.launch {
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            iceRestartAttempts = 0
                            lastSuccessfulConnectionAtMs = System.currentTimeMillis()
                            _connectionStateDescription.value = "Connected (WebRTC P2P)"
                            cancelDisconnectedIceRestart()
                        }
                        PeerConnection.IceConnectionState.CHECKING -> {
                            _connectionStateDescription.value = "Connecting via STUN/TURN..."
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _connectionStateDescription.value = "Connection Lost / Reconnecting"
                            scheduleDisconnectedIceRestart()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            _connectionStateDescription.value = "Connection Failed - Requesting ICE Restart"
                            triggerIceRestart("Parent ICE FAILED")
                        }
                        else -> {
                            _connectionStateDescription.value = newState.name
                        }
                    }
                }
            }

            // CRITICAL: onIceConnectionChange only reports ICE connectivity. SRTP media
            // cannot flow until the DTLS handshake also completes, and that is reported
            // ONLY here. Without this callback the UI happily showed "Connected" while
            // DTLS was still failing, which is why neither video NOR audio ever arrived.
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "Parent PeerConnection (DTLS) State: $newState")
                dtlsStateLabel = newState?.name ?: "-"
                publishTransportText()
                scope.launch {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            iceRestartAttempts = 0
                            lastSuccessfulConnectionAtMs = System.currentTimeMillis()
                            _connectionStateDescription.value = "Connected (Media Flowing)"
                            cancelDisconnectedIceRestart()
                            startReceiveStatsMonitor()
                        }
                        PeerConnection.PeerConnectionState.CONNECTING -> {
                            _connectionStateDescription.value = "DTLS Handshake..."
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            _connectionStateDescription.value = "DTLS/Transport Failed - Requesting ICE restart"
                            triggerIceRestart("Parent DTLS FAILED")
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            _connectionStateDescription.value = "Media Disconnected"
                            scheduleDisconnectedIceRestart()
                        }
                        else -> {
                            if (newState != null) {
                                _connectionStateDescription.value = newState.name
                            }
                        }
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "Parent received remote Child VideoTrack")
                    attachRemoteFrameProbe(track)
                    scope.launch(Dispatchers.Main) {
                        _remoteVideoTrack.value = track
                        _connectionStateDescription.value = "Track received - waiting for frames"
                    }
                    track.setEnabled(true)
                } else if (track is AudioTrack) {
                    Log.d(TAG, "Parent received remote Child AudioTrack - routing to audio output")
                    track.setEnabled(true)
                    track.setVolume(1.0)
                    scope.launch(Dispatchers.Main) {
                        _isMicrophoneMonitoringActive.value = true
                    }
                    try {
                        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                        audioManager.isSpeakerphoneOn = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set audio routing: ${e.message}")
                    }
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    attachRemoteFrameProbe(track)
                    scope.launch(Dispatchers.Main) {
                        _remoteVideoTrack.value = track
                        _connectionStateDescription.value = "Track received - waiting for frames"
                    }
                    track.setEnabled(true)
                } else if (track is AudioTrack) {
                    track.setEnabled(true)
                    track.setVolume(1.0)
                    scope.launch(Dispatchers.Main) {
                        _isMicrophoneMonitoringActive.value = true
                    }
                    try {
                        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                        audioManager.isSpeakerphoneOn = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set audio routing: ${e.message}")
                    }
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { track ->
                    track.setEnabled(true)
                    scope.launch(Dispatchers.Main) {
                        _remoteVideoTrack.value = track
                        _isScreenMirroringActive.value = true
                        _connectionStateDescription.value = "Track OK - waiting for media"
                    }
                }
                stream?.audioTracks?.firstOrNull()?.let { track ->
                    track.setEnabled(true)
                    track.setVolume(1.0)
                    scope.launch(Dispatchers.Main) {
                        _isMicrophoneMonitoringActive.value = true
                    }
                    try {
                        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                        audioManager.isSpeakerphoneOn = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set audio routing: ${e.message}")
                    }
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })

        // NOTE: Do NOT pre-create recvonly transceivers here.
        //
        // In Unified Plan the answerer must let setRemoteDescription() build its
        // receivers from the offer's m-lines. Pre-adding transceivers creates unassociated
        // m-lines that libwebrtc then pairs positionally with the incoming offer. When the
        // pairing does not line up, the child's video m-line gets associated with a
        // transceiver that is never rendered: ICE reaches Connected, onTrack() still fires,
        // the UI shows LIVE SCREEN, but the sink receives ZERO frames (black screen).
        // setRemoteDescription() already creates a receiver for every m-line the child
        // offers, so nothing needs to be declared up front.
    }

    private fun sanitizeSdp(rawSdp: String): String {
        val clean = rawSdp.trim().removeSurrounding("\"").trim()
        val lines = clean.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val filtered = lines.map { it.trim() }.filter { it.isNotEmpty() && it.contains("=") }
        return filtered.joinToString("\r\n", postfix = "\r\n")
    }

    private fun handleRemoteOffer(message: SignalingMessage) {
        val sanitized = sanitizeSdp(message.payload)
        if (sanitized.isBlank() || !sanitized.startsWith("v=")) {
            Log.w(TAG, "Ignoring remote SDP Offer with invalid payload (length=${sanitized.length}) from ${message.senderDeviceId}")
            return
        }

        // Ignore a repeat of an offer we already applied. The child re-publishes its offer
        // whenever the parent re-requests, and closing a healthy PeerConnection to re-apply
        // the same SDP is what produced the CLOSED / black-screen state.
        if (sanitized == lastAppliedRemoteOfferSdp && parentPeerConnection?.remoteDescription != null) {
            Log.d(TAG, "Ignoring duplicate remote SDP Offer - already applied on live connection")
            return
        }

        Log.d(TAG, "Handling verified remote SDP Offer (${sanitized.length} bytes) from ${message.senderDeviceId}")
        currentSessionId = message.sessionId
        remoteDeviceId = message.senderDeviceId
        _isScreenMirroringActive.value = true
        _connectionStateDescription.value = "Connecting Child Stream..."

        // CRITICAL: never tear down a PeerConnection whose DTLS handshake is already
        // running or complete. The old code rebuilt the parent PC for EVERY offer after
        // the first one, which reset DTLS mid-handshake. ICE still reported Connected on
        // stale pairs and onTrack() still fired from the SDP, so the UI showed LIVE SCREEN
        // while zero SRTP bytes (video AND audio) ever arrived.
        // A different SDP on an existing connection is a valid renegotiation (for example,
        // adding audio). Reuse the PeerConnection and apply it instead of discarding it.

        // ROOT CAUSE FIX (v10):
        // The old code rebuilt the parent PeerConnection for EVERY offer after the first
        // one (condition: remoteDescription != null). The child re-publishes its offer on
        // every resend tick and again when the microphone is started, so the parent PC was
        // destroyed and recreated over and over. Each rebuild threw away all gathered ICE
        // candidates and restarted gathering from zero, which is why the on-screen badge
        // showed "ICE:- DTLS:- PAIR:-" forever while onTrack() still fired from the SDP.
        //
        // Applying a second offer to an EXISTING PeerConnection is exactly what WebRTC
        // renegotiation is for. Only build a new PC when there genuinely is none, or when
        // the old one is CLOSED and can no longer be used.
        val currentSignalingState = try {
            parentPeerConnection?.signalingState()
        } catch (e: Exception) {
            null
        }
        if (parentPeerConnection == null ||
            currentSignalingState == PeerConnection.SignalingState.CLOSED
        ) {
            Log.d(TAG, "No usable parent PeerConnection (state=$currentSignalingState); creating a new one")
            try {
                parentPeerConnection?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing parentPeerConnection: ${e.message}")
            }
            parentPeerConnection = null
            createParentPeerConnection()
        } else {
            Log.d(TAG, "Reusing the live parent PeerConnection for renegotiation (state=$currentSignalingState). " +
                "Gathered ICE candidates and the DTLS transport are preserved.")
            startReceiveStatsMonitor()
        }

        lastAppliedRemoteOfferSdp = sanitized
        val sdp = SessionDescription(SessionDescription.Type.OFFER, sanitized)
        setRemoteOfferWithRetry(sdp, retryCount = 0)
    }

    private fun setRemoteOfferWithRetry(sdp: SessionDescription, retryCount: Int) {
        parentPeerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote SDP Offer set successfully on Parent, draining pending ICE and creating SDP Answer")
                parentHasAppliedOffer = true
                drainPendingIceCandidates()
                createAndSendAnswer()
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote offer: $error")
                if (retryCount == 0) {
                    Log.w(TAG, "Closing and recreating parentPeerConnection via createParentPeerConnection() to retry setRemoteDescription() once: $error")
                    try {
                        parentPeerConnection?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing parentPeerConnection on retry: ${e.message}")
                    }
                    parentPeerConnection = null
                    createParentPeerConnection()
                    setRemoteOfferWithRetry(sdp, retryCount = 1)
                } else {
                    _connectionStateDescription.value = "SDP Offer Error: $error"
                }
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    private fun createAndSendAnswer() {
        // Unified Plan: directions come from the transceivers, not from legacy constraints.
        val mediaConstraints = MediaConstraints()

        parentPeerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                parentPeerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local SDP Answer set, transmitting to Child")
                        signaling.sendMessage(
                            SignalingMessage(
                                sessionId = currentSessionId,
                                senderDeviceId = localDeviceId,
                                targetDeviceId = remoteDeviceId,
                                messageType = SignalingType.WEBRTC_ANSWER,
                                payload = desc.description
                            )
                        )
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set local answer: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create answer: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, mediaConstraints)
    }

    private fun handleRemoteAnswer(message: SignalingMessage) {
        val sanitized = sanitizeSdp(message.payload)
        if (sanitized.isBlank() || !sanitized.startsWith("v=")) {
            Log.w(TAG, "Ignoring remote SDP Answer with invalid payload (length=${sanitized.length}) from ${message.senderDeviceId}")
            return
        }

        val pc = childPeerConnection
        if (pc == null) {
            Log.w(TAG, "Ignoring SDP Answer - child has no PeerConnection")
            return
        }

        // CRITICAL: an answer may only be applied to the exact PeerConnection that is
        // still waiting for it (HAVE_LOCAL_OFFER). The child rebuilds its PeerConnection
        // whenever the parent re-requests the screen, so a late answer from a PREVIOUS
        // negotiation used to be pushed onto the new connection. The result was a
        // PeerConnection whose DTLS role/fingerprint never matched, so no SRTP key was
        // ever derived: ICE connected, onTrack fired on the parent, and neither video nor
        // audio ever flowed. Applying an answer in STABLE state fails the same way.
        val signalingState = try {
            pc.signalingState()
        } catch (e: Exception) {
            null
        }
        if (signalingState != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            Log.w(TAG, "Ignoring stale SDP Answer - child PeerConnection is in state " +
                "$signalingState, not HAVE_LOCAL_OFFER. This answer belongs to an older offer.")
            return
        }

        Log.d(TAG, "Handling verified remote SDP Answer (${sanitized.length} bytes) from ${message.senderDeviceId}")
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, sanitized)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote SDP Answer set successfully on Child, draining pending ICE")
                // Negotiation completed: clear the in-flight flag so a genuinely new
                // request can renegotiate. Previously this was only cleared on FAILURE,
                // so the flag stayed true forever and the cooldown logic misbehaved.
                childNegotiationInFlight = false
                drainPendingIceCandidates()
            }
            override fun onSetFailure(error: String?) {
                childNegotiationInFlight = false
                Log.e(TAG, "Failed to set remote answer on Child: $error")
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val payload = "${candidate.sdpMid};${candidate.sdpMLineIndex};${candidate.sdp}"
        signaling.sendMessage(
            SignalingMessage(
                sessionId = currentSessionId,
                senderDeviceId = localDeviceId,
                targetDeviceId = remoteDeviceId,
                messageType = SignalingType.ICE_CANDIDATE,
                payload = payload
            )
        )
    }

    /**
     * Polls real transport stats so a black screen can be diagnosed on the device.
     *
     * - PAIR stays "-"          -> ICE never selected a route at all.
     * - PAIR relay>relay, 0 B  -> TURN server accepted the allocation but relays nothing.
     * - bytes rising, RX 0     -> media arrives but the decoder drops every frame.
     */
    private var receiveStatsJob: kotlinx.coroutines.Job? = null
    private var lastRxFrameCount: Long = 0L
    private var lastRxFrameChangeTimeMs: Long = 0L
    private var lastSinkReattachAtMs: Long = 0L

    private fun startReceiveStatsMonitor() {
        receiveStatsJob?.cancel()
        lastRxFrameCount = 0L
        lastRxFrameChangeTimeMs = System.currentTimeMillis()
        receiveStatsJob = scope.launch {
            while (isActive) {
                val pc = parentPeerConnection
                if (pc == null) {
                    iceStateLabel = "NO-PC"
                    dtlsStateLabel = "NO-PC"
                    publishTransportText()
                    delay(3000)
                    continue
                }

                // Poll the transport states DIRECTLY off the PeerConnection instead of
                // relying only on the observer callbacks. If the PC is ever rebuilt, the
                // callbacks of the old object stop firing and the labels would stay "-"
                // forever, hiding the real state from us.
                try {
                    iceStateLabel = pc.iceConnectionState()?.name ?: "-"
                    dtlsStateLabel = pc.connectionState()?.name ?: "-"
                    publishTransportText()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not poll transport state: ${e.message}")
                }

                try {
                    pc.getStats { report ->
                        // Map candidate ids to type, protocol, and relayProtocol
                        val candidateTypes = HashMap<String, String>()
                        val candidateProtocols = HashMap<String, String>()
                        val candidateRelayProtocols = HashMap<String, String>()

                        for (stat in report.statsMap.values) {
                            if (stat.type == "local-candidate" || stat.type == "remote-candidate") {
                                stat.members["candidateType"]?.let { candidateTypes[stat.id] = it.toString() }
                                stat.members["protocol"]?.let { candidateProtocols[stat.id] = it.toString() }
                                stat.members["relayProtocol"]?.let { candidateRelayProtocols[stat.id] = it.toString() }
                            }
                        }

                        var selectedLocalType = "?"
                        var selectedRemoteType = "?"
                        var selectedLocalProto = ""
                        var selectedRemoteProto = ""
                        var totalBytes = 0L
                        var totalPackets = 0L
                        var totalAudioBytes = 0L
                        var totalAudioPackets = 0L
                        var totalFramesReceived = 0L
                        var totalFramesDecoded = 0L
                        var statFrameWidth = 0
                        var statFrameHeight = 0
                        var lastPacketTimeMs = 0L

                        for (stat in report.statsMap.values) {
                            if (stat.type == "candidate-pair") {
                                val nominated = stat.members["nominated"]
                                val pairState = stat.members["state"]?.toString() ?: ""
                                val selected = stat.members["selected"] == true
                                if (nominated == true || pairState == "succeeded" || selected) {
                                    val localId = stat.members["localCandidateId"]?.toString()
                                    val remoteId = stat.members["remoteCandidateId"]?.toString()
                                    selectedLocalType = candidateTypes[localId] ?: "?"
                                    selectedRemoteType = candidateTypes[remoteId] ?: "?"
                                    selectedLocalProto = candidateProtocols[localId] ?: ""
                                    selectedRemoteProto = candidateProtocols[remoteId] ?: ""
                                    candidatePairLabel = "$selectedLocalType>$selectedRemoteType"
                                    _candidatePairLabelFlow.value = candidatePairLabel
                                    _candidateTypeDetailFlow.value = "Local: $selectedLocalType ($selectedLocalProto) | Remote: $selectedRemoteType ($selectedRemoteProto)"
                                }
                            }
                            if (stat.type == "inbound-rtp") {
                                val kind = stat.members["kind"]?.toString() ?: stat.members["mediaType"]?.toString() ?: ""
                                val bytes = stat.members["bytesReceived"]
                                if (bytes is Number) {
                                    totalBytes += bytes.toLong()
                                    if (kind == "audio") totalAudioBytes += bytes.toLong()
                                }
                                val packets = stat.members["packetsReceived"]
                                if (packets is Number) {
                                    totalPackets += packets.toLong()
                                    if (kind == "audio") totalAudioPackets += packets.toLong()
                                }
                                val rxFrames = stat.members["framesReceived"]
                                if (rxFrames is Number) {
                                    totalFramesReceived += rxFrames.toLong()
                                }
                                val decFrames = stat.members["framesDecoded"]
                                if (decFrames is Number) {
                                    totalFramesDecoded += decFrames.toLong()
                                }
                                val w = stat.members["frameWidth"]
                                if (w is Number) statFrameWidth = w.toInt()
                                val h = stat.members["frameHeight"]
                                if (h is Number) statFrameHeight = h.toInt()
                                val ts = stat.members["lastPacketReceivedTimestamp"]
                                if (ts is Number) lastPacketTimeMs = ts.toLong()
                            }
                        }

                        val text = when {
                            totalBytes >= 1_048_576L -> (totalBytes / 1_048_576L).toString() + " MB"
                            totalBytes >= 1024L -> (totalBytes / 1024L).toString() + " KB"
                            else -> totalBytes.toString() + " B"
                        }
                        _mediaBytesText.value = text
                        publishTransportText()

                        val hasRelay = selectedLocalType.equals("relay", ignoreCase = true) ||
                                       selectedRemoteType.equals("relay", ignoreCase = true)
                        _isRelayActiveFlow.value = hasRelay

                        // Log candidate pair explicitly:
                        Log.d(TAG, "WEBRTC SELECTED CANDIDATE: local=$selectedLocalType ($selectedLocalProto), remote=$selectedRemoteType ($selectedRemoteProto) | pair=$candidatePairLabel | RelayActive=$hasRelay")

                        // Cross-network verification: only claim success if relay is active and frames increase
                        val isStreaming = remoteFrameCount > 0L
                        _isCrossNetworkSuccessFlow.value = (hasRelay && isStreaming)

                        val statusText = when {
                            hasRelay && isStreaming -> "Cross-Network Relay: SUCCESS (Relay: $selectedLocalType > $selectedRemoteType | RX: $remoteFrameCount frames)"
                            hasRelay -> "TURN Relay Connected (Waiting for video frames)"
                            selectedLocalType == "host" && selectedRemoteType == "host" -> "Local P2P (Direct Wi-Fi: host > host - NOT Relay)"
                            selectedLocalType == "srflx" || selectedRemoteType == "srflx" -> "Direct P2P via STUN (srflx - NOT Relay)"
                            selectedLocalType != "?" -> "Direct P2P ($selectedLocalType > $selectedRemoteType - NOT Relay)"
                            else -> "Candidate Pair Pending"
                        }
                        _crossNetworkStatusFlow.value = statusText

                        val agoSec = if (lastPacketTimeMs > 0L) (System.currentTimeMillis() - lastPacketTimeMs) / 1000L else -1L
                        val lastFrameStr = when {
                            agoSec in 0..120 -> "${agoSec}s ago"
                            lastPacketTimeMs > 0L -> java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastPacketTimeMs))
                            remoteFrameCount > 0L -> "Active"
                            else -> "-"
                        }

                        val gatheringState = try { pc.iceGatheringState()?.name ?: "-" } catch (_: Exception) { "-" }
                        val iceState = try { pc.iceConnectionState()?.name ?: "-" } catch (_: Exception) { "-" }
                        val pcState = try { pc.connectionState()?.name ?: "-" } catch (_: Exception) { "-" }

                        val fallbackW = _remoteFrameSize.value.substringBefore("x").toIntOrNull() ?: 0
                        val fallbackH = _remoteFrameSize.value.substringAfter("x").toIntOrNull() ?: 0

                        _safeDiagnostics.value = SafeWebRtcDiagnostics(
                            iceGatheringState = gatheringState,
                            iceConnectionState = iceState,
                            peerConnectionState = pcState,
                            dtlsState = pcState,
                            localCandidateType = selectedLocalType,
                            remoteCandidateType = selectedRemoteType,
                            selectedProtocol = if (selectedLocalProto.isNotEmpty()) "$selectedLocalProto/$selectedRemoteProto" else "-",
                            relaySelected = hasRelay,
                            packetsReceived = totalPackets,
                            bytesReceived = totalBytes,
                            bytesReceivedFormatted = text,
                            audioBytesReceived = totalAudioBytes,
                            audioPacketsReceived = totalAudioPackets,
                            framesReceived = if (totalFramesReceived > 0L) totalFramesReceived else remoteFrameCount,
                            framesDecoded = if (totalFramesDecoded > 0L) totalFramesDecoded else remoteFrameCount,
                            videoWidth = if (statFrameWidth > 0) statFrameWidth else fallbackW,
                            videoHeight = if (statFrameHeight > 0) statFrameHeight else fallbackH,
                            lastFrameTime = lastFrameStr
                        )

                        // 8-second decoded RX frame watchdog
                        val currentFrames = remoteFrameCount
                        val now = System.currentTimeMillis()
                        if (currentFrames > 0L) {
                            if (currentFrames > lastRxFrameCount) {
                                lastRxFrameCount = currentFrames
                                lastRxFrameChangeTimeMs = now
                            } else {
                                val stallMs = now - lastRxFrameChangeTimeMs
                                if (stallMs >= 8_000L && (now - lastSinkReattachAtMs >= 10_000L)) {
                                    Log.w(TAG, "Decoded RX frames stopped for ${stallMs / 1000}s ($currentFrames frames). Detaching and reattaching renderer sink without restarting MediaProjection...")
                                    lastSinkReattachAtMs = now
                                    lastRxFrameChangeTimeMs = now
                                    reattachRendererSink()
                                }
                            }
                        } else {
                            lastRxFrameChangeTimeMs = now
                        }

                        Log.d(TAG, "PARENT TRANSPORT: " + _transportText.value +
                            " | bytes: " + text + " | decoded frames: " + remoteFrameCount + " | $statusText")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Stats poll notice: ${e.message}")
                }
                delay(3000)
            }
        }
    }

    private fun handleRemoteIceCandidate(message: SignalingMessage) {
        try {
            val parts = message.payload.split(";", limit = 3)
            if (parts.size >= 3) {
                val sdpMid = if (parts[0].isEmpty() || parts[0] == "null") null else parts[0]
                val sdpMLineIndex = parts[1].toIntOrNull() ?: 0
                val sdp = parts[2]

                val candidateKey = "$sdpMid:$sdpMLineIndex:$sdp"
                if (!processedCandidateSdpSet.add(candidateKey)) {
                    return
                }

                val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)

                // Route by ROLE with fallback to PC state
                var added = false
                if (localRole == LocalRole.PARENT || (localRole == LocalRole.NONE && parentPeerConnection?.remoteDescription != null)) {
                    if (parentPeerConnection?.remoteDescription != null) {
                        parentPeerConnection?.addIceCandidate(candidate)
                        added = true
                    }
                }
                if (localRole == LocalRole.CHILD || (localRole == LocalRole.NONE && childPeerConnection?.remoteDescription != null)) {
                    if (childPeerConnection?.remoteDescription != null) {
                        childPeerConnection?.addIceCandidate(candidate)
                        added = true
                    }
                }
                if (!added) {
                    Log.d(TAG, "Buffering ICE candidate until remote description is set")
                    pendingIceCandidates.add(candidate)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling remote ICE candidate", e)
        }
    }

    private fun drainPendingIceCandidates() {
        synchronized(pendingIceCandidates) {
            val iterator = pendingIceCandidates.iterator()
            while (iterator.hasNext()) {
                val cand = iterator.next()
                var consumed = false
                if (localRole == LocalRole.PARENT || (localRole == LocalRole.NONE && parentPeerConnection?.remoteDescription != null)) {
                    if (parentPeerConnection?.remoteDescription != null) {
                        parentPeerConnection?.addIceCandidate(cand)
                        consumed = true
                    }
                }
                if (localRole == LocalRole.CHILD || (localRole == LocalRole.NONE && childPeerConnection?.remoteDescription != null)) {
                    if (childPeerConnection?.remoteDescription != null) {
                        childPeerConnection?.addIceCandidate(cand)
                        consumed = true
                    }
                }
                if (consumed) {
                    iterator.remove()
                }
            }
        }
    }

    fun stopParentSession() {
        signaling.sendMessage(
            SignalingMessage(
                sessionId = currentSessionId,
                senderDeviceId = localDeviceId,
                targetDeviceId = remoteDeviceId,
                messageType = SignalingType.STOP_SCREEN
            )
        )
        signaling.sendMessage(
            SignalingMessage(
                sessionId = currentSessionId,
                senderDeviceId = localDeviceId,
                targetDeviceId = remoteDeviceId,
                messageType = SignalingType.STOP_CAMERA
            )
        )
        signaling.sendMessage(
            SignalingMessage(
                sessionId = currentSessionId,
                senderDeviceId = localDeviceId,
                targetDeviceId = remoteDeviceId,
                messageType = SignalingType.STOP_AUDIO
            )
        )
        cleanupSession()
    }

    private fun cleanupSession() {
        _isScreenMirroringActive.value = false
        _isCameraActive.value = false
        _isMicrophoneMonitoringActive.value = false
        _remoteVideoTrack.value = null
        _localVideoTrack.value = null
        _connectionStateDescription.value = "Session Ended"
        parentHasAppliedOffer = false
        receiveStatsJob?.cancel()
        receiveStatsJob = null
        localRole = LocalRole.NONE
        pendingIceCandidates.clear()
        processedCandidateSdpSet.clear()
        _safeDiagnostics.value = SafeWebRtcDiagnostics()

        try {
            parentPeerConnection?.close()
            parentPeerConnection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing parent peer connection", e)
        }
    }
}
