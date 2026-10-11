package sk.mirecky.skener.ui.document

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sk.mirecky.skener.R
import sk.mirecky.skener.SkenerApp
import sk.mirecky.skener.data.DocumentItem
import sk.mirecky.skener.data.PageItem
import sk.mirecky.skener.scanner.PageFilter
import sk.mirecky.skener.ui.common.PageImage

/**
 * Detail dokumentu: mriežka strán.
 * Ťuknutie = otvoriť stranu (vo výbere označiť), podržanie a ťahanie = presun,
 * podržanie bez pohybu = režim výberu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(docId: String, onBack: () -> Unit, onAddPages: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SkenerApp
    val repository = app.documents
    val documents by repository.documents.collectAsStateWithLifecycle()
    val exporting by repository.exporting.collectAsStateWithLifecycle()
    val doc = documents.firstOrNull { it.id == docId }

    // Dokument zmizol (zmazané všetky strany) → späť.
    LaunchedEffect(doc == null) { if (doc == null) onBack() }
    if (doc == null) return

    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var filterTargets by remember { mutableStateOf<Set<String>?>(null) }
    var confirmDelete by remember { mutableStateOf<Set<String>?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    val index = viewerIndex
    if (index != null) {
        BackHandler { viewerIndex = null }
        PageViewer(
            pages = doc.pages,
            initialIndex = index.coerceIn(doc.pages.indices),
            onClose = { viewerIndex = null },
            onRotate = { page -> app.scope.launch { repository.rotate(docId, setOf(page.id)) } },
            onFilter = { page -> filterTargets = setOf(page.id) },
            onDelete = { page -> confirmDelete = setOf(page.id) },
        )
    } else {
        BackHandler { if (selecting) selected = emptySet() else onBack() }
        Scaffold(
            topBar = {
                if (selecting) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.selected_count, selected.size)) },
                        navigationIcon = {
                            IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
                        },
                        actions = {
                            IconButton(onClick = { selected = doc.pages.map { it.id }.toSet() }) {
                                Icon(Icons.Outlined.SelectAll, stringResource(R.string.action_select_all))
                            }
                            IconButton(onClick = { app.scope.launch { repository.rotate(docId, selected) } }) {
                                Icon(Icons.Outlined.RotateRight, stringResource(R.string.action_rotate))
                            }
                            IconButton(onClick = { filterTargets = selected }) {
                                Icon(Icons.Outlined.AutoFixHigh, stringResource(R.string.action_filter))
                            }
                            IconButton(onClick = { confirmDelete = selected }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete))
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column(Modifier.clickable { renaming = true }) {
                                Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (docId in exporting) stringResource(R.string.pdf_updating)
                                    else pluralStringResource(R.plurals.page_count, doc.pages.size, doc.pages.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                        },
                        actions = {
                            IconButton(onClick = { sharePdf(context, doc) }) { Icon(Icons.Outlined.Share, stringResource(R.string.action_share)) }
                        },
                    )
                }
            },
            bottomBar = {
                if (!selecting) {
                    BottomAppBar {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { filterTargets = doc.pages.map { it.id }.toSet() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.AutoFixHigh, null)
                                Text("  " + stringResource(R.string.action_filter_all))
                            }
                            FilledTonalButton(onClick = { onAddPages(docId) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.AddAPhoto, null)
                                Text("  " + stringResource(R.string.action_add_pages))
                            }
                        }
                    }
                }
            },
        ) { padding ->
            PageGrid(
                doc = doc,
                selected = selected,
                onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                onOpen = { viewerIndex = it },
                onReorder = { order -> app.scope.launch { repository.reorder(docId, order) } },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }

    filterTargets?.let { targets ->
        FilterSheet(
            current = doc.pages.filter { it.id in targets }.map { it.filter }.distinct().singleOrNull(),
            count = targets.size,
            onDismiss = { filterTargets = null },
            onSelect = { filter ->
                filterTargets = null
                selected = emptySet()
                app.scope.launch { repository.setFilter(docId, targets, filter) }
            },
        )
    }

    confirmDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(pluralStringResource(R.plurals.delete_pages_title, ids.size, ids.size)) },
            text = {
                Text(stringResource(if (ids.size >= doc.pages.size) R.string.delete_all_pages_text else R.string.delete_pages_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    selected = emptySet()
                    app.scope.launch { repository.deletePages(docId, ids) }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
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
private fun PageGrid(
    doc: DocumentItem,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (Int) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // Lokálne poradie počas ťahania; inak zodpovedá dokumentu.
    var order by remember { mutableStateOf(doc.pages) }
    var dragging by remember { mutableStateOf(false) }
    var dragStartOrder by remember { mutableStateOf(emptyList<String>()) }
    var suppressClickUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(doc.pages) { if (!dragging) order = doc.pages }

    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        itemsIndexed(order, key = { _, page -> page.id }) { index, page ->
            ReorderableItem(reorderState, key = page.id) { isDragging ->
                val scale by animateFloatAsState(if (isDragging) 1.06f else 1f, label = "scale")
                PageTile(
                    page = page,
                    number = index + 1,
                    selected = page.id in selected,
                    selecting = selected.isNotEmpty(),
                    modifier = Modifier
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .shadow(if (isDragging) 12.dp else 0.dp, RoundedCornerShape(8.dp))
                        .clickable {
                            if (System.currentTimeMillis() < suppressClickUntil) return@clickable
                            if (selected.isNotEmpty()) onToggle(page.id) else onOpen(index)
                        }
                        .longPressDraggableHandle(
                            onDragStarted = {
                                dragging = true
                                dragStartOrder = order.map { it.id }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = {
                                dragging = false
                                suppressClickUntil = System.currentTimeMillis() + 400
                                val ids = order.map { it.id }
                                // Podržanie bez presunu = označenie strany.
                                if (ids == dragStartOrder) onToggle(page.id) else onReorder(ids)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun PageTile(page: PageItem, number: Int, selected: Boolean, selecting: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f / 1.414f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
    ) {
        PageImage(page, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Surface(
            shape = RoundedCornerShape(topEnd = 8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (selecting) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
    }
}

private fun sharePdf(context: android.content.Context, doc: DocumentItem) {
    val uri = doc.pdfUri
    if (uri == null) {
        Toast.makeText(context, R.string.pdf_not_ready, Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .putExtra(Intent.EXTRA_TITLE, doc.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, doc.name))
}
