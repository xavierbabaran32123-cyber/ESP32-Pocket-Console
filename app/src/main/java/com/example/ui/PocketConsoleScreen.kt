package com.example.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ble.BleConnectionStatus
import com.example.ble.BleConsoleManager
import com.example.game.render.ConsoleCanvasRenderer
import com.example.ui.theme.ConsoleOk
import kotlinx.coroutines.isActive

@Composable
fun PocketConsoleScreen(
    viewModel: ConsoleViewModel = viewModel(),
    onFullscreenChanged: (Boolean) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.isFullscreen) {
        onFullscreenChanged(uiState.isFullscreen)
    }

    BackHandler(
        enabled = uiState.isFullscreen || uiState.showScannerSheet || uiState.showQrDialog
    ) {
        viewModel.handleBackGesture()
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onBluetoothEnabledResult(
            result.resultCode == Activity.RESULT_OK || viewModel.bleManager.isBluetoothEnabled()
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.isNotEmpty() && grants.values.all { it }
        val needEnableBt = viewModel.onBlePermissionsResult(allGranted)
        if (needEnableBt) {
            try {
                enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
            }
        }
    }

    val onConnectClick = {
        when (viewModel.onConnectButtonClicked()) {
            ConnectClickAction.NEED_PERMISSIONS -> {
                permissionLauncher.launch(BleConsoleManager.requiredPermissions())
            }
            ConnectClickAction.NEED_ENABLE_BLUETOOTH -> {
                try {
                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (_: Exception) {
                }
            }
            ConnectClickAction.HANDLED -> {}
        }
    }

    val pixelTypeface = remember(context) {
        try {
            ResourcesCompat.getFont(context, R.font.silkscreen)
        } catch (_: Exception) {
            null
        }
    }
    val renderer = remember(viewModel.engineState, viewModel.soundEngine, pixelTypeface) {
        ConsoleCanvasRenderer(
            state = viewModel.engineState,
            sound = viewModel.soundEngine,
            pixelTypeface = pixelTypeface
        )
    }

    if (uiState.showScannerSheet) {
        BleScannerSheet(
            bleState = uiState.bleState,
            onSelectDevice = viewModel::selectBleDevice,
            onToggleShowAll = viewModel::setShowAllBleDevices,
            onRescan = viewModel::rescanDevices,
            onDismiss = viewModel::dismissScannerSheet
        )
    }

    if (uiState.showQrDialog) {
        QrDownloadDialog(
            onDismiss = viewModel::dismissQrDialog
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isLandscape = maxWidth > maxHeight
        val useSideRail = !uiState.isFullscreen && isLandscape && maxHeight < 520.dp

        if (uiState.isFullscreen) {
            ConsoleViewportStage(
                renderer = renderer,
                isFullscreen = true,
                onExitFullscreen = viewModel::exitFullscreen,
                modifier = Modifier.fillMaxSize()
            )
        } else if (useSideRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                LandscapeControlRail(
                    uiState = uiState,
                    onConnectClick = onConnectClick,
                    onOpenQr = viewModel::openQrDialog,
                    onToggleSound = viewModel::toggleSoundMute,
                    onToggleFullscreen = viewModel::toggleFullscreen
                )
                VerticalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline
                )
                ConsoleViewportStage(
                    renderer = renderer,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                TopConsoleHeader(
                    uiState = uiState,
                    onConnectClick = onConnectClick,
                    onOpenQr = viewModel::openQrDialog,
                    onToggleSound = viewModel::toggleSoundMute,
                    onToggleFullscreen = viewModel::toggleFullscreen
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline
                )
                ConsoleViewportStage(
                    renderer = renderer,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                ConsoleFooterHint(uiState = uiState)
            }
        }
    }
}

@Composable
private fun ConsoleViewportStage(
    renderer: ConsoleCanvasRenderer,
    isFullscreen: Boolean = false,
    onExitFullscreen: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var frameNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameMillis {
                frameNowMs = System.currentTimeMillis()
            }
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isFullscreen) {
                    if (isFullscreen) {
                        detectTapGestures(
                            onDoubleTap = { onExitFullscreen() }
                        )
                    }
                }
                .testTag("console_stage_canvas")
        ) {
            val tickNow = frameNowMs
            renderer.renderFrame(
                c = drawContext.canvas.nativeCanvas,
                widthPx = size.width,
                heightPx = size.height,
                nowMs = tickNow
            )
        }
    }
}

