package sk.mirecky.skener.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.mirecky.skener.R
import sk.mirecky.skener.SkenerApp
import sk.mirecky.skener.data.DocumentItem
import sk.mirecky.skener.data.LibrarySort
import sk.mirecky.skener.data.SettingsRepository
import sk.mirecky.skener.data.TextSearch
import sk.mirecky.skener.ui.common.PageImage
import sk.mirecky.skener.ui.common.sharePdf
import sk.mirecky.skener.ui.theme.SkenerColors
import java.text.Collator
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Riadok knižnice: dokument a prípadný úryvok textu, v ktorom sa našiel hľadaný výraz. */
private class LibraryEntry(val doc: DocumentItem, val snippet: AnnotatedString?)

/**
 * Knižnica dokumentov (podľa schváleného prototypu): vyhľadávanie v názvoch aj v rozpoznanom texte,
 * triedenie, ponuka ⋮ pri každom dokumente, import z galérie a hromadný výber (podržaním).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpenDocument: (String) -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SkenerApp
    val repository = app.documents
    val settings = remember { SettingsRepository(context) }
    val documents by repository.documents.collectAsStateWithLifecycle()
    val exporting by repository.exporting.collectAsStateWithLifecycle()
    val sizes by repository.pdfSizes.collectAsStateWithLifecycle()
    val ocrRevision by repository.ocrRevision.collectAsStateWithLifecycle()
    val sort by settings.librarySort.collectAsStateWithLifecycle(LibrarySort.NEWEST)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var mergeFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Set<String>?>(null) }
    var importing by remember { mutableIntStateOf(0) }
    var openAfterImport by remember { mutableStateOf<String?>(null) }
    val selecting = selected.isNotEmpty()

    BackHandler {
        when {
            selecting -> selected = emptySet()
            query.isNotEmpty() -> query = ""
            else -> onBack()
        }
    }

    // Import z galérie: systémový výber fotografií, nepotrebuje žiadne povolenie.
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = uris.size
        app.scope.launch {
            val docId = repository.importImages(uris, null) { importing = (importing - 1).coerceAtLeast(0) }
            importing = 0
            if (docId == null) Toast.makeText(context, R.string.import_failed, Toast.LENGTH_SHORT).show()
            openAfterImport = docId
        }
    }
    LaunchedEffect(openAfterImport) { openAfterImport?.let { openAfterImport = null; onOpenDocument(it) } }

    // Staršie PDF z priečinka Dokumenty/Skener (napr. spred preinštalovania aplikácie):
    // Android ich sprístupní až po jednorazovom povolení priečinka.
    val folder by settings.pdfFolder.collectAsStateWithLifecycle(null)
    val promptDismissed by settings.folderPromptDismissed.collectAsStateWithLifecycle(true)
    val folderSync by repository.folderSync.collectAsStateWithLifecycle()
    val folderGranted = folder?.let { f -> context.contentResolver.persistedUriPermissions.any { it.uri.toString() == f && it.isReadPermission } } == true
    fun syncFolder(uri: android.net.Uri, announce: Boolean) {
        app.scope.launch {
            val n = repository.importFolder(uri)
            if (n > 0 || announce) {
                app.snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.folder_imported, n, n))
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        scope.launch { settings.setPdfFolder(uri.toString()) }
        syncFolder(uri, announce = true)
    }
    // Pri každom otvorení knižnice sa doplnia PDF, ktoré v priečinku pribudli mimo aplikácie.
    LaunchedEffect(folder, folderGranted) {
        val f = folder
        if (f != null && folderGranted) syncFolder(android.net.Uri.parse(f), announce = false)
    }
    val showFolderPrompt = !folderGranted && !promptDismissed

    // Vyhľadávanie beží mimo hlavného vlákna; text strán sa číta z výsledkov OCR.
    val entries by produceState(initialValue = documents.map { LibraryEntry(it, null) }, documents, query, ocrRevision) {
        value = withContext(Dispatchers.Default) { search(repository, documents, query) }
    }
    val collator = remember { Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY } }
    val shown = when (sort) {
        LibrarySort.NEWEST -> entries.sortedByDescending { it.doc.updatedAt }
        LibrarySort.NAME -> entries.sortedWith { a, b -> collator.compare(a.doc.name, b.doc.name) }
        LibrarySort.SIZE -> entries.sortedByDescending { sizes[it.doc.id] ?: 0L }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selecting) stringResource(R.string.selected_count, selected.size)
                        else stringResource(R.string.library_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) }
                    } else {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(
                            onClick = {
                                // Cieľom je najstarší z vybraných; ostatné sa pripoja v poradí vzniku.
                                val ordered = documents.filter { it.id in selected }.sortedBy { it.createdAt }
                                val target = ordered.first()
                                selected = emptySet()
                                app.scope.launch {
                                    val n = repository.merge(target.id, ordered.drop(1).map { it.id })
                                    app.snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.merged_pages, n, n, target.name))
                                }
                            },
                            enabled = selected.size >= 2,
                        ) { Icon(Icons.AutoMirrored.Outlined.CallMerge, stringResource(R.string.action_merge_selected)) }
                        IconButton(onClick = { confirmDelete = selected }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(app.snackbar) },
        floatingActionButton = {
            if (!selecting) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        shape = RoundedCornerShape(18.dp),
                        color = SkenerColors.Raise,
                        shadowElevation = 6.dp,
                    ) {
                        Icon(
                            Icons.Outlined.PhotoLibrary,
                            stringResource(R.string.action_import_gallery),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    Surface(
                        onClick = onScan,
                        shape = RoundedCornerShape(18.dp),
                        color = SkenerColors.Accent,
                        contentColor = SkenerColors.AccentInk,
                        shadowElevation = 6.dp,
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CameraAlt, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_scan), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!selecting) {
                SearchField(query, onChange = { query = it }, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (query.isBlank()) {
                            pluralStringResource(R.plurals.document_count, documents.size, documents.size) + " · " + stringResource(R.string.library_location)
                        } else {
                            stringResource(R.string.search_found, shown.size)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = SkenerColors.Muted,
                        modifier = Modifier.weight(1f),
                    )
                    SortButton(sort, onSelect = { s -> scope.launch { settings.setLibrarySort(s) } })
                }
            }
            if (showFolderPrompt && !selecting) {
                FolderPrompt(
                    onAllow = { pickFolder.launch(skenerFolderUri()) },
                    onDismiss = { scope.launch { settings.dismissFolderPrompt() } },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            folderSync?.let { left ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                    Text(
                        pluralStringResource(R.plurals.folder_loading, left, left),
                        style = MaterialTheme.typography.labelMedium,
                        color = SkenerColors.Accent,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = SkenerColors.Accent, trackColor = SkenerColors.Raise)
                }
            }
            if (importing > 0) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                    Text(
                        pluralStringResource(R.plurals.importing_photos, importing, importing),
                        style = MaterialTheme.typography.labelMedium,
                        color = SkenerColors.Accent,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = SkenerColors.Accent, trackColor = SkenerColors.Raise)
                }
            }
            when {
                documents.isEmpty() -> EmptyLibrary(Modifier.weight(1f))
                shown.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                    Text(stringResource(R.string.search_nothing, query), color = SkenerColors.Muted, style = MaterialTheme.typography.bodyLarge)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(shown, key = { it.doc.id }) { entry ->
                        val doc = entry.doc
                        val isSelected = doc.id in selected
                        DocumentCard(
                            doc = doc,
                            size = sizes[doc.id],
                            snippet = entry.snippet,
                            selected = isSelected,
                            selecting = selecting,
                            exporting = doc.id in exporting,
                            onMenu = { menuFor = doc.id },
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (selecting) selected = if (isSelected) selected - doc.id else selected + doc.id
                                    else onOpenDocument(doc.id)
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selected = selected + doc.id
                                },
                            ),
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { id ->
        val doc = documents.firstOrNull { it.id == id }
        if (doc == null) {
            menuFor = null
        } else {
            DocumentMenu(
                doc = doc,
                size = sizes[doc.id],
                onDismiss = { menuFor = null },
                onShare = { sharePdf(context, doc) },
                onRename = { renaming = doc.id },
                onJpeg = {
                    app.scope.launch {
                        val n = repository.exportJpeg(doc.id)
                        app.snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.jpeg_saved, n, n))
                    }
                },
                onMerge = { mergeFor = doc.id },
                onCompact = {
                    app.scope.launch {
                        repository.setCompact(doc.id, !doc.compact)
                        app.snackbar.showSnackbar(context.getString(if (doc.compact) R.string.pdf_full_quality_set else R.string.pdf_compact_set))
                    }
                },
                onCopyText = {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { repository.displayText(doc) }
                        copyText(context, text)
                    }
                },
                onDelete = { confirmDelete = setOf(doc.id) },
            )
        }
    }

    mergeFor?.let { id ->
        val doc = documents.firstOrNull { it.id == id }
        val others = documents.filter { it.id != id }
        if (doc == null) {
            mergeFor = null
        } else {
            MergePicker(
                doc = doc,
                others = others,
                onDismiss = { mergeFor = null },
                onPick = { other ->
                    mergeFor = null
                    app.scope.launch {
                        val n = repository.merge(doc.id, listOf(other.id))
                        app.snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.merged_pages, n, n, doc.name))
                    }
                },
            )
        }
    }

    renaming?.let { id ->
        val doc = documents.firstOrNull { it.id == id }
        if (doc == null) {
            renaming = null
        } else {
            var name by remember(id) { mutableStateOf(doc.name) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text(stringResource(R.string.rename_title)) },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        renaming = null
                        app.scope.launch { repository.rename(id, name) }
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }

    confirmDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(pluralStringResource(R.plurals.delete_documents_title, ids.size, ids.size)) },
            text = { Text(stringResource(R.string.delete_documents_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    selected = emptySet()
                    app.scope.launch { repository.deleteDocuments(ids) }
                }) { Text(stringResource(R.string.action_delete), color = SkenerColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Počiatočné umiestnenie výberu priečinka: Dokumenty/Skener na vnútornom úložisku. */
