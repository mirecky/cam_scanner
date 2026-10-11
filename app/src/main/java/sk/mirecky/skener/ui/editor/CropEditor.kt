package sk.mirecky.skener.ui.editor

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import sk.mirecky.skener.R
import sk.mirecky.skener.data.PageItem
import sk.mirecky.skener.scanner.DocumentDetector
import sk.mirecky.skener.scanner.Pt
import sk.mirecky.skener.scanner.Quad
import sk.mirecky.skener.ui.theme.SkenerColors
import kotlin.math.max
import kotlin.math.roundToInt

/** Celá fotografia s malým okrajom (tlačidlo Celá strana). */
private val FULL = listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0))

/**
 * Ručný orez strany na pôvodnej fotografii: štyri rohy a stredy hrán sa dajú ťahať,
 * počas ťahania lupa ukazuje zväčšené okolie prsta. Automaticky = nová detekcia obrysu.
 */
@Composable
fun CropEditor(page: PageItem, onCancel: () -> Unit, onApply: (Quad?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var image by remember(page.id) { mutableStateOf<ImageBitmap?>(null) }
    var points by remember(page.id) { mutableStateOf(page.quad?.corners ?: FULL) }
    var active by remember { mutableStateOf<Int?>(null) }
    var touch by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(page.id) {
        image = withContext(Dispatchers.IO) { loadOriginal(page) }?.asImageBitmap()
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val bmp = image
            if (bmp == null) {
                CircularProgressIndicator(color = SkenerColors.Accent)
            } else {
                val handleHit = with(density) { 36.dp.toPx() }
                val cornerR = with(density) { 13.dp.toPx() }
                val edgeR = with(density) { 9.dp.toPx() }
                val loupeR = with(density) { 52.dp.toPx() }
                val loupeLift = with(density) { 110.dp.toPx() }
                val stroke = with(density) { 2.5.dp.toPx() }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(bmp) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    val rect = imageRect(size.width.toFloat(), size.height.toFloat(), bmp)
                                    val handles = handlePositions(points, rect)
                                    val nearest = handles.indices.minByOrNull { (handles[it] - start).getDistance() }
                                    active = nearest?.takeIf { (handles[it] - start).getDistance() <= handleHit }
                                    touch = start
                                },
                                onDragEnd = { active = null; touch = null },
                                onDragCancel = { active = null; touch = null },
                                onDrag = { change, delta ->
                                    val idx = active ?: return@detectDragGestures
                                    change.consume()
                                    touch = change.position
                                    val rect = imageRect(size.width.toFloat(), size.height.toFloat(), bmp)
                                    val dx = delta.x / rect.width
                                    val dy = delta.y / rect.height
                                    val moved = if (idx < 4) listOf(idx) else listOf(idx - 4, (idx - 3) % 4)
                                    points = points.mapIndexed { i, p ->
                                        if (i in moved) Pt((p.x + dx).coerceIn(0.0, 1.0), (p.y + dy).coerceIn(0.0, 1.0)) else p
                                    }
                                },
                            )
                        },
                ) {
                    val rect = imageRect(size.width, size.height, bmp)
                    drawImage(
                        bmp,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                    )
                    val corners = points.map { Offset(rect.left + (it.x * rect.width).toFloat(), rect.top + (it.y * rect.height).toFloat()) }
                    val path = Path().apply {
                        moveTo(corners[0].x, corners[0].y)
                        corners.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    drawPath(path, SkenerColors.Accent.copy(alpha = 0.12f))
                    drawPath(path, SkenerColors.Accent, style = Stroke(stroke))
                    handlePositions(points, rect).forEachIndexed { i, c ->
                        val r = if (i < 4) cornerR else edgeR
                        drawCircle(SkenerColors.Ink.copy(alpha = 0.55f), r, c)
                        drawCircle(SkenerColors.Accent, r, c, style = Stroke(stroke))
                    }
                    val t = touch
                    if (active != null && t != null) drawLoupe(bmp, rect, t, loupeR, loupeLift)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(SkenerColors.Panel).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = {
                    val bmp = image ?: return@FilledTonalButton
                    scope.launch {
                        val found = withContext(Dispatchers.Default) { detect(bmp, page) }
                        if (found != null) points = found.corners
                        else Toast.makeText(context, R.string.crop_not_found, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.crop_auto)) }
            FilledTonalButton(onClick = { points = FULL }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.crop_full)) }
        }
        Row(Modifier.fillMaxWidth().background(SkenerColors.Panel).padding(start = 14.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = {
                    val quad = Quad.fromUnordered(points)
                    when {
                        points == FULL -> onApply(null)
                        !quad.isConvex() -> Toast.makeText(context, R.string.crop_invalid, Toast.LENGTH_SHORT).show()
                        else -> onApply(quad)
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_done)) }
        }
    }
}

