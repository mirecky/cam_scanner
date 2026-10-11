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

    suspend fun setAutoCapture(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_CAPTURE] = enabled }
    }

    suspend fun setScanMode(mode: ScanMode) {
        context.dataStore.edit { it[KEY_SCAN_MODE] = mode.name }
    }

    private companion object {
        val KEY_AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        val KEY_SCAN_MODE = stringPreferencesKey("scan_mode")
    }
}