private fun skenerFolderUri(): android.net.Uri =
    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/Skener")

/** Ponuka sprístupniť staršie PDF z priečinka Dokumenty/Skener. */
@Composable
private fun FolderPrompt(onAllow: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SkenerColors.Raise)
            .border(1.dp, SkenerColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.FolderOpen, null, tint = SkenerColors.Accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.folder_prompt_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.folder_prompt_text), style = MaterialTheme.typography.bodySmall, color = SkenerColors.Muted)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.folder_prompt_no), color = SkenerColors.Muted) }
            TextButton(onClick = onAllow) { Text(stringResource(R.string.folder_prompt_allow), fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Dokumenty, ktorých názov alebo rozpoznaný text obsahuje výraz (bez ohľadu na diakritiku). */
private fun search(repository: sk.mirecky.skener.data.DocumentRepository, documents: List<DocumentItem>, query: String): List<LibraryEntry> {
    val q = TextSearch.normalize(query.trim())
    if (q.isEmpty()) return documents.map { LibraryEntry(it, null) }
    return documents.mapNotNull { doc ->
        val inName = TextSearch.normalize(doc.name).contains(q)
        val normalized = repository.searchableText(doc)
        val inText = normalized.contains(q)
        when {
            inText -> {
                val snippet = TextSearch.snippet(repository.displayText(doc), normalized, q)?.let { (text, range) ->
                    buildAnnotatedString {
                        append(text)
                        addStyle(SpanStyle(color = SkenerColors.Accent, fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
                    }
                }
                LibraryEntry(doc, snippet)
            }
            inName -> LibraryEntry(doc, null)
            else -> null
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SkenerColors.Panel)
            .border(1.dp, SkenerColors.Line, RoundedCornerShape(24.dp))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = SkenerColors.Muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.search_hint), color = SkenerColors.Muted, style = MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = SkenerColors.Text),
                cursorBrush = SolidColor(SkenerColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, stringResource(R.string.action_clear), tint = SkenerColors.Muted) }
        } else {
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun SortButton(sort: LibrarySort, onSelect: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp), tint = SkenerColors.Muted)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(sort.label()), color = SkenerColors.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in LibrarySort.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(s.label()), color = if (s == sort) SkenerColors.Accent else SkenerColors.Text) },
                    onClick = { open = false; onSelect(s) },
                )
            }
        }
    }
}

