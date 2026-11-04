package com.example.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface BleConnectionStatus {
    data object Idle : BleConnectionStatus
    data object Disconnected : BleConnectionStatus
    data object Scanning : BleConnectionStatus
    data class Connecting(val deviceName: String) : BleConnectionStatus
    data class Connected(val deviceName: String, val address: String) : BleConnectionStatus
    data class Reconnecting(val attempt: Int, val deviceName: String) : BleConnectionStatus
    data object Unavailable : BleConnectionStatus
    data class Error(val message: String) : BleConnectionStatus
}

data class ScannedBleDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val isPocketConsoleCandidate: Boolean,
    val device: BluetoothDevice
)

data class BleUiState(
    val status: BleConnectionStatus = BleConnectionStatus.Idle,
    val devices: List<ScannedBleDevice> = emptyList(),
    val showAllDevices: Boolean = false
)

class BleConsoleManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPacketReceived: (ByteArray) -> Unit,
    private val onSignalLost: () -> Unit
) {
    companion object {
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MAX_RECONNECT_ATTEMPTS = 8
        private const val SCAN_TIMEOUT_MS = 15_000L

        fun requiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val discoveredMap = ConcurrentHashMap<String, ScannedBleDevice>()
    private val _uiState = MutableStateFlow(
        BleUiState(
            status = if (isBleHardwareSupported()) BleConnectionStatus.Idle else BleConnectionStatus.Unavailable
        )
    )
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    @Volatile
    private var activeGatt: BluetoothGatt? = null
    @Volatile
    private var wantConnection: Boolean = false
    @Volatile
    private var lastDevice: BluetoothDevice? = null
    @Volatile
    private var lastDeviceName: String = "ESP32"
    @Volatile
    private var retryCount: Int = 0
    @Volatile
    private var isScanningInternal: Boolean = false

    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null

    fun isBleHardwareSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            bluetoothAdapter != null
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun hasBlePermissions(): Boolean {
        return requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun setShowAllDevices(showAll: Boolean) {
        _uiState.update { state ->
            state.copy(
                showAllDevices = showAll,
                devices = filteredDeviceList(showAll)
            )
        }
    }

    private fun filteredDeviceList(showAll: Boolean): List<ScannedBleDevice> {
        return discoveredMap.values
            .filter { showAll || it.isPocketConsoleCandidate }
            .sortedWith(
                compareByDescending<ScannedBleDevice> { it.isPocketConsoleCandidate }
                    .thenByDescending { it.rssi }
            )
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!isBleHardwareSupported()) {
            _uiState.update { it.copy(status = BleConnectionStatus.Unavailable) }
            return
        }
        if (!hasBlePermissions() || !isBluetoothEnabled()) return

        stopScanInternal()
        reconnectJob?.cancel()
        discoveredMap.clear()
        _uiState.update {
            it.copy(
                status = BleConnectionStatus.Scanning,
                devices = emptyList()
            )
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _uiState.update { it.copy(status = BleConnectionStatus.Error("BLE scanner unavailable")) }
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            isScanningInternal = true
            scanner.startScan(null, settings, scanCallback)
            scanTimeoutJob?.cancel()
            scanTimeoutJob = scope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (isScanningInternal) {
                    stopScanInternal()
                    if (_uiState.value.status is BleConnectionStatus.Scanning) {
                        _uiState.update { st ->
                            st.copy(
                                status = if (st.devices.isEmpty()) BleConnectionStatus.Idle else st.status
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            isScanningInternal = false
            _uiState.update { it.copy(status = BleConnectionStatus.Error(e.message ?: "Scan failed")) }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        stopScanInternal()
        if (_uiState.value.status is BleConnectionStatus.Scanning) {
            _uiState.update { it.copy(status = BleConnectionStatus.Idle) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        if (!isScanningInternal) return
        isScanningInternal = false
        if (hasBlePermissions() && isBluetoothEnabled()) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(item: ScannedBleDevice) {
        if (!hasBlePermissions()) return
        stopScanInternal()
        reconnectJob?.cancel()
        wantConnection = true
        retryCount = 0
        lastDevice = item.device
        lastDeviceName = item.name.ifBlank { item.address }
        openGattConnection(item.device, isReconnect = false)
    }

    @SuppressLint("MissingPermission")
    private fun openGattConnection(device: BluetoothDevice, isReconnect: Boolean) {
        if (!hasBlePermissions()) {
            _uiState.update { it.copy(status = BleConnectionStatus.Disconnected) }
            return
        }
        closeActiveGatt()
        val displayName = lastDeviceName
        if (!isReconnect) {
            _uiState.update { it.copy(status = BleConnectionStatus.Connecting(displayName)) }
        }
        try {
            activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(status = BleConnectionStatus.Error(e.message ?: "Connection error")) }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        wantConnection = false
        retryCount = 0
        reconnectJob?.cancel()
        reconnectJob = null
        stopScanInternal()
        closeActiveGatt()
        onSignalLost()
        _uiState.update {
            it.copy(
                status = if (isBleHardwareSupported()) BleConnectionStatus.Disconnected else BleConnectionStatus.Unavailable
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeActiveGatt() {
        val g = activeGatt
        activeGatt = null
        if (g != null) {
            try {
                g.disconnect()
            } catch (_: Exception) {
            }
            try {
                g.close()
            } catch (_: Exception) {
            }
        }
    }

    fun release() {
        disconnect()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val record = result.scanRecord
            val rawName = try {
                device.name ?: record?.deviceName ?: ""
            } catch (_: SecurityException) {
                record?.deviceName ?: ""
            }

            val hasNusUuid = record?.serviceUuids?.any { it.uuid == NUS_SERVICE_UUID } == true
            val lowerName = rawName.lowercase()
            val nameMatches = lowerName.contains("mask") ||
                lowerName.contains("tomb") ||
                lowerName.contains("pocket") ||
                lowerName.contains("esp") ||
                lowerName.contains("nus") ||
                lowerName.contains("uart")

            val isCandidate = hasNusUuid || nameMatches
            if (!isCandidate && rawName.isBlank()) {
                // Skip unnamed non-candidate beacons to keep the scanner list clean
                return
            }

            val displayName = rawName.ifBlank { "ESP32 ($address)" }
            val entry = ScannedBleDevice(
                address = address,
                name = displayName,
                rssi = result.rssi,
                isPocketConsoleCandidate = isCandidate,
                device = device
            )
            discoveredMap[address] = entry
            _uiState.update { state ->
                state.copy(devices = filteredDeviceList(state.showAllDevices))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanningInternal = false
            _uiState.update {
                it.copy(status = BleConnectionStatus.Error("BLE scan error ($errorCode)"))
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                retryCount = 0
                val mtuRequested = try {
                    gatt.requestMtu(247)
                } catch (_: Exception) {
                    false
                }
                if (!mtuRequested) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (activeGatt === gatt) {
                    activeGatt = null
                }
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                onSignalLost()

                val target = lastDevice
                if (wantConnection && target != null && retryCount < MAX_RECONNECT_ATTEMPTS && hasBlePermissions() && isBluetoothEnabled()) {
                    val attempt = retryCount + 1
                    retryCount = attempt
                    val waitMs = min(4000L, 700L * attempt)
                    _uiState.update {
                        it.copy(status = BleConnectionStatus.Reconnecting(attempt, lastDeviceName))
                    }
                    reconnectJob?.cancel()
                    reconnectJob = scope.launch {
                        delay(waitMs)
                        if (wantConnection) {
                            openGattConnection(target, isReconnect = true)
                        }
                    }
                } else {
                    wantConnection = false
                    _uiState.update { it.copy(status = BleConnectionStatus.Disconnected) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.disconnect()
                return
            }
            val service = gatt.getService(NUS_SERVICE_UUID)
            val txChar = service?.getCharacteristic(NUS_TX_UUID)
            if (txChar == null) {
                wantConnection = false
                closeActiveGatt()
                _uiState.update {
                    it.copy(status = BleConnectionStatus.Error("Nordic UART service not found on device"))
                }
                return
            }

            gatt.setCharacteristicNotification(txChar, true)
            val cccd = txChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                val props = txChar.properties
                val enableValue = if (
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
                    (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
                ) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, enableValue)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = enableValue
                        gatt.writeDescriptor(cccd)
                    }
                }
            }

            _uiState.update {
                it.copy(
                    status = BleConnectionStatus.Connected(
                        deviceName = lastDeviceName,
                        address = gatt.device?.address.orEmpty()
                    )
                )
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == NUS_TX_UUID && value.isNotEmpty()) {
                onPacketReceived(value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == NUS_TX_UUID) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value
                if (bytes != null && bytes.isNotEmpty()) {
                    onPacketReceived(bytes)
                }
            }
        }
    }
}
