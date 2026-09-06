// SPDX-License-Identifier: GPL-3.0-only
package app.shackcq.mobile.bandmap

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

internal fun Modifier.bandMapTouchGestures(
    band: String,
    controller: BandMapController,
    vertical: Boolean,
    segment: () -> BandMapSegment,
): Modifier {
    val drag = pointerInput(band, vertical) {
        if (vertical) {
            detectVerticalDragGestures { change, amount ->
                controller.pan(band, (-amount / size.height.coerceAtLeast(1)).toDouble())
                change.consume()
            }
        } else {
            detectHorizontalDragGestures { change, amount ->
                controller.pan(band, (-amount / size.width.coerceAtLeast(1)).toDouble())
                change.consume()
            }
        }
    }
    return drag.pointerInput(band, vertical) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var anyPressed: Boolean
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (abs(zoom - 1f) > .01f) {
                        val active = segment()
                        val centroid = event.calculateCentroid()
                        val position = if (vertical) centroid.y / size.height.coerceAtLeast(1) else centroid.x / size.width.coerceAtLeast(1)
                        controller.zoom(band, 1.0 / zoom, active.lowerHz + (active.spanHz * position).toLong())
                    }
                    event.changes.forEach { it.consume() }
                }
                anyPressed = event.changes.any { it.pressed }
            } while (anyPressed)
        }
    }
}