private fun LibrarySort.label() = when (this) {
    LibrarySort.NEWEST -> R.string.sort_newest
    LibrarySort.NAME -> R.string.sort_name
    LibrarySort.SIZE -> R.string.sort_size
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Description, null, tint = SkenerColors.Muted, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.library_empty), color = SkenerColors.Muted, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DocumentCard(
    doc: DocumentItem,
    size: Long?,
    snippet: AnnotatedString?,
    selected: Boolean,
    selecting: Boolean,
    exporting: Boolean,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) SkenerColors.Raise else SkenerColors.Panel)
            .then(if (selected) Modifier.border(2.dp, SkenerColors.Accent, shape) else Modifier)
            .then(modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 54.dp, height = 76.dp).clip(RoundedCornerShape(6.dp)).background(SkenerColors.Raise)) {
            doc.pages.firstOrNull()?.let { PageImage(it, null, Modifier.fillMaxSize(), ContentScale.Crop) }
            if (selected) {
                Icon(Icons.Filled.CheckCircle, null, tint = SkenerColors.Accent, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            val meta = buildString {
                append(context.resources.getQuantityString(R.plurals.page_count, doc.pages.size, doc.pages.size))
                if (size != null) append(" · ").append(Formatter.formatShortFileSize(context, size))
            }
            Text(meta, style = MaterialTheme.typography.bodySmall, color = SkenerColors.Muted)
            Text(
                if (exporting) stringResource(R.string.pdf_updating) else relativeTime(context, doc.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = if (exporting) SkenerColors.Accent else SkenerColors.Muted,
            )
            if (snippet != null) {
                Spacer(Modifier.height(4.dp))
                Text(snippet, style = MaterialTheme.typography.bodySmall, color = SkenerColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!selecting) {
            IconButton(onClick = onMenu) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_options), tint = SkenerColors.Muted) }
        }
    }
}

