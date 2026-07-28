package com.example.facetracking3d.vision

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger // ADD IMPORT

class FrameAnalyzer(
    private val onFrameProcessed: (Bitmap) -> Unit,
    private val onFaceUpdated: (FaceData) -> Unit
) : ImageAnalysis.Analyzer {

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build()
    )

    private val greenScreenBlender = GreenScreenBlender()
    private val faceTracker = FaceTracker(onFaceUpdated)
    private val bgExecutor = Executors.newFixedThreadPool(3)

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rawBitmap = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees

        val standardBitmap = FrameStandardizer.standardize(rawBitmap, rotation)
        val imageForMlKit = InputImage.fromBitmap(standardBitmap, 0)

        // THE FIX: Atomic memory barrier guarantees cross-thread visibility
        val tasksCompleted = AtomicInteger(0)
        var maskBuffer: ByteBuffer? = null

        fun checkAndBlend() {
            // Only the thread that pushes the counter to 2 will execute the blend
            if (tasksCompleted.incrementAndGet() == 2) {
                if (maskBuffer != null) {
                    val finalFrame = greenScreenBlender.applyGreenScreen(
                        cameraBitmap = standardBitmap,
                        maskBuffer = maskBuffer!!,
                        width = standardBitmap.width,
                        height = standardBitmap.height
                    )
                    onFrameProcessed(finalFrame)
                }
                imageProxy.close()
            }
        }

        // TASK 1: Segmenter
        segmenter.process(imageForMlKit)
            .addOnSuccessListener(bgExecutor) { mask ->
                maskBuffer = mask.buffer
            }
            .addOnFailureListener(bgExecutor) {
                // SAFETY NET: Ensure the camera doesn't permanently freeze on error
                checkAndBlend()
            }
            .addOnCompleteListener(bgExecutor) {
                checkAndBlend()
            }

        // TASK 2: Face Tracker
        faceTracker.processImage(imageForMlKit, bgExecutor) {
            checkAndBlend()
        }
    }
}