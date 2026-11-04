package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.ChiptuneSynthEngine
import com.example.audio.SoundPlayer
import com.example.ble.BleConnectionStatus
import com.example.ble.BleConsoleManager
import com.example.ble.BleUiState
import com.example.ble.ScannedBleDevice
import com.example.data.ConsolePreferencesRepository
import com.example.game.local.ConsoleButton
import com.example.game.local.LocalConsoleSimulator
import com.example.game.state.ConsoleEngineState
import com.example.game.state.PacketProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectClickAction {
    HANDLED,
    NEED_PERMISSIONS,
    NEED_ENABLE_BLUETOOTH
}

data class ConsoleScreenUiState(
    val bleState: BleUiState = BleUiState(),
    val isLocalMode: Boolean = false,
    val isFullscreen: Boolean = false,
    val soundMuted: Boolean = false,
    val showGamepadInLocal: Boolean = true,
    val showScannerSheet: Boolean = false,
    val permissionDeniedBanner: Boolean = false
)

class ConsoleViewModel(application: Application) : AndroidViewModel(application) {

    val engineState = ConsoleEngineState()
    val soundEngine: SoundPlayer = ChiptuneSynthEngine()
    val packetProcessor = PacketProcessor(engineState, soundEngine)

    private val prefsRepo = ConsolePreferencesRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(ConsoleScreenUiState())
    val uiState: StateFlow<ConsoleScreenUiState> = _uiState.asStateFlow()

    val bleManager = BleConsoleManager(
        context = application.applicationContext,
        scope = viewModelScope,
        onPacketReceived = { bytes ->
            if (!_uiState.value.isLocalMode) {
                packetProcessor.onPacket(bytes)
            }
        },
        onSignalLost = {
            if (!_uiState.value.isLocalMode) {
                engineState.resetSignal()
                soundEngine.wantTrack = -1
                soundEngine.setRumble(0f)
            }
        }
    )

    private val localSimulator = LocalConsoleSimulator(
        scope = viewModelScope,
        emitPacket = { bytes ->
            if (_uiState.value.isLocalMode) {
                packetProcessor.onPacket(bytes)
            }
        },
        onPersistSave = { updatedSave ->
            viewModelScope.launch {
                prefsRepo.saveLocalProgress(updatedSave)
            }
        }
    )

    init {
        viewModelScope.launch {
            bleManager.uiState.collectLatest { ble ->
                _uiState.update { it.copy(bleState = ble) }
            }
        }
        viewModelScope.launch {
            prefsRepo.prefsFlow.collectLatest { saved ->
                soundEngine.mute = saved.soundMuted
                soundEngine.musicOn = saved.musicOn
                soundEngine.sfxOn = saved.sfxOn
                localSimulator.syncPrefs(saved)
                _uiState.update {
                    it.copy(
                        soundMuted = saved.soundMuted,
                        showGamepadInLocal = saved.showGamepadInLocal
                    )
                }
            }
        }
    }

    fun ensureAudioStarted() {
        soundEngine.ensureStarted()
    }

    fun toggleSoundMute() {
        soundEngine.ensureStarted()
        val next = !_uiState.value.soundMuted
        soundEngine.mute = next
        _uiState.update { it.copy(soundMuted = next) }
        viewModelScope.launch {
            prefsRepo.setSoundMuted(next)
        }
    }

    fun toggleFullscreen() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun exitFullscreen() {
        _uiState.update { it.copy(isFullscreen = false) }
    }

    fun toggleGamepadVisibility() {
        val next = !_uiState.value.showGamepadInLocal
        _uiState.update { it.copy(showGamepadInLocal = next) }
        viewModelScope.launch {
            prefsRepo.setShowGamepad(next)
        }
    }

    fun enterLocalMode() {
        soundEngine.ensureStarted()
        bleManager.disconnect()
        engineState.resetSignal()
        _uiState.update {
            it.copy(
                isLocalMode = true,
                showScannerSheet = false,
                permissionDeniedBanner = false
            )
        }
        localSimulator.start()
    }

