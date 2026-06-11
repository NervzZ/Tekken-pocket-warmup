package com.nervz.movementtrainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nervz.movementtrainer.gfx.ArenaSim
import com.nervz.movementtrainer.gfx.Calibration
import com.nervz.movementtrainer.gfx.ArenaView
import com.nervz.movementtrainer.gfx.MokujinView
import com.nervz.movementtrainer.input.BUTTON_ICONS
import com.nervz.movementtrainer.input.InputMonitor

class MainActivity : ComponentActivity() {
    private val monitor = InputMonitor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    intent ?: return
                    if (intent.hasExtra("group")) {
                        val g = intent.getStringExtra("group") ?: return
                        Calibration.stanceOverrides[g] = floatArrayOf(
                            intent.getFloatExtra("rx", 0f),
                            intent.getFloatExtra("ry", 0f),
                            intent.getFloatExtra("rz", 0f),
                        )
                    }
                    if (intent.hasExtra("rootDy")) {
                        Calibration.rootDy = intent.getFloatExtra("rootDy", 0f)
                    }
                    if (intent.hasExtra("reset")) {
                        Calibration.stanceOverrides.clear()
                        Calibration.rootDy = 0f
                    }
                    if (intent.hasExtra("part")) {
                        Calibration.part = intent.getIntExtra("part", -1)
                    }
                    if (intent.hasExtra("dump")) {
                        Calibration.dumpPose = true
                    }
                    // debug: inject tech events without the input pipeline
                    // (adb keyevents can't produce validator-legal timings)
                    when (intent.getStringExtra("event")) {
                        "backdash" -> monitor.movement.backdashes.incrementAndGet()
                        "ssup" -> monitor.movement.sidestepsUp.incrementAndGet()
                        "ssdown" -> monitor.movement.sidestepsDown.incrementAndGet()
                    }
                }
            },
            IntentFilter("com.nervz.movementtrainer.CAL"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        getSystemService(InputManager::class.java).registerInputDeviceListener(
            object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = monitor.refreshDevices()
                override fun onInputDeviceRemoved(deviceId: Int) = monitor.refreshDevices()
                override fun onInputDeviceChanged(deviceId: Int) = monitor.refreshDevices()
            },
            null,
        )
        monitor.refreshDevices()
        setContent { MonitorScreen(monitor) }
    }

    override fun onResume() {
        super.onResume()
        monitor.refreshDevices()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (monitor.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (monitor.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
}

private val DimText = Color(0xFF7B8794)
private val P1Color = Color(0xFF5BC8FF)
private val P2Color = Color(0xFFFF6B74)
private val AccentRed = Color(0xFFE8333F)

@Composable
fun MonitorScreen(monitor: InputMonitor) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .pointerInput(Unit) {
                        detectTapGestures { monitor.clear() }
                    },
            ) {
                HeaderRow(
                    monitor,
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val sim = remember { ArenaSim(monitor.movement) }
                    var show3d by remember { mutableStateOf(true) }
                    // visibility (not removal) so the Filament engine and GL
                    // context survive the toggle; GONE destroys the surfaces,
                    // which stops all GPU work, and the frame loops early-out
                    val vis = if (show3d) android.view.View.VISIBLE else android.view.View.GONE
                    AndroidView(
                        factory = { ctx -> ArenaView(ctx, sim) },
                        update = { it.visibility = vis },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AndroidView(
                        factory = { ctx -> MokujinView(ctx, sim) },
                        update = { it.visibility = vis },
                        modifier = Modifier.fillMaxSize(),
                    )
                    HistoryList(
                        monitor,
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 14.dp)
                            .width(150.dp)
                            .fillMaxHeight(),
                    )
                    Column(
                        Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 12.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        StreakCounter("KBD", monitor.tech.kbdStreak.intValue)
                        Spacer(Modifier.height(2.dp))
                        StreakCounter("WAVEDASH", monitor.tech.wdStreak.intValue)
                    }
                    if (Calibration.display.value.isNotEmpty()) {
                        Text(
                            Calibration.display.value,
                            color = Color(0xFFFFD54F),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                        )
                    }
                    Column(
                        Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        // bare label + mini track/thumb: the Material Switch
                        // carries a 52x32 min-touch slot + focus ripple that
                        // read as a darkening square container (user request)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { show3d = !show3d },
                        ) {
                            Text(
                                if (show3d) "3D ON" else "3D OFF",
                                color = DimText,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(30.dp, 16.dp)
                                    .background(
                                        if (show3d) Color(0xFF3A2226) else Color(0xFF1E242B),
                                        RoundedCornerShape(8.dp),
                                    ),
                                contentAlignment = if (show3d) {
                                    Alignment.CenterEnd
                                } else {
                                    Alignment.CenterStart
                                },
                            ) {
                                Box(
                                    Modifier
                                        .padding(horizontal = 2.dp)
                                        .size(12.dp)
                                        .background(
                                            if (show3d) AccentRed else DimText,
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                        FpsCounter(Modifier)
                    }
                }
            }
        }
    }
}

// Measures real display frame delivery on the Compose frame clock: n frame
// intervals over their summed duration, refreshed ~4x/s. UI-thread jank and
// missed vsyncs lower it — exactly what "can this phone hold 60" needs.
@Composable
private fun FpsCounter(modifier: Modifier) {
    var fps by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var acc = 0L
        var n = 0
        while (true) {
            val t = withFrameNanos { it }
            acc += t - last
            last = t
            n++
            if (acc >= 250_000_000L) {
                fps = n * 1e9f / acc
                acc = 0L
                n = 0
            }
        }
    }
    Text(
        "%.1f fps".format(fps),
        color = DimText,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = modifier,
    )
}

