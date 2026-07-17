package com.example.facetracking3d.vision

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer

class FrameAnalyzer(
    private val onFrameProcessed: (Bitmap) -> Unit,
    private val onFaceUpdated: (FaceData) -> Unit
) : ImageAnalysis.Analyzer {

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask()
            .build()
    )

    // Reusable (yeniden kullanılabilir) bellek havuzumuz buraya taşındı
    private var outputBitmap: Bitmap? = null
    private var imagePixels: IntArray? = null
    private var resultPixels: IntArray? = null
    // 2. FaceTracker'ı FrameAnalyzer'ın içinde başlatıyoruz
    private val faceTracker = FaceTracker(onFaceUpdated)

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Önce A YOLU: Yeşil Ekran İşlemi
            segmenter.process(image)
                .addOnSuccessListener { segmentationMask ->
                    val maskBuffer = segmentationMask.buffer
                    val maskWidth = segmentationMask.width
                    val maskHeight = segmentationMask.height

                    // kameradan gelen ham veri karelere dönüştürülüyor
                    val originalBitmap = imageProxy.toBitmap()

                    if (originalBitmap != null) {
                        // 1. Yan kamerayı dik (Portrait) hale getiriyoruz
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                        // Döndürülmüş resmimiz:
                        val rotatedBitmap = Bitmap.createBitmap(
                            originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
                        )

                        // 2. Yırtılmayı Önleyen Hamle: Artık boyut değiştirme/ezme (Scale) YAPMIYORUZ!
                        // rotatedBitmap ve maskWidth zaten birebir aynı boyutlarda.
                        if (outputBitmap == null || outputBitmap!!.width != maskWidth || outputBitmap!!.height != maskHeight) {
                            outputBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                            imagePixels = IntArray(maskWidth * maskHeight)
                            resultPixels = IntArray(maskWidth * maskHeight)
                        }

                        // Doğrudan döndürülmüş resmin piksellerini alıyoruz
                        rotatedBitmap.getPixels(imagePixels!!, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                        // Yeşil ekran maskesini uyguluyoruz
                        applyGreenScreenEffect(maskBuffer, maskWidth, maskHeight)

                        // Çıktıyı doğrudan arayüze paslıyoruz. (Scale işlemi tamamen kaldırıldı, saf netlik!)
                        onFrameProcessed(outputBitmap!!)
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("FrameAnalyzer", "Segmentasyon hatası", e)
                }
                .addOnCompleteListener {
                    // YENİ: A Yolu BİTTİ. Kapıyı kapatmıyoruz!
                    // Aynı görseli B Yolu'na (FaceTracker) paslıyoruz.
                    faceTracker.processImage(image) {
                        // B Yolu da bitince FaceTracker bu bloğu tetikler ve kareyi güvenle kapatırız.
                        imageProxy.close()
                    }
                }
        } else {
            imageProxy.close()
        }
    }

    private fun applyGreenScreenEffect(maskBuffer: ByteBuffer, maskWidth: Int, maskHeight: Int) {

        // ByteBuffer'ı en performanslı şekilde float'lar halinde okuyabilmek için bir float view oluşturuyoruz
        val floatBuffer = maskBuffer.asFloatBuffer()
        floatBuffer.rewind()

        val greenR = 0
        val greenG = 255
        val greenB = 0

        for (i in 0 until maskWidth * maskHeight) {
            val personConfidence = floatBuffer.get()
            val origColor = imagePixels!![i]

            val r = (origColor shr 16) and 0xFF
            val g = (origColor shr 8) and 0xFF
            val b = origColor and 0xFF

            val blendedR = (r * personConfidence + greenR * (1 - personConfidence)).toInt()
            val blendedG = (g * personConfidence + greenG * (1 - personConfidence)).toInt()
            val blendedB = (b * personConfidence + greenB * (1 - personConfidence)).toInt()

            resultPixels!![i] = (0xFF shl 24) or (blendedR shl 16) or (blendedG shl 8) or blendedB
        }
        outputBitmap!!.setPixels(resultPixels!!, 0, maskWidth, 0, 0, maskWidth, maskHeight)
    }
}