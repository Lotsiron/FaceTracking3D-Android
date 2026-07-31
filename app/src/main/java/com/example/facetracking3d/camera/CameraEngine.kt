package com.example.facetracking3d.camera

import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.facetracking3d.graphics.MaskRenderer
import com.example.facetracking3d.graphics.gl.legacy.GreenScreenRenderer
import com.example.facetracking3d.graphics.gl.YuvGreenScreenRenderer
import com.example.facetracking3d.vision.legacy.FrameAnalyzer
import com.example.facetracking3d.vision.YuvFrameAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// The "Air Traffic Controller." This manages the Android CameraX library, requesting the hardware streams and wiring them to the custom logic.

class CameraEngine(
    private val activity: AppCompatActivity,
    private val glSurfaceView: GLSurfaceView,
    private val maskRenderer: MaskRenderer,
    private val useYuvPipeline: Boolean = true,
    private val enableGreenScreen: Boolean = true,
    private val enableFaceTracking: Boolean = true
) {
    // A background thread pool specifically for CameraX. We use a single thread so
    // camera frames are processed sequentially in the order they arrive, preventing bottlenecks.
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // SurfaceTexture is the Android OS bridge that lets hardware (the camera)
    // stream pixels directly into GPU memory (OpenGL) without the CPU having to copy them.
    private var yuvSurfaceTexture: SurfaceTexture? = null
    private var activeAnalyzer: ImageAnalysis.Analyzer? = null
    private var activeYuvRenderer: YuvGreenScreenRenderer? = null
    private var activeYuvAnalyzer: YuvFrameAnalyzer? = null

    fun start() {
        // We require OpenGL ES 3.0 to use advanced shader features (like OES external textures)
        glSurfaceView.setEGLContextClientVersion(3)

        if (useYuvPipeline) {
            activeYuvRenderer = YuvGreenScreenRenderer(
                enableGreenScreen,
                isMirrored = true,
                requestRender = { glSurfaceView.requestRender() },
                onSurfaceReady = { surfaceTex ->
                    yuvSurfaceTexture = surfaceTex
                    activity.runOnUiThread { bindCameraUseCases() }
                }
            )
            activeAnalyzer = YuvFrameAnalyzer(
                renderer = activeYuvRenderer!!,
                enableGreenScreen = enableGreenScreen,
                enableFaceTracking = enableFaceTracking,
                onRequestRender = { glSurfaceView.requestRender() },
                onFaceUpdated = { faceData -> activity.runOnUiThread { maskRenderer.updateFace(faceData) } }
            )
            activeYuvAnalyzer = activeAnalyzer as? YuvFrameAnalyzer
            glSurfaceView.setRenderer(activeYuvRenderer)
        } else {
            val bmpRenderer = GreenScreenRenderer()
            activeAnalyzer = FrameAnalyzer(
                greenScreenRenderer = bmpRenderer,
                onRequestRender = { glSurfaceView.requestRender() },
                onFaceUpdated = { faceData -> activity.runOnUiThread { maskRenderer.updateFace(faceData) } }
            )
            glSurfaceView.setRenderer(bmpRenderer)
            bindCameraUseCases()
        }

        // RENDERMODE_WHEN_DIRTY saves battery. It means the GPU only draws a new frame
        // when we explicitly call glSurfaceView.requestRender(), rather than looping infinitely.
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }


    // --- NEW UI CONTROLLER METHODS ---

    fun setGreenScreenEnabled(enabled: Boolean) {
        activeYuvAnalyzer?.enableGreenScreen = enabled
        activeYuvRenderer?.enableGreenScreen = enabled
        glSurfaceView.requestRender() // Force an immediate redraw
    }

    fun setFaceTrackingEnabled(enabled: Boolean) {
        activeYuvAnalyzer?.enableFaceTracking = enabled
        maskRenderer.sceneView.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    fun setMirroringEnabled(enabled: Boolean) {
        activeYuvRenderer?.isMirrored = enabled
        glSurfaceView.requestRender()
    }

    fun setMaskSensitivity(min: Float, max: Float) {
        activeYuvRenderer?.setSensitivity(min, max)
    }
    private fun bindCameraUseCases() {
        // ProcessCameraProvider binds the lifecycle of the camera to your Activity.
        // If you minimize the app, it automatically turns the camera off to save battery.
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val displayRotation = activity.windowManager.defaultDisplay.rotation

            // USE CASE 1: ImageAnalysis (The AI CPU Stream)
            // This pulls frames from the camera, converts them to a format the CPU can read,
            // and feeds them to ML Kit.
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(displayRotation)
                // STRATEGY_KEEP_ONLY_LATEST drops frames if the AI is running slow, keeping the feed real-time.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    activeAnalyzer?.let { analyzer.setAnalyzer(cameraExecutor, it) }
                }

            try {
                cameraProvider.unbindAll()

                if (useYuvPipeline && yuvSurfaceTexture != null) {
                    // USE CASE 2: Preview (The Screen GPU Stream)
                    // This dumps raw pixels straight from the sensor to the screen via OpenGL.
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .setTargetRotation(displayRotation)
                        .build().also {
                            it.setSurfaceProvider { request ->
                                // FOV FIX: We force the GPU buffer to perfectly match the resolution
                                // that CameraX is sending to the AI stream, so their fields of view match.
                                val resolution = request.resolution
                                yuvSurfaceTexture?.setDefaultBufferSize(resolution.width, resolution.height)
                                val surface = Surface(yuvSurfaceTexture)
                                request.provideSurface(surface, ContextCompat.getMainExecutor(activity)) {
                                    surface.release()
                                }
                            }
                        }
                    // Attach both streams to the front-facing camera hardware.
                    cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
                } else {
                    cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalyzer)
                }
            } catch (exc: Exception) {
                Log.e("FaceTracking", "Lifecycle failed", exc)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}