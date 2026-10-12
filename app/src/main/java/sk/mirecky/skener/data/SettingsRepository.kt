package sk.mirecky.skener.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sk.mirecky.skener.scanner.ScanMode

/** Triedenie knižnice dokumentov. */
enum class LibrarySort { NEWEST, NAME, SIZE }

/** Signál po nasnímaní strany. */
enum class CaptureSignal { SOUND, VIBRATION, SILENT }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Trvalé nastavenia, ktoré si aplikácia pamätá medzi spusteniami. */
class SettingsRepository(private val context: Context) {

    val autoCapture: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_AUTO_CAPTURE] ?: true }

    val scanMode: Flow<ScanMode> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_SCAN_MODE]?.let { name -> ScanMode.entries.firstOrNull { it.name == name } }
                ?: ScanMode.DOCUMENT
        }

    val captureSignal: Flow<CaptureSignal> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_SIGNAL]?.let { name -> CaptureSignal.entries.firstOrNull { it.name == name } } ?: CaptureSignal.SOUND
        }

    val librarySort: Flow<LibrarySort> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_SORT]?.let { name -> LibrarySort.entries.firstOrNull { it.name == name } } ?: LibrarySort.NEWEST
        }

    suspend fun setLibrarySort(sort: LibrarySort) {
        context.dataStore.edit { it[KEY_SORT] = sort.name }
    }

    suspend fun setCaptureSignal(signal: CaptureSignal) {
        context.dataStore.edit { it[KEY_SIGNAL] = signal.name }
    }

    suspend fun setAutoCapture(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_CAPTURE] = enabled }
    }

    suspend fun setScanMode(mode: ScanMode) {
        context.dataStore.edit { it[KEY_SCAN_MODE] = mode.name }
    }

    private companion object {
        val KEY_AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        val KEY_SCAN_MODE = stringPreferencesKey("scan_mode")
        val KEY_SIGNAL = stringPreferencesKey("capture_signal")
        val KEY_SORT = stringPreferencesKey("library_sort")
    }
}
