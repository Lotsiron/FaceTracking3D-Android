package com.example.facetracking3d.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.facetracking3d.R
import com.example.facetracking3d.graphics.MaskRenderer
import com.example.facetracking3d.vision.FrameAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val CAMERA_REQUEST_CODE = 1001
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var maskRenderer: MaskRenderer

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

        val sceneView = findViewById<io.github.sceneview.SceneView>(R.id.sceneView)
        maskRenderer = MaskRenderer(lifecycleScope, sceneView)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

//            // Sadece arka planda kamerayı canlı tutan görünmez Preview
//            val preview = Preview.Builder().build().also {
//                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder)?.surfaceProvider)
//            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor, FrameAnalyzer(
                        onFrameProcessed = { finalBitmap ->
                            runOnUiThread {
                                // ADD <ImageView> here so the compiler knows the type
                                findViewById<ImageView>(R.id.processedImageView)?.setImageBitmap(finalBitmap)
                            }
                        },
                        onFaceUpdated = { faceData ->
                            runOnUiThread {
                                // FIX the typo: maskRenderer instead of maskRender
                                maskRenderer.updateFace(faceData)
                            }
                        }
                    ))
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("FaceTracking", "Lifecycle failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}