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
//   BACKDASH — two quick back inputs; 34 frames, displacement front-loaded
//              into the first 19 (the rest is recovery). UNCANCELABLE: the
//              full 34 frames always play. A backdash completed mid-anim is
//              BUFFERED and chains gaplessly at frame 34 — but any other
//              directional input after buffering invalidates the buffer.
//              (Explicit cancel inputs to be specified later.)
class ArenaSim(private val movement: MovementState) {

    enum class MoveState { IDLE, WALK_F, WALK_B, BACKDASH }

    var orbitAng = 0f; private set
    var dist = 3.4f; private set

    private var camX = 0.4f
    private var camZoom = 1.7f
    private var walkAmt = 0f
    private var seenBd = 0
    private var bdT = 0f
    private var bdBuffered = false

    // outputs shared with the Filament layer
    @Volatile var camEyeX = 2.5f
    @Volatile var camEyeY = 2.4f
    @Volatile var camEyeZ = 11.2f
    @Volatile var camCtrX = 0f
    @Volatile var facingF = 1f
    @Volatile var state = MoveState.IDLE
    @Volatile var walkPhase = 0f     // radians; one 2*PI cycle = 2 steps
    @Volatile var walkAmount = 0f    // 0..1 idle->walk blend
    @Volatile var walkDir = 0f       // smoothed +1 fwd / -1 back (anim blend)
    @Volatile var bdProgress = -1f   // backdash 0..1, or -1 when inactive
    private var dirSm = 0f

    fun step(dt: Float) {
        val facing = movement.facing
        facingF = facing.toFloat()

        // backdash event (two quick back inputs, side-aware tech engine);
        // a fresh event (re)starts the state
        val bdCount = movement.backdashes.get()
        while (seenBd < bdCount) {
            seenBd++
            if (state != MoveState.BACKDASH) {
                state = MoveState.BACKDASH
                bdT = 0f
                bdBuffered = false
            } else {
                // BB completed mid-anim: buffer the next backdash
                bdBuffered = true
            }
        }
        if (state == MoveState.BACKDASH) {
            // doing anything else after buffering (forward, up, down)
            // invalidates the buffer — only sustained BB spam chains
            if (bdBuffered &&
                (movement.heldX * facing == 1 || movement.heldUp || movement.heldDown)
            ) {
                bdBuffered = false
            }
            val uPrev = (bdT / BACKDASH_DUR).coerceAtMost(1f)
            bdT += dt
            val u = (bdT / BACKDASH_DUR).coerceAtMost(1f)
            dist += (bdDisp(u) - bdDisp(uPrev)) * BACKDASH_DIST
            bdProgress = u
            if (bdT >= BACKDASH_DUR) {
                if (bdBuffered) {
                    // gapless chain: carry the leftover time into the next
                    // dash and integrate its first displacement slice
                    bdBuffered = false
                    bdT -= BACKDASH_DUR
                    val u2 = (bdT / BACKDASH_DUR).coerceAtMost(1f)
                    dist += bdDisp(u2) * BACKDASH_DIST
                    bdProgress = u2
                } else {
                    state = MoveState.IDLE
                    bdProgress = -1f
                }
            }
        } else {
            bdProgress = -1f
        }

        // walking is slow in Tekken, and backward slower than forward
        val relDir = movement.heldX * facing   // +1 = toward the opponent
        var speed = 0f
        if (state != MoveState.BACKDASH) {
            state = when (relDir) {
                1 -> MoveState.WALK_F
                -1 -> MoveState.WALK_B
                else -> MoveState.IDLE
            }
            speed = when (state) {              // signed, relative-forward
                MoveState.WALK_F -> WALK_FWD_SPEED
                MoveState.WALK_B -> -WALK_BACK_SPEED
                else -> 0f
            }
            dist -= speed * dt
        }
        dist = dist.coerceIn(1.1f, 5.5f)

        // anim drive: phase advances with signed speed (backward walks the
        // cycle in reverse, with shorter steps); amount eases the offsets in
        // and out of idle; dir cross-fades the F/B animation parameter sets
        val stride = if (state == MoveState.WALK_B) WALK_STRIDE_BACK else WALK_STRIDE_FWD
        if (speed != 0f) {
            walkPhase += PI.toFloat() * speed / stride * dt
            val dTarget = if (speed > 0f) 1f else -1f
            dirSm += (dTarget - dirSm) * min(1f, dt * 6f)
        }
        walkDir = dirSm
        val target = if (speed != 0f) 1f else 0f
        // ease the walk layer out fast when a backdash takes over
        val ease = if (state == MoveState.BACKDASH) 14f else 7f
        walkAmt += (target - walkAmt) * min(1f, dt * ease)
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

    // cumulative backdash displacement fraction: ease-out covers 88% of the
    // distance inside the first 19/34 frames, the rest drifts out in recovery
    private fun bdDisp(u: Float): Float {
        val u1 = 19f / 34f
        return if (u < u1) {
            val k = 1f - u / u1
            BD_MOVE_SPLIT * (1f - k * k * k)
        } else {
            BD_MOVE_SPLIT + (1f - BD_MOVE_SPLIT) * (u - u1) / (1f - u1)
        }
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
        const val WALK_STRIDE_FWD = 0.30f   // units per step (sets cadence)
        const val WALK_STRIDE_BACK = 0.22f  // shorter, less covering steps
        const val BACKDASH_DUR = 34f / 60f  // 34 frames
        const val BACKDASH_DIST = 0.60f     // arena units covered
        const val BD_MOVE_SPLIT = 0.88f     // share of distance in frames 0-19
    }
}
