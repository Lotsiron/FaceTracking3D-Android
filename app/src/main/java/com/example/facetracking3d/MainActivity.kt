package com.example.facetracking3d

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.segmentation.SegmentationMask
import androidx.core.graphics.scale

class MainActivity : AppCompatActivity() {

    private val CAMERA_REQUEST_CODE = 1001
    private lateinit var cameraExecutor: ExecutorService

    private var processedBitmap: Bitmap? = null

    // Optimization for real time (STREAM_MODE)
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask() // Use raw mask size for performance
            .build()
    )

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Turning ImageProxy to InputImage for ML Kit
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            segmenter.process(image)
                .addOnSuccessListener { segmentationMask ->
                    // Successful: ML separated human and background
                    val maskBuffer = segmentationMask.buffer
                    val maskWidth = segmentationMask.width
                    val maskHeight = segmentationMask.height

                    // Turning raw image (ImageProxy) into Bitmap to process
                    val originalBitmap = imageProxy.toBitmap()

                    if (originalBitmap != null) {
                        // Scale the original camera image to the mask size so pixels match
                        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, maskWidth, maskHeight, true)
                        val outputBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)

                        // Pixel array
                        val pixels = IntArray(maskWidth * maskHeight)
                        scaledBitmap.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                        maskBuffer.rewind()

                        // Performans için yeşil rengin RGB bileşenlerini döngü dışında tanımlıyoruz
                        val greenR = 0
                        val greenG = 255
                        val greenB = 0

                        for (i in 0 until maskWidth * maskHeight) {
                            // ML Kit bize insan olma güven skorunu verir: 1.0 (Sen), 0.0 (Arkaplan)
                            val personConfidence = maskBuffer.float

                            // Orijinal kameradan gelen pikselin renk bileşenlerini (RGB) ayırıyoruz
                            val origColor = pixels[i]
                            val r = (origColor shr 16) and 0xFF
                            val g = (origColor shr 8) and 0xFF
                            val b = origColor and 0xFF

                            // ALPHA BLENDING MATEMATİĞİ:
                            // personConfidence 1.0 ise (Sensin): Orijinal rengini korur (r * 1.0 + green * 0.0)
                            // personConfidence 0.0 ise (Arkaplan): Tamamen yeşil yapar (r * 0.0 + green * 1.0)
                            // Saç telleri veya omuz kenarları (örn: 0.5): Yarı yeşil yarı orijinal yaparak yumuşak geçiş sağlar
                            val blendedR = (r * personConfidence + greenR * (1 - personConfidence)).toInt()
                            val blendedG = (g * personConfidence + greenG * (1 - personConfidence)).toInt()
                            val blendedB = (b * personConfidence + greenB * (1 - personConfidence)).toInt()

                            // Yeni pikselleri renk dizisine bitwise olarak geri yazıyoruz
                            pixels[i] = (0xFF shl 24) or (blendedR shl 16) or (blendedG shl 8) or blendedB
                        }

                        // Write the new pixel array to a new bitmap
                        outputBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                        // Resizing bitmap to original size
                        val finalBitmap =
                            outputBitmap.scale(originalBitmap.width, originalBitmap.height)

                        // Write the image to the ImageView on the UI thread
                        runOnUiThread {
                            val processedImageView = findViewById<android.widget.ImageView>(R.id.processedImageView)
                            processedImageView?.setImageBitmap(finalBitmap)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("Segmentation", "Model error: ", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

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

        // Camera permission
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE
            )
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera access denied.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // provider to bind the camera to the lifecycle
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Camera video to the preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(findViewById<androidx.camera.view.PreviewView>(R.id.viewFinder).surfaceProvider)
                }

            // 2. Machine Learning analysis layer
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        // Process each camera frame
                        processImageProxy(imageProxy)
                    }
                }

            // We've chosen the front camera
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()

                // Bind the camera to the activity's lifecycle
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                // If camera can't start log it
                android.util.Log.e("FaceTracking", "Lifecycle failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }
}