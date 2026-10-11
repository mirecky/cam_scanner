package sk.mirecky.skener.ui.editor

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import sk.mirecky.skener.R
import sk.mirecky.skener.SkenerApp
import sk.mirecky.skener.data.DocumentItem
import sk.mirecky.skener.data.PageItem
import sk.mirecky.skener.scanner.Adjust
import sk.mirecky.skener.scanner.Filters
import sk.mirecky.skener.scanner.PageFilter
import sk.mirecky.skener.ui.common.PageImage
import sk.mirecky.skener.ui.common.ZoomablePageImage
import sk.mirecky.skener.ui.common.sharePdf
import sk.mirecky.skener.ui.document.FilterPreview
import sk.mirecky.skener.ui.theme.SkenerColors

/** Nástroje editora strany. Orez, filtre a úpravy majú vlastný panel; ostatné sú okamžité akcie. */
private enum class Tool { CROP, FILTER, ADJUST }

/** Rozpracovaná úprava aktuálnej strany (zobrazuje sa na náhľade, kým sa neuloží). */
private data class Edit(val filter: PageFilter, val level: Int, val adjust: Adjust)

/**
 * Editor strany podľa schváleného návrhu: strana so zoomom, pás náhľadov, panel nástrojov
 * (Znova · Orezať · Otočiť · Filtre · Upraviť · Vyčistiť · Text · Zmazať) a hlavné akcie
 * Pokračovať v skenovaní / Zdieľať PDF.
 */
