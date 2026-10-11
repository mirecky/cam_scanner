package sk.mirecky.skener.ui.scanner

import android.app.Application
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sk.mirecky.skener.data.PageStore
import sk.mirecky.skener.data.SettingsRepository
import sk.mirecky.skener.scanner.ScanMode

enum class ScanStatus { SEARCHING, MANUAL, SAVING, CAPTURED, ERROR }

data class ScannerUiState(
    val mode: ScanMode = ScanMode.DOCUMENT,
    val autoCapture: Boolean = true,
    val pageCount: Int = 0,
    val status: ScanStatus = ScanStatus.SEARCHING,
)

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    private val pageStore = PageStore(application)

    private val pageCount = MutableStateFlow(pageStore.pages().size)
    private val transientStatus = MutableStateFlow<ScanStatus?>(null)
    private var statusResetJob: Job? = null

    val uiState: StateFlow<ScannerUiState> =
        combine(settings.scanMode, settings.autoCapture, pageCount, transientStatus) { mode, auto, count, transient ->
            ScannerUiState(
                mode = mode,
                autoCapture = auto,
                pageCount = count,
                status = transient ?: if (auto) ScanStatus.SEARCHING else ScanStatus.MANUAL,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScannerUiState())

    fun setMode(mode: ScanMode) {
        viewModelScope.launch { settings.setScanMode(mode) }
    }

    fun toggleAutoCapture() {
        viewModelScope.launch { settings.setAutoCapture(!uiState.value.autoCapture) }
    }

    /** Nasníma fotografiu v plnom rozlíšení a uloží ju ako ďalšiu stranu. */
    fun capture(controller: LifecycleCameraController, onCaptured: () -> Unit) {
        if (uiState.value.status == ScanStatus.SAVING) return
        setTransientStatus(ScanStatus.SAVING, resetAfterMs = null)

        val file = pageStore.newPageFile()
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        controller.takePicture(
            options,
            ContextCompat.getMainExecutor(getApplication()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    pageCount.value = pageStore.pages().size
                    setTransientStatus(ScanStatus.CAPTURED, resetAfterMs = 1_500)
                    onCaptured()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Snímanie zlyhalo", exception)
                    file.delete()
                    setTransientStatus(ScanStatus.ERROR, resetAfterMs = 2_000)
                }
            },
        )
    }

    private fun setTransientStatus(status: ScanStatus, resetAfterMs: Long?) {
        statusResetJob?.cancel()
        transientStatus.value = status
        if (resetAfterMs != null) {
            statusResetJob = viewModelScope.launch {
                delay(resetAfterMs)
                transientStatus.value = null
            }
        }
    }

    private companion object {
        const val TAG = "ScannerViewModel"
    }
}
