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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun MonitorScreen(monitor: InputMonitor) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101418)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { monitor.clear() }
                    },
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        monitor.side.value,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
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
                Spacer(Modifier.height(6.dp))
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
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(monitor.rows) { row ->
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.height(34.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.width(44.dp),
                                ) {
                                    if (row.dir.icon != null) {
                                        Image(
                                            painterResource(row.dir.icon),
                                            contentDescription = row.dir.label,
                                            modifier = Modifier.size(28.dp),
                                        )
                                        if (row.buttons.isNotEmpty()) {
                                            Spacer(Modifier.width(8.dp))
                                        }
                                    }
                                    if (row.buttons.isNotEmpty()) {
                                        Text(
                                            row.buttons.joinToString("+"),
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 18.sp,
                                        )
                                    }
                                }
                                Text(
                                    "${row.frames.intValue}",
                                    color = Color(0xFF7B8794),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                )
                            }
                            HorizontalDivider(
                                color = Color(0xFF1E242B),
                                thickness = 1.dp,
                                modifier = Modifier.width(76.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
