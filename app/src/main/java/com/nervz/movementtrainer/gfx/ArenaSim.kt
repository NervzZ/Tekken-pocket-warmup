package com.nervz.movementtrainer.gfx

import com.nervz.movementtrainer.input.MovementState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

// Single source of truth for the arena: orbit position, camera, animation
// clips, and the character's root motion. Stepped once per frame on the GL
// thread (grid layer); the Filament layer reads the @Volatile outputs from
// the UI thread.
class ArenaSim(private val movement: MovementState) {

    enum class Clip(val dur: Float) {
        NONE(0f),
        BACKDASH(0.40f),
        CANCELDIP(0.14f),
        CROUCHDASH(0.42f),
        SIDESTEP_UP(0.32f),
        SIDESTEP_DOWN(0.32f),
        JUMP(0.62f),
    }

    var orbitAng = 0f; private set
    var dist = 3.4f; private set

    private var vImpulse = 0f
    private var zImpulse = 0f
    private var zWalk = 0f
    private var crouch = 0f
    private var breath = 0f
    private var time = 0f

    private var seenBd = 0
    private var seenCancel = 0
    private var seenDash = 0
    private var seenCd = 0
    private var seenSsUp = 0
    private var seenSsDown = 0

    private var clip = Clip.NONE
    private var clipT = 0f
    private var lastSsTime = -10f
    private var sidewalk = 0
    private var heldUpStart = -1f
    private var jumpArmed = true
    private var camX = 0.4f
    private var camZoom = 1.7f

    // outputs shared with the Filament layer
    @Volatile var camEyeX = 2.5f
    @Volatile var camEyeY = 2.4f
    @Volatile var camEyeZ = 11.2f
    @Volatile var camCtrX = 0f
    @Volatile var facingF = 1f
    @Volatile var charHopY = 0f
    @Volatile var charLean = 0f
    @Volatile var charTwist = 0f
    @Volatile var charCrouch = 0f
    @Volatile var charSpeedX = 0f

    fun step(dt: Float) {
        time += dt
        val facing = movement.facing
        facingF = facing.toFloat()

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

        dist -= vx * facing * dt
        dist = dist.coerceIn(1.1f, 5.5f)
        val vSide = zImpulse + zWalk
        orbitAng += -vSide * dt / dist
        zImpulse *= exp(-dt * 8f)

        val crouchTarget = if (movement.crouching && sidewalk != -1) 1f else 0f
        crouch += (crouchTarget - crouch) * min(1f, dt * 12f)
        breath = sin(time * 1.7f)

        // camera
        val targetCamX = facingF * dist * 0.12f
        camX += (targetCamX - camX) * 0.04f
        val targetZoom = (dist / 2.0f).coerceAtLeast(1f)
        camZoom += (targetZoom - camZoom) * 0.04f
        camEyeX = camX + 2.5f * camZoom
        camEyeY = 2.4f * camZoom
        camEyeZ = 11.2f * camZoom
        camCtrX = camX

        // character root motion
        var hop = 0.012f * breath
        var lean = 1.2f * breath
        var twist = 0f
        val env = envelope()
        when (clip) {
            Clip.BACKDASH -> {
                val t01 = (clipT / clip.dur).coerceIn(0f, 1f)
                if (t01 < 0.35f) {
                    hop += -0.05f * (t01 / 0.35f)
                    lean += -8f * (t01 / 0.35f)
                } else {
                    val e = (t01 - 0.35f) / 0.65f
                    hop += 0.11f * sin(e * PI.toFloat())
                    lean += -18f * (1f - e * 0.5f)
                }
            }
            Clip.CANCELDIP -> {
                hop += -0.07f * env
                lean += 6f * env
            }
            Clip.CROUCHDASH -> {
                hop += -0.13f * env
                lean += 20f * env
            }
            Clip.SIDESTEP_UP, Clip.SIDESTEP_DOWN -> {
                val dir = if (clip == Clip.SIDESTEP_UP) 1f else -1f
                twist += dir * 32f * env
                hop += 0.05f * env
            }
            Clip.JUMP -> {
                hop += (3.4f * clipT - 5.6f * clipT * clipT).coerceAtLeast(0f)
            }
            Clip.NONE -> {}
        }
        lean += vx * facing * 2.2f
        charHopY = hop
        charLean = lean
        charTwist = twist
        charCrouch = crouch
        charSpeedX = vx
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

    private fun envelope(): Float {
        if (clip == Clip.NONE || clip.dur <= 0f) return 0f
        val t01 = (clipT / clip.dur).coerceIn(0f, 1f)
        return sin(t01 * PI.toFloat())
    }

    // R_y(a) maps polar angle phi -> phi - a; the char->pivot direction sits
    // at angle alpha+pi, so mapping it onto +x (P1) needs a = alpha+pi.
    fun worldRotationDeg(): Float {
        val psi = if (movement.facing == 1) orbitAng + PI.toFloat() else orbitAng
        return Math.toDegrees(psi.toDouble()).toFloat()
    }

    fun charWorldX() = dist * cos(orbitAng)
    fun charWorldZ() = dist * sin(orbitAng)
}
