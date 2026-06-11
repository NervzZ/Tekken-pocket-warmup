package com.nervz.movementtrainer.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.nervz.movementtrainer.R
import com.nervz.movementtrainer.gfx.Calibration
import kotlin.math.abs
import kotlin.math.min

// P1-side convention: f(orward) = right on screen.
// Icons: user-provided PNGs matching the in-game arrow design (neutral renders empty).
enum class Direction(val label: String, val icon: Int?) {
    N("n", null),
    U("u", R.drawable.arrow_u),
    UF("u/f", R.drawable.arrow_uf),
    F("f", R.drawable.arrow_f),
    DF("d/f", R.drawable.arrow_df),
    D("d", R.drawable.arrow_d),
    DB("d/b", R.drawable.arrow_db),
    B("b", R.drawable.arrow_b),
    UB("u/b", R.drawable.arrow_ub),
}

fun directionFrom(x: Float, y: Float): Direction {
    val dz = 0.5f
    val right = x > dz
    val left = x < -dz
    val up = y < -dz
    val down = y > dz
    return when {
        up && right -> Direction.UF
        up && left -> Direction.UB
        down && right -> Direction.DF
        down && left -> Direction.DB
        up -> Direction.U
        down -> Direction.D
        right -> Direction.F
        left -> Direction.B
        else -> Direction.N
    }
}

val BUTTON_NAMES = mapOf(
    KeyEvent.KEYCODE_BUTTON_A to "A",
    KeyEvent.KEYCODE_BUTTON_B to "B",
    KeyEvent.KEYCODE_BUTTON_X to "X",
    KeyEvent.KEYCODE_BUTTON_Y to "Y",
    KeyEvent.KEYCODE_BUTTON_L1 to "LB",
    KeyEvent.KEYCODE_BUTTON_R1 to "RB",
    KeyEvent.KEYCODE_BUTTON_L2 to "LT",
    KeyEvent.KEYCODE_BUTTON_R2 to "RT",
    KeyEvent.KEYCODE_BUTTON_THUMBL to "L3",
    KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
    KeyEvent.KEYCODE_BUTTON_START to "Start",
    KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
    KeyEvent.KEYCODE_BUTTON_MODE to "Guide",
    KeyEvent.KEYCODE_DPAD_UP to "DpadUp",
    KeyEvent.KEYCODE_DPAD_DOWN to "DpadDown",
    KeyEvent.KEYCODE_DPAD_LEFT to "DpadLeft",
    KeyEvent.KEYCODE_DPAD_RIGHT to "DpadRight",
)

// Tekken default pad mapping, face buttons only (shoulders configurable later;
// unmapped pad buttons are consumed but never enter the history).
val TEKKEN_DEFAULT = mapOf(
    KeyEvent.KEYCODE_BUTTON_X to "1",
    KeyEvent.KEYCODE_BUTTON_Y to "2",
    KeyEvent.KEYCODE_BUTTON_A to "3",
    KeyEvent.KEYCODE_BUTTON_B to "4",
)

// Keyboard bridge for emulator testing: arrows arrive as KEYCODE_DPAD_*.
val KEYBOARD_TEKKEN = mapOf(
    KeyEvent.KEYCODE_U to "1",
    KeyEvent.KEYCODE_I to "2",
    KeyEvent.KEYCODE_J to "3",
    KeyEvent.KEYCODE_K to "4",
    KeyEvent.KEYCODE_1 to "1",
    KeyEvent.KEYCODE_2 to "2",
    KeyEvent.KEYCODE_3 to "3",
    KeyEvent.KEYCODE_4 to "4",
    KeyEvent.KEYCODE_NUMPAD_1 to "1",
    KeyEvent.KEYCODE_NUMPAD_2 to "2",
    KeyEvent.KEYCODE_NUMPAD_3 to "3",
    KeyEvent.KEYCODE_NUMPAD_4 to "4",
)

