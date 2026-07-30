package com.example.facetracking3d.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.facetracking3d.R
import com.example.facetracking3d.graphics.MaskRenderer
import com.example.facetracking3d.graphics.gl.GreenScreenRenderer
import com.example.facetracking3d.graphics.gl.YuvGreenScreenRenderer
import com.example.facetracking3d.vision.FrameAnalyzer
import com.example.facetracking3d.vision.YuvFrameAnalyzer
import io.github.sceneview.SceneView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.AspectRatio

class MainActivity : AppCompatActivity() {

    private val CAMERA_REQUEST_CODE = 1001
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var maskRenderer: MaskRenderer

    private lateinit var glSurfaceView: GLSurfaceView

    private val useYuvPipeline = true
    private val enableGreenScreen = true
    private val enableFaceTracking = true

    private lateinit var activeRenderer: GLSurfaceView.Renderer
    private lateinit var activeAnalyzer: ImageAnalysis.Analyzer
    private var yuvSurfaceTexture: SurfaceTexture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val sceneView = findViewById<SceneView>(R.id.sceneView)
        sceneView.visibility = if (enableFaceTracking) View.VISIBLE else View.GONE
        maskRenderer = MaskRenderer(lifecycleScope, sceneView)

        glSurfaceView = findViewById(R.id.glSurfaceView)
        glSurfaceView.setEGLContextClientVersion(3)

        if (useYuvPipeline) {
            val yuvRenderer = YuvGreenScreenRenderer(enableGreenScreen, { glSurfaceView.requestRender() }) { surfaceTex ->
                yuvSurfaceTexture = surfaceTex
                runOnUiThread { startCamera() }
            }
            activeRenderer = yuvRenderer
            activeAnalyzer = YuvFrameAnalyzer(
                renderer = yuvRenderer,
                enableGreenScreen = enableGreenScreen,
                enableFaceTracking = enableFaceTracking,
                onRequestRender = { glSurfaceView.requestRender() },
                onFaceUpdated = { faceData -> runOnUiThread { maskRenderer.updateFace(faceData) } }
            )
        } else {
            val bmpRenderer = GreenScreenRenderer()
            activeRenderer = bmpRenderer
            activeAnalyzer = FrameAnalyzer(
                greenScreenRenderer = bmpRenderer,
                onRequestRender = { glSurfaceView.requestRender() },
                onFaceUpdated = { faceData -> runOnUiThread { maskRenderer.updateFace(faceData) } }
            )
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
            }
        }

        glSurfaceView.setRenderer(activeRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!allPermissionsGranted()) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val displayRotation = windowManager.defaultDisplay.rotation

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(displayRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor, activeAnalyzer)
                }

            try {
                cameraProvider.unbindAll()

                if (useYuvPipeline && yuvSurfaceTexture != null) {
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .setTargetRotation(displayRotation)
                        .build().also {
                            it.setSurfaceProvider { request ->
                                // THE FOV FIX: Force the hardware buffer to match the exact 16:9 crop request
                                val resolution = request.resolution
                                yuvSurfaceTexture?.setDefaultBufferSize(resolution.width, resolution.height)

                                val surface = android.view.Surface(yuvSurfaceTexture)
                                request.provideSurface(surface, ContextCompat.getMainExecutor(this@MainActivity)) {
                                    surface.release()
                                }
                            }
                        }
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
                } else {
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalyzer)
                }

            } catch (exc: Exception) {
                Log.e("FaceTracking", "Lifecycle failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST_CODE && allPermissionsGranted()) {
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}