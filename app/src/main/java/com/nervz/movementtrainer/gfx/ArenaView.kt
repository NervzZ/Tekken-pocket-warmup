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
import kotlin.math.round
import kotlin.math.sin

class ArenaView(context: Context, movement: MovementState) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setRenderer(ArenaRenderer(movement))
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}

// Tekken-style movement scene built on an ORBIT model: the character keeps a
// distance + orbit angle around an invisible opponent (shown as a ghost
// pillar). Forward/back changes the distance, sidesteps/sidewalks move along
// the circle — the grid rotates and slides exactly like the in-game camera.
// The character itself stays at the origin in a quarter view.
class ArenaRenderer(private val movement: MovementState) : GLSurfaceView.Renderer {

    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uLit = 0

    private lateinit var cube: FloatBuffer
    private lateinit var grid: FloatBuffer
    private var gridLineCount = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    // ---- simulation: orbit around the opponent ----
    private var orbitAng = 0f
    private var dist = 3.4f
    private var vImpulse = 0f
    private var zImpulse = 0f
    private var zWalk = 0f
    private var crouch = 0f
    private var walkPhase = 0f
    private var sidePhase = 0f
    private var breath = 0f
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
        BACKDASH(0.40f),
        CANCELDIP(0.14f),
        CROUCHDASH(0.42f),
        SIDESTEP_UP(0.32f),
        SIDESTEP_DOWN(0.32f),
        JUMP(0.62f),
    }

    private var clip = Clip.NONE
    private var clipT = 0f
    private var lastSsTime = -10f
    private var sidewalk = 0
    private var heldUpStart = -1f
    private var jumpArmed = true
    private var camX = 0.4f
    private var camZoom = 1.7f

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.063f, 0.078f, 0.094f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vs = """
            attribute vec3 aPos;
            attribute vec3 aNormal;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            varying vec3 vN;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vN = (uModel * vec4(aNormal, 0.0)).xyz;
            }
        """
        val fs = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uLit;
            varying vec3 vN;
            void main() {
                vec3 n = normalize(vN);
                float diff = max(dot(n, normalize(vec3(0.45, 0.8, 0.65))), 0.0);
                float light = mix(1.0, 0.42 + 0.62 * diff, uLit);
                gl_FragColor = vec4(uColor.rgb * light, uColor.a);
            }
        """
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uLit = GLES20.glGetUniformLocation(program, "uLit")

        cube = floatBufferOf(*buildCube())
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
        time += dt
        step(dt)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        // camera unzooms with distance so both the character and the pivot
        // dot stay in frame; slight bias keeps the character right of the
        // history overlay
        val f = movement.facing.toFloat()
        val targetCamX = f * dist * 0.12f
        camX += (targetCamX - camX) * 0.04f
        val targetZoom = (dist / 2.0f).coerceAtLeast(1f)
        camZoom += (targetZoom - camZoom) * 0.04f
        Matrix.setLookAtM(
            view, 0,
            camX + 2.5f * camZoom, 2.4f * camZoom, 11.2f * camZoom,
            camX, 1.0f, 0f, 0f, 1f, 0f,
        )
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        drawGrid()
        drawShadowAndOpponent()
        drawCharacter()
    }

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

        // orbit integration: vx (screen x) toward +facing = toward the pivot.
        // dist is hard-capped: at the edges the character keeps animating but
        // "slides" in place instead of drifting further away
        dist -= vx * facing * dt
        dist = dist.coerceIn(1.1f, 5.5f)
        val vSide = zImpulse + zWalk
        orbitAng += -vSide * dt / dist
        zImpulse *= exp(-dt * 8f)

        val crouchTarget = if (movement.crouching && sidewalk != -1) 1f else 0f
        crouch += (crouchTarget - crouch) * min(1f, dt * 12f)

        breath = sin(time * 1.7f)
        if (clip == Clip.NONE) walkPhase += abs(vx) * dt * 7f
        sidePhase += abs(vSide) * dt * 7f
    }

    private fun envelope(): Float {
        if (clip == Clip.NONE || clip.dur <= 0f) return 0f
        val t01 = (clipT / clip.dur).coerceIn(0f, 1f)
        return sin(t01 * PI.toFloat())
    }

    // grid lines live at integer WORLD coordinates; the model matrix rotates
    // the world so the opponent sits toward +facing*x, with the character at
    // the origin. The patch is re-centered on the nearest integer cell, so the
    // floor is seamless and infinite while it rotates around you mid-sidestep.
    // R_y(a) maps polar angle phi -> phi - a, and the char->pivot direction
    // sits at angle alpha+pi, so mapping it onto +x (P1) needs a = alpha+pi.
    // (The sign was flipped once: grid rotated against the world, the pivot
    // dot visibly slid across the floor — user-caught.)
    private fun worldRotationDeg(): Float {
        val psi = if (movement.facing == 1) orbitAng + PI.toFloat() else orbitAng
        return Math.toDegrees(psi.toDouble()).toFloat()
    }

    private fun charWorldX() = dist * cos(orbitAng)
    private fun charWorldZ() = dist * sin(orbitAng)

    private fun drawGrid() {
        // solid ground plane the character actually stands on (static in
        // character space — uniform color, so no scroll is visible on it)
        part(0f, -0.05f, 0f, 0f, 0f, 0f, 80f, 0.1f, 80f, 0.085f, 0.104f, 0.124f, 1f, lit = false)

        // grid texture on top of it, carrying the orbit scroll/rotation
        val cx = charWorldX()
        val cz = charWorldZ()
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0.012f, 0f)
        Matrix.rotateM(model, 0, worldRotationDeg(), 0f, 1f, 0f)
        Matrix.translateM(model, 0, round(cx) - cx, 0f, round(cz) - cz)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform1f(uLit, 0f)
        GLES20.glUniform4f(uColor, 0.55f, 0.65f, 0.75f, 0.21f)
        GLES20.glEnableVertexAttribArray(aPos)
        cube.position(0)
        grid.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, grid)
        GLES20.glLineWidth(2f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridLineCount * 2)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun drawShadowAndOpponent() {
        val f = movement.facing.toFloat()
        // soft shadow under the character
        part(0f, 0.022f, 0f, 0f, 0f, 0f, 0.85f, 0.02f, 0.6f, 0.02f, 0.03f, 0.04f, 0.55f, lit = false)
        // red dot at the TRUE pivot position (the world origin — it always
        // sits on a grid crossing, which doubles as a sync check)
        part(f * dist, 0.03f, 0f, 0f, 45f, 0f, 0.26f, 0.015f, 0.26f, 0.91f, 0.20f, 0.23f, 0.6f, lit = false)
    }

    // ---- mokujin ----

    private class Pose {
        var rootY = 0f
        var twist = 0f
        var lean = 0f
        var chestBreath = 0f
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

        // battle stance: quarter-turned, knees loaded, fists guarding in front
        p.rootY = 0.012f * breath
        p.twist = -32f
        p.lean = 4f + 1.2f * breath
        p.chestBreath = 0.035f * breath
        p.legFSag = 18f; p.legFKnee = 14f; p.legFLat = -8f
        p.legBSag = -20f; p.legBKnee = 24f; p.legBLat = 8f
        p.armFSag = 32f + 3.5f * breath; p.armFElbow = 118f; p.armFLat = -16f
        p.armBSag = 14f + 3f * breath; p.armBElbow = 128f; p.armBLat = 12f

        val walkAmp = if (clip == Clip.NONE) min(1f, abs(movement.heldX.toFloat())) else 0f
        if (walkAmp > 0.05f) {
            val s = sin(walkPhase)
            val lift = sin(walkPhase + PI.toFloat() / 2f)
            p.legFSag += s * 26f * walkAmp
            p.legBSag += -s * 26f * walkAmp
            p.legFKnee += (lift).coerceAtLeast(0f) * 24f * walkAmp
            p.legBKnee += (-lift).coerceAtLeast(0f) * 24f * walkAmp
            p.armFSag += -s * 10f * walkAmp
            p.armBSag += s * 10f * walkAmp
        }

        if (abs(zWalk) > 0.1f) {
            val s = sin(sidePhase)
            val dir = if (zWalk < 0) 1f else -1f
            p.twist += dir * 16f
            p.legFLat += s * 22f * dir
            p.legBLat += -s * 22f * dir
            p.legFKnee += 10f
            p.legBKnee += 10f
        }

        p.legFKnee += 60f * c
        p.legBKnee += 58f * c
        p.legFSag += 30f * c
        p.legBSag += 44f * c
        p.lean += 14f * c

        when (clip) {
            Clip.BACKDASH -> {
                // two phases: compress, then the hop-back glide with the front
                // leg reaching forward — clearly not a walk cycle
                val t01 = (clipT / clip.dur).coerceIn(0f, 1f)
                if (t01 < 0.35f) {
                    val e = t01 / 0.35f
                    p.legFKnee += 26f * e
                    p.legBKnee += 30f * e
                    p.lean += -10f * e
                    p.rootY += -0.05f * e
                } else {
                    val e = (t01 - 0.35f) / 0.65f
                    val arc = sin(e * PI.toFloat())
                    p.rootY += 0.11f * arc - 0.02f
                    p.lean += -20f * (1f - e * 0.5f)
                    p.legFSag += 40f * (1f - e * 0.35f)
                    p.legFKnee += 4f
                    p.legBSag += -22f * (1f - e * 0.3f)
                    p.legBKnee += 18f * (1f - e)
                    p.armFSag += -10f * arc
                    p.armBSag += 8f * arc
                }
            }
            Clip.CANCELDIP -> {
                p.legFKnee += 36f * env
                p.legBKnee += 34f * env
                p.lean += 7f * env
                p.rootY += -0.08f * env
            }
            Clip.CROUCHDASH -> {
                val t01 = (clipT / clip.dur).coerceIn(0f, 1f)
                val drive = sin(t01 * PI.toFloat())
                p.lean += 26f * drive
                p.rootY += -0.17f * drive
                p.legFSag += 36f * drive
                p.legFKnee += 26f * drive
                p.legBSag += -34f * drive
                p.legBKnee += 8f * drive
                p.armFSag += 24f * drive
                p.armBSag += -10f * drive
            }
            Clip.SIDESTEP_UP, Clip.SIDESTEP_DOWN -> {
                val dir = if (clip == Clip.SIDESTEP_UP) 1f else -1f
                p.twist += dir * 40f * env
                p.legFLat += -dir * 26f * env
                p.legBLat += dir * 16f * env
                p.legFKnee += 12f * env
                p.rootY += 0.06f * env
            }
            Clip.JUMP -> {
                val t = clipT
                p.rootY += (3.4f * t - 5.6f * t * t).coerceAtLeast(0f)
                p.legFKnee += 55f * env
                p.legBKnee += 60f * env
                p.legFSag += 25f * env
                p.legBSag += -20f * env
                p.armFSag += 16f * env
                p.armBSag += 12f * env
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
        val kneeB = p.legBKnee * rad
        val sagB = p.legBSag * rad
        val hipY = (l1 * cos(sagB) + l2 * cos(sagB - kneeB)) + p.rootY

        val chestY = hipY + 0.34f
        val shoulderY = hipY + 0.46f
        val headY = hipY + 0.70f

        val wr = 0.79f; val wg = 0.63f; val wb = 0.43f
        val dr = 0.62f; val dg = 0.48f; val db = 0.31f
        val hr = 0.88f; val hg = 0.74f; val hb = 0.54f

        // hips + chest (chest breathes)
        part(0f, hipY + 0.06f, 0f, 0f, p.twist * f, p.lean * f, 0.27f, 0.20f, 0.20f, dr, dg, db, 1f)
        part(
            0.02f * f, chestY, 0f, 0f, p.twist * f, p.lean * f,
            0.33f + p.chestBreath * 0.4f, 0.34f * (1f + p.chestBreath), 0.23f + p.chestBreath * 0.4f,
            wr, wg, wb, 1f,
        )
        // shoulders
        part(0.02f * f, shoulderY, -0.21f, 0f, p.twist * f, p.lean * f, 0.13f, 0.12f, 0.13f, dr, dg, db, 1f)
        part(0.02f * f, shoulderY, 0.21f, 0f, p.twist * f, p.lean * f, 0.13f, 0.12f, 0.13f, dr, dg, db, 1f)
        // head + face mark
        part(0.03f * f, headY, 0f, 0f, p.twist * f, p.lean * f * 0.5f, 0.21f, 0.24f, 0.21f, wr, wg, wb, 1f)
        part(0.03f * f + f * 0.10f, headY + 0.02f, -0.03f, 0f, p.twist * f, 0f, 0.05f, 0.07f, 0.09f, 0.28f, 0.18f, 0.10f, 1f)

        // legs — knees flex BACKWARD relative to the thigh (negative fold)
        limb(0.04f * f, hipY, -0.10f, l1, l2, 0.14f, p.legFSag * f, p.legFLat, -p.legFKnee * f, 0f, wr, wg, wb, dr, dg, db, fist = false, fr = 0f, fg = 0f, fb = 0f)
        limb(-0.04f * f, hipY, 0.10f, l1, l2, 0.14f, p.legBSag * f, p.legBLat, -p.legBKnee * f, 0f, wr, wg, wb, dr, dg, db, fist = false, fr = 0f, fg = 0f, fb = 0f)

        // arms with fists: positive elbow folds the forearm up-forward into guard
        limb(0.03f * f, shoulderY, -0.21f, 0.28f, 0.26f, 0.10f, p.armFSag * f, p.armFLat, p.armFElbow * f, 0f, wr, wg, wb, dr, dg, db, fist = true, fr = hr, fg = hg, fb = hb)
        limb(0.01f * f, shoulderY, 0.21f, 0.28f, 0.26f, 0.10f, p.armBSag * f, p.armBLat, p.armBElbow * f, 0f, wr, wg, wb, dr, dg, db, fist = true, fr = hr, fg = hg, fb = hb)
    }

    private fun limb(
        ax: Float, ay: Float, az: Float,
        len1: Float, len2: Float, thick: Float,
        sag1: Float, lat1: Float, sag2: Float, lat2: Float,
        r1: Float, g1: Float, b1: Float,
        r2: Float, g2: Float, b2: Float,
        fist: Boolean, fr: Float, fg: Float, fb: Float,
    ) {
        val t1 = sag1 * rad
        val q1 = lat1 * rad
        val d1x = sin(t1) * cos(q1)
        val d1y = -cos(t1) * cos(q1)
        val d1z = -sin(q1)
        part(
            ax + d1x * len1 / 2f, ay + d1y * len1 / 2f, az + d1z * len1 / 2f,
            lat1, 0f, sag1, thick, len1, thick, r1, g1, b1, 1f,
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
            lat1 + lat2, 0f, sag1 + sag2, thick * 0.82f, len2, thick * 0.82f, r2, g2, b2, 1f,
        )
        if (fist) {
            part(
                ex + d2x * (len2 + 0.05f), ey + d2y * (len2 + 0.05f), ez + d2z * (len2 + 0.05f),
                lat1 + lat2, 0f, sag1 + sag2, 0.12f, 0.12f, 0.12f, fr, fg, fb, 1f,
            )
        }
    }

    private fun part(
        x: Float, y: Float, z: Float,
        rotX: Float, rotY: Float, rotZ: Float,
        sx: Float, sy: Float, sz: Float,
        r: Float, g: Float, b: Float, a: Float,
        lit: Boolean = true,
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        if (rotZ != 0f) Matrix.rotateM(model, 0, rotZ, 0f, 0f, 1f)
        if (rotY != 0f) Matrix.rotateM(model, 0, rotY, 0f, 1f, 0f)
        if (rotX != 0f) Matrix.rotateM(model, 0, rotX, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, sx, sy, sz)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform1f(uLit, if (lit) 1f else 0f)
        GLES20.glUniform4f(uColor, r, g, b, a)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNormal)
        cube.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, cube)
        cube.position(3)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, cube)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
        cube.position(0)
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
        // interleaved pos(3) + normal(3), 36 vertices
        private fun buildCube(): FloatArray {
            val out = ArrayList<Float>(36 * 6)
            fun face(n: FloatArray, v: Array<FloatArray>) {
                val order = intArrayOf(0, 1, 2, 0, 2, 3)
                for (i in order) {
                    out.addAll(v[i].toList())
                    out.addAll(n.toList())
                }
            }
            val p = 0.5f
            val m = -0.5f
            face(floatArrayOf(1f, 0f, 0f), arrayOf(
                floatArrayOf(p, m, m), floatArrayOf(p, p, m), floatArrayOf(p, p, p), floatArrayOf(p, m, p),
            ))
            face(floatArrayOf(-1f, 0f, 0f), arrayOf(
                floatArrayOf(m, m, m), floatArrayOf(m, m, p), floatArrayOf(m, p, p), floatArrayOf(m, p, m),
            ))
            face(floatArrayOf(0f, 1f, 0f), arrayOf(
                floatArrayOf(m, p, m), floatArrayOf(m, p, p), floatArrayOf(p, p, p), floatArrayOf(p, p, m),
            ))
            face(floatArrayOf(0f, -1f, 0f), arrayOf(
                floatArrayOf(m, m, m), floatArrayOf(p, m, m), floatArrayOf(p, m, p), floatArrayOf(m, m, p),
            ))
            face(floatArrayOf(0f, 0f, 1f), arrayOf(
                floatArrayOf(m, m, p), floatArrayOf(p, m, p), floatArrayOf(p, p, p), floatArrayOf(m, p, p),
            ))
            face(floatArrayOf(0f, 0f, -1f), arrayOf(
                floatArrayOf(m, m, m), floatArrayOf(m, p, m), floatArrayOf(p, p, m), floatArrayOf(p, m, m),
            ))
            return out.toFloatArray()
        }
    }
}
