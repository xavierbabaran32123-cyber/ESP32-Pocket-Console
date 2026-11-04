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
    val isFullscreen: Boolean = false,
    val soundMuted: Boolean = false,
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
            packetProcessor.onPacket(bytes)
        },
        onSignalLost = {
            engineState.resetSignal()
            soundEngine.wantTrack = -1
            soundEngine.setRumble(0f)
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
                _uiState.update {
                    it.copy(soundMuted = saved.soundMuted)
                }
            }
        }
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

    fun onConnectButtonClicked(): ConnectClickAction {
        soundEngine.ensureStarted()

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
            return true
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
        _uiState.update { it.copy(showScannerSheet = false) }
        bleManager.connectToDevice(device)
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
        return false
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.release()
        soundEngine.release()
    }
}
