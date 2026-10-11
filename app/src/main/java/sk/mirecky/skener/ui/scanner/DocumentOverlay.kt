package sk.mirecky.skener.ui.scanner

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import sk.mirecky.skener.scanner.CapturePhase

private val Searching = Color(0xFFFFFFFF)
private val Stabilizing = Color(0xFFFFC107)
private val Ready = Color(0xFF4CAF50)
private val Done = Color(0xFF42A5F5)

/**
 * Obrys nájdeného dokumentu nad náhľadom kamery.
 *
 * Farba ukazuje stav: žltá → zelená počas ustaľovania (podľa postupu), zelená = sníma sa,
 * modrá prerušovaná = tento list je už nasnímaný.
 */
@Composable
fun DocumentOverlay(state: OverlayState, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val quad = state.quad ?: return@Canvas

        // Náhľad je zobrazený celý (FIT_CENTER) s pomerom strán analyzovaného obrazu.
        val viewAspect = size.width / size.height
        val contentW: Float
        val contentH: Float
        if (viewAspect > state.aspect) {
            contentH = size.height
            contentW = (size.height * state.aspect).toFloat()
        } else {
            contentW = size.width
            contentH = (size.width / state.aspect).toFloat()
        }
        val left = (size.width - contentW) / 2
        val top = (size.height - contentH) / 2
        fun map(x: Double, y: Double) = Offset(left + (x * contentW).toFloat(), top + (y * contentH).toFloat())

        val pts = quad.corners.map { map(it.x, it.y) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }

        val capture = state.capture
        val color = when (capture.phase) {
            CapturePhase.SEARCHING -> Searching
            CapturePhase.STABILIZING -> lerp(Stabilizing, Ready, capture.progress)
            CapturePhase.CAPTURING -> Ready
            CapturePhase.WAITING_FOR_CHANGE -> Done
        }
        val dashed = capture.phase == CapturePhase.WAITING_FOR_CHANGE

        drawPath(path, color.copy(alpha = 0.18f))
        drawPath(
            path,
            color,
            style = Stroke(
                width = 4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(18.dp.toPx(), 12.dp.toPx())) else null,
            ),
        )
        pts.forEach { drawCircle(color, radius = 7.dp.toPx(), center = it) }
    }
}