@Composable
fun EditorScreen(
    docId: String,
    initialIndex: Int,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenOverview: (String) -> Unit,
    onAddPages: (String) -> Unit,
    onRetake: (docId: String, pageId: String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as SkenerApp
    val repository = app.documents
    val documents by repository.documents.collectAsStateWithLifecycle()
    val exporting by repository.exporting.collectAsStateWithLifecycle()
    val doc = documents.firstOrNull { it.id == docId }

    LaunchedEffect(doc == null) { if (doc == null) onBack() }
    if (doc == null || doc.pages.isEmpty()) return

    val pages = doc.pages
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(pages.indices)) { pages.size }
    val current = pagerState.currentPage.coerceIn(pages.indices)
    val page = pages[current]
    val scope = rememberCoroutineScope()

    var tool by rememberSaveable { mutableStateOf<Tool?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = app.snackbar

    LaunchedEffect(pages.size) {
        if (pagerState.currentPage > pages.lastIndex) pagerState.scrollToPage(pages.lastIndex)
    }

    // Úprava filtra a úprav aktuálnej strany s okamžitým náhľadom.
    val committed = Edit(page.filter, page.level, page.adjust)
    var edit by remember(page.id) { mutableStateOf(committed) }
    val preview = remember(page.id, page.version) { FilterPreview(page) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    var thumbnails by remember(page.id, page.version) { mutableStateOf<Map<PageFilter, ImageBitmap>>(emptyMap()) }
    var previewImage by remember(page.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(preview) {
        preview.load()
        thumbnails = preview.thumbnails()
    }
    LaunchedEffect(preview, edit) {
        if (edit == committed) {
            previewImage = null
            return@LaunchedEffect
        }
        previewImage = preview.render(edit.filter, edit.level, edit.adjust)
        delay(COMMIT_DELAY_MS)
        val target = edit
        val pageId = page.id
        app.scope.launch {
            if (target.filter != committed.filter || target.level != committed.level) {
                repository.setFilter(docId, setOf(pageId), target.filter, target.level)
            }
            if (target.adjust != committed.adjust) repository.setAdjust(docId, setOf(pageId), target.adjust)
        }
    }
    val busy = edit != committed

    BackHandler { if (tool != null) tool = null else onBack() }

    Column(Modifier.fillMaxSize().background(SkenerColors.Ink).systemBarsPadding()) {
        // ---------- horná lišta ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenLibrary) { Icon(Icons.Outlined.Home, stringResource(R.string.action_library)) }
            Column(Modifier.weight(1f).clickable { renaming = true }.padding(horizontal = 4.dp)) {
                Text(
                    doc.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = TextDecoration.None,
                )
                Text(
                    if (docId in exporting) stringResource(R.string.pdf_updating) else stringResource(R.string.pdf_saved_location),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (docId in exporting) SkenerColors.Accent else SkenerColors.Muted,
                )
            }
            TextButton(onClick = { onOpenOverview(docId) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Outlined.GridView, null, tint = SkenerColors.Text, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_pages_short), color = SkenerColors.Text, style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = { sharePdf(context, doc) }) { Icon(Icons.Outlined.Share, stringResource(R.string.action_share)) }
        }

        // ---------- strana ----------
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B0D10))) {
            if (tool == Tool.CROP) {
                CropEditor(
                    page = page,
                    onCancel = { tool = null },
                    onApply = { quad ->
                        tool = null
                        app.scope.launch { repository.recrop(docId, page.id, quad) }
                    },
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    key = { pages[it].id },
                    userScrollEnabled = tool == null,
                    modifier = Modifier.fillMaxSize(),
                ) { index ->
                    val image = previewImage
                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                        if (index == current && image != null) {
                            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        } else {
                            ZoomablePageImage(
                                pages[index],
                                contentDescription = stringResource(R.string.page_position, index + 1, pages.size),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                if (tool == null) {
                    PageChip(
                        index = current,
                        count = pages.size,
                        onPrev = { scope.launch { pagerState.animateScrollToPage(current - 1) } },
                        onNext = { scope.launch { pagerState.animateScrollToPage(current + 1) } },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    )
                }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp),
                        color = SkenerColors.Accent,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        // ---------- kontextový panel / pás náhľadov ----------
        when (tool) {
            null -> ThumbnailStrip(
                pages = pages,
                current = current,
                onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
                onAdd = { onAddPages(docId) },
                onReorder = { order ->
                    val currentId = page.id
                    app.scope.launch {
                        repository.reorder(docId, order)
                        // Editor ostane na tej istej strane, aj keď sa posunula.
                        val newIndex = order.indexOf(currentId)
                        if (newIndex >= 0) scope.launch { pagerState.scrollToPage(newIndex) }
                    }
                },
            )
            Tool.FILTER -> FilterPanel(
                thumbnails = thumbnails,
                edit = edit,
                onEdit = { edit = it },
                onApplyAll = {
                    val target = edit
                    app.scope.launch { repository.setFilter(docId, pages.map { it.id }.toSet(), target.filter, target.level) }
                    Toast.makeText(context, R.string.applied_to_all, Toast.LENGTH_SHORT).show()
                },
            )
            Tool.ADJUST -> AdjustPanel(
                adjust = edit.adjust,
                onAdjust = { edit = edit.copy(adjust = it) },
                onApplyAll = {
                    val target = edit.adjust
                    app.scope.launch { repository.setAdjust(docId, pages.map { it.id }.toSet(), target) }
                    Toast.makeText(context, R.string.applied_to_all, Toast.LENGTH_SHORT).show()
                },
                onDone = { tool = null },
            )
            Tool.CROP -> Unit
        }

        // ---------- nástroje ----------
        if (tool != Tool.CROP) {
            Row(
                Modifier.fillMaxWidth().background(SkenerColors.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                ToolButton(Icons.Outlined.CameraAlt, R.string.tool_retake) { onRetake(docId, page.id) }
                ToolButton(Icons.Outlined.Crop, R.string.tool_crop, active = tool == Tool.CROP) { tool = Tool.CROP }
                ToolButton(Icons.Outlined.RotateRight, R.string.action_rotate) {
                    app.scope.launch { repository.rotate(docId, setOf(page.id)) }
                }
                ToolButton(Icons.Outlined.AutoFixHigh, R.string.action_filter, active = tool == Tool.FILTER) {
                    tool = if (tool == Tool.FILTER) null else Tool.FILTER
                }
                ToolButton(Icons.Outlined.Tune, R.string.tool_adjust, active = tool == Tool.ADJUST) {
                    tool = if (tool == Tool.ADJUST) null else Tool.ADJUST
                }
                ToolButton(Icons.Outlined.CleaningServices, R.string.tool_clean, upcoming = true) {
                    Toast.makeText(context, R.string.tool_upcoming, Toast.LENGTH_SHORT).show()
                }
                ToolButton(Icons.Outlined.TextFields, R.string.tool_text, upcoming = true) {
                    Toast.makeText(context, R.string.tool_upcoming, Toast.LENGTH_SHORT).show()
                }
                ToolButton(Icons.Outlined.Delete, R.string.action_delete) {
                    if (pages.size == 1) {
                        // Posledná strana: zmizol by celý dokument, preto potvrdenie.
                        confirmDelete = true
                    } else {
                        val number = current + 1
                        val id = page.id
                        app.scope.launch {
                            val removed = repository.deletePages(docId, setOf(id))
                            // Časový limit: bez zobrazenej hlášky (odchod z obrazovky) sa nečaká donekonečna.
                            val result = withTimeoutOrNull(UNDO_WINDOW_MS) {
                                snackbar.showSnackbar(
                                    message = context.getString(R.string.page_deleted, number),
                                    actionLabel = context.getString(R.string.action_undo),
                                    duration = SnackbarDuration.Long,
                                )
                            }
                            if (result == SnackbarResult.ActionPerformed) repository.restorePages(docId, removed)
                        }
                    }
                }
            }
        }

        // ---------- hlavné akcie ----------
        if (tool == null) {
            Row(
                Modifier.fillMaxWidth().background(SkenerColors.Panel).padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onAddPages(docId) },
                    border = BorderStroke(1.5.dp, SkenerColors.Line),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_continue_scanning), fontWeight = FontWeight.SemiBold, color = SkenerColors.Text) }
                Button(
                    onClick = { sharePdf(context, doc) },
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_share), fontWeight = FontWeight.SemiBold) }
            }
        }
    }

    if (confirmDelete) {
        val last = pages.size == 1
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_page_title, current + 1)) },
            text = { Text(stringResource(if (last) R.string.delete_all_pages_text else R.string.delete_pages_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val id = page.id
                    app.scope.launch { repository.deletePages(docId, setOf(id)) }
                }) { Text(stringResource(R.string.action_delete), color = SkenerColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (renaming) {
        var name by remember { mutableStateOf(doc.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename_title)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    app.scope.launch { repository.rename(docId, name) }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PageChip(index: Int, count: Int, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val bg = SkenerColors.Ink.copy(alpha = 0.85f)
        FilledTonalIconButton(onClick = onPrev, enabled = index > 0, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_previous_page))
        }
        Surface(shape = RoundedCornerShape(16.dp), color = bg) {
            Text(
                stringResource(R.string.page_of, index + 1, count),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        FilledTonalIconButton(onClick = onNext, enabled = index < count - 1, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.action_next_page))
        }
    }
}

/**
 * Pás náhľadov: ťuknutie = prejsť na stranu, podržanie a ťahanie = presunúť stranu,
 * „+“ = pridať ďalšie strany.
 */
@Composable
private fun ThumbnailStrip(
    pages: List<PageItem>,
    current: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var order by remember { mutableStateOf(pages) }
    var dragging by remember { mutableStateOf(false) }
    var startOrder by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(pages) { if (!dragging) order = pages }
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (!dragging) listState.animateScrollToItem(current) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // Položka „+“ na konci sa nepresúva.
        if (from.index < order.size && to.index < order.size) {
            order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().background(SkenerColors.Panel),
    ) {
        itemsIndexed(order, key = { _, p -> p.id }) { index, p ->
            ReorderableItem(reorderState, key = p.id) { isDragging ->
                val selected = p.id == pages.getOrNull(current)?.id
                val scale by animateFloatAsState(if (isDragging) 1.15f else 1f, label = "lift")
                Box(
                    Modifier
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .shadow(if (isDragging) 10.dp else 0.dp, RoundedCornerShape(4.dp))
                        .width(46.dp)
                        .aspectRatio(1f / 1.414f)
                        .clip(RoundedCornerShape(4.dp))
                        .border(2.dp, if (selected || isDragging) SkenerColors.Accent else Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable { onSelect(pages.indexOfFirst { it.id == p.id }.coerceAtLeast(0)) }
                        .longPressDraggableHandle(
                            onDragStarted = {
                                dragging = true
                                startOrder = order.map { it.id }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = {
                                dragging = false
                                val ids = order.map { it.id }
                                if (ids != startOrder) onReorder(ids)
                            },
                        ),
                ) {
                    PageImage(p, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomStart).background(SkenerColors.Ink.copy(alpha = 0.85f)).padding(horizontal = 4.dp),
                    )
                }
            }
        }
        item(key = "add") {
            Box(
                Modifier
                    .width(46.dp)
                    .aspectRatio(1f / 1.414f)
                    .border(1.5.dp, SkenerColors.Line, RoundedCornerShape(4.dp))
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, stringResource(R.string.action_add_pages), tint = SkenerColors.Muted) }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: Int, active: Boolean = false, upcoming: Boolean = false, onClick: () -> Unit) {
    val color = when {
        active -> SkenerColors.Accent
        upcoming -> SkenerColors.Muted.copy(alpha = 0.5f)
        else -> SkenerColors.Muted
    }
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
private fun FilterPanel(
    thumbnails: Map<PageFilter, ImageBitmap>,
    edit: Edit,
    onEdit: (Edit) -> Unit,
    onApplyAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(SkenerColors.Panel).padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (filter in PageFilter.entries) {
                val selected = filter == edit.filter
                Column(
                    Modifier.width(64.dp).clickable { onEdit(edit.copy(filter = filter, level = 0)) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(width = 56.dp, height = 76.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SkenerColors.Raise)
                            .border(if (selected) 2.dp else 1.dp, if (selected) SkenerColors.Accent else SkenerColors.Line, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val thumb = thumbnails[filter]
                        if (thumb != null) {
                            Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = SkenerColors.Muted)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(filter.label()),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) SkenerColors.Accent else SkenerColors.Muted,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onEdit(edit.copy(level = (edit.level - 1).coerceIn(Filters.LEVELS))) }, enabled = edit.level > Filters.LEVELS.first) {
                Icon(Icons.Filled.Remove, stringResource(R.string.action_less))
            }
            Column(Modifier.weight(1f).clickable { onEdit(edit.copy(level = 0)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(edit.filter.parameterLabel()), style = MaterialTheme.typography.labelMedium, color = SkenerColors.Muted)
                Text(if (edit.level > 0) "+${edit.level}" else "${edit.level}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for (v in Filters.LEVELS) {
                        val on = v == 0 || (edit.level > 0 && v in 1..edit.level) || (edit.level < 0 && v in edit.level..-1)
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) SkenerColors.Accent else SkenerColors.Line))
                    }
                }
            }
            FilledTonalIconButton(onClick = { onEdit(edit.copy(level = (edit.level + 1).coerceIn(Filters.LEVELS))) }, enabled = edit.level < Filters.LEVELS.last) {
                Icon(Icons.Filled.Add, stringResource(R.string.action_more))
            }
        }
        TextButton(onClick = onApplyAll, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Outlined.DoneAll, null)
            Text("  " + stringResource(R.string.apply_to_all))
        }
    }
}

