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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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

// Tekken-practice-style scene. The wooden mannequin stays centered; the floor
// grid scrolls in BOTH axes (x = forward/back travel, z = sidestep depth), so
// movement reads like the in-game camera following the character. Quarter-view
// camera avoids the flat "perfect profile" look.
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

    // ---- simulation ----
    private var px = 0f
    private var pz = 0f
    private var vImpulse = 0f
    private var zImpulse = 0f
    private var zWalk = 0f
    private var crouch = 0f
    private var walkPhase = 0f
    private var sidePhase = 0f
    private var time = 0f
    private var lastNanos = 0L

    private var seenBd = 0
    private var seenCancel = 0
    private var seenDash = 0
    private var seenCd = 0
    private var seenSsUp = 0
    private var seenSsDown = 0

    private enum class Clip(val dur: Float) {
        NONE(0f),
        BACKDASH(0.38f),
        CANCELDIP(0.14f),
        CROUCHDASH(0.42f),
        SIDESTEP_UP(0.30f),
        SIDESTEP_DOWN(0.30f),
        JUMP(0.62f),
    }

    private var clip = Clip.NONE
    private var clipT = 0f
    private var lastSsTime = -10f
    private var sidewalk = 0          // -1 toward camera, +1 into screen, 0 off
    private var heldUpStart = -1f
    private var jumpArmed = true

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
        Matrix.setLookAtM(view, 0, 2.7f, 2.3f, 10.3f, 0.3f, 1.0f, 0f, 0f, 1f, 0f)
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

    // Interrupt rules: a backdash is only broken early by the KBD cancel chain,
    // a crouchdash only by the next crouchdash/backdash, sidesteps by anything,
    // a jump never (you are airborne).
    private fun canStart(new: Clip): Boolean {
        if (clip == Clip.NONE) return true
        val t01 = if (clip.dur > 0f) clipT / clip.dur else 1f
        return when (clip) {
            Clip.JUMP -> false
            Clip.SIDESTEP_UP, Clip.SIDESTEP_DOWN -> true
            Clip.BACKDASH -> new == Clip.BACKDASH || new == Clip.CANCELDIP || t01 > 0.55f
            Clip.CANCELDIP -> new == Clip.BACKDASH || new == Clip.CANCELDIP || t01 > 0.8f
            Clip.CROUCHDASH -> new == Clip.CROUCHDASH || new == Clip.BACKDASH || t01 > 0.55f
            else -> true
        }
    }

    private fun startClip(c: Clip) {
        if (!canStart(c)) return
        clip = c
        clipT = 0f
    }

    private fun step(dt: Float) {
        val facing = movement.facing

        var n = movement.backdashes.get()
        while (seenBd < n) { seenBd++; vImpulse = -5.5f * facing; startClip(Clip.BACKDASH) }
        n = movement.kbdCancels.get()
        while (seenCancel < n) { seenCancel++; vImpulse -= 1.2f * facing; startClip(Clip.CANCELDIP) }
        n = movement.dashes.get()
        while (seenDash < n) { seenDash++; vImpulse = 5.5f * facing }
        n = movement.crouchDashes.get()
        while (seenCd < n) { seenCd++; vImpulse = 4.6f * facing; startClip(Clip.CROUCHDASH) }
        n = movement.sidestepsUp.get()
        while (seenSsUp < n) {
            seenSsUp++; zImpulse = -3.4f; lastSsTime = time; startClip(Clip.SIDESTEP_UP)
        }
        n = movement.sidestepsDown.get()
        while (seenSsDown < n) {
            seenSsDown++; zImpulse = 3.4f; lastSsTime = time; startClip(Clip.SIDESTEP_DOWN)
        }

        // sidewalk: a sidestep followed shortly by holding the same vertical input
        sidewalk = when {
            movement.heldUp && (time - lastSsTime < 0.5f || sidewalk == 1) -> 1
            movement.heldDown && (time - lastSsTime < 0.5f || sidewalk == -1) -> -1
            else -> 0
        }
        val zTarget = when (sidewalk) {
            1 -> -1.3f
            -1 -> 1.3f
            else -> 0f
        }
        zWalk += (zTarget - zWalk) * min(1f, dt * 9f)

        // jump: straight up held with no sidestep context
        if (movement.heldUp) {
            if (heldUpStart < 0f) heldUpStart = time
            if (jumpArmed && sidewalk == 0 && time - heldUpStart > 0.22f &&
                time - lastSsTime > 0.6f && clip != Clip.JUMP
            ) {
                startClip(Clip.JUMP)
                if (clip == Clip.JUMP) jumpArmed = false
            }
        } else {
            heldUpStart = -1f
            jumpArmed = true
        }

        clipT += dt
        if (clip != Clip.NONE && clipT >= clip.dur) clip = Clip.NONE

        val inClipDamp = if (clip == Clip.BACKDASH || clip == Clip.CROUCHDASH) {
            1f - 0.6f * envelope()
        } else 1f
        val heldX = movement.heldX
        val walkSpeed = if (heldX == facing) 1.8f else 1.1f
        val vx = heldX * walkSpeed * inClipDamp + vImpulse
        vImpulse *= exp(-dt * 6f)
        px += vx * dt

        pz += (zImpulse + zWalk) * dt
        zImpulse *= exp(-dt * 8f)

        val crouchTarget = if (movement.crouching && sidewalk != -1) 1f else 0f
        crouch += (crouchTarget - crouch) * min(1f, dt * 12f)

        walkPhase += abs(vx) * dt * 7f
        sidePhase += abs(zImpulse + zWalk) * dt * 7f
    }

    private fun envelope(): Float {
        if (clip == Clip.NONE || clip.dur <= 0f) return 0f
        val t01 = (clipT / clip.dur).coerceIn(0f, 1f)
        return sin(t01 * PI.toFloat())
    }

    private fun drawGrid() {
        val offX = -(((px % 1f) + 1f) % 1f)
        val offZ = -(((pz % 1f) + 1f) % 1f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, offX, 0f, offZ)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(uColor, 0.55f, 0.65f, 0.75f, 0.17f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, grid)
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridLineCount * 2)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    // ---- mokujin ----

    private class Pose {
        var rootY = 0f
        var twist = 0f          // whole-body yaw, degrees
        var lean = 0f           // torso sagittal lean, + = toward facing
        var legFSag = 0f; var legFKnee = 0f; var legFLat = 0f
        var legBSag = 0f; var legBKnee = 0f; var legBLat = 0f
        var armFSag = 0f; var armFElbow = 0f; var armFLat = 0f
        var armBSag = 0f; var armBElbow = 0f; var armBLat = 0f
    }

    private val pose = Pose()

    private fun computePose() {
        val p = pose
        val env = envelope()
        val c = crouch
        val bob = sin(time * 2.1f) * 1.5f

        // battle stance (facing-local sagittal angles, + = forward)
        p.rootY = 0f
        p.twist = -24f
        p.lean = 3f + bob * 0.4f
        p.legFSag = 16f; p.legFKnee = 20f; p.legFLat = -6f
        p.legBSag = -14f; p.legBKnee = 16f; p.legBLat = 6f
        p.armFSag = 42f; p.armFElbow = -98f; p.armFLat = -8f
        p.armBSag = 22f; p.armBElbow = -105f; p.armBLat = 10f

        // walk cycle on top of stance
        val walkAmp = min(1f, abs(movement.heldX.toFloat()) + abs(vImpulse) * 0.3f)
        if (walkAmp > 0.05f) {
            val s = sin(walkPhase)
            p.legFSag += s * 24f * walkAmp
            p.legBSag += -s * 24f * walkAmp
            p.legFKnee += (1f - s).coerceAtLeast(0f) * 10f * walkAmp
            p.legBKnee += (1f + s).coerceAtLeast(0f) * 10f * walkAmp
            p.armFSag += -s * 14f * walkAmp
            p.armBSag += s * 14f * walkAmp
        }

        // sidewalk cycle: legs swing laterally, body squares up to the camera
        if (abs(zWalk) > 0.1f) {
            val s = sin(sidePhase)
            val dir = if (zWalk < 0) 1f else -1f
            p.twist += dir * 18f
            p.legFLat += s * 20f * dir
            p.legBLat += -s * 20f * dir
            p.legFKnee += 8f; p.legBKnee += 8f
        }

        // crouch
        p.legFKnee += 52f * c
        p.legBKnee += 48f * c
        p.legFSag += 22f * c
        p.legBSag += -26f * c
        p.lean += 14f * c

        when (clip) {
            Clip.BACKDASH -> {
                p.lean += -16f * env
                p.legFSag += 26f * env
                p.legFKnee += 6f * env
                p.legBSag += -18f * env
                p.legBKnee += 22f * env
                p.armFSag += -12f * env
                p.rootY += 0.10f * env
            }
            Clip.CANCELDIP -> {
                p.legFKnee += 30f * env
                p.legBKnee += 28f * env
                p.lean += 8f * env
                p.rootY -= 0.05f * env
            }
            Clip.CROUCHDASH -> {
                p.lean += 24f * env
                p.legFSag += 30f * env
                p.legFKnee += 18f * env
                p.legBSag += -24f * env
                p.legBKnee += 6f * env
                p.armFSag += 26f * env
                p.armBSag += -14f * env
                p.rootY -= 0.16f * env
            }
            Clip.SIDESTEP_UP, Clip.SIDESTEP_DOWN -> {
                val dir = if (clip == Clip.SIDESTEP_UP) 1f else -1f
                p.twist += dir * 26f * env
                p.legFLat += -dir * 22f * env
                p.legBLat += dir * 14f * env
                p.legFKnee += 10f * env
                p.rootY += 0.05f * env
            }
            Clip.JUMP -> {
                val t = clipT
                p.rootY += (3.4f * t - 5.6f * t * t).coerceAtLeast(0f)
                val tuck = env
                p.legFKnee += 55f * tuck
                p.legBKnee += 60f * tuck
                p.legFSag += 25f * tuck
                p.legBSag += -20f * tuck
                p.armFSag += 20f * tuck
                p.armBSag += 14f * tuck
            }
            Clip.NONE -> {}
        }
    }

    private val rad = (PI / 180.0).toFloat()

    private fun drawCharacter() {
        computePose()
        val p = pose
        val f = movement.facing.toFloat()

        val l1 = 0.45f
        val l2 = 0.42f
        // support height from the back leg so feet stay near the floor
        val kneeB = (p.legBKnee * rad)
        val sagB = (p.legBSag * rad)
        val hipY = (l1 * cos(sagB) + l2 * cos(sagB + kneeB)) + p.rootY

        val chestY = hipY + 0.40f
        val headY = hipY + 0.80f
        val shoulderY = hipY + 0.52f

        // wood palette
        val wr = 0.78f; val wg = 0.62f; val wb = 0.42f
        val dr = 0.64f; val dg = 0.50f; val db = 0.33f

        // torso: hips + chest, slightly twisted toward camera
        part(0f, hipY + 0.08f, 0f, 0f, p.twist * f, p.lean * f, 0.34f, 0.26f, 0.26f, dr, dg, db, 0.98f)
        part(0.02f * f, chestY, 0f, 0f, p.twist * f, p.lean * f, 0.42f, 0.44f, 0.30f, wr, wg, wb, 0.98f)
        // head + face mark
        part(0.03f * f, headY, 0f, 0f, p.twist * f, p.lean * f * 0.5f, 0.24f, 0.26f, 0.24f, wr, wg, wb, 0.98f)
        part(0.03f * f + f * 0.11f, headY + 0.02f, -0.04f, 0f, p.twist * f, 0f, 0.06f, 0.08f, 0.10f, 0.30f, 0.20f, 0.12f, 1f)

        // legs (hip anchors offset along z by stance + facing twist)
        limb(0.04f * f, hipY, -0.11f, l1, l2, 0.15f, p.legFSag * f, p.legFLat, p.legFKnee * f, 0f, wr, wg, wb, dr, dg, db)
        limb(-0.04f * f, hipY, 0.11f, l1, l2, 0.15f, p.legBSag * f, p.legBLat, p.legBKnee * f, 0f, wr, wg, wb, dr, dg, db)

        // arms (negative elbow = forearm folds forward/up into guard)
        limb(0.04f * f, shoulderY, -0.27f, 0.30f, 0.28f, 0.11f, p.armFSag * f, p.armFLat, p.armFElbow * f, 0f, wr, wg, wb, dr, dg, db)
        limb(-0.02f * f, shoulderY, 0.27f, 0.30f, 0.28f, 0.11f, p.armBSag * f, p.armBLat, p.armBElbow * f, 0f, wr, wg, wb, dr, dg, db)
    }

    // Two-segment limb. Sagittal angles rotate about Z (0 = straight down,
    // + = toward +x), lateral about X (+ = toward -z / into the screen).
    private fun limb(
        ax: Float, ay: Float, az: Float,
        len1: Float, len2: Float, thick: Float,
        sag1: Float, lat1: Float, sag2: Float, lat2: Float,
        r1: Float, g1: Float, b1: Float,
        r2: Float, g2: Float, b2: Float,
    ) {
        val t1 = sag1 * rad
        val q1 = lat1 * rad
        val d1x = sin(t1) * cos(q1)
        val d1y = -cos(t1) * cos(q1)
        val d1z = -sin(q1)
        part(
            ax + d1x * len1 / 2f, ay + d1y * len1 / 2f, az + d1z * len1 / 2f,
            lat1, 0f, sag1, thick, len1, thick, r1, g1, b1, 0.98f,
        )
        val ex = ax + d1x * len1
        val ey = ay + d1y * len1
        val ez = az + d1z * len1
        val t2 = t1 + sag2 * rad
        val q2 = q1 + lat2 * rad
        val d2x = sin(t2) * cos(q2)
        val d2y = -cos(t2) * cos(q2)
        val d2z = -sin(q2)
        part(
            ex + d2x * len2 / 2f, ey + d2y * len2 / 2f, ez + d2z * len2 / 2f,
            lat1 + lat2, 0f, sag1 + sag2, thick * 0.82f, len2, thick * 0.82f, r2, g2, b2, 0.98f,
        )
    }

    private fun part(
        x: Float, y: Float, z: Float,
        rotX: Float, rotY: Float, rotZ: Float,
        sx: Float, sy: Float, sz: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        if (rotZ != 0f) Matrix.rotateM(model, 0, rotZ, 0f, 0f, 1f)
        if (rotY != 0f) Matrix.rotateM(model, 0, rotY, 0f, 1f, 0f)
        if (rotX != 0f) Matrix.rotateM(model, 0, rotX, 1f, 0f, 0f)
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
        val zNear = 4.5f
        val zFar = -9.5f
        for (i in -15..15) {
            lines.addAll(listOf(i.toFloat(), 0f, zNear, i.toFloat(), 0f, zFar))
        }
        var z = zNear
        while (z >= zFar) {
            lines.addAll(listOf(-15f, 0f, z, 15f, 0f, z))
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
