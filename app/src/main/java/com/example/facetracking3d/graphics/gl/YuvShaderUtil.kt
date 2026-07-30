package com.example.facetracking3d.graphics.gl

import android.opengl.GLES30

object YuvShaderUtil {

    // =========================================================================
    // PASS 1: THE COPIER (Hardware OES -> Hidden Frame Buffer)
    // =========================================================================
    const val COPY_VERTEX_SHADER = """#version 300 es
        in vec4 aPosition;
        in vec2 aTextureCoord;
        uniform mat4 uTransformMatrix; 
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vec2 hwCoord = (uTransformMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
            // Apply all orientation math here, so it is saved perfectly upright
            vTexCoord = vec2(1.0 - hwCoord.y, 1.0 - hwCoord.x);
        }
    """

    const val COPY_FRAGMENT_SHADER = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        in vec2 vTexCoord;
        out vec4 FragColor;
        uniform samplerExternalOES uCameraTexture;
        void main() {
            FragColor = texture(uCameraTexture, vTexCoord);
        }
    """

    // =========================================================================
    // PASS 2: THE COMPOSITOR (Hidden Frame Buffer + Mask -> Screen)
    // =========================================================================
    const val COMP_VERTEX_SHADER = """#version 300 es
        in vec4 aPosition;
        in vec2 aTextureCoord;
        out vec2 vCameraCoord;
        out vec2 vMaskCoord;
        void main() {
            gl_Position = aPosition;
            vCameraCoord = aTextureCoord; // Already corrected by Pass 1!
            vMaskCoord = vec2(aTextureCoord.x, 1.0 - aTextureCoord.y);
        }
    """

    const val COMP_FRAGMENT_SHADER = """#version 300 es
        precision mediump float;
        in vec2 vCameraCoord;
        in vec2 vMaskCoord;
        out vec4 FragColor;
        
        // This is a standard sampler2D now, because we are pulling from the FBO, not the camera!
        uniform sampler2D uCameraTexture; 
        uniform sampler2D uMaskTexture;
        uniform int uEnableGreenScreen;
        
        void main() {
            vec4 cameraColor = texture(uCameraTexture, vCameraCoord);
            if (uEnableGreenScreen == 1) {
                float maskConfidence = texture(uMaskTexture, vMaskCoord).r;
                float alpha = smoothstep(0.4, 0.6, maskConfidence);
                FragColor = mix(vec4(0.0, 1.0, 0.0, 1.0), cameraColor, alpha);
            } else {
                FragColor = cameraColor; 
            }
        }
    """

    fun createCopyProgram(): Int {
        val v = loadShader(GLES30.GL_VERTEX_SHADER, COPY_VERTEX_SHADER)
        val f = loadShader(GLES30.GL_FRAGMENT_SHADER, COPY_FRAGMENT_SHADER)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        return p
    }

    fun createCompProgram(): Int {
        val v = loadShader(GLES30.GL_VERTEX_SHADER, COMP_VERTEX_SHADER)
        val f = loadShader(GLES30.GL_FRAGMENT_SHADER, COMP_FRAGMENT_SHADER)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        return p
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        return shader
    }
}