@Composable
private fun AdjustPanel(adjust: Adjust, onAdjust: (Adjust) -> Unit, onApplyAll: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(SkenerColors.Panel).padding(horizontal = 14.dp, vertical = 8.dp)) {
        AdjustRow(R.string.param_brightness, adjust.brightness) { onAdjust(adjust.copy(brightness = it)) }
        AdjustRow(R.string.param_contrast, adjust.contrast) { onAdjust(adjust.copy(contrast = it)) }
        AdjustRow(R.string.param_sharpness, adjust.sharpness) { onAdjust(adjust.copy(sharpness = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = { onAdjust(Adjust.NONE) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_reset)) }
            OutlinedButton(onClick = onApplyAll, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.apply_to_all_short)) }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_done)) }
        }
    }
}

@Composable
private fun AdjustRow(label: Int, value: Int, onChange: (Int) -> Unit) {
    // Posuvník mení hodnotu len lokálne počas ťahania; náhľad sa prepočíta až po pustení.
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = SkenerColors.Muted, modifier = Modifier.width(84.dp))
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local.toInt()) },
            valueRange = Adjust.RANGE.first.toFloat()..Adjust.RANGE.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = SkenerColors.Accent, activeTrackColor = SkenerColors.Accent),
            modifier = Modifier.weight(1f),
        )
        Text(
            local.toInt().let { if (it > 0) "+$it" else "$it" },
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp),
        )
    }
}

/** Ako dlho sa ponúka „Späť“ po zmazaní strany (repozitár drží súbory o chvíľu dlhšie). */
internal const val UNDO_WINDOW_MS = 7_000L

/** Oneskorenie prepočtu strany v plnom rozlíšení po poslednej zmene. */
private const val COMMIT_DELAY_MS = 700L

internal fun PageFilter.label() = when (this) {
    PageFilter.BLACK_WHITE -> R.string.filter_bw
    PageFilter.GRAYSCALE -> R.string.filter_gray
    PageFilter.COLOR -> R.string.filter_color
    PageFilter.WHITEBOARD -> R.string.filter_whiteboard
    PageFilter.ORIGINAL -> R.string.filter_original
}

private fun PageFilter.parameterLabel() = when (this) {
    PageFilter.BLACK_WHITE -> R.string.param_ink
    PageFilter.GRAYSCALE -> R.string.param_contrast
    PageFilter.COLOR, PageFilter.WHITEBOARD -> R.string.param_saturation
    PageFilter.ORIGINAL -> R.string.param_brightness
}

