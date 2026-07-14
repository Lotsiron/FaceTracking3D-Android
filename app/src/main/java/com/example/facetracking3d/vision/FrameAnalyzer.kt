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
    // Bu callback sayesinde işlenen resmi MainActivity'ye geri göndereceğiz
    private val onFrameProcessed: (Bitmap) -> Unit
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

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            segmenter.process(image)
                .addOnSuccessListener { segmentationMask ->
                    val maskBuffer = segmentationMask.buffer
                    val maskWidth = segmentationMask.width
                    val maskHeight = segmentationMask.height

                    val originalBitmap = imageProxy.toBitmap()

                    if (originalBitmap != null) {
                        // Boyut kontrolü ve bellek tahsisi
                        if (outputBitmap == null || outputBitmap!!.width != maskWidth || outputBitmap!!.height != maskHeight) {
                            outputBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                            imagePixels = IntArray(maskWidth * maskHeight)
                            resultPixels = IntArray(maskWidth * maskHeight)
                        }

                        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, maskWidth, maskHeight, true)
                        scaledBitmap.getPixels(imagePixels!!, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                        // Yeşil ekran Alpha Blending işlemi
                        applyGreenScreenEffect(maskBuffer, maskWidth, maskHeight)

                        val finalBitmap = Bitmap.createScaledBitmap(
                            outputBitmap!!,
                            originalBitmap.width,
                            originalBitmap.height,
                            true
                        )

                        // İşlem bitince arayüze pasla
                        onFrameProcessed(finalBitmap)
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
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