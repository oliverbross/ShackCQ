package app.shackcq.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.shackcq.mobile.hamclock.HamClockDisplayPreference
import app.shackcq.mobile.hamclock.HamClockTimeZoneMode
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun AnalogClockPanel(display: HamClockDisplayPreference, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(60_000)
        }
    }
    val zone = if (display.timeZoneMode == HamClockTimeZoneMode.LOCAL) ZoneId.systemDefault() else ZoneOffset.UTC
    val time = now.atZone(zone)
    Surface(color = Color(0xFF111C22), modifier = modifier) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = min(size.width, size.height) * .42f
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(Color(0xFF32434C), radius, center, style = Stroke(3f))
                repeat(12) { index ->
                    val angle = index * PI / 6 - PI / 2
                    val outer = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
                    val inner = Offset(center.x + cos(angle).toFloat() * radius * .86f, center.y + sin(angle).toFloat() * radius * .86f)
                    drawLine(Color(0xFFF0AD35), inner, outer, strokeWidth = if (index % 3 == 0) 5f else 2f)
                }
                fun hand(angle: Double, length: Float, color: Color, width: Float) {
                    drawLine(color, center, Offset(center.x + cos(angle).toFloat() * radius * length,
                        center.y + sin(angle).toFloat() * radius * length), strokeWidth = width)
                }
                hand(((time.hour % 12) + time.minute / 60.0) * PI / 6 - PI / 2, .52f, Color(0xFFEAF0ED), 8f)
                hand(time.minute * PI / 30 - PI / 2, .76f, Color(0xFF42C7D8), 5f)
                drawCircle(Color(0xFFF0AD35), 7f, center)
            }
        }
    }
}