@Composable
private fun HeaderRow(monitor: InputMonitor, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            monitor.side.value,
            color = if (monitor.side.value == "P1") P1Color else P2Color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            fontSize = 22.sp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "press start to switch sides",
            color = DimText,
            fontSize = 10.sp,
        )
        Spacer(Modifier.weight(1f))
        if (monitor.devices.isEmpty()) {
            Text("no gamepad detected", color = Color(0xFFE08A8A), fontSize = 12.sp)
        } else {
            Text(
                monitor.devices.first(),
                color = Color(0xFF8FD18F),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StreakCounter(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = DimText,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$value",
            color = if (value > 0) AccentRed else Color(0xFF3A434D),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            fontSize = 26.sp,
        )
    }
}

@Composable
private fun HistoryList(monitor: InputMonitor, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(monitor.rows.size) {
        if (monitor.rows.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            monitor.sample(SystemClock.uptimeMillis())
        }
    }
    LazyColumn(state = listState, modifier = modifier) {
        items(monitor.rows) { row ->
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(34.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.width(76.dp),
                    ) {
                        if (row.dir.icon != null) {
                            Image(
                                painterResource(row.dir.icon),
                                contentDescription = row.dir.label,
                                modifier = Modifier.size(28.dp),
                            )
                            if (row.buttons.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                        if (row.buttons.isNotEmpty()) {
                            val icon = BUTTON_ICONS[row.buttons.joinToString("")]
                            if (icon != null) {
                                Image(
                                    painterResource(icon),
                                    contentDescription = row.buttons.joinToString("+"),
                                    modifier = Modifier.size(30.dp),
                                )
                            } else {
                                Text(
                                    row.buttons.joinToString("+"),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 18.sp,
                                )
                            }
                        }
                    }
                    Text(
                        "${row.frames.intValue}",
                        color = Color(0xFFE2E8EE),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                }
                HorizontalDivider(
                    color = Color(0xFF5A646F),
                    thickness = 1.dp,
                    modifier = Modifier.width(116.dp),
                )
            }
        }
    }
}