@Composable
private fun TopConsoleHeader(
    uiState: ConsoleScreenUiState,
    onConnectClick: () -> Unit,
    onOpenQr: () -> Unit,
    onToggleSound: () -> Unit,
    onToggleFullscreen: () -> Unit
) {
    val statusInfo = rememberStatusPresentation(uiState)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                StatusLedDot(
                    color = statusInfo.dotColor,
                    pulsing = statusInfo.pulsing
                )
                Text(
                    text = statusInfo.statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("connection_status_text")
                )
            }

            FilledTonalIconButton(
                onClick = onOpenQr,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("qr_download_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCode2,
                    contentDescription = stringResource(R.string.cd_qr_download)
                )
            }

            FilledTonalIconButton(
                onClick = onToggleSound,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (uiState.soundMuted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("sound_toggle_button")
            ) {
                Icon(
                    imageVector = if (uiState.soundMuted) {
                        Icons.AutoMirrored.Filled.VolumeOff
                    } else {
                        Icons.AutoMirrored.Filled.VolumeUp
                    },
                    contentDescription = stringResource(
                        if (uiState.soundMuted) R.string.cd_sound_off else R.string.cd_sound_on
                    )
                )
            }

            FilledTonalIconButton(
                onClick = onToggleFullscreen,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("fullscreen_toggle_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.cd_fullscreen_enter)
                )
            }

            Button(
                onClick = onConnectClick,
                enabled = uiState.bleState.status !is BleConnectionStatus.Unavailable,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("connect_button")
            ) {
                Icon(
                    imageVector = if (uiState.bleState.status is BleConnectionStatus.Connected) {
                        Icons.Filled.BluetoothConnected
                    } else {
                        Icons.Filled.Bluetooth
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusInfo.connectButtonLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun LandscapeControlRail(
    uiState: ConsoleScreenUiState,
    onConnectClick: () -> Unit,
    onOpenQr: () -> Unit,
    onToggleSound: () -> Unit,
    onToggleFullscreen: () -> Unit
) {
    val statusInfo = rememberStatusPresentation(uiState)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .width(156.dp)
            .fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatusLedDot(color = statusInfo.dotColor, pulsing = statusInfo.pulsing)
                Text(
                    text = statusInfo.statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("connection_status_text")
                )
            }

            Button(
                onClick = onConnectClick,
                enabled = uiState.bleState.status !is BleConnectionStatus.Unavailable,
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("connect_button")
            ) {
                Text(
                    text = statusInfo.connectButtonLabel,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalIconButton(
                    onClick = onOpenQr,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("qr_download_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCode2,
                        contentDescription = stringResource(R.string.cd_qr_download)
                    )
                }

                FilledTonalIconButton(
                    onClick = onToggleSound,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (uiState.soundMuted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        }
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("sound_toggle_button")
                ) {
                    Icon(
                        imageVector = if (uiState.soundMuted) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = stringResource(
                            if (uiState.soundMuted) R.string.cd_sound_off else R.string.cd_sound_on
                        )
                    )
                }

                FilledTonalIconButton(
                    onClick = onToggleFullscreen,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("fullscreen_toggle_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Fullscreen,
                        contentDescription = stringResource(R.string.cd_fullscreen_enter)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleFooterHint(uiState: ConsoleScreenUiState) {
    val hintText = when {
        uiState.permissionDeniedBanner -> stringResource(R.string.hint_ble_permission_denied)
        uiState.bleState.status is BleConnectionStatus.Connected -> stringResource(R.string.hint_ble_connected)
        uiState.bleState.status is BleConnectionStatus.Unavailable -> stringResource(R.string.hint_ble_unavailable)
        else -> stringResource(R.string.hint_ble_idle)
    }

    Text(
        text = hintText,
        style = MaterialTheme.typography.bodyMedium,
        color = if (uiState.permissionDeniedBanner) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun StatusLedDot(
    color: Color,
    pulsing: Boolean
) {
    val infinite = rememberInfiniteTransition(label = "status_led")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "led_alpha"
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .alpha(if (pulsing) alpha else 1f)
            .clip(CircleShape)
            .background(color)
    )
}

private data class StatusPresentation(
    val statusText: String,
    val connectButtonLabel: String,
    val dotColor: Color,
    val pulsing: Boolean
)

@Composable
private fun rememberStatusPresentation(uiState: ConsoleScreenUiState): StatusPresentation {
    return when (val st = uiState.bleState.status) {
        is BleConnectionStatus.Idle -> StatusPresentation(
            statusText = stringResource(R.string.status_not_connected),
            connectButtonLabel = stringResource(R.string.btn_connect),
            dotColor = MaterialTheme.colorScheme.error,
            pulsing = false
        )
        is BleConnectionStatus.Disconnected -> StatusPresentation(
            statusText = stringResource(R.string.status_disconnected),
            connectButtonLabel = stringResource(R.string.btn_connect),
            dotColor = MaterialTheme.colorScheme.error,
            pulsing = false
        )
        is BleConnectionStatus.Scanning -> StatusPresentation(
            statusText = stringResource(R.string.status_searching),
            connectButtonLabel = stringResource(R.string.btn_connect),
            dotColor = MaterialTheme.colorScheme.tertiary,
            pulsing = true
        )
        is BleConnectionStatus.Connecting -> StatusPresentation(
            statusText = stringResource(R.string.status_connecting),
            connectButtonLabel = stringResource(R.string.btn_stop),
            dotColor = MaterialTheme.colorScheme.tertiary,
            pulsing = true
        )
        is BleConnectionStatus.Reconnecting -> StatusPresentation(
            statusText = stringResource(R.string.status_reconnecting),
            connectButtonLabel = stringResource(R.string.btn_stop),
            dotColor = MaterialTheme.colorScheme.tertiary,
            pulsing = true
        )
        is BleConnectionStatus.Connected -> StatusPresentation(
            statusText = stringResource(R.string.status_connected_fmt, st.deviceName),
            connectButtonLabel = stringResource(R.string.btn_disconnect),
            dotColor = ConsoleOk,
            pulsing = false
        )
        is BleConnectionStatus.Unavailable -> StatusPresentation(
            statusText = stringResource(R.string.status_not_connected),
            connectButtonLabel = stringResource(R.string.btn_connect),
            dotColor = MaterialTheme.colorScheme.error,
            pulsing = false
        )
        is BleConnectionStatus.Error -> StatusPresentation(
            statusText = st.message,
            connectButtonLabel = stringResource(R.string.btn_connect),
            dotColor = MaterialTheme.colorScheme.error,
            pulsing = false
        )
    }
}