/** „Dnes 11:02“, „Včera 18:44“, inak dátum. */
private fun relativeTime(context: Context, time: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = time }
    val clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(now, then) -> context.getString(R.string.date_today, clock)
        sameDay(yesterday, then) -> context.getString(R.string.date_yesterday, clock)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
    }
}

/** Ponuka ⋮ dokumentu (spodný panel s hlavičkou dokumentu). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentMenu(
    doc: DocumentItem,
    size: Long?,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onJpeg: () -> Unit,
    onMerge: () -> Unit,
    onCompact: () -> Unit,
    onCopyText: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SkenerColors.Panel) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 38.dp, height = 54.dp).clip(RoundedCornerShape(4.dp)).background(SkenerColors.Raise)) {
                    doc.pages.firstOrNull()?.let { PageImage(it, null, Modifier.fillMaxSize(), ContentScale.Crop) }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(doc.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = buildString {
                        append(context.resources.getQuantityString(R.plurals.page_count, doc.pages.size, doc.pages.size))
                        if (size != null) append(" · ").append(Formatter.formatShortFileSize(context, size))
                        if (doc.compact) append(" · ").append(context.getString(R.string.pdf_compact_badge))
                    }
                    Text(meta, style = MaterialTheme.typography.labelMedium, color = SkenerColors.Muted)
                }
            }
            HorizontalDivider(color = SkenerColors.Line)
            Spacer(Modifier.height(4.dp))
            fun pick(action: () -> Unit): () -> Unit = { onDismiss(); action() }
            MenuItem(Icons.Outlined.Share, stringResource(R.string.action_share), onClick = pick(onShare))
            MenuItem(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.action_rename), onClick = pick(onRename))
            MenuItem(Icons.Outlined.Image, stringResource(R.string.action_save_jpeg), stringResource(R.string.action_save_jpeg_hint), onClick = pick(onJpeg))
            MenuItem(Icons.AutoMirrored.Outlined.CallMerge, stringResource(R.string.action_merge), onClick = pick(onMerge))
            if (doc.compact) {
                MenuItem(Icons.Outlined.HighQuality, stringResource(R.string.action_pdf_full), stringResource(R.string.action_pdf_full_hint), onClick = pick(onCompact))
            } else {
                MenuItem(Icons.Outlined.Compress, stringResource(R.string.action_pdf_compact), stringResource(R.string.action_pdf_compact_hint), onClick = pick(onCompact))
            }
            MenuItem(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy_text), onClick = pick(onCopyText))
            MenuItem(Icons.Outlined.Delete, stringResource(R.string.action_delete), danger = true, onClick = pick(onDelete))
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, hint: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) SkenerColors.Danger else SkenerColors.Text
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = SkenerColors.Muted)
        }
    }
}

/** Výber dokumentu, ktorého strany sa pripoja na koniec [doc]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergePicker(doc: DocumentItem, others: List<DocumentItem>, onDismiss: () -> Unit, onPick: (DocumentItem) -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SkenerColors.Panel) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                stringResource(R.string.merge_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                stringResource(R.string.merge_hint, doc.name),
                style = MaterialTheme.typography.bodyMedium,
                color = SkenerColors.Muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            if (others.isEmpty()) {
                Text(stringResource(R.string.merge_none), color = SkenerColors.Muted, modifier = Modifier.padding(20.dp))
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(others, key = { it.id }) { other ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(other) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(width = 38.dp, height = 54.dp).clip(RoundedCornerShape(4.dp)).background(SkenerColors.Raise)) {
                            other.pages.firstOrNull()?.let { PageImage(it, null, Modifier.fillMaxSize(), ContentScale.Crop) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(other.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                context.resources.getQuantityString(R.plurals.page_count, other.pages.size, other.pages.size) + " · " + relativeTime(context, other.updatedAt),
                                style = MaterialTheme.typography.labelMedium,
                                color = SkenerColors.Muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun copyText(context: Context, text: String) {
    if (text.isBlank()) {
        Toast.makeText(context, R.string.text_none_yet, Toast.LENGTH_SHORT).show()
        return
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Skener", text))
    Toast.makeText(context, R.string.text_copied, Toast.LENGTH_SHORT).show()
}
