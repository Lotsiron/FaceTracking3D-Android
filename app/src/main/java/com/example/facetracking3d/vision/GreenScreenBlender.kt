package com.example.facetracking3d.vision

import android.graphics.Bitmap
import java.nio.ByteBuffer

class GreenScreenBlender {
    private var outputBitmap1: Bitmap? = null
    private var outputBitmap2: Bitmap? = null
    private var useBitmap1 = true

    private var imagePixels: IntArray? = null
    private var resultPixels: IntArray? = null
    private var maskArray: FloatArray? = null

    fun applyGreenScreen(cameraBitmap: Bitmap, maskBuffer: ByteBuffer, width: Int, height: Int): Bitmap {
        val totalPixels = width * height

        if (outputBitmap1 == null || outputBitmap1!!.width != width || outputBitmap1!!.height != height) {
            outputBitmap1 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            outputBitmap2 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            imagePixels = IntArray(totalPixels)
            resultPixels = IntArray(totalPixels)
            maskArray = FloatArray(totalPixels)
        }

        // Extract to local non-null variables to KILL the Intrinsics.checkNotNull overhead
        val localImagePixels = imagePixels!!
        val localResultPixels = resultPixels!!
        val localMaskArray = maskArray!!

        cameraBitmap.getPixels(localImagePixels, 0, width, 0, 0, width, height)

        maskBuffer.rewind()
        maskBuffer.asFloatBuffer().get(localMaskArray)

        for (i in 0 until totalPixels) {
            val personConfidence = localMaskArray[i]

            if (personConfidence > 0.95f) {
                localResultPixels[i] = localImagePixels[i]
            } else if (personConfidence < 0.05f) {
                localResultPixels[i] = 0xFF00FF00.toInt()
            } else {
                val origColor = localImagePixels[i]
                val r = (origColor shr 16) and 0xFF
                val g = (origColor shr 8) and 0xFF
                val b = origColor and 0xFF

                val blendedR = (r * personConfidence).toInt()
                val blendedG = (g * personConfidence + 255f * (1f - personConfidence)).toInt()
                val blendedB = (b * personConfidence).toInt()

                localResultPixels[i] = (0xFF shl 24) or (blendedR shl 16) or (blendedG shl 8) or blendedB
            }
        }

        val targetBitmap = if (useBitmap1) outputBitmap1!! else outputBitmap2!!
        useBitmap1 = !useBitmap1

        targetBitmap.setPixels(localResultPixels, 0, width, 0, 0, width, height)

        return targetBitmap
    }
}