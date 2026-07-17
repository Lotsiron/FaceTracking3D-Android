package com.example.facetracking3d.vision

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceTracker(
    // İşlem bitince dışarıya (örneğin 3D motora) FaceData fırlatacağımız callback
    private val onFaceUpdated: (FaceData) -> Unit
) {

    // 1. YAPILANDIRMA: İşlemciyi yormamak için sadece ihtiyaç olanları açıyoruz
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE) // Performans için göz/burun noktaları kapalı
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE) // Göz kırpma, gülümseme kapalı
        .build()

    private val detector = FaceDetection.getClient(options)

    // Kriz Senaryosu A (Kayıp Yüz) Yönetimi İçin Durum Değişkenleri
    private var lostFaceFrames = 0
    private val MAX_LOST_FRAMES = 5 // Yüz 5 kare (yaklaşık 150ms) yoksa gizle

    fun processImage(image: InputImage, onComplete: () -> Unit) {
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    handleLostFace()
                } else {
                    val mainFace = getLargestFace(faces)
                    // DÜZELTME: Resmin (frame) genişlik ve yüksekliğini de gönderiyoruz
                    updateFaceData(mainFace, image.width, image.height)
                    lostFaceFrames = 0
                }
            }
            .addOnFailureListener { e ->
                Log.e("FaceTracker", "Model hatasi: ", e)
            }
            .addOnCompleteListener {
                onComplete()
            }
    }

    // FEDAİ MANTIĞI: Bounding Box alanını (Genişlik x Yükseklik) hesaplayıp en büyük yüzü döndürür
    private fun getLargestFace(faces: List<Face>): Face {
        return faces.maxByOrNull { face ->
            val bounds = face.boundingBox
            bounds.width() * bounds.height()
        } ?: faces.first()
    }

    // HAYALET MANTIĞI: Yüz anlık mı kayboldu (titreme) yoksa yayıncı gerçekten mi gitti?
    private fun handleLostFace() {
        lostFaceFrames++
        if (lostFaceFrames >= MAX_LOST_FRAMES) {
            // Gerçekten kayboldu. 3D motora isVisible = false gönder ki şapkayı silsin.
            onFaceUpdated(FaceData(isVisible = false))

            // Sayaç çok şişmesin diye limitliyoruz
            if (lostFaceFrames > 100) lostFaceFrames = MAX_LOST_FRAMES
        }
    }

    // Yüz verilerini paketleyip dışarı aktarma
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
            // DÜZELTME: Artık sıfır (0) değil, gerçek çözünürlük gidiyor
            frameWidth = frameWidth,
            frameHeight = frameHeight
        )
        onFaceUpdated(data)
    }
}