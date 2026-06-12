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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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

    private lateinit var grid: FloatBuffer
    private lateinit var disc: FloatBuffer
    private lateinit var rim: FloatBuffer
    private var discVertCount = 0
    private var rimVertCount = 0
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

        buildGrid()
        buildDisc()
        buildRim()
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

        // the arena is a disc of radius MAX_DIST around the orbit pivot: the
        // floor, grid, and rim all end at the movement limit so the boundary
        // is visible. The pivot (world origin) lands exactly on the red dot
        // at char-space (facing*dist, 0), and the disc/rim are rotation-
        // invariant, so both draw directly there; only the grid carries the
        // world rotation.
        val px = sim.facingF * sim.dist
        dot(px, 0f, 0f, ArenaSim.MAX_DIST, 0.085f, 0.104f, 0.124f, 1f)

        // grid texture carrying the orbit scroll/rotation, clipped at the
        // rim: the chord geometry is static in WORLD space, so it translates
        // by the true -C offset (the lattice round() trick needed periodic
        // geometry and no longer applies)
        val cx = sim.charWorldX()
        val cz = sim.charWorldZ()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0.012f, 0f)
        Matrix.rotateM(model, 0, sim.worldRotationDeg(), 0f, 1f, 0f)
        Matrix.translateM(model, 0, -cx, 0f, -cz)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(uColor, 0.55f, 0.65f, 0.75f, 0.21f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, grid)
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridLineCount * 2)
        GLES20.glDisableVertexAttribArray(aPos)

        // boundary rim — the visible "you can't move past this" line
        ring(px, 0.018f, 0f, ArenaSim.MAX_DIST, 0.55f, 0.65f, 0.75f, 0.55f)

        // round blob shadow under the character (the alignment-reference red
        // dot served its purpose and is retired); red dot = the orbit pivot
        dot(0f, 0.022f, 0f, 0.42f, 0.02f, 0.03f, 0.04f, 0.5f)
        dot(px, 0.03f, 0f, 0.105f, 1f, 0.13f, 0.16f, 0.95f)
    }

    // flat disc (triangle fan) lying in the ground plane
    private fun dot(
        x: Float, y: Float, z: Float, radius: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.scaleM(model, 0, radius, 1f, radius)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(uColor, r, g, b, a)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, disc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, discVertCount)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    // flat circle outline (line loop) lying in the ground plane
    private fun ring(
        x: Float, y: Float, z: Float, radius: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.scaleM(model, 0, radius, 1f, radius)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(uColor, r, g, b, a)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, rim)
        GLES20.glLineWidth(3f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, rimVertCount)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun buildDisc() {
        // 64 segments: the same fan draws the tiny dots AND the arena floor,
        // whose rim is big enough to show 32-segment facets
        val segs = 64
        val verts = ArrayList<Float>(3 * (segs + 2))
        verts.addAll(listOf(0f, 0f, 0f))
        for (i in 0..segs) {
            val a = i.toDouble() / segs * 2.0 * Math.PI
            verts.addAll(listOf(cos(a).toFloat(), 0f, sin(a).toFloat()))
        }
        discVertCount = segs + 2
        disc = floatBufferOf(*verts.toFloatArray())
    }

    private fun buildRim() {
        val segs = 96
        val verts = ArrayList<Float>(3 * segs)
        for (i in 0 until segs) {
            val a = i.toDouble() / segs * 2.0 * Math.PI
            verts.addAll(listOf(cos(a).toFloat(), 0f, sin(a).toFloat()))
        }
        rimVertCount = segs
        rim = floatBufferOf(*verts.toFloatArray())
    }

    // world-space integer grid clipped to the arena circle: chords of the
    // MAX_DIST disc around the world origin (the orbit pivot)
    private fun buildGrid() {
        val r = ArenaSim.MAX_DIST
        val lines = ArrayList<Float>()
        for (i in -r.toInt()..r.toInt()) {
            val h = sqrt(r * r - i * i)   // half-chord at lattice offset i
            lines.addAll(listOf(i.toFloat(), 0f, h, i.toFloat(), 0f, -h))
            lines.addAll(listOf(h, 0f, i.toFloat(), -h, 0f, i.toFloat()))
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
}
