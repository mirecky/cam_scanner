package sk.mirecky.skener.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
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
import sk.mirecky.skener.ui.pages.PageImage
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import sk.mirecky.skener.R
import sk.mirecky.skener.scanner.ScanMode

@Composable
fun ScannerScreen(onOpenPages: () -> Unit, viewModel: ScannerViewModel = viewModel()) {
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
            ScannerContent(viewModel, onOpenPages)
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
private fun ScannerContent(viewModel: ScannerViewModel, onOpenPages: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val controller = remember {
        val ratio4to3 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            previewResolutionSelector = ratio4to3
            imageCaptureResolutionSelector = ratio4to3
            bindToLifecycle(lifecycleOwner)
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        StatusBar(state, Modifier.fillMaxWidth().padding(16.dp))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            CameraPreview(controller, Modifier.fillMaxSize())
        }

        ModeSelector(
            mode = state.mode,
            onSelect = viewModel::setMode,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
        )

        ControlBar(
            state = state,
            onCapture = {
                viewModel.capture(controller) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
            },
            onToggleAuto = viewModel::toggleAutoCapture,
            onOpenPages = onOpenPages,
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 24.dp),
        )
    }
}

@Composable
private fun StatusBar(state: ScannerUiState, modifier: Modifier = Modifier) {
    val text = when (state.status) {
        ScanStatus.SEARCHING -> R.string.status_searching
        ScanStatus.MANUAL -> R.string.status_manual
        ScanStatus.SAVING -> R.string.status_saving
        ScanStatus.CAPTURED -> R.string.status_captured
        ScanStatus.ERROR -> R.string.status_error
    }
    val container = when (state.status) {
        ScanStatus.CAPTURED -> MaterialTheme.colorScheme.primaryContainer
        ScanStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = container) {
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
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
    onOpenPages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        val pagesLabel = pluralStringResource(R.plurals.page_count, state.pageCount, state.pageCount)
        BadgedBox(
            badge = { if (state.pageCount > 0) Badge { Text(state.pageCount.toString()) } },
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenPages)
                    .semantics { contentDescription = pagesLabel },
                contentAlignment = Alignment.Center,
            ) {
                val last = state.lastPage
                if (last != null) {
                    PageImage(last, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Outlined.Description, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }

        ShutterButton(enabled = state.status != ScanStatus.SAVING, onClick = onCapture)

        AutoCaptureToggle(enabled = state.autoCapture, onToggle = onToggleAuto)
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.action_capture)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(80.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .semantics { contentDescription = description },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.4f),
        ),
    ) {}
}

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
