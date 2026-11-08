package com.example.stickermaker.editor

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A video decoder's output [surface], drawn small on the GPU and read back. The decoder hands
 * frames straight to the GPU, which rotates them upright and shrinks them to [width]×[height]
 * (a box filter, so detail doesn't shimmer), and only that small frame is copied out. A full
 * size frame never reaches app memory, which is most of the cost of reading a video.
 *
 * Use from one thread only: the GL context is current on the thread that built it.
 *
 * @param rotation clockwise degrees the video is shown at (0, 90, 180 or 270).
 * @param shrink source pixels per output pixel (1 or less: no filtering needed).
 */
internal class FrameGrabber(private val width: Int, private val height: Int, rotation: Int, shrink: Float) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val pbuffer: android.opengl.EGLSurface
    private val texture: Int
    private val program: Int
    private val surfaceTexture: SurfaceTexture
    val surface: Surface

    /** Delivers "frame available", which can't arrive on the (busy, looper-less) GL thread. */
    private val callbacks = HandlerThread("video-frames-gl").apply { start() }
    private val lock = Object()
    private var available = false

    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    private val st = FloatArray(16)
    private val upright = uprightMatrix(rotation)
    private val matrix = FloatArray(16)
    private val spread = (shrink - 1f).coerceIn(0f, 1f)

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val found = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) && found[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface" }
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = link(VERTEX, FRAGMENT)

        surfaceTexture = SurfaceTexture(texture)
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(lock) {
                available = true
                lock.notifyAll()
            }
        }, Handler(callbacks.looper))
        surface = Surface(surfaceTexture)
    }

    /**
     * Waits for the frame the decoder just released to [surface], draws it and returns its
     * pixels: RGBA, top row first, valid until the next call.
     */
    fun grab(): ByteBuffer {
        synchronized(lock) {
            val deadline = SystemClock.uptimeMillis() + FRAME_TIMEOUT_MS
            while (!available) {
                val left = deadline - SystemClock.uptimeMillis()
                check(left > 0) { "No frame from the decoder" }
                lock.wait(left)
            }
            available = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(st)
        // Output position → upright source position → texture position.
        Matrix.multiplyMM(matrix, 0, st, 0, upright, 0)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uM"), 1, false, matrix, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uPixel"), 1f / width, 1f / height)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSpread"), spread)
        val position = GLES20.glGetAttribLocation(program, "aPos")
        val out = GLES20.glGetAttribLocation(program, "aOut")
        QUAD.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(position)
        QUAD.position(2)
        GLES20.glVertexAttribPointer(out, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(out)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Reading back right after drawing stalls far longer on some GPUs than finishing first.
        GLES20.glFinish()
        pixels.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "glReadPixels" }
        pixels.rewind()
        return pixels
    }

    override fun close() {
        surface.release()
        surfaceTexture.release()
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        // Not eglTerminate: the display is shared with the rest of the process.
        EGL14.eglReleaseThread()
        callbacks.quitSafely()
    }

    private companion object {
        const val FRAME_TIMEOUT_MS = 3_000L

        /**
         * Output (u, v) → where that point is in the stored frame, for a video shown turned
         * [rotation]° clockwise. Both in texture space: 0..1, v up.
         */
        fun uprightMatrix(rotation: Int): FloatArray {
            // x' = a·u + b·v + c, y' = d·u + e·v + f
            val (a, b, c, d, e, f) = when (((rotation % 360) + 360) % 360) {
                90 -> Six(0f, -1f, 1f, 1f, 0f, 0f)
                180 -> Six(-1f, 0f, 1f, 0f, -1f, 1f)
                270 -> Six(0f, 1f, 0f, -1f, 0f, 1f)
                else -> Six(1f, 0f, 0f, 0f, 1f, 0f)
            }
            // Column-major.
            return floatArrayOf(a, d, 0f, 0f, b, e, 0f, 0f, 0f, 0f, 1f, 0f, c, f, 0f, 1f)
        }

        data class Six(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)

        /**
         * A full-screen strip, drawn upside down: glReadPixels returns the bottom row first,
         * so this way its first row is the frame's top. Each vertex: position x, y; output u, v.
         */
        val QUAD: java.nio.FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f, -1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f))
        }

        const val VERTEX = """
            attribute vec4 aPos;
            attribute vec2 aOut;
            varying vec2 vOut;
            void main() {
                gl_Position = aPos;
                vOut = aOut;
            }
        """

        /**
         * A 4×4 box of bilinear taps across each output pixel ([uSpread] narrows it when the
         * frame barely shrinks), clamped inside the frame so edges never pick up padding.
         * highp: mediump can't address a 4K frame's pixels.
         */
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vOut;
            uniform samplerExternalOES sTex;
            uniform mat4 uM;
            uniform vec2 uPixel;
            uniform float uSpread;
            void main() {
                vec4 sum = vec4(0.0);
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        vec2 o = vOut + (vec2(float(i), float(j)) - 1.5) * 0.25 * uSpread * uPixel;
                        o = clamp(o, uPixel * 0.5, 1.0 - uPixel * 0.5);
                        sum += texture2D(sTex, (uM * vec4(o, 0.0, 1.0)).xy);
                    }
                }
                gl_FragColor = vec4((sum / 16.0).rgb, 1.0);
            }
        """

        fun link(vertex: String, fragment: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source.trimIndent())
                GLES20.glCompileShader(shader)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "Shader: " + GLES20.glGetShaderInfoLog(shader) }
                return shader
            }
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(program)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "Link: " + GLES20.glGetProgramInfoLog(program) }
            return program
        }
    }
}
