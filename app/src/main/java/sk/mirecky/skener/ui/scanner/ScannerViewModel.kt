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
import sk.mirecky.skener.SkenerApp
import sk.mirecky.skener.data.CaptureSignal
import sk.mirecky.skener.data.PageItem
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
    val signal: CaptureSignal = CaptureSignal.SOUND,
    /** Dokument, do ktorého sa práve skenuje (null = nasledujúca strana založí nový). */
    val documentId: String? = null,
    val pageCount: Int = 0,
    val lastPage: PageItem? = null,
    /** Počet strán, ktoré sa ešte spracúvajú (orezanie, filter). */
    val processing: Int = 0,
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
    private val repository = (application as SkenerApp).documents
    private val auto = AutoCaptureController()

    private val transientStatus = MutableStateFlow<ScanStatus?>(null)
    private var statusResetJob: Job? = null

    private val _overlay = MutableStateFlow(OverlayState())
    val overlay: StateFlow<OverlayState> = _overlay.asStateFlow()

    /** Udalosť úspešného nasnímania (záblesk a signál v UI). */
    private val _captureEvents = MutableSharedFlow<CaptureSignal>(extraBufferCapacity = 1)
    val captureEvents: SharedFlow<CaptureSignal> = _captureEvents.asSharedFlow()

    /** Strana, ktorá sa má prefotiť (dokument, strana); null = bežné skenovanie. */
    private val _retake = MutableStateFlow<Pair<String, String>?>(null)
    val retake: StateFlow<Pair<String, String>?> = _retake.asStateFlow()

    /** Po prefotení strany: (dokument, strana) na návrat do editora. */
    private val _retakeDone = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
    val retakeDone: SharedFlow<Pair<String, String>> = _retakeDone.asSharedFlow()

    /** Aktuálny dokument, do ktorého pribúdajú strany. */
    private val documentId = MutableStateFlow<String?>(null)
    private val processing = MutableStateFlow(0)

    @Volatile private var autoEnabled = true
    @Volatile var currentMode = ScanMode.DOCUMENT
        private set
    @Volatile private var latest: AnalysisResult? = null
    @Volatile private var saving = false
    private var camera: LifecycleCameraController? = null

    private val documentState = combine(documentId, repository.documents, processing) { id, docs, busy ->
        Triple(id, docs.firstOrNull { it.id == id }, busy)
    }

    private val settingsState = combine(settings.scanMode, settings.autoCapture, settings.captureSignal) { mode, autoOn, signal ->
        Triple(mode, autoOn, signal)
    }

    val uiState: StateFlow<ScannerUiState> =
        combine(settingsState, documentState, transientStatus, _overlay) { (mode, autoOn, signal), (id, doc, busy), transient, overlay ->
            ScannerUiState(
                mode = mode,
                autoCapture = autoOn,
                signal = signal,
                documentId = id,
                pageCount = (doc?.pages?.size ?: 0) + busy,
                lastPage = doc?.pages?.lastOrNull(),
                processing = busy,
                status = transient ?: statusOf(overlay.capture, autoOn),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScannerUiState())

    @Volatile private var signal = CaptureSignal.SOUND

    init {
        viewModelScope.launch { settings.autoCapture.collect { autoEnabled = it } }
        viewModelScope.launch { settings.scanMode.collect { currentMode = it } }
        viewModelScope.launch { settings.captureSignal.collect { signal = it } }
        // Ak aktuálny dokument zmizne (zmazanie všetkých strán), ďalšie skenovanie začne nový.
        viewModelScope.launch {
            repository.documents.collect { docs ->
                val id = documentId.value
                if (id != null && !saving && processing.value == 0 && docs.none { it.id == id }) startNewDocument()
            }
        }
    }

    /** Ďalšia nasnímaná strana založí nový dokument. */
    fun startNewDocument() {
        documentId.value = null
        synchronized(auto) { auto.reset() }
    }

    /** Skenovanie bude pokračovať do existujúceho dokumentu (Pridať strany). */
    fun continueDocument(id: String) {
        if (documentId.value != id) {
            documentId.value = id
            synchronized(auto) { auto.reset() }
        }
    }

    /** Ďalšia snímka nahradí stranu [pageId] dokumentu [docId]. */
    fun retake(docId: String, pageId: String) {
        continueDocument(docId)
        _retake.value = docId to pageId
        synchronized(auto) { auto.reset() }
    }

    fun cancelRetake() {
        _retake.value = null
    }

    fun cycleSignal() {
        val next = when (uiState.value.signal) {
            CaptureSignal.SOUND -> CaptureSignal.VIBRATION
            CaptureSignal.VIBRATION -> CaptureSignal.SILENT
            CaptureSignal.SILENT -> CaptureSignal.SOUND
        }
        viewModelScope.launch { settings.setCaptureSignal(next) }
    }

    fun attachCamera(controller: LifecycleCameraController?) {
        camera = controller
        // Nastavený jas platí, kým je aplikácia otvorená; po pripojení kamery sa znova uplatní.
        controller?.initializationFuture?.addListener(
            { applyExposure(_exposure.value) },
            ContextCompat.getMainExecutor(getApplication()),
        )
    }

    private val _exposure = MutableStateFlow(0)
    /** Korekcia expozície (index v krokoch kamery); platí do zatvorenia aplikácie. */
    val exposure: StateFlow<Int> = _exposure.asStateFlow()

    /** Rozsah korekcie expozície, alebo null, ak ju kamera nepodporuje (alebo ešte nie je pripravená). */
    fun exposureRange(): IntRange? =
        camera?.cameraInfo?.exposureState
            ?.takeIf { it.isExposureCompensationSupported }
            ?.exposureCompensationRange
            ?.let { it.lower..it.upper }

    /** Veľkosť jedného kroku expozície v EV. */
    fun exposureStep(): Float = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f

    fun setExposure(index: Int) {
        if (_exposure.value == index) return
        _exposure.value = index
        applyExposure(index)
    }

    private fun applyExposure(index: Int) {
        val range = exposureRange() ?: return
        camera?.cameraControl?.setExposureCompensationIndex(index.coerceIn(range))
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

        val mode = currentMode
        val slot = repository.reservePage(documentId.value)
        // Prvá strana nového dokumentu určí jeho ID; ďalšie strany pribúdajú do neho.
        documentId.value = slot.docId
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(slot.originalFile).build(),
            ContextCompat.getMainExecutor(getApplication()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    _captureEvents.tryEmit(signal)
                    synchronized(auto) { auto.onCaptured(fingerprint) }
                    saving = false
                    setTransientStatus(ScanStatus.CAPTURED, resetAfterMs = 1_500)
                    // Orezanie a filter bežia na pozadí; ďalší list sa dá snímať hneď.
                    processing.value += 1
                    val target = _retake.value?.takeIf { it.first == slot.docId }
                    if (target != null) _retake.value = null
                    viewModelScope.launch {
                        try {
                            if (target != null) {
                                repository.replacePage(slot, target.second, quad, mode)
                                _retakeDone.tryEmit(target.first to slot.pageId)
                            } else {
                                repository.addPage(slot, quad, mode)
                            }
                        } finally {
                            processing.value -= 1
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Snímanie zlyhalo", exception)
                    repository.discard(slot)
                    if (repository.document(slot.docId) == null && processing.value == 0) documentId.value = null
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
        /** Minimálna ostrosť (rozptyl Laplaceovho operátora); ostré zábery na S25 Ultra majú 1500–2500. */
        const val MIN_SHARPNESS = 400.0
        const val RECENT_MS = 500L
    }
}
