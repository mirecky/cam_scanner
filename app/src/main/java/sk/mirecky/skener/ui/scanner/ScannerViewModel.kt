package sk.mirecky.skener.ui.scanner

import android.app.Application
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.mirecky.skener.SkenerApp
import sk.mirecky.skener.data.Page
import sk.mirecky.skener.data.PageProcessor
import sk.mirecky.skener.data.SettingsRepository
import sk.mirecky.skener.scanner.AutoCaptureController
import sk.mirecky.skener.scanner.CapturePhase
import sk.mirecky.skener.scanner.CaptureState
import sk.mirecky.skener.scanner.FrameObservation
import sk.mirecky.skener.scanner.Quad
import sk.mirecky.skener.scanner.ScanMode

enum class ScanStatus {
    /** Dokument nie je v zábere. */
    SEARCHING,
    /** Dokument nájdený, ustaľuje sa (automatické snímanie). */
    HOLD_STILL,
    /** Dokument nájdený, čaká sa na stlačenie spúšte (ručné snímanie). */
    READY_MANUAL,
    SAVING,
    CAPTURED,
    /** V zábere je list, ktorý už bol nasnímaný. */
    SAME_SHEET,
    ERROR,
}

data class ScannerUiState(
    val mode: ScanMode = ScanMode.DOCUMENT,
    val autoCapture: Boolean = true,
    val pageCount: Int = 0,
    val lastPage: Page? = null,
    val status: ScanStatus = ScanStatus.SEARCHING,
)

/** Údaje pre vykreslenie obrysu dokumentu nad náhľadom kamery. */
data class OverlayState(
    val quad: Quad? = null,
    /** Pomer šírky a výšky analyzovaného obrazu (zodpovedá náhľadu). */
    val aspect: Double = 3.0 / 4.0,
    val capture: CaptureState = CaptureState(CapturePhase.SEARCHING),
    /** Diagnostické hodnoty na ladenie prahov. */
    val debug: String = "",
)

class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    private val pageStore = (application as SkenerApp).pageStore
    private val auto = AutoCaptureController()

    private val transientStatus = MutableStateFlow<ScanStatus?>(null)
    private var statusResetJob: Job? = null

    private val _overlay = MutableStateFlow(OverlayState())
    val overlay: StateFlow<OverlayState> = _overlay.asStateFlow()

    /** Udalosť úspešného nasnímania (záblesk a vibrácia v UI). */
    private val _captureEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val captureEvents: SharedFlow<Unit> = _captureEvents.asSharedFlow()

    @Volatile private var autoEnabled = true
    @Volatile var currentMode = ScanMode.DOCUMENT
        private set
    @Volatile private var latest: AnalysisResult? = null
    @Volatile private var saving = false
    private var camera: LifecycleCameraController? = null

    val uiState: StateFlow<ScannerUiState> =
        combine(
            settings.scanMode, settings.autoCapture, pageStore.pages, transientStatus, _overlay,
        ) { mode, autoOn, pages, transient, overlay ->
            ScannerUiState(
                mode = mode,
                autoCapture = autoOn,
                pageCount = pages.size,
                lastPage = pages.lastOrNull(),
                status = transient ?: statusOf(overlay.capture, autoOn),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScannerUiState())

    init {
        viewModelScope.launch { settings.autoCapture.collect { autoEnabled = it } }
        viewModelScope.launch { settings.scanMode.collect { currentMode = it } }
        // Po zmazaní všetkých strán sa zabudne aj posledný nasnímaný list.
        viewModelScope.launch {
            pageStore.pages.collect { if (it.isEmpty()) synchronized(auto) { auto.reset() } }
        }
    }

    fun attachCamera(controller: LifecycleCameraController?) {
        camera = controller
    }

    fun setMode(mode: ScanMode) {
        viewModelScope.launch { settings.setScanMode(mode) }
    }

    fun toggleAutoCapture() {
        viewModelScope.launch { settings.setAutoCapture(!uiState.value.autoCapture) }
    }

    /** Volá sa z vlákna analyzátora pre každý snímok. */
    fun onAnalysis(result: AnalysisResult) {
        latest = result
        val detection = result.detection
        val observation = FrameObservation(
            timeMs = result.timeMs,
            quad = detection?.quad,
            sharp = (detection?.sharpness ?: 0.0) >= MIN_SHARPNESS,
            motion = result.motion,
            fingerprint = result.fingerprint,
        )
        val (state, trigger) = synchronized(auto) { auto.onFrame(observation, autoEnabled && !saving) }
        _overlay.value = OverlayState(
            quad = detection?.quad,
            aspect = result.aspect,
            capture = state,
            debug = buildString {
                if (detection != null) {
                    append("kontrast %.0f · ostrosť %.0f · plocha %.2f".format(detection.contrast, detection.sharpness, detection.areaFraction))
                } else {
                    append("nenájdený")
                    result.rejection?.let { append(" (").append(it).append(")") }
                }
                append("\npohyb %.1f · %s".format(result.motion, state.phase.name.lowercase()))
                state.difference?.let { append(" · rozdiel od posl. %.3f".format(it)) }
            },
        )
        if (trigger) {
            viewModelScope.launch(Dispatchers.Main) { takePicture(detection?.quad, result.fingerprint) }
        }
    }

    /** Ručné snímanie spúšťou; ak je v zábere nájdený dokument, oreže sa podľa neho. */
    fun captureManually() {
        val recent = latest?.takeIf { System.currentTimeMillis() - it.timeMs < RECENT_MS }
        takePicture(recent?.detection?.quad, recent?.fingerprint)
    }

    private fun takePicture(quad: Quad?, fingerprint: FloatArray?) {
        val controller = camera
        if (controller == null || saving) {
            synchronized(auto) { auto.onCaptureFailed() }
            return
        }
        saving = true
        setTransientStatus(ScanStatus.SAVING, resetAfterMs = null)

        val pageFile = pageStore.newPageFile()
        val original = pageStore.originalFile(pageFile)
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(original).build(),
            ContextCompat.getMainExecutor(getApplication()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    _captureEvents.tryEmit(Unit)
                    viewModelScope.launch {
                        val ok = withContext(Dispatchers.Default) {
                            runCatching { PageProcessor.process(original, pageFile, quad) }
                                .onFailure { Log.e(TAG, "Spracovanie strany zlyhalo", it) }
                                .isSuccess
                        }
                        if (!ok) original.copyTo(pageFile, overwrite = true)
                        pageStore.refresh()
                        synchronized(auto) { auto.onCaptured(fingerprint) }
                        saving = false
                        setTransientStatus(ScanStatus.CAPTURED, resetAfterMs = 1_500)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Snímanie zlyhalo", exception)
                    original.delete()
                    synchronized(auto) { auto.onCaptureFailed() }
                    saving = false
                    setTransientStatus(ScanStatus.ERROR, resetAfterMs = 2_000)
                }
            },
        )
    }

    private fun statusOf(capture: CaptureState, autoOn: Boolean): ScanStatus = when (capture.phase) {
        CapturePhase.SEARCHING -> ScanStatus.SEARCHING
        CapturePhase.STABILIZING -> if (autoOn) ScanStatus.HOLD_STILL else ScanStatus.READY_MANUAL
        CapturePhase.CAPTURING -> ScanStatus.SAVING
        CapturePhase.WAITING_FOR_CHANGE -> ScanStatus.SAME_SHEET
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
        /** Minimálna ostrosť (rozptyl Laplaceovho operátora); predbežná hodnota, ladí sa na zariadení. */
        const val MIN_SHARPNESS = 100.0
        const val RECENT_MS = 500L
    }
}
