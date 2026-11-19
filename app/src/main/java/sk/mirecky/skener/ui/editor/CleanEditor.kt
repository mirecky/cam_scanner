package sk.mirecky.skener.ui.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.mirecky.skener.R
import sk.mirecky.skener.data.PageItem
import sk.mirecky.skener.ui.theme.SkenerColors
import kotlin.math.max
import kotlin.math.roundToInt

/** Veľkosti štetca (polomer ako podiel šírky strany). */
private val BRUSHES = listOf(R.string.brush_small to 0.008, R.string.brush_medium to 0.016, R.string.brush_large to 0.032)

/**
 * Nástroj Vyčistiť: prstom sa pretrú miesta, ktoré majú zmiznúť (škvrny, prsty, tiene okraja,
 * poznámky). Pretreté miesta sa hneď zobrazia ako čistý papier; Hotovo ich uloží natrvalo,
 * „Pôvodné“ vráti stranu bez vyčistení.
 */
@Composable
fun CleanEditor(
    page: PageItem,
    onCancel: () -> Unit,
    onApply: (strokes: List<List<Pair<Double, Double>>>, radius: Double) -> Unit,
    onReset: () -> Unit,
) {
    var image by remember(page.id, page.version) { mutableStateOf<ImageBitmap?>(null) }
    val strokes = remember(page.id) { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    var brush by rememberSaveable { mutableStateOf(1) }
    val radius = BRUSHES[brush].second
    val hasSaved = page.cleanFile.exists()

    LaunchedEffect(page.id, page.version) {
        image = withContext(Dispatchers.IO) { loadDisplay(page) }?.asImageBitmap()
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val bmp = image
            if (bmp == null) {
                CircularProgressIndicator(color = SkenerColors.Accent)
            } else {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(bmp) {
                            // Ťuknutie vyčistí jedno miesto (škvrna, bodka).
                            detectTapGestures { pos ->
                                val r = imageRect(size.width.toFloat(), size.height.toFloat(), bmp)
                                if (r.contains(pos)) strokes += listOf(Offset((pos.x - r.left) / r.width, (pos.y - r.top) / r.height))
                            }
                        }
                        .pointerInput(bmp) {
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val r = imageRect(size.width.toFloat(), size.height.toFloat(), bmp)
                                    currentStroke = listOf(Offset((pos.x - r.left) / r.width, (pos.y - r.top) / r.height))
                                    touch = pos
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val r = imageRect(size.width.toFloat(), size.height.toFloat(), bmp)
                                    val p = change.position
                                    currentStroke = currentStroke + Offset((p.x - r.left) / r.width, (p.y - r.top) / r.height)
                                    touch = p
                                },
                                onDragEnd = {
                                    if (currentStroke.isNotEmpty()) strokes += currentStroke
                                    currentStroke = emptyList()
                                    touch = null
                                },
                                onDragCancel = {
                                    currentStroke = emptyList()
                                    touch = null
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
                    // Pretreté miesta vyzerajú ako čistý papier (výsledok po uložení).
                    val width = (radius * 2 * rect.width).toFloat()
                    val style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    for (s in strokes + listOf(currentStroke)) {
                        if (s.isEmpty()) continue
                        val pts = s.map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
                        if (pts.size == 1) {
                            drawCircle(Color.White, width / 2, pts[0])
                        } else {
                            val path = Path().apply {
                                moveTo(pts[0].x, pts[0].y)
                                pts.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, Color.White, style = style)
                        }
                    }
                    // Kruh veľkosti štetca pod prstom.
                    touch?.let { t ->
                        drawCircle(SkenerColors.Accent, max(width / 2, 4f), t, style = Stroke(2.5f))
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(SkenerColors.Panel).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.clean_hint),
                style = MaterialTheme.typography.labelMedium,
                color = SkenerColors.Muted,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.brush_label), style = MaterialTheme.typography.labelLarge, color = SkenerColors.Muted)
                Spacer(Modifier.width(8.dp))
                BRUSHES.forEachIndexed { i, (label, _) ->
                    FilterChip(
                        selected = brush == i,
                        onClick = { brush = i },
                        label = { Text(stringResource(label)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SkenerColors.Accent,
                            selectedLabelColor = SkenerColors.AccentInk,
                        ),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.action_undo_stroke))
                }
                IconButton(onClick = { strokes.clear(); if (hasSaved) onReset() }, enabled = strokes.isNotEmpty() || hasSaved) {
                    Icon(Icons.Outlined.RestartAlt, stringResource(R.string.action_reset))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(SkenerColors.Panel).padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = {
                    if (strokes.isEmpty()) onCancel()
                    else onApply(strokes.map { s -> s.map { it.x.toDouble().coerceIn(0.0, 1.0) to it.y.toDouble().coerceIn(0.0, 1.0) } }, radius)
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_done)) }
        }
    }
}

/** Výsledná strana zmenšená na najviac 1600 px. */
private fun loadDisplay(page: PageItem): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(page.displayFile.path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
    BitmapFactory.decodeFile(page.displayFile.path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
