package com.example.facetracking3d.graphics.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class YuvGreenScreenRenderer(
    private val enableGreenScreen: Boolean,
    private val requestRender: () -> Unit,
    private val onSurfaceReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    // --- RING BUFFER STATE ---
    class FrameBuffer(val fboId: Int, val textureId: Int) {
        var timestamp: Long = 0L
    }
    private val RING_BUFFER_SIZE = 10 // Holds ~300ms of video history
    private val fboArray = ArrayList<FrameBuffer>()
    private var fboHead = 0
    private var needsFboAllocation = false

    private val maskLock = Object()
    private var pendingMaskBuffer: FloatBuffer? = null
    private var pendingMaskWidth = 0
    private var pendingMaskHeight = 0
    private var pendingMaskTimestamp = 0L
    private var isMaskDirty = false
    private var hasNewHardwareFrame = false

    private var cameraTextureId = -1
    private var maskTextureId = -1
    private var isMaskAllocated = false
    var surfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)

    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var videoWidth = 1
    private var videoHeight = 1
    private var isCropDirty = true

    // Shader handles
    private var copyProgram = 0
    private var copyPosHandle = 0; private var copyTexHandle = 0; private var copyMatrixHandle = 0; private var copySamplerHandle = 0

    private var compProgram = 0
    private var compPosHandle = 0; private var compTexHandle = 0; private var compCameraHandle = 0; private var compMaskHandle = 0; private var compEnableHandle = 0

    private val baseVertexData = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val textureData = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)

    // baseVertexBuffer maps 1:1 for saving to the hidden FBOs
    private val baseVertexBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(baseVertexData).apply { position(0) }
    // croppedVertexBuffer applies the aspect ratio fix ONLY when drawing to the final screen
    private val croppedVertexBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(baseVertexData).apply { position(0) }
    private val textureBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(textureData).apply { position(0) }

    fun setVideoDimensions(w: Int, h: Int) {
        if (videoWidth != w || videoHeight != h) {
            videoWidth = w
            videoHeight = h
            needsFboAllocation = true
            isCropDirty = true
        }
    }

    private fun allocateFBOs() {
        if (fboArray.isNotEmpty()) {
            val fbos = IntArray(fboArray.size) { fboArray[it].fboId }
            val texs = IntArray(fboArray.size) { fboArray[it].textureId }
            GLES30.glDeleteFramebuffers(fbos.size, fbos, 0)
            GLES30.glDeleteTextures(texs.size, texs, 0)
            fboArray.clear()
        }

        for (i in 0 until RING_BUFFER_SIZE) {
            val tex = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, videoWidth, videoHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

            val fbo = IntArray(1)
            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)

            fboArray.add(FrameBuffer(fbo[0], tex[0]))
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun applyCenterCrop() {
        if (surfaceWidth == 0 || surfaceHeight == 0 || videoWidth == 0 || videoHeight == 0) return
        val screenRatio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        var scaleX = 1f; var scaleY = 1f
        if (screenRatio > videoRatio) scaleY = screenRatio / videoRatio else scaleX = videoRatio / screenRatio

        val vData = floatArrayOf(-scaleX, -scaleY, scaleX, -scaleY, -scaleX, scaleY, scaleX, scaleY)
        croppedVertexBuffer.clear()
        croppedVertexBuffer.put(vData)
        croppedVertexBuffer.position(0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        copyProgram = YuvShaderUtil.createCopyProgram()
        copyPosHandle = GLES30.glGetAttribLocation(copyProgram, "aPosition")
        copyTexHandle = GLES30.glGetAttribLocation(copyProgram, "aTextureCoord")
        copyMatrixHandle = GLES30.glGetUniformLocation(copyProgram, "uTransformMatrix")
        copySamplerHandle = GLES30.glGetUniformLocation(copyProgram, "uCameraTexture")

        compProgram = YuvShaderUtil.createCompProgram()
        compPosHandle = GLES30.glGetAttribLocation(compProgram, "aPosition")
        compTexHandle = GLES30.glGetAttribLocation(compProgram, "aTextureCoord")
        compCameraHandle = GLES30.glGetUniformLocation(compProgram, "uCameraTexture")
        compMaskHandle = GLES30.glGetUniformLocation(compProgram, "uMaskTexture")
        compEnableHandle = GLES30.glGetUniformLocation(compProgram, "uEnableGreenScreen")

        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        cameraTextureId = textures[0]
        maskTextureId = textures[1]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
        val dummyMask = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(0f).apply { position(0) }
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, 1, 1, 0, GLES30.GL_RED, GLES30.GL_FLOAT, dummyMask)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        surfaceTexture = SurfaceTexture(cameraTextureId)
        surfaceTexture?.setOnFrameAvailableListener {
            // ALWAYS request render so the Ring Buffer can quietly save the newest frames
            synchronized(maskLock) { hasNewHardwareFrame = true }
            requestRender()
        }

        onSurfaceReady(surfaceTexture!!)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        isCropDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        if (needsFboAllocation && videoWidth > 1 && videoHeight > 1) {
            allocateFBOs()
            needsFboAllocation = false
        }

        if (isCropDirty) { applyCenterCrop(); isCropDirty = false }

        // --- STEP 1: CACHE NEW VIDEO FRAME TO RING BUFFER ---
        synchronized(maskLock) {
            if (hasNewHardwareFrame) {
                surfaceTexture?.updateTexImage()
                val timestamp = surfaceTexture?.timestamp ?: 0L
                surfaceTexture?.getTransformMatrix(stMatrix)

                if (fboArray.isNotEmpty()) {
                    val currentFbo = fboArray[fboHead]
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, currentFbo.fboId)
                    GLES30.glViewport(0, 0, videoWidth, videoHeight)

                    GLES30.glUseProgram(copyProgram)
                    GLES30.glUniformMatrix4fv(copyMatrixHandle, 1, false, stMatrix, 0)

                    GLES30.glEnableVertexAttribArray(copyPosHandle)
                    GLES30.glVertexAttribPointer(copyPosHandle, 2, GLES30.GL_FLOAT, false, 0, baseVertexBuffer)
                    GLES30.glEnableVertexAttribArray(copyTexHandle)
                    GLES30.glVertexAttribPointer(copyTexHandle, 2, GLES30.GL_FLOAT, false, 0, textureBuffer)

                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
                    GLES30.glUniform1i(copySamplerHandle, 0)

                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

                    currentFbo.timestamp = timestamp
                    fboHead = (fboHead + 1) % RING_BUFFER_SIZE
                }
                hasNewHardwareFrame = false
            }

            // --- STEP 2: UPLOAD NEW AI MASK ---
            if (isMaskDirty && pendingMaskBuffer != null) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
                pendingMaskBuffer!!.rewind()
                if (!isMaskAllocated) {
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, pendingMaskWidth, pendingMaskHeight, 0, GLES30.GL_RED, GLES30.GL_FLOAT, pendingMaskBuffer)
                    isMaskAllocated = true
                } else {
                    GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, pendingMaskWidth, pendingMaskHeight, GLES30.GL_RED, GLES30.GL_FLOAT, pendingMaskBuffer)
                }
                isMaskDirty = false
            }
        }

        // --- STEP 3: FIND THE PERFECTLY SYNCED FRAME ---
        if (fboArray.isEmpty()) return // Don't draw if buffer isn't ready

        var matchedTexId = -1
        var bestIdx = (fboHead - 1 + RING_BUFFER_SIZE) % RING_BUFFER_SIZE // Default to absolute newest frame

        // If Green Screen is on, travel back in time to match the AI timestamp!
        if (enableGreenScreen && pendingMaskTimestamp != 0L) {
            var minDiff = Long.MAX_VALUE
            for (i in fboArray.indices) {
                if (fboArray[i].timestamp == 0L) continue
                val diff = Math.abs(fboArray[i].timestamp - pendingMaskTimestamp)
                if (diff < minDiff) {
                    minDiff = diff
                    bestIdx = i
                }
            }
        }
        matchedTexId = fboArray[bestIdx].textureId

        // --- STEP 4: DRAW TO SCREEN ---
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(compProgram)
        GLES30.glUniform1i(compEnableHandle, if (enableGreenScreen) 1 else 0)

        // Draw using the Cropped Vertex Buffer so it looks proportional on screen
        GLES30.glEnableVertexAttribArray(compPosHandle)
        GLES30.glVertexAttribPointer(compPosHandle, 2, GLES30.GL_FLOAT, false, 0, croppedVertexBuffer)
        GLES30.glEnableVertexAttribArray(compTexHandle)
        GLES30.glVertexAttribPointer(compTexHandle, 2, GLES30.GL_FLOAT, false, 0, textureBuffer)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, matchedTexId)
        GLES30.glUniform1i(compCameraHandle, 0)

        if (enableGreenScreen) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTextureId)
            GLES30.glUniform1i(compMaskHandle, 1)
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun setNextMask(rawByteBuffer: ByteBuffer, width: Int, height: Int, timestamp: Long) {
        synchronized(maskLock) {
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
            pendingMaskTimestamp = timestamp
            isMaskDirty = true
        }
    }
}