    fun exitLocalMode() {
        localSimulator.stop()
        engineState.resetSignal()
        soundEngine.wantTrack = -1
        soundEngine.setRumble(0f)
        _uiState.update { it.copy(isLocalMode = false) }
    }

    fun toggleLocalMode() {
        if (_uiState.value.isLocalMode) {
            exitLocalMode()
        } else {
            enterLocalMode()
        }
    }

    fun onConnectButtonClicked(): ConnectClickAction {
        soundEngine.ensureStarted()
        if (_uiState.value.isLocalMode) {
            exitLocalMode()
        }

        val currentStatus = bleManager.uiState.value.status
        if (currentStatus is BleConnectionStatus.Connected ||
            currentStatus is BleConnectionStatus.Connecting ||
            currentStatus is BleConnectionStatus.Reconnecting
        ) {
            bleManager.disconnect()
            _uiState.update { it.copy(showScannerSheet = false) }
            return ConnectClickAction.HANDLED
        }

        if (!bleManager.isBleHardwareSupported()) {
            return ConnectClickAction.HANDLED
        }
        if (!bleManager.hasBlePermissions()) {
            return ConnectClickAction.NEED_PERMISSIONS
        }
        if (!bleManager.isBluetoothEnabled()) {
            return ConnectClickAction.NEED_ENABLE_BLUETOOTH
        }

        _uiState.update { it.copy(showScannerSheet = true, permissionDeniedBanner = false) }
        bleManager.startScan()
        return ConnectClickAction.HANDLED
    }

    fun onBlePermissionsResult(granted: Boolean): Boolean {
        _uiState.update { it.copy(permissionDeniedBanner = !granted) }
        if (!granted) return false
        if (!bleManager.isBluetoothEnabled()) {
            return true // Caller should launch ACTION_REQUEST_ENABLE
        }
        _uiState.update { it.copy(showScannerSheet = true) }
        bleManager.startScan()
        return false
    }

    fun onBluetoothEnabledResult(enabled: Boolean) {
        if (enabled && bleManager.hasBlePermissions()) {
            _uiState.update { it.copy(showScannerSheet = true, permissionDeniedBanner = false) }
            bleManager.startScan()
        }
    }

    fun dismissScannerSheet() {
        _uiState.update { it.copy(showScannerSheet = false) }
        bleManager.stopScan()
    }

    fun rescanDevices() {
        if (bleManager.hasBlePermissions() && bleManager.isBluetoothEnabled()) {
            bleManager.startScan()
        }
    }

    fun setShowAllBleDevices(showAll: Boolean) {
        bleManager.setShowAllDevices(showAll)
    }

    fun selectBleDevice(device: ScannedBleDevice) {
        soundEngine.ensureStarted()
        if (_uiState.value.isLocalMode) {
            exitLocalMode()
        }
        _uiState.update { it.copy(showScannerSheet = false) }
        bleManager.connectToDevice(device)
    }

    fun onGamepadPress(button: ConsoleButton) {
        soundEngine.ensureStarted()
        localSimulator.onButtonPress(button)
    }

    fun onGamepadRelease(button: ConsoleButton) {
        localSimulator.onButtonRelease(button)
    }

    fun onStageSwipe(dir: Int) {
        if (_uiState.value.isLocalMode) {
            soundEngine.ensureStarted()
            localSimulator.onSwipeDirection(dir)
        }
    }

    fun handleBackGesture(): Boolean {
        if (_uiState.value.isFullscreen) {
            exitFullscreen()
            return true
        }
        if (_uiState.value.showScannerSheet) {
            dismissScannerSheet()
            return true
        }
        if (_uiState.value.isLocalMode) {
            if (!localSimulator.onNavigateBack()) {
                exitLocalMode()
            }
            return true
        }
        return false
    }

    override fun onCleared() {
        super.onCleared()
        localSimulator.stop()
        bleManager.release()
        soundEngine.release()
    }
}
