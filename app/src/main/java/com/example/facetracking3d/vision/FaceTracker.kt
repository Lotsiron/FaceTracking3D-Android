package com.example.facetracking3d.vision

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor

class FaceTracker(
    private val onFaceUpdated: (FaceData) -> Unit
) {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val detector = FaceDetection.getClient(options)

    private var lostFaceFrames = 0
    private val MAX_LOST_FRAMES = 5

    // NEW: Accept an executor so we can run this simultaneously with the segmenter
    fun processImage(image: InputImage, executor: Executor, onComplete: () -> Unit) {
        detector.process(image)
            .addOnSuccessListener(executor) { faces ->
                if (faces.isEmpty()) {
                    handleLostFace()
                } else {
                    val mainFace = getLargestFace(faces)
                    // Pass the exact width/height from the current frame
                    updateFaceData(mainFace, image.width, image.height)
                    lostFaceFrames = 0
                }
            }
            .addOnFailureListener(executor) { e ->
                Log.e("FaceTracker", "Model error: ", e)
                onComplete()
            }
            .addOnCompleteListener(executor) {
                onComplete()
            }
    }

    private fun getLargestFace(faces: List<Face>): Face {
        return faces.maxByOrNull { face ->
            val bounds = face.boundingBox
            bounds.width() * bounds.height()
        } ?: faces.first()
    }

    private fun handleLostFace() {
        lostFaceFrames++
        if (lostFaceFrames >= MAX_LOST_FRAMES) {
            onFaceUpdated(FaceData(isVisible = false))
            if (lostFaceFrames > 100) lostFaceFrames = MAX_LOST_FRAMES
        }
    }

    private fun updateFaceData(face: Face, frameWidth: Int, frameHeight: Int) {
        val bounds = face.boundingBox
        val data = FaceData(
            isVisible = true,
            x = bounds.exactCenterX(),
            y = bounds.exactCenterY(),
            width = bounds.width().toFloat(),
            height = bounds.height().toFloat(),
            headEulerAngleX = face.headEulerAngleX,
            headEulerAngleY = face.headEulerAngleY,
            headEulerAngleZ = face.headEulerAngleZ,
            frameWidth = frameWidth,
            frameHeight = frameHeight
        )
        onFaceUpdated(data)
    }
}