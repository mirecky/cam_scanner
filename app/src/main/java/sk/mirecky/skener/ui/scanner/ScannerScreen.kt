package sk.mirecky.skener.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.roundToInt
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.graphicsLayer
import java.util.concurrent.Executors
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import sk.mirecky.skener.ui.common.PageImage
import sk.mirecky.skener.data.CaptureSignal
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sk.mirecky.skener.R
import sk.mirecky.skener.scanner.ScanMode

@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel,
    onOpenDocument: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onRetakeDone: (docId: String, pageId: String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var askedOnce by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        askedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            ScannerContent(viewModel, onOpenDocument, onOpenLibrary, onRetakeDone)
        } else {
            PermissionRationale(
                showSettings = askedOnce,
                onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null)),
                    )
                },
            )
        }
    }
}

@Composable
private fun ScannerContent(
    viewModel: ScannerViewModel,
    onOpenDocument: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onRetakeDone: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val overlay by viewModel.overlay.collectAsStateWithLifecycle()
    val exposure by viewModel.exposure.collectAsStateWithLifecycle()
    val retake by viewModel.retake.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.retakeDone.collect { (doc, page) -> onRetakeDone(doc, page) } }
    var adjustingExposure by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(true) }
    val flash = remember { Animatable(0f) }
    val signalPlayer = remember { SignalPlayer(context) }
    DisposableEffect(signalPlayer) { onDispose { signalPlayer.release() } }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember {
        val ratio4to3 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val analysisSize = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            previewResolutionSelector = ratio4to3
            imageCaptureResolutionSelector = ratio4to3
            imageAnalysisResolutionSelector = analysisSize
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setImageAnalysisAnalyzer(
                analysisExecutor,
                DocumentAnalyzer(mode = { viewModel.currentMode }, onResult = viewModel::onAnalysis),
            )
            bindToLifecycle(lifecycleOwner)
        }
    }
    DisposableEffect(controller) {
        viewModel.attachCamera(controller)
        onDispose {
            viewModel.attachCamera(null)
            controller.clearImageAnalysisAnalyzer()
            analysisExecutor.shutdown()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.captureEvents.collect { signal ->
            signalPlayer.play(signal)
            flash.snapTo(0.8f)
            flash.animateTo(0f, tween(350))
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            IconButton(onClick = onOpenLibrary) {
                Icon(Icons.Outlined.Home, stringResource(R.string.action_library), tint = Color.White)
            }
            StatusBar(
                state,
                retaking = retake != null,
                onCancelRetake = viewModel::cancelRetake,
                onCaptureAnyway = viewModel::captureManually,
                debug = if (showDebug) overlay.debug else null,
                onClick = { showDebug = !showDebug },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::cycleSignal) {
                val (icon, label) = when (state.signal) {
                    CaptureSignal.SOUND -> Icons.AutoMirrored.Outlined.VolumeUp to R.string.signal_sound
                    CaptureSignal.VIBRATION -> Icons.Outlined.Vibration to R.string.signal_vibration
                    CaptureSignal.SILENT -> Icons.AutoMirrored.Outlined.VolumeOff to R.string.signal_silent
                }
                Icon(icon, stringResource(label), tint = Color.White)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CameraPreview(controller, Modifier.fillMaxSize())
            DocumentOverlay(overlay, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
            val range = viewModel.exposureRange()
            if (adjustingExposure && range != null) {
                ExposureSlider(exposure, range, viewModel.exposureStep(), Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            } else if (exposure != 0) {
                // Nastavený jas je viditeľný; ťuknutím sa vráti na predvolený.
                AssistChip(
                    onClick = { viewModel.setExposure(0) },
                    label = { Text(stringResource(R.string.exposure_label, "%+.1f".format(exposure * viewModel.exposureStep()))) },
                    leadingIcon = { Icon(Icons.Outlined.BrightnessMedium, null) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = Color.Black.copy(alpha = 0.5f), labelColor = Color.White, leadingIconContentColor = Color.White),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
        }

        Box(Modifier.fillMaxWidth().padding(top = 12.dp, start = 16.dp, end = 16.dp)) {
            ModeSelector(
                mode = state.mode,
                onSelect = viewModel::setMode,
                modifier = Modifier.align(Alignment.Center),
            )
            val docId = state.documentId
            if (docId != null && state.pageCount > 0) {
                // Hotovo: dokument je kompletný, otvorí sa a ďalšie skenovanie začne nový dokument.
                FilledTonalButton(
                    onClick = {
                        onOpenDocument(docId)
                        viewModel.startNewDocument()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) { Text(stringResource(R.string.action_done)) }
            }
        }

        ControlBar(
            state = state,
            onCapture = viewModel::captureManually,
            onToggleAuto = viewModel::toggleAutoCapture,
            onOpenDocument = { state.documentId?.let(onOpenDocument) },
            exposure = exposure,
            exposureRange = viewModel::exposureRange,
            onExposure = viewModel::setExposure,
            onAdjustingExposure = { adjustingExposure = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 24.dp),
        )
    }
}

@Composable
private fun StatusBar(
    state: ScannerUiState,
    retaking: Boolean,
    onCancelRetake: () -> Unit,
    onCaptureAnyway: () -> Unit,
    debug: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when (state.status) {
        ScanStatus.SEARCHING -> R.string.status_searching
        ScanStatus.HOLD_STILL -> R.string.status_hold_still
        ScanStatus.READY_MANUAL -> R.string.status_ready_manual
        ScanStatus.SAVING -> R.string.status_saving
        ScanStatus.CAPTURED -> R.string.status_captured
        ScanStatus.SAME_SHEET -> R.string.status_same_sheet
        ScanStatus.FOCUSING -> R.string.status_focusing
        ScanStatus.OUT_OF_FRAME -> R.string.status_out_of_frame
        ScanStatus.ERROR -> R.string.status_error
    }
    val container = when (state.status) {
        ScanStatus.CAPTURED, ScanStatus.HOLD_STILL, ScanStatus.READY_MANUAL, ScanStatus.FOCUSING -> MaterialTheme.colorScheme.primaryContainer
        ScanStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = container, onClick = onClick) {
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        // Automat čaká na iný list; ak je to predsa nový list (rovnaký formulár), dá sa nasnímať hneď.
        if (state.status == ScanStatus.SAME_SHEET && state.autoCapture && !retaking) {
            AssistChip(
                onClick = onCaptureAnyway,
                label = { Text(stringResource(R.string.action_capture_anyway)) },
                leadingIcon = { Icon(Icons.Filled.CameraAlt, null) },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (retaking) {
            AssistChip(
                onClick = onCancelRetake,
                label = { Text(stringResource(R.string.retake_hint)) },
                trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.action_cancel)) },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (debug != null) {
            Text(
                debug,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ModeSelector(mode: ScanMode, onSelect: (ScanMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = listOf(ScanMode.DOCUMENT to R.string.mode_document, ScanMode.WHITEBOARD to R.string.mode_whiteboard)
    SingleChoiceSegmentedButtonRow(modifier) {
        modes.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            ) { Text(stringResource(label)) }
        }
    }
}

/**
 * Spodná lišta: strany (vľavo), spúšť (stred), prepínač automatického snímania (vpravo, vedľa spúšte).
 */
@Composable
private fun ControlBar(
    state: ScannerUiState,
    onCapture: () -> Unit,
    onToggleAuto: () -> Unit,
    onOpenDocument: () -> Unit,
    exposure: Int,
    exposureRange: () -> IntRange?,
    onExposure: (Int) -> Unit,
    onAdjustingExposure: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        val pagesLabel = pluralStringResource(R.plurals.page_count, state.pageCount, state.pageCount)
        // Bez strán nie je čo otvoriť: náhľad je neaktívny.
        val hasPages = state.documentId != null && state.pageCount > 0
        BadgedBox(
            badge = { if (state.pageCount > 0) Badge { Text(state.pageCount.toString()) } },
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(2.dp, Color.White.copy(alpha = if (hasPages) 1f else 0.3f), RoundedCornerShape(8.dp))
                    .clickable(enabled = hasPages, onClick = onOpenDocument)
                    .semantics { contentDescription = pagesLabel },
                contentAlignment = Alignment.Center,
            ) {
                val last = state.lastPage
                if (last != null) {
                    PageImage(last, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Outlined.Description, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(30.dp))
                }
                if (state.processing > 0) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                }
            }
        }

        ShutterButton(
            enabled = state.status != ScanStatus.SAVING,
            onClick = onCapture,
            exposure = exposure,
            exposureRange = exposureRange,
            onExposure = onExposure,
            onAdjusting = onAdjustingExposure,
        )

        AutoCaptureToggle(enabled = state.autoCapture, onToggle = onToggleAuto)
    }
}

/**
 * Spúšť: ťuknutie nasníma stranu, podržanie zapne nastavenie jasu – ťahanie doľava stmaví,
 * doprava zosvetlí (korekcia expozície kamery).
 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit,
    exposure: Int,
    exposureRange: () -> IntRange?,
    onExposure: (Int) -> Unit,
    onAdjusting: (Boolean) -> Unit,
) {
    val description = stringResource(R.string.action_capture)
    val haptics = LocalHapticFeedback.current
    val stepPx = with(LocalDensity.current) { EXPOSURE_STEP_DP.dp.toPx() }
    val currentExposure by rememberUpdatedState(exposure)
    val currentEnabled by rememberUpdatedState(enabled)
    Box(
        Modifier
            .size(80.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var released = false
                    val finished = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        released = waitForUpOrCancellation() != null
                        true
                    }
                    if (finished != null) {
                        if (released && currentEnabled) onClick()
                        return@awaitEachGesture
                    }
                    // Podržanie: nastavenie jasu ťahaním vodorovne.
                    val range = exposureRange() ?: return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAdjusting(true)
                    val startIndex = currentExposure
                    val startX = down.position.x
                    drag(down.id) { change ->
                        val index = (startIndex + ((change.position.x - startX) / stepPx).roundToInt()).coerceIn(range)
                        if (index != currentExposure) {
                            onExposure(index)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        change.consume()
                    }
                    onAdjusting(false)
                }
            },
    )
}

/** Posuvník jasu zobrazený počas podržania spúšte. */
@Composable
private fun ExposureSlider(index: Int, range: IntRange, step: Float, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color.Black.copy(alpha = 0.6f), modifier = modifier) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.exposure_label, "%+.1f".format(index * step)),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BrightnessLow, null, tint = Color.White)
                Slider(
                    value = index.toFloat(),
                    onValueChange = {},
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = (range.last - range.first - 1).coerceAtLeast(0),
                    enabled = false,
                    colors = SliderDefaults.colors(
                        disabledThumbColor = Color.White,
                        disabledActiveTrackColor = Color.White,
                        disabledInactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        disabledActiveTickColor = Color.Transparent,
                        disabledInactiveTickColor = Color.Transparent,
                    ),
                    modifier = Modifier.width(220.dp),
                )
                Icon(Icons.Outlined.BrightnessHigh, null, tint = Color.White)
            }
        }
    }
}

/** Šírka ťahu prstom na jeden krok expozície. */
private const val EXPOSURE_STEP_DP = 22

@Composable
private fun AutoCaptureToggle(enabled: Boolean, onToggle: () -> Unit) {
    val description = stringResource(if (enabled) R.string.action_auto_on else R.string.action_auto_off)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconToggleButton(
            checked = enabled,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(56.dp).semantics { contentDescription = description },
        ) {
            Icon(
                imageVector = if (enabled) Icons.Filled.AutoMode else Icons.Outlined.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(if (enabled) R.string.label_auto else R.string.label_manual),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun PermissionRationale(showSettings: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.permission_text),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.permission_grant)) }
        if (showSettings) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.permission_settings)) }
        }
    }
}