// User-provided Tekken button-cluster icons; key = pressed buttons sorted, e.g. "12".
val BUTTON_ICONS = mapOf(
    "1" to R.drawable.btn_1, "2" to R.drawable.btn_2,
    "3" to R.drawable.btn_3, "4" to R.drawable.btn_4,
    "12" to R.drawable.btn_12, "13" to R.drawable.btn_13, "14" to R.drawable.btn_14,
    "23" to R.drawable.btn_23, "24" to R.drawable.btn_24, "34" to R.drawable.btn_34,
    "123" to R.drawable.btn_123, "124" to R.drawable.btn_124,
    "134" to R.drawable.btn_134, "234" to R.drawable.btn_234,
    "1234" to R.drawable.btn_1234,
)

val DPAD_KEYCODES = setOf(
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
)

fun InputDevice.isGamepadLike(): Boolean =
    sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

class HistoryRow(val dir: Direction, val buttons: List<String>) {
    val frames = mutableIntStateOf(1)
}

// Dead-simple input model, mirroring the game itself: input events ONLY update
// the live pad state; a 60Hz sampler reads that state once per frame and
// builds the history — what was held, and for how many frames. No debounce,
// no filtering, no timing tricks anywhere.
class InputMonitor {
    val devices = mutableStateListOf<String>()
    val rows = mutableStateListOf<HistoryRow>()
    val direction = mutableStateOf(Direction.N)
    val side = mutableStateOf("P1")
    val movement = MovementState()
    val tech = TechEngine(movement)

    private val pressedButtons = sortedSetOf<String>()
    private var keyLeft = false
    private var keyRight = false
    private var keyUp = false
    private var keyDown = false
    private var hatX = 0f
    private var hatY = 0f
    private var stickX = 0f
    private var stickY = 0f

    private val epochMs = SystemClock.uptimeMillis()
    private var lastFrame = 0L

    fun clear() {
        rows.clear()
        tech.resetStreaks()
    }

