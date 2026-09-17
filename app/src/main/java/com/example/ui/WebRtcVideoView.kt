package com.example.ui

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

/**
 * Jetpack Compose wrapper for displaying a live WebRTC VideoTrack.
 *
 * BLACK SCREEN FIX NOTES
 * ----------------------
 * A TextureView can ONLY draw when the window that hosts it is hardware accelerated.
 * A Compose `Dialog` creates its own window, and on several OEM skins (Realme/Oppo ColorOS,
 * Xiaomi MIUI) that window is created WITHOUT FLAG_HARDWARE_ACCELERATED. The WebRTC stack then
 * works perfectly - track negotiated, onTrack fired, audio audible, frames decoded and handed
 * to the sink - but the TextureView composites nothing and the user sees a permanently BLACK
 * rectangle with a green "LIVE SCREEN" badge on top. That is exactly the reported symptom.
 *
 * So this view now:
 *  1. Force-enables hardware acceleration on the hosting window before the renderer is created.
 *  2. Forces the renderer itself onto a hardware layer.
 *  3. Keeps the renderer alive across recompositions and only detaches the sink on real dispose.
 */
@Composable
fun WebRtcVideoView(
	videoTrack: VideoTrack?,
	eglBase: EglBase,
	modifier: Modifier = Modifier,
	scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT
) {
	var rendererInstance by remember { mutableStateOf<VideoTextureViewRenderer?>(null) }
	val hostView = LocalView.current

	// 1. Guarantee the hosting window (Activity window OR Dialog window) is hardware accelerated.
	DisposableEffect(hostView) {
		try {
			val window = when (val parent = hostView.parent) {
				is androidx.compose.ui.window.DialogWindowProvider -> parent.window
				else -> (hostView.context as? Activity)?.window
			}
			window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
			android.util.Log.d(
				"WebRtcVideoView",
				"Host window hardware accelerated = " + (window != null)
			)
		} catch (e: Throwable) {
			android.util.Log.w("WebRtcVideoView", "HW accel flag notice: " + e.message)
		}
		onDispose { }
	}

	DisposableEffect(Unit) {
		onDispose {
			try { rendererInstance?.pauseVideo() } catch (_: Throwable) {}
			rendererInstance = null
		}
	}

	// 2. Attach / detach the track sink.
	DisposableEffect(videoTrack, rendererInstance) {
		val renderer = rendererInstance
		if (videoTrack != null && renderer != null) {
			try {
				videoTrack.setEnabled(true)
				try {
					renderer.resumeVideo()
				} catch (_: Throwable) {
				}
				videoTrack.addSink(renderer)
				android.util.Log.d("WebRtcVideoView", "Sink attached to renderer")
			} catch (e: Exception) {
				android.util.Log.w("WebRtcVideoView", "Sink attach failed: " + e.message)
			}
		}
		onDispose {
			try {
				if (videoTrack != null && renderer != null) {
					videoTrack.removeSink(renderer)
				}
			} catch (_: Exception) {
			}
		}
	}

	// 2b. Frame Watchdog: Detach and reattach sink if decoded RX frames stall for 8s
	val context = LocalContext.current
	val webRtcManager = remember { com.example.webrtc.WebRtcManager.getInstance(context) }
	val reattachEpoch by webRtcManager.rendererReattachTrigger.collectAsState()

	LaunchedEffect(reattachEpoch) {
		if (reattachEpoch > 0) {
			val renderer = rendererInstance
			if (videoTrack != null && renderer != null) {
				try {
					android.util.Log.w("WebRtcVideoView", "Watchdog triggered sink detach/reattach (epoch=$reattachEpoch)")
					videoTrack.removeSink(renderer)
					try { renderer.pauseVideo() } catch (_: Throwable) {}
					delay(100)
					try { renderer.resumeVideo() } catch (_: Throwable) {}
					videoTrack.addSink(renderer)
					android.util.Log.d("WebRtcVideoView", "Watchdog sink detach/reattach completed")
				} catch (e: Exception) {
					android.util.Log.w("WebRtcVideoView", "Watchdog sink reattach error: ${e.message}")
				}
			}
		}
	}

	LaunchedEffect(scalingType, rendererInstance) {
		rendererInstance?.setScalingType(scalingType)
	}

	Box(modifier = modifier.background(Color.Black)) {
		AndroidView(
			factory = { context ->
				VideoTextureViewRenderer(context).apply {
					id = com.example.R.id.webrtc_video_renderer
					// 3. A TextureView must live on a hardware layer, otherwise it stays black.
					try {
						setLayerType(View.LAYER_TYPE_HARDWARE, null)
						isOpaque = true
					} catch (_: Throwable) {
					}
					try {
						init(eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
							override fun onFirstFrameRendered() {
								android.util.Log.d("WebRtcVideoView", "FIRST FRAME RENDERED")
							}

							override fun onFrameResolutionChanged(
								videoWidth: Int,
								videoHeight: Int,
								rotation: Int
							) {
								android.util.Log.d(
									"WebRtcVideoView",
									"Frame resolution " + videoWidth + "x" + videoHeight + " rot " + rotation
								)
							}
						})
					} catch (e: Throwable) {
						android.util.Log.w("WebRtcVideoView", "Renderer init notice: " + e.message)
					}
					setScalingType(scalingType)
					rendererInstance = this
				}
			},
			update = { view ->
				if (rendererInstance !== view) {
					rendererInstance = view
				}
				view.setScalingType(scalingType)
			},
			modifier = Modifier.fillMaxSize()
		)
	}
}
