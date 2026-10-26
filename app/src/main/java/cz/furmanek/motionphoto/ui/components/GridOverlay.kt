package cz.furmanek.motionphoto.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The area a FIT_CENTER preview of [aspect] (width / height) occupies in [container]. */
fun fittedRect(container: Size, aspect: Float): Rect {
    val width = minOf(container.width, container.height * aspect)
    val height = width / aspect
    val left = (container.width - width) / 2
    val top = (container.height - height) / 2
    return Rect(left, top, left + width, top + height)
}

/** Rule-of-thirds lines over the visible preview frame. */
@Composable
fun GridOverlay(aspect: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val frame = fittedRect(size, aspect)
        val stroke = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.5f)
        for (i in 1..2) {
            val x = frame.left + frame.width * i / 3
            val y = frame.top + frame.height * i / 3
            drawLine(color, Offset(x, frame.top), Offset(x, frame.bottom), stroke)
            drawLine(color, Offset(frame.left, y), Offset(frame.right, y), stroke)
        }
    }
}