/** Obdĺžnik, do ktorého sa obrázok zmestí (zachovaný pomer strán). */
private fun imageRect(w: Float, h: Float, bmp: ImageBitmap): Rect {
    val scale = minOf(w / bmp.width, h / bmp.height)
    val iw = bmp.width * scale
    val ih = bmp.height * scale
    return Rect(Offset((w - iw) / 2, (h - ih) / 2), Size(iw, ih))
}

/** Rohy (0–3) a stredy hrán (4–7, hrana i medzi rohmi i a i+1). */
private fun handlePositions(points: List<Pt>, rect: Rect): List<Offset> {
    val c = points.map { Offset(rect.left + (it.x * rect.width).toFloat(), rect.top + (it.y * rect.height).toFloat()) }
    return c + (0 until 4).map { i -> (c[i] + c[(i + 1) % 4]) / 2f }
}

/** Lupa nad prstom: 2,5× zväčšené okolie bodu, aby sa roh dal presne umiestniť. */
private fun DrawScope.drawLoupe(bmp: ImageBitmap, rect: Rect, touch: Offset, radius: Float, lift: Float) {
    val center = Offset(touch.x.coerceIn(radius, size.width - radius), max(radius, touch.y - lift))
    val zoom = 2.5f
    val scale = bmp.width / rect.width
    val srcHalf = radius / zoom * scale
    val sx = ((touch.x - rect.left) * scale - srcHalf).roundToInt().coerceIn(0, max(0, bmp.width - 1))
    val sy = ((touch.y - rect.top) * scale - srcHalf).roundToInt().coerceIn(0, max(0, bmp.height - 1))
    val sw = (srcHalf * 2).roundToInt().coerceAtMost(bmp.width - sx).coerceAtLeast(1)
    val sh = (srcHalf * 2).roundToInt().coerceAtMost(bmp.height - sy).coerceAtLeast(1)
    val clip = Path().apply { addOval(Rect(center, radius)) }
    clipPath(clip) {
        drawRect(SkenerColors.Ink, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2))
        drawImage(
            bmp,
            srcOffset = IntOffset(sx, sy),
            srcSize = IntSize(sw, sh),
            dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
            dstSize = IntSize((sw / scale * zoom).roundToInt(), (sh / scale * zoom).roundToInt()),
        )
    }
    drawCircle(androidx.compose.ui.graphics.Color.White, radius, center, style = Stroke(3f))
    drawCircle(SkenerColors.Accent, 6f, center, style = Stroke(2.5f))
}

/** Pôvodná fotografia zmenšená na najviac 1600 px (orientácia z EXIF je uplatnená). */
private fun loadOriginal(page: PageItem): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(page.originalFile)) { decoder, info, _ ->
        val longest = max(info.size.width, info.size.height)
        if (longest > 1600) {
            val f = 1600f / longest
            decoder.setTargetSize((info.size.width * f).roundToInt(), (info.size.height * f).roundToInt())
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

/** Nová automatická detekcia obrysu na pôvodnej fotografii. */
private fun detect(image: ImageBitmap, page: PageItem): Quad? {
    val rgba = Mat()
    Utils.bitmapToMat(image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false), rgba)
    val gray = Mat()
    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
    rgba.release()
    val small = Mat()
    val f = 480.0 / gray.cols()
    Imgproc.resize(gray, small, org.opencv.core.Size(480.0, gray.rows() * f), 0.0, 0.0, Imgproc.INTER_AREA)
    gray.release()
    val detector = DocumentDetector()
    return detector.detect(small, page.mode).also {
        small.release()
        detector.release()
    }?.quad
}

