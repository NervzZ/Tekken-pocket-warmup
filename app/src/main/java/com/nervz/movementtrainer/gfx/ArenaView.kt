package com.nervz.movementtrainer.gfx

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import kotlin.math.round

// Ground layer: solid floor, scrolling/rotating grid texture, pivot dot, and
// the character's blob shadow. Also the thread that steps the shared ArenaSim.
// The skinned character itself is rendered by MokujinView (Filament) above.
class ArenaView(context: Context, sim: ArenaSim) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setRenderer(ArenaRenderer(sim))
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}

class ArenaRenderer(private val sim: ArenaSim) : GLSurfaceView.Renderer {

    private var program = 0
    private var aPos = 0
    private var uMvp = 0
    private var uColor = 0

    private lateinit var cube: FloatBuffer
    private lateinit var grid: FloatBuffer
    private var gridLineCount = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private var lastNanos = 0L

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.063f, 0.078f, 0.094f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vs = """
            attribute vec3 aPos;
            uniform mat4 uMvp;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); }
        """
        val fs = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uColor = GLES20.glGetUniformLocation(program, "uColor")

        cube = floatBufferOf(*CUBE_VERTS)
        buildGrid()
        lastNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 42f, aspect, 0.4f, 80f)
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = System.nanoTime()
        val dt = min(0.05f, (now - lastNanos) / 1_000_000_000f)
        lastNanos = now
        sim.step(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        Matrix.setLookAtM(
            view, 0,
            sim.camEyeX, sim.camEyeY, sim.camEyeZ,
            sim.camCtrX, 1.0f, 0f, 0f, 1f, 0f,
        )
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // solid ground plane (static in character space)
        part(0f, -0.05f, 0f, 80f, 0.1f, 80f, 0.085f, 0.104f, 0.124f, 1f)

        // grid texture carrying the orbit scroll/rotation
        val cx = sim.charWorldX()
        val cz = sim.charWorldZ()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0.012f, 0f)
        Matrix.rotateM(model, 0, sim.worldRotationDeg(), 0f, 1f, 0f)
        Matrix.translateM(model, 0, round(cx) - cx, 0f, round(cz) - cz)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(uColor, 0.55f, 0.65f, 0.75f, 0.21f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, grid)
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridLineCount * 2)
        GLES20.glDisableVertexAttribArray(aPos)

        // ground-contact reference dots: character (char space origin) and
        // the orbit pivot — the square blob shadow is gone (user request)
        part(0f, 0.03f, 0f, 0.26f, 0.015f, 0.26f, 0.91f, 0.20f, 0.23f, 0.6f)
        part(sim.facingF * sim.dist, 0.03f, 0f, 0.26f, 0.015f, 0.26f, 0.91f, 0.20f, 0.23f, 0.6f)
    }

    private fun part(
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.scaleM(model, 0, sx, sy, sz)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(uColor, r, g, b, a)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, cube)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun buildGrid() {
        val lines = ArrayList<Float>()
        for (i in -16..16) {
            lines.addAll(listOf(i.toFloat(), 0f, 16f, i.toFloat(), 0f, -16f))
            lines.addAll(listOf(16f, 0f, i.toFloat(), -16f, 0f, i.toFloat()))
        }
        gridLineCount = lines.size / 6
        grid = floatBufferOf(*lines.toFloatArray())
    }

    private fun compile(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .apply { position(0) }

    companion object {
        private val CUBE_VERTS = floatArrayOf(
            -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f,
            0.5f, 0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f,
            0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f,
            0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f,
            -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f,
            0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f,
            -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f,
            0.5f, 0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f,
            0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f,
            0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f,
            0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f,
            0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f,
        )
    }
}
