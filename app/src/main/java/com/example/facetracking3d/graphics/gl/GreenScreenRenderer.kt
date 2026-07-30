package com.example.facetracking3d.graphics.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GreenScreenRenderer : GLSurfaceView.Renderer {

    private val frameLock = Object()
    private var pendingBitmap: Bitmap? = null
    private var pendingMaskBuffer: FloatBuffer? = null
    private var pendingMaskWidth = 0
    private var pendingMaskHeight = 0
    private var isDirty = false

    private var cameraTextureId = -1
    private var maskTextureId = -1
    private var program = 0
    private var isMaskAllocated = false

    private var positionHandle = 0
    private var texCoordHandle = 0
    private var cameraSamplerHandle = 0
    private var maskSamplerHandle = 0

    private val vertexData = floatArrayOf(
        -1.0f, -1.0f,  1.0f, -1.0f,
        -1.0f,  1.0f,  1.0f,  1.0f
    )
    private val textureData = floatArrayOf(
        0.0f, 0.0f,  1.0f, 0.0f,
        0.0f, 1.0f,  1.0f, 1.0f
    )

    private val vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertexData).apply { position(0) }
    private val textureBuffer = ByteBuffer.allocateDirect(textureData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureData).apply { position(0) }

    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private val videoWidth = 360
    private val videoHeight = 640

    // --- THE MISSING FUNCTION IS BACK ---
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = ShaderUtil.createProgram()
        positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES30.glGetAttribLocation(program, "aTextureCoord")
        cameraSamplerHandle = GLES30.glGetUniformLocation(program, "uCameraTexture")
        maskSamplerHandle = GLES30.glGetUniformLocation(program, "uMaskTexture")

        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        cameraTextureId = textures[0]
        maskTextureId = textures[1]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
    }

    private fun calculateCenterCrop(videoW: Int, videoH: Int) {
        if (surfaceWidth == 0 || surfaceHeight == 0 || videoW == 0 || videoH == 0) return

        val screenRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val videoRatio = videoW.toFloat() / videoH.toFloat()

        var scaleX = 1f
        var scaleY = 1f

        if (screenRatio > videoRatio) {
            scaleY = screenRatio / videoRatio // Screen is wider, scale Y up to crop top/bottom
        } else {
            scaleX = videoRatio / screenRatio // Screen is taller, scale X up to crop sides
        }

        val vData = floatArrayOf(
            -scaleX, -scaleY,  scaleX, -scaleY,
            -scaleX,  scaleY,  scaleX,  scaleY
        )
        vertexBuffer.clear()
        vertexBuffer.put(vData)
        vertexBuffer.position(0)
    }

    private var isCameraAllocated = false

    override fun onDrawFrame(gl: GL10?) {
        synchronized(frameLock) {
            if (isDirty && pendingBitmap != null && pendingMaskBuffer != null) {

                // 1. Upload Camera Bitmap efficiently
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraTextureId)
                if (!isCameraAllocated) {
                    calculateCenterCrop(pendingBitmap!!.width, pendingBitmap!!.height)

                    // First time: Allocate GPU memory block
                    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, pendingBitmap, 0)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                    isCameraAllocated = true
                } else {
                    // Subsequent frames: Overwrite existing VRAM instantly without reallocating
                    GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, pendingBitmap)
                }

                // 2. Upload Mask Buffer
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
                pendingMaskBuffer!!.rewind()
                if (!isMaskAllocated) {
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, pendingMaskWidth, pendingMaskHeight, 0, GLES30.GL_RED, GLES30.GL_FLOAT, pendingMaskBuffer)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                    isMaskAllocated = true
                } else {
                    GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, pendingMaskWidth, pendingMaskHeight, GLES30.GL_RED, GLES30.GL_FLOAT, pendingMaskBuffer)
                }

                isDirty = false
            }
        }

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(positionHandle, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glEnableVertexAttribArray(texCoordHandle)
        GLES30.glVertexAttribPointer(texCoordHandle, 2, GLES30.GL_FLOAT, false, 0, textureBuffer)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraTextureId)
        GLES30.glUniform1i(cameraSamplerHandle, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        GLES30.glUniform1i(maskSamplerHandle, 1)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glDisableVertexAttribArray(texCoordHandle)
    }

    fun setNextFrame(bitmap: Bitmap, rawByteBuffer: ByteBuffer, width: Int, height: Int) {
        synchronized(frameLock) {
            pendingBitmap = bitmap
            val capacity = rawByteBuffer.capacity()

            if (pendingMaskBuffer == null || pendingMaskBuffer!!.capacity() * 4 != capacity) {
                pendingMaskBuffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder()).asFloatBuffer()
            }

            rawByteBuffer.rewind()
            pendingMaskBuffer!!.clear()
            pendingMaskBuffer!!.put(rawByteBuffer.asFloatBuffer())
            pendingMaskBuffer!!.flip()

            pendingMaskWidth = width
            pendingMaskHeight = height
            isDirty = true
        }
    }
}