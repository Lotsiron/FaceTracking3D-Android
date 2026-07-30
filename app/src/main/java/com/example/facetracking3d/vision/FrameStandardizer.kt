package com.example.facetracking3d.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff

object FrameStandardizer {
    const val TARGET_WIDTH = 360
    const val TARGET_HEIGHT = 640

    private const val TARGET_SHORT = 360
    private const val TARGET_LONG = 640

    // DOUBLE BUFFERING: Two distinct Bitmaps to prevent CPU/GPU write collisions
    private var outputBitmapA: Bitmap? = null
    private var outputBitmapB: Bitmap? = null
    private var canvasA: Canvas? = null
    private var canvasB: Canvas? = null
    private var useBitmapA = true

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun standardize(rawBitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val isRotated = rotationDegrees % 180 != 0
        val inWidth = if (isRotated) rawBitmap.height else rawBitmap.width
        val inHeight = if (isRotated) rawBitmap.width else rawBitmap.height

        val isLandscape = inWidth > inHeight
        val targetW = if (isLandscape) TARGET_LONG else TARGET_SHORT
        val targetH = if (isLandscape) TARGET_SHORT else TARGET_LONG

        // Allocate both buffers if uninitialized or resized
        if (outputBitmapA == null || outputBitmapA!!.width != targetW || outputBitmapA!!.height != targetH) {
            outputBitmapA = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            canvasA = Canvas(outputBitmapA!!)

            outputBitmapB = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            canvasB = Canvas(outputBitmapB!!)
        }

        // Ping-pong between the two buffers
        val targetBitmap = if (useBitmapA) outputBitmapA!! else outputBitmapB!!
        val targetCanvas = if (useBitmapA) canvasA!! else canvasB!!
        useBitmapA = !useBitmapA

        val scaleX = targetW.toFloat() / inWidth
        val scaleY = targetH.toFloat() / inHeight
        val scale = maxOf(scaleX, scaleY)

        val matrix = Matrix()
        matrix.postTranslate(-rawBitmap.width / 2f, -rawBitmap.height / 2f)
        matrix.postRotate(rotationDegrees.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(targetW / 2f, targetH / 2f)

        // Clear ONLY the write-buffer for this frame
        targetCanvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
        targetCanvas.drawBitmap(rawBitmap, matrix, paint)

        rawBitmap.recycle()

        return targetBitmap
    }
}