package com.nervz.movementtrainer.input

import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.atomic.AtomicInteger

// Shared between the input path (writes) and the GL render thread (reads).
class MovementState {
    @Volatile var heldX = 0          // physical: -1 = left, +1 = right
    @Volatile var crouching = false
    @Volatile var facing = 1         // P1 faces right (+1), P2 faces left (-1)
    val backdashes = AtomicInteger()
    val dashes = AtomicInteger()
    val crouchDashes = AtomicInteger()
    val sidesteps = AtomicInteger()
}

// Consumes the closed-state stream (direction + how many frames it was held)
// and detects clean movement tech. Directions arrive in PHYSICAL space;
// `logical` mirrors them per side so BACK is always B in the state machines.
// Thresholds are first guesses — tune against real-pad feel.
class TechEngine(private val movement: MovementState) {
    val kbdStreak = mutableIntStateOf(0)
    val wdStreak = mutableIntStateOf(0)

    private var p2 = false
    private val maxHold = 20
    private val maxGap = 10

    private enum class K { IDLE, B1, N1, BD, BD_N, AFTER_DB, AFTER_DB_N }
    private enum class W { IDLE, F, FN, D, DF, DF_N }
    private enum class DSH { IDLE, F1, N1 }
    private var k = K.IDLE
    private var w = W.IDLE
    private var dash = DSH.IDLE

    fun setSide(isP2: Boolean) {
        p2 = isP2
        resetStreaks()
    }

    fun resetStreaks() {
        kbdStreak.intValue = 0
        wdStreak.intValue = 0
        k = K.IDLE
        w = W.IDLE
        dash = DSH.IDLE
    }

    fun onState(dirPhysical: Direction, hasButtons: Boolean, frames: Int) {
        val d = logical(dirPhysical)
        kbd(d, hasButtons, frames)
        wavedash(d, hasButtons, frames)
        dashDetect(d, hasButtons, frames)
        if (!hasButtons && dirPhysical == Direction.U && frames <= 8) {
            movement.sidesteps.incrementAndGet()
        }
    }

    private fun logical(d: Direction): Direction = if (!p2) d else when (d) {
        Direction.B -> Direction.F
        Direction.F -> Direction.B
        Direction.DB -> Direction.DF
        Direction.DF -> Direction.DB
        Direction.UB -> Direction.UF
        Direction.UF -> Direction.UB
        else -> d
    }

    // KBD: b, n, b = backdash, then d/b cancels it (streak++). After the cancel,
    // either roll d/b -> b (fast KBD) or re-seed b, n, b (double-tap variant).
    private fun kbd(d: Direction, btn: Boolean, frames: Int) {
        if (btn) {
            kbdStreak.intValue = 0
            k = K.IDLE
            return
        }
        val quick = frames <= maxHold
        val shortGap = frames <= maxGap
        k = when (k) {
            K.IDLE -> if (d == Direction.B && quick) K.B1 else K.IDLE
            K.B1 -> if (d == Direction.N && shortGap) K.N1 else kFail(d, quick)
            K.N1 -> if (d == Direction.B && quick) {
                movement.backdashes.incrementAndGet()
                K.BD
            } else kFail(d, quick)
            K.BD -> when {
                d == Direction.DB && quick -> {
                    kbdStreak.intValue++
                    K.AFTER_DB
                }
                d == Direction.N && shortGap -> K.BD_N
                else -> kFail(d, quick)
            }
            K.BD_N -> when {
                d == Direction.DB && quick -> {
                    kbdStreak.intValue++
                    K.AFTER_DB
                }
                d == Direction.B && quick -> {
                    movement.backdashes.incrementAndGet()
                    K.BD
                }
                else -> kFail(d, quick)
            }
            K.AFTER_DB -> when {
                d == Direction.B && quick -> {
                    movement.backdashes.incrementAndGet()
                    K.BD
                }
                d == Direction.N && shortGap -> K.AFTER_DB_N
                else -> kFail(d, quick)
            }
            K.AFTER_DB_N -> if (d == Direction.B && quick) {
                movement.backdashes.incrementAndGet()
                K.BD
            } else kFail(d, quick)
        }
    }

    private fun kFail(d: Direction, quick: Boolean): K {
        kbdStreak.intValue = 0
        return if (d == Direction.B && quick) K.B1 else K.IDLE
    }

    // Wavedash: f, (n), d, d/f = one crouchdash (streak++), then f arms the next.
    private fun wavedash(d: Direction, btn: Boolean, frames: Int) {
        if (btn) {
            wdStreak.intValue = 0
            w = W.IDLE
            return
        }
        val quick = frames <= maxHold
        val shortGap = frames <= maxGap
        w = when (w) {
            W.IDLE -> if (d == Direction.F && quick) W.F else W.IDLE
            W.F -> when {
                d == Direction.N && shortGap -> W.FN
                d == Direction.D && quick -> W.D
                else -> wFail(d, quick)
            }
            W.FN -> if (d == Direction.D && quick) W.D else wFail(d, quick)
            W.D -> if (d == Direction.DF && quick) {
                wdStreak.intValue++
                movement.crouchDashes.incrementAndGet()
                W.DF
            } else wFail(d, quick)
            W.DF -> when {
                d == Direction.F && quick -> W.F
                d == Direction.N && shortGap -> W.DF_N
                else -> wFail(d, quick)
            }
            W.DF_N -> if (d == Direction.F && quick) W.F else wFail(d, quick)
        }
    }

    private fun wFail(d: Direction, quick: Boolean): W {
        wdStreak.intValue = 0
        return if (d == Direction.F && quick) W.F else W.IDLE
    }

    // Forward dash f, n, f — drives the arena character only, no streak.
    private fun dashDetect(d: Direction, btn: Boolean, frames: Int) {
        if (btn) {
            dash = DSH.IDLE
            return
        }
        dash = when (dash) {
            DSH.IDLE -> if (d == Direction.F && frames <= maxHold) DSH.F1 else DSH.IDLE
            DSH.F1 -> if (d == Direction.N && frames <= maxGap) DSH.N1 else DSH.IDLE
            DSH.N1 -> if (d == Direction.F && frames <= maxHold) {
                movement.dashes.incrementAndGet()
                DSH.F1
            } else DSH.IDLE
        }
    }
}
