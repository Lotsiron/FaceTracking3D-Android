package com.example.facetracking3d.vision

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.facetracking3d.graphics.gl.YuvGreenScreenRenderer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger


// The "AI Brain." This sits between CameraX and ML Kit, capturing frames, launching parallel AI tasks, and passing the results to the GPU.

class YuvFrameAnalyzer(
    private val renderer: YuvGreenScreenRenderer,
    var enableGreenScreen: Boolean,
    var enableFaceTracking: Boolean,
    private val onRequestRender: () -> Unit,
    private val onFaceUpdated: (FaceData) -> Unit
) : ImageAnalysis.Analyzer {

    // Initialize Google ML Kit Selfie Segmenter in STREAM_MODE (optimized for live video)
    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build()
    )
    private val faceTracker = FaceTracker(onFaceUpdated)
    // A small background thread pool so we can run Face Tracking and Masking at the same time.
    private val bgExecutor = Executors.newFixedThreadPool(3)

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        // ImageProxy is CameraX's wrapper. It contains the raw YUV byte arrays from the sensor.
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        // CameraX tells us how the sensor is physically rotated relative to the phone body.
        val rotation = imageProxy.imageInfo.rotationDegrees
        val isRotated = rotation % 180 != 0
        val vWidth = if (isRotated) imageProxy.height else imageProxy.width
        val vHeight = if (isRotated) imageProxy.width else imageProxy.height

        // The exact hardware clock time (nanoseconds) this frame hit the sensor.
        // We pass this to the GPU so it knows which frame in the Ring Buffer belongs to this AI data.
        val timestamp = imageProxy.imageInfo.timestamp

        renderer.setVideoDimensions(vWidth, vHeight)

        if (!enableGreenScreen && !enableFaceTracking) {
            imageProxy.close()
            return
        }

        // ML Kit requires we wrap the Android MediaImage into its proprietary InputImage format.
        val imageForMlKit = InputImage.fromMediaImage(mediaImage, rotation)

        val targetTasks = (if (enableGreenScreen) 1 else 0) + (if (enableFaceTracking) 1 else 0)

        // AtomicInteger is thread-safe. Because Green Screen and Face Tracking run on
        // different threads, they could try to close the camera frame at the same time and crash.
        val tasksCompleted = AtomicInteger(0)

        // Only release the camera frame back to the hardware pool when ALL AI tasks are done with it.
        fun checkAndClose() {
            if (tasksCompleted.incrementAndGet() >= targetTasks) {
                imageProxy.close()
            }
        }

        if (enableGreenScreen) {
            segmenter.process(imageForMlKit)
                .addOnSuccessListener(bgExecutor) { mask ->
                    // The mask.buffer is a raw ByteBuffer of confidence floats (0.0 to 1.0)
                    renderer.setNextMask(mask.buffer, mask.width, mask.height, timestamp)

                    // We trigger the GPU to draw the moment the mask is ready,
                    // without waiting for the slower Face Tracker.
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