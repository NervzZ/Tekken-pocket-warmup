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
//               opponent (orbit arc). CANCELABLE BY ANY OTHER MOVEMENT:
//               back/forward (walk), down (duck), another up/down tap
//               (fresh sidestep), backdash, dash. A MATCHING-direction
//               hold outranks the duck: it means sidewalk.
//   SIDEWALK_UP / SIDEWALK_DOWN — holding the direction from a sidestep
//               (u,U / d,D) flows into a continuous strafe (entered
//               seamlessly once the inside foot plants). Free state:
//               anything cancels it; exits on release.
//   CROUCH    — held down/down-back; a FREE state: ducks in ~7-8 frames,
//               locks nothing (any event fires straight out of it), and
//               exits the moment the hold releases.
//   JUMP      — any up-component held >= 10 frames (a sidestep tap releases
//               within 8, so taps never jump). u = in place, ub = slight
//               backward arc, uf = slight forward arc. UNCANCELABLE, and it
//               lands into CROUCH with a short locked landing recovery.
//   DASH      — double-tap forward; a 34-frame run, much faster than
//               walking. A third forward tap (dash event mid-dash) ARMS
//               maintain: holding that last forward keeps the run going
//               until released. CANCELABLE BY ANY OTHER MOVEMENT at any
//               moment: backdash, sidestep taps, crouch, or holding back.
class ArenaSim(private val movement: MovementState) {

    enum class MoveState {
        IDLE, WALK_F, WALK_B, BACKDASH, SIDESTEP_UP, SIDESTEP_DOWN,
        SIDEWALK_UP, SIDEWALK_DOWN, CROUCH, DASH, JUMP,
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
    private var jumpT = 0f
    private var jumpDir = 0f
    private var upHoldT = 0f
    private var landLock = 0f

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
    @Volatile var jumpProgress = -1f // jump 0..1, or -1 when inactive
    @Volatile var jumpDirF = 0f      // +1 uf arc / 0 in place / -1 ub arc
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
        // continuous up-component hold time drives the jump trigger
        upHoldT = if (movement.heldUpward) upHoldT + dt else 0f
        if (landLock > 0f) landLock -= dt

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
                // cancelable by any other movement; a matching-direction
                // hold means sidewalk (handled in the progress block) and
                // outranks the duck
                val holdMatches = (ssDir > 0f && movement.heldUp) ||
                    (ssDir < 0f && movement.heldDown)
                when {
                    bdEvt -> {
                        state = MoveState.BACKDASH; bdT = 0f; bdBuffered = false
                        ssProgress = -1f
                    }
                    dashEvt && dist > MIN_DIST + 0.01f -> {
                        state = MoveState.DASH; dashT = 0f; dashMaintain = false
                        ssProgress = -1f
                    }
                    ssUpEvt -> startSidestep(+1f)
                    ssDownEvt -> startSidestep(-1f)
                    holdMatches -> {}
                    crouchHeld -> { state = MoveState.CROUCH; ssProgress = -1f }
                    relDir == 1 -> { state = MoveState.WALK_F; ssProgress = -1f }
                    relDir == -1 -> { state = MoveState.WALK_B; ssProgress = -1f }
                    else -> {}
                }
            }
            MoveState.JUMP -> {
                // uncancelable in any way: every event is dropped
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
                // landing recovery: stay crouched, events dropped
                landLock > 0f -> state = MoveState.CROUCH
                // IDLE / WALK / SIDEWALK / CROUCH are free states
                bdEvt -> { state = MoveState.BACKDASH; bdT = 0f; bdBuffered = false }
                // no room to run: a dash can't start at the closest distance
                dashEvt && dist > MIN_DIST + 0.01f -> {
                    state = MoveState.DASH; dashT = 0f; dashMaintain = false
                }
                ssUpEvt -> startSidestep(+1f)
                ssDownEvt -> startSidestep(-1f)
                // sidewalk persistence MUST outrank the duck: holding down
                // IS the sidewalk-down input (crouching includes plain D —
                // checked after, so down still ducks everything else)
                state == MoveState.SIDEWALK_UP && movement.heldUp -> {}
                state == MoveState.SIDEWALK_DOWN && movement.heldDown -> {}
                // up-component held past the tap window = jump (u/ub/uf)
                upHoldT >= JUMP_HOLD -> {
                    state = MoveState.JUMP
                    jumpT = 0f
                    jumpDir = relDir.toFloat()
                    jumpDirF = jumpDir
                }
                crouchHeld -> state = MoveState.CROUCH
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
            // arc around the opponent; up (+) circles into the background —
            // the world sense flips with the side (P2 was mirrored)
            orbitAng += (ssDisp(u) - ssDisp(uPrev)) * SIDESTEP_ARC * ssDir * facingF / dist
            ssProgress = u
            // matching hold converts into the sidewalk as soon as the inside
            // foot has planted (~u 0.5) — no waiting for the full 24 frames
            val holdMatches = (ssDir > 0f && movement.heldUp) ||
                (ssDir < 0f && movement.heldDown)
            if (ssT >= SIDESTEP_DUR || (u >= 0.5f && holdMatches)) {
                state = when {
                    holdMatches && ssDir > 0f -> MoveState.SIDEWALK_UP
                    holdMatches && ssDir < 0f -> MoveState.SIDEWALK_DOWN
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
            orbitAng += SIDEWALK_SPEED * dir * facingF / dist * dt
            swPhase += PI.toFloat() * SIDEWALK_SPEED / SIDEWALK_STRIDE * dt
        }
        val swTarget =
            if (state == MoveState.SIDEWALK_UP || state == MoveState.SIDEWALK_DOWN) 1f else 0f
        swAmt += (swTarget - swAmt) * min(1f, dt * 7f)
        swAmount = swAmt
        if (state == MoveState.JUMP) {
            jumpT += dt
            val u = (jumpT / JUMP_DUR).coerceAtMost(1f)
            // uf drifts toward the opponent, ub away, u stays in place
            dist -= JUMP_DRIFT * jumpDir / JUMP_DUR * dt
            jumpProgress = u
            if (jumpT >= JUMP_DUR) {
                // lands in crouch with a short locked landing recovery
                state = MoveState.CROUCH
                landLock = LAND_LOCK
                jumpProgress = -1f
            }
        } else {
            jumpProgress = -1f
        }
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
        const val SIDEWALK_SPEED = 1.7f     // brisk Tekken strafe, units / s
        const val SIDEWALK_STRIDE = 0.42f   // lateral units per step (cadence)
        const val JUMP_HOLD = 10f / 60f     // up-hold frames to trigger (tap = 8)
        const val JUMP_DUR = 48f / 60f      // airborne portion
        const val JUMP_DRIFT = 0.35f        // ub/uf horizontal arc
        const val LAND_LOCK = 12f / 60f     // locked crouch landing recovery
        const val DASH_DUR = 34f / 60f      // 34 frames (user-corrected from 80)
        // speed raised with stride scaled to match: covers ground faster at
        // the SAME animation cadence (speed/stride unchanged, ~2.9 steps/s)
        const val DASH_SPEED = 3.0f         // a run — much faster than walking
        const val DASH_STRIDE = 1.02f       // long reaching strides (slow cadence)
    }
}
