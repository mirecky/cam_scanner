package sk.mirecky.skener.ui.document

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sk.mirecky.skener.R
import sk.mirecky.skener.scanner.PageFilter

/** Výber filtra pre [count] strán; [current] je spoločný filter vybraných strán, ak je jednotný. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(current: PageFilter?, count: Int, onDismiss: () -> Unit, onSelect: (PageFilter) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                pluralStringResource(R.plurals.filter_title, count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            for (filter in PageFilter.entries) {
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(filter) }.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = filter == current, onClick = { onSelect(filter) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(filter.label()), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(filter.description()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun PageFilter.label() = when (this) {
    PageFilter.BLACK_WHITE -> R.string.filter_bw
    PageFilter.GRAYSCALE -> R.string.filter_gray
    PageFilter.COLOR -> R.string.filter_color
    PageFilter.WHITEBOARD -> R.string.filter_whiteboard
    PageFilter.ORIGINAL -> R.string.filter_original
}

private fun PageFilter.description() = when (this) {
    PageFilter.BLACK_WHITE -> R.string.filter_bw_desc
    PageFilter.GRAYSCALE -> R.string.filter_gray_desc
    PageFilter.COLOR -> R.string.filter_color_desc
    PageFilter.WHITEBOARD -> R.string.filter_whiteboard_desc
    PageFilter.ORIGINAL -> R.string.filter_original_desc
}
