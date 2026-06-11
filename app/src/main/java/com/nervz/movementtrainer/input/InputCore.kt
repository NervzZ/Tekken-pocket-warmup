package com.nervz.movementtrainer.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.nervz.movementtrainer.R
import kotlin.math.abs
import kotlin.math.max
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

// Keyboard bridge: lets the emulator (which forwards host keyboard, not gamepads)
// and pad-to-keyboard mappers drive the app. Arrows already arrive as KEYCODE_DPAD_*.
val KEYBOARD_TEKKEN = mapOf(
    KeyEvent.KEYCODE_U to "1",
    KeyEvent.KEYCODE_I to "2",
    KeyEvent.KEYCODE_J to "3",
    KeyEvent.KEYCODE_K to "4",
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

// One row = one input state (direction + held buttons), Tekken-style.
// `frames` counts how many 60fps frames the state has been active, capped at 999.
class HistoryRow(val startFrame: Long, val dir: Direction, val buttons: List<String>) {
    val frames = mutableIntStateOf(1)
    var closed = false
}

class InputMonitor {
    val devices = mutableStateListOf<String>()
    val rows = mutableStateListOf<HistoryRow>()
    val direction = mutableStateOf(Direction.N)
    val side = mutableStateOf("P1")
    val movement = MovementState()
    val tech = TechEngine(movement)

    private val epochMs = SystemClock.uptimeMillis()
    private val pressedButtons = sortedSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val pendingKeyUp = HashMap<Int, Runnable>()
    private var keyLeft = false
    private var keyRight = false
    private var keyUp = false
    private var keyDown = false
    private var hatX = 0f
    private var hatY = 0f
    private var stickX = 0f
    private var stickY = 0f

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
        if (!isPad && !isDpad && label == null && !isStart && event.keyCode !in BUTTON_NAMES) return false
        if (event.repeatCount > 0) return true
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        if (isStart) {
            if (down) {
                side.value = if (side.value == "P1") "P2" else "P1"
                val p2 = side.value == "P2"
                movement.facing = if (p2) -1 else 1
                tech.setSide(p2)
            }
            return true
        }
        if (!isDpad && label == null) return true
        if (!isPad) {
            // Host keyboard auto-repeat reaches the guest as rapid release/press
            // pairs; debounce releases so a held key reads as a continuous hold.
            // Real pads bypass this — their timing must stay raw.
            if (down) {
                val pending = pendingKeyUp.remove(event.keyCode)
                if (pending != null) {
                    handler.removeCallbacks(pending)
                    return true
                }
            } else {
                val t = event.eventTime
                val code = event.keyCode
                val r = Runnable {
                    pendingKeyUp.remove(code)
                    applyKey(code, false, t, label, isDpad)
                }
                pendingKeyUp[code] = r
                handler.postDelayed(r, 50)
                return true
            }
        }
        applyKey(event.keyCode, down, event.eventTime, label, isDpad)
        return true
    }

    private fun applyKey(keyCode: Int, down: Boolean, t: Long, label: String?, isDpad: Boolean) {
        if (isDpad) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> keyLeft = down
                KeyEvent.KEYCODE_DPAD_RIGHT -> keyRight = down
                KeyEvent.KEYCODE_DPAD_UP -> keyUp = down
                KeyEvent.KEYCODE_DPAD_DOWN -> keyDown = down
            }
            recomputeDirection(t)
        } else if (label != null) {
            if (down) pressedButtons.add(label) else pressedButtons.remove(label)
            onInputChanged(t)
        }
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        for (h in 0 until event.historySize) processSample(event, h)
        processSample(event, -1)
        return true
    }

    private fun processSample(event: MotionEvent, h: Int) {
        fun axis(a: Int) = if (h >= 0) event.getHistoricalAxisValue(a, h) else event.getAxisValue(a)
        val t = if (h >= 0) event.getHistoricalEventTime(h) else event.eventTime
        hatX = axis(MotionEvent.AXIS_HAT_X)
        hatY = axis(MotionEvent.AXIS_HAT_Y)
        stickX = axis(MotionEvent.AXIS_X)
        stickY = axis(MotionEvent.AXIS_Y)
        recomputeDirection(t)
    }

    private fun recomputeDirection(t: Long) {
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
        movement.heldDown = dir == Direction.D
        movement.crouching =
            dir == Direction.D || dir == Direction.DB || dir == Direction.DF
        if (dir != direction.value) {
            direction.value = dir
            onInputChanged(t)
        }
    }

    private fun frameAt(t: Long) = (t - epochMs) * 60 / 1000

    // Inputs register on the NEXT frame boundary, mirroring the game's per-frame
    // input sampling. A state lasting less than one frame is dropped — the game
    // would never have sampled it either.
    // Newest row lives at index 0 — each new state pushes the history down.
    private fun onInputChanged(t: Long) {
        val dir = direction.value
        val buttons = pressedButtons.toList()
        val live = rows.firstOrNull()
        if (live != null && !live.closed && live.dir == dir && live.buttons == buttons) return
        val reg = frameAt(t) + 1
        if (live != null && !live.closed) {
            val finalCount = (reg - live.startFrame).toInt()
            if (finalCount <= 0) {
                rows.removeAt(0)
                val prev = rows.firstOrNull()
                if (prev != null && prev.dir == dir && prev.buttons == buttons) {
                    prev.closed = false
                    return
                }
            } else {
                live.frames.intValue = min(999, finalCount)
                live.closed = true
                tech.onState(live.dir, live.buttons.isNotEmpty(), finalCount)
            }
        }
        rows.add(0, HistoryRow(reg, dir, buttons))
        while (rows.size > 300) rows.removeAt(rows.size - 1)
    }

    fun tick(nowMs: Long) {
        val live = rows.firstOrNull() ?: return
        if (live.closed) return
        live.frames.intValue = min(999L, max(1L, frameAt(nowMs) - live.startFrame + 1)).toInt()
    }
}
