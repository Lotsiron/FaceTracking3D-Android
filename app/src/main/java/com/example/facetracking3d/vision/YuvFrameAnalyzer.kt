package com.example.facetracking3d.vision

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.facetracking3d.graphics.gl.YuvGreenScreenRenderer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class YuvFrameAnalyzer(
    private val renderer: YuvGreenScreenRenderer,
    private val enableGreenScreen: Boolean,
    private val enableFaceTracking: Boolean,
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

        val rotation = imageProxy.imageInfo.rotationDegrees
        val isRotated = rotation % 180 != 0
        val vWidth = if (isRotated) imageProxy.height else imageProxy.width
        val vHeight = if (isRotated) imageProxy.width else imageProxy.height

        // The magic timestamp that links the Hardware Video to the AI data
        val timestamp = imageProxy.imageInfo.timestamp

        renderer.setVideoDimensions(vWidth, vHeight)

        if (!enableGreenScreen && !enableFaceTracking) {
            imageProxy.close()
            return
        }

        val imageForMlKit = InputImage.fromMediaImage(mediaImage, rotation)

        val targetTasks = (if (enableGreenScreen) 1 else 0) + (if (enableFaceTracking) 1 else 0)
        val tasksCompleted = AtomicInteger(0)

        fun checkAndClose() {
            if (tasksCompleted.incrementAndGet() >= targetTasks) {
                imageProxy.close()
            }
        }

        if (enableGreenScreen) {
            segmenter.process(imageForMlKit)
                .addOnSuccessListener(bgExecutor) { mask ->
                    // Pass the timestamp to the GPU so it knows which frame to pull!
                    renderer.setNextMask(mask.buffer, mask.width, mask.height, timestamp)
                    onRequestRender()
                }
                .addOnFailureListener(bgExecutor) { onRequestRender() }
                .addOnCompleteListener(bgExecutor) { checkAndClose() }
        }

        if (enableFaceTracking) {
            faceTracker.processImage(imageForMlKit, bgExecutor) {
                checkAndClose()
            }
        }
    }
}