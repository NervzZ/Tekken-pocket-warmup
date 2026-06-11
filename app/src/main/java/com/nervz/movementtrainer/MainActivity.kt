package com.nervz.movementtrainer

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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import com.nervz.movementtrainer.gfx.ArenaView
import com.nervz.movementtrainer.input.BUTTON_ICONS
import com.nervz.movementtrainer.input.InputMonitor

class MainActivity : ComponentActivity() {
    private val monitor = InputMonitor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                    AndroidView(
                        factory = { ctx -> ArenaView(ctx, monitor.movement) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .width(180.dp)
                            .background(
                                Brush.horizontalGradient(
                                    0f to Color(0xFF101418),
                                    0.62f to Color(0xFF101418),
                                    1f to Color(0x00101418),
                                ),
                            ),
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
                }
            }
        }
    }
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
            monitor.tick(SystemClock.uptimeMillis())
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
                        color = DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    )
                }
                HorizontalDivider(
                    color = Color(0xFF1E242B),
                    thickness = 1.dp,
                    modifier = Modifier.width(116.dp),
                )
            }
        }
    }
}
