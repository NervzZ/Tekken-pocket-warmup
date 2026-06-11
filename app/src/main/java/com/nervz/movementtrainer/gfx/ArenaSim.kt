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
// States and cancel rules are specified by the user one by one:
//   IDLE      — battle stance + breathing/bob (pose lives in MokujinView)
//   WALK_F    — slow walk toward the opponent
//   WALK_B    — slower walk away (Tekken: backward walk < forward walk)
//   BACKDASH  — two quick back inputs; 34 frames, displacement front-loaded
//               into the first 19. CANCELS (user spec): up/down tap -> the
//               matching sidestep; forward -> walk; crouch (held down or
//               down-back) -> crouch. A backdash completed mid-anim is
//               BUFFERED and chains gaplessly at frame 34; any other
//               directional input after buffering invalidates the buffer.
//   SIDESTEP_UP / SIDESTEP_DOWN — up/down tap; 24 frames circling the
//               opponent (orbit arc). UNCANCELABLE for now (rules TBD).
//   SIDEWALK_UP / SIDEWALK_DOWN — holding the direction when the sidestep
//               ends (u,U / d,D) flows into a continuous strafe around the
//               opponent until the hold releases. Free state: events fire
//               straight out of it.
//   CROUCH    — held down/down-back; a FREE state: ducks in ~7-8 frames,
//               locks nothing (any event fires straight out of it), and
//               exits the moment the hold releases.
//   DASH      — double-tap forward; a 34-frame run, much faster than
//               walking. A third forward tap (dash event mid-dash) ARMS
//               maintain: holding that last forward keeps the run going
//               until released. CANCELABLE BY ANY OTHER MOVEMENT at any
//               moment: backdash, sidestep taps, crouch, or holding back.
class ArenaSim(private val movement: MovementState) {

    enum class MoveState {
        IDLE, WALK_F, WALK_B, BACKDASH, SIDESTEP_UP, SIDESTEP_DOWN,
        SIDEWALK_UP, SIDEWALK_DOWN, CROUCH, DASH,
    }

    var orbitAng = 0f; private set
    var dist = 3.4f; private set

    private var camX = 0.4f
    private var camZoom = 1.7f
    private var walkAmt = 0f
    private var dirSm = 0f
    private var crouchAmt = 0f
    private var seenBd = 0
    private var seenSsUp = 0
    private var seenSsDown = 0
    private var seenDash = 0
    private var bdT = 0f
    private var bdBuffered = false
    private var ssT = 0f
    private var dashT = 0f
    private var dashMaintain = false
    private var runAmt = 0f

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
    @Volatile var ssProgress = -1f   // sidestep 0..1, or -1 when inactive
    @Volatile var ssDir = 0f         // +1 = up (background), -1 = down
    @Volatile var crouchAmount = 0f  // 0..1 duck blend
    @Volatile var runAmount = 0f     // 0..1 walk->run anim blend
    @Volatile var swPhase = 0f       // sidewalk stepping cycle (radians)
    @Volatile var swAmount = 0f      // 0..1 sidewalk anim blend
    @Volatile var swDir = 0f         // +1 up / -1 down
    private var swAmt = 0f

