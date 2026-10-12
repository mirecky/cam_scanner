package sk.mirecky.skener.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sk.mirecky.skener.R
import sk.mirecky.skener.SkenerApp
import sk.mirecky.skener.data.DocumentItem
import sk.mirecky.skener.data.PageItem
import sk.mirecky.skener.ui.theme.SkenerColors

/**
 * Panel „Text“: rozpoznaný text aktuálnej strany alebo celého dokumentu,
 * s možnosťou kopírovať, zdieľať a uložiť ako TXT do Dokumenty/Skener.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSheet(doc: DocumentItem, page: PageItem, pageNumber: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SkenerApp
    val repository = app.documents
    val pending by repository.ocrPending.collectAsStateWithLifecycle()
    val revision by repository.ocrRevision.collectAsStateWithLifecycle()
    var wholeDocument by rememberSaveable { mutableStateOf(false) }

    // Text sa načíta znova po každej rozpoznanej strane.
    val text = remember(revision, wholeDocument, page.id, page.cropVersion) {
        if (wholeDocument) repository.documentText(doc.id) else repository.ocr(page)?.text.orEmpty()
    }
    val waiting = if (wholeDocument) doc.pages.any { it.id in pending } else page.id in pending
    val words = text.split(Regex("\\s+")).count { it.isNotBlank() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SkenerColors.Panel) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.text_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = !wholeDocument, onClick = { wholeDocument = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text(stringResource(R.string.text_scope_page, pageNumber))
                    }
                    SegmentedButton(selected = wholeDocument, onClick = { wholeDocument = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text(stringResource(R.string.text_scope_document))
                    }
                }
            }
            Text(
                if (waiting) stringResource(R.string.text_recognizing)
                else stringResource(R.string.text_meta, pluralStringResource(R.plurals.word_count, words, words)),
                style = MaterialTheme.typography.labelMedium,
                color = SkenerColors.Muted,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            )
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                when {
                    text.isNotBlank() -> SelectionContainer {
                        Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.25)
                    }
                    waiting -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = SkenerColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.text_recognizing_hint), color = SkenerColors.Muted)
                    }
                    else -> Text(stringResource(R.string.text_empty), color = SkenerColors.Muted, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val enabled = text.isNotBlank()
                FilledTonalButton(onClick = { copy(context, text) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_copy))
                }
                FilledTonalButton(onClick = { share(context, text, doc.name) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_share_short))
                }
                FilledTonalButton(onClick = { saveTxt(context, text, if (wholeDocument) doc.name else "${doc.name}_strana-$pageNumber") }, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_save_txt))
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Skener", text))
    Toast.makeText(context, R.string.text_copied, Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
    context.startActivity(Intent.createChooser(send, title))
}

/** Uloží text ako `<názov>.txt` do Dokumenty/Skener (MediaStore, bez povolení). */
private fun saveTxt(context: Context, text: String, name: String) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.txt")
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Skener/")
    }
    val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    if (uri == null) {
        Toast.makeText(context, R.string.text_save_failed, Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { resolver.openOutputStream(uri, "w")!!.use { it.write(text.toByteArray()) } }
        .onSuccess { Toast.makeText(context, context.getString(R.string.text_saved, "$name.txt"), Toast.LENGTH_SHORT).show() }
        .onFailure { Toast.makeText(context, R.string.text_save_failed, Toast.LENGTH_SHORT).show() }
}
