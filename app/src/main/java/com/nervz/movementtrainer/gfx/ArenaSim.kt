package com.nervz.movementtrainer.gfx

import com.nervz.movementtrainer.input.MovementState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Single source of truth for the arena: orbit position, camera, and the
// movement STATE MACHINE. Stepped once per frame on the GL thread (grid
// layer); the Filament layer reads the @Volatile outputs from the UI thread.
//
// The state machine is deliberately minimal — states and their cancel rules
// are specified by the user one by one. Current states:
//   IDLE     — battle stance + breathing/bob (pose lives in MokujinView)
//   WALK_F   — slow walk toward the opponent
//   WALK_B   — slower walk away (Tekken: backward walk < forward walk)
class ArenaSim(private val movement: MovementState) {

    enum class MoveState { IDLE, WALK_F, WALK_B }

    var orbitAng = 0f; private set
    var dist = 3.4f; private set

    private var camX = 0.4f
    private var camZoom = 1.7f
    private var walkAmt = 0f

    // outputs shared with the Filament layer
    @Volatile var camEyeX = 2.5f
    @Volatile var camEyeY = 2.4f
    @Volatile var camEyeZ = 11.2f
    @Volatile var camCtrX = 0f
    @Volatile var facingF = 1f
    @Volatile var state = MoveState.IDLE
    @Volatile var walkPhase = 0f     // radians; one 2*PI cycle = 2 steps
    @Volatile var walkAmount = 0f    // 0..1 idle->walk blend

    fun step(dt: Float) {
        val facing = movement.facing
        facingF = facing.toFloat()

        // walking is slow in Tekken, and backward slower than forward
        val relDir = movement.heldX * facing   // +1 = toward the opponent
        state = when (relDir) {
            1 -> MoveState.WALK_F
            -1 -> MoveState.WALK_B
            else -> MoveState.IDLE
        }
        val speed = when (state) {              // signed, relative-forward
            MoveState.WALK_F -> WALK_FWD_SPEED
            MoveState.WALK_B -> -WALK_BACK_SPEED
            MoveState.IDLE -> 0f
        }
        dist -= speed * dt
        dist = dist.coerceIn(1.1f, 5.5f)

        // anim drive: phase advances with signed speed (backward walks the
        // cycle in reverse); amount eases the offsets in and out of idle
        if (speed != 0f) walkPhase += PI.toFloat() * speed / WALK_STRIDE * dt
        val target = if (speed != 0f) 1f else 0f
        walkAmt += (target - walkAmt) * min(1f, dt * 7f)
        walkAmount = walkAmt

        // camera
        val targetCamX = facingF * dist * 0.12f
        camX += (targetCamX - camX) * 0.04f
        val targetZoom = (dist / 2.0f).coerceAtLeast(1f)
        camZoom += (targetZoom - camZoom) * 0.04f
        camEyeX = camX + 2.5f * camZoom
        camEyeY = 2.4f * camZoom
        camEyeZ = 11.2f * camZoom
        camCtrX = camX
    }

    // R_y(a) maps polar angle phi -> phi - a; the char->pivot direction sits
    // at angle alpha+pi, so mapping it onto +x (P1) needs a = alpha+pi.
    fun worldRotationDeg(): Float {
        val psi = if (movement.facing == 1) orbitAng + PI.toFloat() else orbitAng
        return Math.toDegrees(psi.toDouble()).toFloat()
    }

    fun charWorldX() = dist * cos(orbitAng)
    fun charWorldZ() = dist * sin(orbitAng)

    companion object {
        const val WALK_FWD_SPEED = 0.85f    // arena units / s
        const val WALK_BACK_SPEED = 0.55f
        const val WALK_STRIDE = 0.30f       // units per step (sets cadence)
    }
}
