package com.example.facetracking3d.vision

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.facetracking3d.graphics.gl.GreenScreenRenderer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FrameAnalyzer(
    private val greenScreenRenderer: GreenScreenRenderer,
    private val onRequestRender: () -> Unit,
    private val onFaceUpdated: (FaceData) -> Unit
) : ImageAnalysis.Analyzer {

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build()
    )

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

        // Standardize the raw frame (handles landscape/portrait rotation & scaling cleanly)
        val standardBitmap = FrameStandardizer.standardize(rawBitmap, rotation)
        val imageForMlKit = InputImage.fromBitmap(standardBitmap, 0)

        val tasksCompleted = AtomicInteger(0)
        var maskBuffer: ByteBuffer? = null
        var maskWidth = 0
        var maskHeight = 0

        // Helper function to render ONLY when both background AI tasks finish
        fun checkAndRender() {
            if (tasksCompleted.incrementAndGet() == 2) {
                if (maskBuffer != null) {
                    // Hand off both the standardized bitmap AND the mask buffer to the GPU
                    greenScreenRenderer.setNextFrame(
                        bitmap = standardBitmap,
                        rawByteBuffer = maskBuffer!!,
                        width = maskWidth,
                        height = maskHeight
                    )
                    onRequestRender()
                }
                imageProxy.close() // Release lock for the next CameraX frame
            }
        }

        // Task 1: Segmentation
        segmenter.process(imageForMlKit)
            .addOnSuccessListener(bgExecutor) { mask ->
                maskBuffer = mask.buffer
                maskWidth = mask.width
                maskHeight = mask.height
            }
            .addOnFailureListener(bgExecutor) { checkAndRender() }
            .addOnCompleteListener(bgExecutor) { checkAndRender() }

        // Task 2: Face Tracking
        faceTracker.processImage(imageForMlKit, bgExecutor) {
            checkAndRender()
        }
    }
}