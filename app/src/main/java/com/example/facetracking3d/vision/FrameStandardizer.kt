package com.example.facetracking3d.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff

object FrameStandardizer {
    // Expose fixed targets so FaceTracker knows the new dimensions
    const val TARGET_WIDTH = 480
    const val TARGET_HEIGHT = 854

    // Update the internal targets
    private const val TARGET_SHORT = 480
    private const val TARGET_LONG = 854

    private var outputBitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun standardize(rawBitmap: Bitmap, rotationDegrees: Int): Bitmap {
        // 1. Calculate actual dimensions after rotation is applied
        val isRotated = rotationDegrees % 180 != 0
        val inWidth = if (isRotated) rawBitmap.height else rawBitmap.width
        val inHeight = if (isRotated) rawBitmap.width else rawBitmap.height

        // 2. Detect Landscape vs Portrait
        val isLandscape = inWidth > inHeight
        val targetW = if (isLandscape) TARGET_LONG else TARGET_SHORT
        val targetH = if (isLandscape) TARGET_SHORT else TARGET_LONG

        // 3. Reallocate ONLY if orientation changes
        if (outputBitmap == null || outputBitmap!!.width != targetW || outputBitmap!!.height != targetH) {
            outputBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            canvas = Canvas(outputBitmap!!)
        }

        // 4. Center Crop Math
        val scaleX = targetW.toFloat() / inWidth
        val scaleY = targetH.toFloat() / inHeight
        val scale = maxOf(scaleX, scaleY)

        matrix.reset()
        matrix.postTranslate(-rawBitmap.width / 2f, -rawBitmap.height / 2f)
        matrix.postRotate(rotationDegrees.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(targetW / 2f, targetH / 2f)

        // 5. Draw
        canvas!!.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
        canvas!!.drawBitmap(rawBitmap, matrix, paint)

        rawBitmap.recycle()

        return outputBitmap!!
    }
}