    fun step(dt: Float) {
        val facing = movement.facing
        facingF = facing.toFloat()

        // ---- consume tech events into this-frame flags
        var bdEvt = false
        var ssUpEvt = false
        var ssDownEvt = false
        val bdCount = movement.backdashes.get()
        while (seenBd < bdCount) { seenBd++; bdEvt = true }
        val suCount = movement.sidestepsUp.get()
        while (seenSsUp < suCount) { seenSsUp++; ssUpEvt = true }
        val sdCount = movement.sidestepsDown.get()
        while (seenSsDown < sdCount) { seenSsDown++; ssDownEvt = true }
        var dashEvt = false
        val dashCount = movement.dashes.get()
        while (seenDash < dashCount) { seenDash++; dashEvt = true }

        val relDir = movement.heldX * facing   // +1 = toward the opponent
        val crouchHeld = movement.crouching

        // ---- state transitions
        when (state) {
            MoveState.BACKDASH -> when {
                // user-spec cancels, checked before anything else
                ssUpEvt -> startSidestep(+1f)
                ssDownEvt -> startSidestep(-1f)
                crouchHeld -> leaveBackdash(MoveState.CROUCH)
                relDir == 1 -> leaveBackdash(MoveState.IDLE) // walk block takes over
                else -> {
                    if (bdEvt) bdBuffered = true
                    // a non-back directional hold invalidates the buffer
                    if (bdBuffered && (movement.heldUp || movement.heldDown)) {
                        bdBuffered = false
                    }
                }
            }
            MoveState.SIDESTEP_UP, MoveState.SIDESTEP_DOWN -> {
                // uncancelable: all events are consumed and dropped
            }
            MoveState.DASH -> when {
                // any other movement cancels the dash at any moment
                bdEvt -> { state = MoveState.BACKDASH; bdT = 0f; bdBuffered = false }
                ssUpEvt -> startSidestep(+1f)
                ssDownEvt -> startSidestep(-1f)
                crouchHeld -> state = MoveState.CROUCH
                relDir == -1 -> state = MoveState.WALK_B
                // a third forward tap arms maintain
                dashEvt -> dashMaintain = true
                else -> {}
            }
            else -> when {
                // IDLE / WALK / SIDEWALK / CROUCH are free states
                bdEvt -> { state = MoveState.BACKDASH; bdT = 0f; bdBuffered = false }
                // no room to run: a dash can't start at the closest distance
                dashEvt && dist > MIN_DIST + 0.01f -> {
                    state = MoveState.DASH; dashT = 0f; dashMaintain = false
                }
                ssUpEvt -> startSidestep(+1f)
                ssDownEvt -> startSidestep(-1f)
                crouchHeld -> state = MoveState.CROUCH
                // sidewalk persists while its direction stays held
                state == MoveState.SIDEWALK_UP && movement.heldUp -> {}
                state == MoveState.SIDEWALK_DOWN && movement.heldDown -> {}
                else -> state = when (relDir) {
                    1 -> MoveState.WALK_F
                    -1 -> MoveState.WALK_B
                    else -> MoveState.IDLE
                }
            }
        }

        // ---- per-state progress + movement
        var speed = 0f
        if (state == MoveState.BACKDASH) {
            val uPrev = (bdT / BACKDASH_DUR).coerceAtMost(1f)
            bdT += dt
            val u = (bdT / BACKDASH_DUR).coerceAtMost(1f)
            dist += (bdDisp(u) - bdDisp(uPrev)) * BACKDASH_DIST
            bdProgress = u
            if (bdT >= BACKDASH_DUR) {
                if (bdBuffered) {
                    // gapless chain: carry leftover time + its displacement
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
        if (state == MoveState.SIDESTEP_UP || state == MoveState.SIDESTEP_DOWN) {
            val uPrev = (ssT / SIDESTEP_DUR).coerceAtMost(1f)
            ssT += dt
            val u = (ssT / SIDESTEP_DUR).coerceAtMost(1f)
            // arc around the opponent; up (+) circles one way, down the other
            orbitAng += (ssDisp(u) - ssDisp(uPrev)) * SIDESTEP_ARC * ssDir / dist
            ssProgress = u
            if (ssT >= SIDESTEP_DUR) {
                // direction still held at the end -> flow into a sidewalk
                state = when {
                    ssDir > 0f && movement.heldUp -> MoveState.SIDEWALK_UP
                    ssDir < 0f && movement.heldDown -> MoveState.SIDEWALK_DOWN
                    else -> MoveState.IDLE
                }
                ssProgress = -1f
            }
        } else {
            ssProgress = -1f
        }
        if (state == MoveState.SIDEWALK_UP || state == MoveState.SIDEWALK_DOWN) {
            val dir = if (state == MoveState.SIDEWALK_UP) 1f else -1f
            swDir = dir
            orbitAng += SIDEWALK_SPEED * dir / dist * dt
            swPhase += PI.toFloat() * SIDEWALK_SPEED / SIDEWALK_STRIDE * dt
        }
        val swTarget =
            if (state == MoveState.SIDEWALK_UP || state == MoveState.SIDEWALK_DOWN) 1f else 0f
        swAmt += (swTarget - swAmt) * min(1f, dt * 7f)
        swAmount = swAmt
        if (state == MoveState.DASH) {
            dashT += dt
            speed = DASH_SPEED
            dist -= speed * dt
            // 34f burst; maintained (armed + forward held) runs until
            // release; ends instantly when forward motion runs out of room
            if (dist <= MIN_DIST ||
                (dashT >= DASH_DUR && !(dashMaintain && relDir == 1))
            ) {
                state = MoveState.IDLE
            }
        }
        if (state == MoveState.WALK_F || state == MoveState.WALK_B) {
            speed = if (state == MoveState.WALK_F) WALK_FWD_SPEED else -WALK_BACK_SPEED
            dist -= speed * dt
        }
        dist = dist.coerceIn(MIN_DIST, MAX_DIST)

        // ---- anim drives
        val stride = when (state) {
            MoveState.DASH -> DASH_STRIDE
            MoveState.WALK_B -> WALK_STRIDE_BACK
            else -> WALK_STRIDE_FWD
        }
        if (speed != 0f) {
            walkPhase += PI.toFloat() * speed / stride * dt
            val dTarget = if (speed > 0f) 1f else -1f
            dirSm += (dTarget - dirSm) * min(1f, dt * 6f)
        }
        walkDir = dirSm
        val wTarget = if (speed != 0f) 1f else 0f
        val wEase = if (state == MoveState.BACKDASH) 14f else 7f
        walkAmt += (wTarget - walkAmt) * min(1f, dt * wEase)
        walkAmount = walkAmt
        // duck in ~7-8 frames (user-tuned from 3-4: too snappy), rise gentler
        val cTarget = if (state == MoveState.CROUCH) 1f else 0f
        val cEase = if (cTarget > crouchAmt) 22f else 18f
        crouchAmt += (cTarget - crouchAmt) * min(1f, dt * cEase)
        crouchAmount = crouchAmt
        // walk->run blend for the anim layer
        val rTarget = if (state == MoveState.DASH) 1f else 0f
        runAmt += (rTarget - runAmt) * min(1f, dt * 8f)
        runAmount = runAmt

        // ---- camera
        // eye sits exactly behind the look target (no lateral offset): the
        // view direction then has no x component, so the char<->pivot axis
        // runs truly horizontal on screen and both are equidistant from the
        // camera on either side. (The old +2.5*zoom x-offset skewed the
        // orbit: P1 showed the mokujin's front with the dot nearer the
        // camera, P2 the mirror — user-caught.)
        val targetCamX = facingF * dist * 0.12f
        camX += (targetCamX - camX) * 0.04f
        val targetZoom = (dist / 2.0f).coerceAtLeast(1f)
        camZoom += (targetZoom - camZoom) * 0.04f
        camEyeX = camX
        camEyeY = 2.4f * camZoom
        camEyeZ = 11.2f * camZoom
        camCtrX = camX
    }

    private fun startSidestep(dir: Float) {
        state = if (dir > 0f) MoveState.SIDESTEP_UP else MoveState.SIDESTEP_DOWN
        ssDir = dir
        ssT = 0f
        bdProgress = -1f
        bdBuffered = false
    }

    private fun leaveBackdash(to: MoveState) {
        state = to
        bdProgress = -1f
        bdBuffered = false
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

    // sidestep arc fraction: simple ease-out, motion front-loaded
    private fun ssDisp(u: Float): Float {
        val k = 1f - u
        return 1f - k * k * k
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
        const val MIN_DIST = 1.1f           // closest approach to the opponent
        const val MAX_DIST = 5.5f
        const val WALK_FWD_SPEED = 0.85f    // arena units / s
        const val WALK_BACK_SPEED = 0.55f
        const val WALK_STRIDE_FWD = 0.30f   // units per step (sets cadence)
        const val WALK_STRIDE_BACK = 0.22f  // shorter, less covering steps
        const val BACKDASH_DUR = 34f / 60f  // 34 frames
        const val BACKDASH_DIST = 0.60f     // arena units covered
        const val BD_MOVE_SPLIT = 0.88f     // share of distance in frames 0-19
        const val SIDESTEP_DUR = 24f / 60f  // 24 frames
        const val SIDESTEP_ARC = 0.84f      // lateral units circled per step
                                            // (user: was half of a real step)
        const val SIDEWALK_SPEED = 0.9f     // continuous strafe, units / s
        const val SIDEWALK_STRIDE = 0.30f   // lateral units per step (cadence)
        const val DASH_DUR = 34f / 60f      // 34 frames (user-corrected from 80)
        // speed raised with stride scaled to match: covers ground faster at
        // the SAME animation cadence (speed/stride unchanged, ~2.9 steps/s)
        const val DASH_SPEED = 3.0f         // a run — much faster than walking
        const val DASH_STRIDE = 1.02f       // long reaching strides (slow cadence)
    }
}