    fun refreshDevices() {
        devices.clear()
        InputDevice.getDeviceIds().forEach { id ->
            val d = InputDevice.getDevice(id) ?: return@forEach
            if (d.isGamepadLike()) devices.add("${d.name} (id $id)")
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        val isPad = event.device?.isGamepadLike() == true
        val isDpad = event.keyCode in DPAD_KEYCODES
        val label = TEKKEN_DEFAULT[event.keyCode] ?: KEYBOARD_TEKKEN[event.keyCode]
        val isStart = event.keyCode == KeyEvent.KEYCODE_BUTTON_START ||
            event.keyCode == KeyEvent.KEYCODE_SPACE
        val isCalToggle = event.keyCode == KeyEvent.KEYCODE_C ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
        val isCalPause = event.keyCode == KeyEvent.KEYCODE_X
        val isCalPose = event.keyCode == KeyEvent.KEYCODE_V
        val isTPose = event.keyCode == KeyEvent.KEYCODE_T
        val isTuneKey =
            event.keyCode == KeyEvent.KEYCODE_G || event.keyCode == KeyEvent.KEYCODE_H ||
                event.keyCode == KeyEvent.KEYCODE_B || event.keyCode == KeyEvent.KEYCODE_N ||
                event.keyCode == KeyEvent.KEYCODE_Q || event.keyCode == KeyEvent.KEYCODE_W ||
                event.keyCode == KeyEvent.KEYCODE_E || event.keyCode == KeyEvent.KEYCODE_R ||
                event.keyCode == KeyEvent.KEYCODE_D || event.keyCode == KeyEvent.KEYCODE_F ||
                event.keyCode == KeyEvent.KEYCODE_A || event.keyCode == KeyEvent.KEYCODE_S
        if (!isPad && !isDpad && label == null && !isStart && !isCalToggle && !isCalPause &&
            !isCalPose && !isTPose && !isTuneKey && event.keyCode !in BUTTON_NAMES
        ) return false
        if (event.repeatCount > 0) return true
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        if (isCalToggle) {
            if (down) Calibration.auto = !Calibration.auto
            return true
        }
        if (isCalPause) {
            if (down && (Calibration.auto || Calibration.tPose)) {
                Calibration.paused = !Calibration.paused
            }
            return true
        }
        if (isCalPose) {
            if (down) Calibration.testPose = !Calibration.testPose
            return true
        }
        if (isTPose) {
            if (down) Calibration.tPose = !Calibration.tPose
            return true
        }
        if (isTuneKey) {
            if (down) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_G -> Calibration.headYaw -= 4f
                    KeyEvent.KEYCODE_H -> Calibration.headYaw += 4f
                    KeyEvent.KEYCODE_B -> Calibration.pelvisYaw -= 2f
                    KeyEvent.KEYCODE_N -> Calibration.pelvisYaw += 2f
                    KeyEvent.KEYCODE_Q -> Calibration.footAYaw -= 2f
                    KeyEvent.KEYCODE_W -> Calibration.footAYaw += 2f
                    KeyEvent.KEYCODE_E -> Calibration.footBYaw -= 2f
                    KeyEvent.KEYCODE_R -> Calibration.footBYaw += 2f
                    KeyEvent.KEYCODE_D -> Calibration.footBPitch -= 2f
                    KeyEvent.KEYCODE_F -> Calibration.footBPitch += 2f
                    KeyEvent.KEYCODE_A -> Calibration.footBRoll -= 2f
                    KeyEvent.KEYCODE_S -> Calibration.footBRoll += 2f
                }
            }
            return true
        }
        if (isStart) {
            if (down) {
                side.value = if (side.value == "P1") "P2" else "P1"
                val p2 = side.value == "P2"
                movement.facing = if (p2) -1 else 1
                tech.setSide(p2)
            }
            return true
        }
        when {
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> keyLeft = down
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> keyRight = down
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> keyUp = down
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> keyDown = down
            label != null -> if (down) pressedButtons.add(label) else pressedButtons.remove(label)
            else -> return true
        }
        recomputeDirection()
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        stickX = event.getAxisValue(MotionEvent.AXIS_X)
        stickY = event.getAxisValue(MotionEvent.AXIS_Y)
        recomputeDirection()
        return true
    }

    private fun recomputeDirection() {
        val x = when {
            abs(hatX) > 0.5f -> hatX
            keyLeft || keyRight -> (if (keyRight) 1f else 0f) - (if (keyLeft) 1f else 0f)
            else -> stickX
        }
        val y = when {
            abs(hatY) > 0.5f -> hatY
            keyUp || keyDown -> (if (keyDown) 1f else 0f) - (if (keyUp) 1f else 0f)
            else -> stickY
        }
        val dir = directionFrom(x, y)
        movement.heldX = when (dir) {
            Direction.B, Direction.UB -> -1
            Direction.F, Direction.UF -> 1
            else -> 0
        }
        movement.heldUp = dir == Direction.U
        movement.heldUpward =
            dir == Direction.U || dir == Direction.UB || dir == Direction.UF
        movement.heldDown = dir == Direction.D
        movement.crouching =
            dir == Direction.D || dir == Direction.DB || dir == Direction.DF
        direction.value = dir
    }

    // The 60Hz sampler — call once per display frame; ticks are derived from
    // the wall clock so display refresh rate and UI stalls don't skew counts.
    fun sample(nowMs: Long) {
        val frame = (nowMs - epochMs) * 60 / 1000
        if (frame <= lastFrame) return
        val ticks = (frame - lastFrame).toInt()
        lastFrame = frame

        val dir = direction.value
        val buttons = pressedButtons.toList()
        val live = rows.firstOrNull()
        if (live != null && live.dir == dir && live.buttons == buttons) {
            live.frames.intValue = min(999, live.frames.intValue + ticks)
            return
        }
        if (live != null) {
            tech.onState(live.dir, live.buttons.isNotEmpty(), live.frames.intValue)
        } else if (dir == Direction.N && buttons.isEmpty()) {
            return
        }
        // completions ending on a HELD input fire on the press, not the
        // release — the validators see the new state the moment it opens
        tech.onOpen(dir, buttons.isNotEmpty())
        rows.add(0, HistoryRow(dir, buttons).also { it.frames.intValue = min(999, ticks) })
        while (rows.size > 300) rows.removeAt(rows.size - 1)
    }
}
