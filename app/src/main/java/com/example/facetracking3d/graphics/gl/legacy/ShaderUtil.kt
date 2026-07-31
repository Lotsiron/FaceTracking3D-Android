package com.example.facetracking3d.graphics.gl.legacy

import android.opengl.GLES30

object ShaderUtil {
    private const val TAG = "ShaderUtil"

    const val VERTEX_SHADER = """#version 300 es
        in vec4 aPosition;
        in vec4 aTextureCoord;
        
        out vec2 vCoord;
        
        void main() {
            gl_Position = aPosition;
            // Android Bitmaps load upside down in OpenGL. We flip the Y-axis.
            vCoord = vec2(aTextureCoord.x, 1.0 - aTextureCoord.y);
        }
    """

    const val FRAGMENT_SHADER = """#version 300 es
        precision mediump float;
        
        in vec2 vCoord;
        out vec4 FragColor;
        
        // BOTH are now standard 2D textures!
        uniform sampler2D uCameraTexture;
        uniform sampler2D uMaskTexture;
        
        void main() {
            vec4 cameraColor = texture(uCameraTexture, vCoord);
            float maskConfidence = texture(uMaskTexture, vCoord).r;
            
            float alpha = smoothstep(0.4, 0.6, maskConfidence);
            
            vec4 greenColor = vec4(0.0, 1.0, 0.0, 1.0);
            FragColor = mix(greenColor, cameraColor, alpha);
        }
    """

    fun createProgram(): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertexShader == 0 || fragmentShader == 0) return 0

        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        return shader
    }
}