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

class MainActivity : AppCompatActivity() {

    private val CAMERA_REQUEST_CODE = 1001
    private lateinit var cameraExecutor: ExecutorService

    // Gerçek zamanlı (STREAM_MODE) çalışacak şekilde optimize ediyoruz
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask() // Performans için ham maske boyutunu kullan
            .build()
    )

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // CameraX ImageProxy'yi ML Kit'in anladığı InputImage formatına çeviriyoruz
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            segmenter.process(image)
                .addOnSuccessListener { segmentationMask ->
                    // BAŞARILI: Yapay zeka arkaplanı ve insanı ayırdı!
                    // segmentationMask nesnesinin içinde maske matrisi var.

                    val maskBuffer = segmentationMask.buffer
                    val maskWidth = segmentationMask.width
                    val maskHeight = segmentationMask.height

                    // TODO: Bu aşamada maskBuffer'ı kullanarak arkaplan piksellerini yeşile boyayacağız.
                    // Log atarak çalıştığını test edelim:
                    android.util.Log.d("Segmentation", "Maske üretildi: ${maskWidth}x${maskHeight}")
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("Segmentation", "Model hatası: ", e)
                }
                .addOnCompleteListener {
                    // Çok Kritik: İşlem bitince bu kareyi serbest bırakmalıyız ki sonraki kare gelebilsin
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
                Toast.makeText(this, "Kamera izni verilmedi, uygulama çalışamaz.", Toast.LENGTH_SHORT).show()
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
            // Galaxy M23'ü yormamak için performans ayarı yapıyoruz
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Kuyrukta frame biriktirme, hep en son kareyi al
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888) // Standart Android kamera formatı
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        // Her kamera karesi buraya düşecek!
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