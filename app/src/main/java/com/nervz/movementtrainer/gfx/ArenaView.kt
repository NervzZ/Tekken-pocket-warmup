package com.nervz.movementtrainer.gfx

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.nervz.movementtrainer.input.MovementState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

class ArenaView(context: Context, movement: MovementState) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setRenderer(ArenaRenderer(movement))
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}

// Minimal Tekken-practice-style scene: a translucent floor grid that scrolls
// under a low-poly mannequin. The character stays centered; world position px
// only moves the grid, so the arena is effectively infinite for backdash work.
class ArenaRenderer(private val movement: MovementState) : GLSurfaceView.Renderer {

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

    // simulation
    private var px = 0f
    private var pz = 0f
    private var vImpulse = 0f
    private var zImpulse = 0f
    private var crouch = 0f
    private var crouchPulse = 0f
    private var walkPhase = 0f
    private var time = 0f
    private var lastNanos = 0L
    private var seenBd = 0
    private var seenDash = 0
    private var seenCd = 0
    private var seenSs = 0

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
        Matrix.setLookAtM(view, 0, 0f, 2.1f, 11f, 0f, 1.0f, 0f, 0f, 1f, 0f)
        lastNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 42f, aspect, 0.4f, 60f)
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = System.nanoTime()
        val dt = min(0.05f, (now - lastNanos) / 1_000_000_000f)
        lastNanos = now
        time += dt
        step(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        drawGrid()
        drawCharacter()
    }

    private fun step(dt: Float) {
        val facing = movement.facing
        var bd = movement.backdashes.get()
        while (seenBd < bd) { seenBd++; vImpulse = -5.5f * facing }
        bd = movement.dashes.get()
        while (seenDash < bd) { seenDash++; vImpulse = 5.5f * facing }
        bd = movement.crouchDashes.get()
        while (seenCd < bd) { seenCd++; vImpulse = 4.4f * facing; crouchPulse = 0.22f }
        bd = movement.sidesteps.get()
        while (seenSs < bd) { seenSs++; zImpulse = -3.6f }

        val heldX = movement.heldX
        val walkSpeed = if (heldX == facing) 1.8f else 1.1f
        val vWalk = heldX * walkSpeed
        val vx = vWalk + vImpulse
        vImpulse *= exp(-dt * 6f)
        px += vx * dt

        pz += zImpulse * dt
        zImpulse *= exp(-dt * 8f)
        pz = pz.coerceIn(-2.2f, 2.2f)

        crouchPulse = (crouchPulse - dt).coerceAtLeast(0f)
        val crouchTarget = if (movement.crouching || crouchPulse > 0f) 1f else 0f
        crouch += (crouchTarget - crouch) * min(1f, dt * 12f)

        walkPhase += abs(vx) * dt * 7f
    }

    private fun drawGrid() {
        // world scrolls opposite to the character's travel
        val offset = -(((px % 1f) + 1f) % 1f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, offset, 0f, 0f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(uColor, 0.55f, 0.65f, 0.75f, 0.16f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, grid)
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridLineCount * 2)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun drawCharacter() {
        val facing = movement.facing.toFloat()
        val c = crouch
        val bob = sin(time * 2.3f) * 0.02f
        val drop = 0.42f * c
        val swingAmp = min(0.22f, abs(vImpulse + movement.heldX * 1.5f) * 0.09f) + 0.02f
        val swing = sin(walkPhase) * swingAmp
        val lean = facing * 0.12f * c

        // legs
        part(lean + swing, 0.34f - drop * 0.4f, pz - 0.13f, 0.16f, 0.62f - 0.22f * c, 0.16f, 0.78f, 0.80f, 0.86f, 0.95f)
        part(lean - swing, 0.34f - drop * 0.4f, pz + 0.13f, 0.16f, 0.62f - 0.22f * c, 0.16f, 0.70f, 0.72f, 0.78f, 0.95f)
        // torso
        part(lean, 0.97f + bob - drop, pz, 0.46f, 0.62f, 0.30f, 0.86f, 0.88f, 0.93f, 0.95f)
        // arms
        part(lean - swing * 0.7f, 0.97f + bob - drop, pz - 0.30f, 0.12f, 0.5f, 0.12f, 0.72f, 0.74f, 0.80f, 0.95f)
        part(lean + swing * 0.7f, 0.97f + bob - drop, pz + 0.30f, 0.12f, 0.5f, 0.12f, 0.72f, 0.74f, 0.80f, 0.95f)
        // head
        part(lean, 1.54f + bob - drop, pz, 0.26f, 0.26f, 0.26f, 0.86f, 0.88f, 0.93f, 0.95f)
        // visor marks the facing direction
        part(lean + facing * 0.12f, 1.56f + bob - drop, pz, 0.08f, 0.07f, 0.20f, 0.91f, 0.20f, 0.25f, 1f)
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
        val zNear = 2.5f
        val zFar = -5.5f
        for (i in -14..14) {
            lines.addAll(listOf(i.toFloat(), 0f, zNear, i.toFloat(), 0f, zFar))
        }
        var z = zNear
        while (z >= zFar) {
            lines.addAll(listOf(-14f, 0f, z, 14f, 0f, z))
            z -= 1f
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
        // unit cube, 12 triangles
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
