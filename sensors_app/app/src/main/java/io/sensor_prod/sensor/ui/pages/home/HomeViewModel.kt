package io.sensor_prod.sensor.ui.pages.home

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.sensor_prod.sensor.domains.chart.mpchart.MpChartDataManager
import io.sensor_prod.sensor.domains.sensors.SensorsConstants
import io.sensor_prod.sensor.domains.sensors.packets.SensorPacketConfig
import io.sensor_prod.sensor.domains.sensors.packets.SensorPacketsProvider
import io.sensor_prod.sensor.domains.sensors.provider.SensorsProvider
import io.sensor_prod.sensor.ui.pages.home.model.ModelHomeSensor
import io.sensor_prod.sensor.ui.pages.home.state.HomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
// BLE
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.IOException
import java.util.UUID


class HomeViewModel : ViewModel() {

    private var mSensors: MutableList<ModelHomeSensor> = mutableListOf()
    private var lastDetectionTime = 0L
    private val cooldownMillis = 5000L // 5 seconds

    // Adjustable RMS threshold
    private val WINDOW_SIZE = 10
    val thresholdFlow = MutableStateFlow(4f)
    fun setThreshold(value: Float) { thresholdFlow.value = value }

    private val gyroscopeValues = mutableListOf<Float>()

    private val sensorEventListener = object : SensorEventListener {
        @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    val magnitude = sqrt(x * x + y * y + z * z)
                    if (gyroscopeValues.size >= WINDOW_SIZE) gyroscopeValues.removeFirst()
                    gyroscopeValues.add(magnitude)
                    checkPotholeFromSensors()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val _potholeDetected = MutableStateFlow(false)
    val potholeDetected: StateFlow<Boolean> = _potholeDetected.asStateFlow()

    fun startGyroListening(context: Context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager.registerListener(sensorEventListener, gyroscope, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun checkPotholeFromSensors() {
        val now = System.currentTimeMillis()
        if (now - lastDetectionTime < cooldownMillis) return
        if (gyroscopeValues.size < WINDOW_SIZE) return
        val rms = sqrt(gyroscopeValues.map { it * it }.average().toFloat())
        val pothole = rms > thresholdFlow.value
        if (pothole) {
            lastDetectionTime = now
            _potholeDetected.value = true
            Log.d("POTHOLE", "RMS threshold exceeded. RMS=$rms, threshold=${thresholdFlow.value}")
        } else {
            _potholeDetected.value = false
        }
    }

    // Game UI state
    private val _uiState = MutableStateFlow(HomeUiState())
    val mUiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _mSensorsList = mutableStateListOf<ModelHomeSensor>()
    val mSensorsList: SnapshotStateList<ModelHomeSensor> = _mSensorsList

    private val _mActiveSensorListFlow = MutableStateFlow<MutableList<ModelHomeSensor>>(mutableListOf())
    val mActiveSensorListFlow: StateFlow<MutableList<ModelHomeSensor>> = _mActiveSensorListFlow
    private val _mActiveSensorList = mutableListOf<ModelHomeSensor>()

    private val mIsActiveMap = mutableMapOf<Int, Boolean>(Pair(Sensor.TYPE_GYROSCOPE, true))
    private val mChartDataManagerMap = mutableMapOf<Int, MpChartDataManager>()

    init {
        viewModelScope.launch {
            SensorsProvider.getInstance().mSensorsFlow.map { value ->
                value.map {
                    ModelHomeSensor(
                        it.type,
                        it.sensor,
                        it.info,
                        0f,
                        mIsActiveMap.getOrDefault(it.type, false)
                    )
                }.toMutableList()
            }.collectLatest {
                mSensors = it
                if (_mSensorsList.size == 0) {
                    _mSensorsList.addAll(mSensors)
                    val activeSensors = it.filter { modelHomeSensor -> modelHomeSensor.isActive }
                    _mActiveSensorList.addAll(activeSensors)
                    _mActiveSensorListFlow.emit(_mActiveSensorList)
                    getInitialChartData()
                    initializeFlow()
                }
            }
        }
        SensorsProvider.getInstance().listenSensors()
    }

    private fun getInitialChartData() { for (sensor in _mActiveSensorList) { getChartDataManager(sensor.type) } }

    // Logging to CSV (sensors)
    private var csvFile: File? = null
    private var writer: BufferedWriter? = null
    private var isLogging = MutableStateFlow(false)
    private var currentDateString: String? = null
    private val tzIST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private val dateFormatterIST = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tzIST }
    private val timeFormatterIST = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = tzIST }

    private fun baseCsvDir(): File {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val folder = File(docs, "SensifyCSV")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }
    private fun fileForDate(dateStr: String): File = File(baseCsvDir(), "sensor_data_${dateStr}.csv")

    private fun openWriterForDate(dateStr: String) {
        val file = fileForDate(dateStr)
        val isNew = !file.exists()
        writer = BufferedWriter(FileWriter(file, true))
        csvFile = file
        if (isNew) {
            val header = StringBuilder("Time,SensorType")
            var maxAxisCount = 3
            for (i in 0 until SensorsConstants.MAP_TYPE_TO_AXIS_COUNT.size()) {
                val axisCount = SensorsConstants.MAP_TYPE_TO_AXIS_COUNT.valueAt(i)
                if (axisCount > maxAxisCount) maxAxisCount = axisCount
            }
            for (i in 0 until maxAxisCount) header.append(",Value${i + 1}")
            header.append("\n")
            writer?.write(header.toString())
        }
        Log.d("CSV", "Logging to: ${file.absolutePath}")
    }

    private fun rotateIfNeeded(nowMs: Long) {
        val today = dateFormatterIST.format(Date(nowMs))
        if (currentDateString == null || currentDateString != today || writer == null) {
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
            currentDateString = today
            openWriterForDate(today)
        }
    }

    private fun startCsvLogging() { val now = System.currentTimeMillis(); rotateIfNeeded(now); isLogging.value = true }
    private fun stopCsvLogging() { isLogging.value = false; try { writer?.flush(); writer?.close() } catch (_: Exception) {}; writer = null; csvFile = null; Log.d("CSV", "Logging Stopped") }
    fun toggleCsvLogging() { if (isLogging.value) stopCsvLogging() else startCsvLogging() }

    private fun logSensorData(sensorType: Int, values: FloatArray?) {
        if (isLogging.value && values != null) {
            val now = System.currentTimeMillis()
            rotateIfNeeded(now)
            val timeStr = timeFormatterIST.format(Date(now))
            val sensorName = SensorsConstants.MAP_TYPE_TO_NAME[sensorType] ?: "Unknown"
            val axisCount = SensorsConstants.MAP_TYPE_TO_AXIS_COUNT[sensorType]
            val csvLine = StringBuilder()
            csvLine.append("$timeStr,$sensorName")
            for (i in 0 until axisCount) {
                val v = if (i < values.size) String.format(Locale.US, "%.6f", values[i]) else ""
                csvLine.append(",").append(v)
            }
            csvLine.append("\n")
            writer?.write(csvLine.toString())
        }
    }

    private fun initializeFlow() {
        val sensorPacketFlow = SensorPacketsProvider.getInstance().mSensorPacketFlow
        for (sensor in _mActiveSensorList) { attachPacketListener(sensor) }
        viewModelScope.launch { sensorPacketFlow.collect { logSensorData(it.type, it.values); mChartDataManagerMap[it.type]?.addEntry(it) } }
        mChartDataManagerMap.forEach { (_, mpChartDataManager) -> viewModelScope.launch { mpChartDataManager.runPeriodically() } }
    }

    private fun attachPacketListener(sensor: ModelHomeSensor) { SensorPacketsProvider.getInstance().attachSensor(SensorPacketConfig(sensor.type, SensorManager.SENSOR_DELAY_NORMAL)) }
    private fun detachPacketListener(sensor: ModelHomeSensor) { SensorPacketsProvider.getInstance().detachSensor(sensor.type) }

    fun onSensorChecked(type: Int, isChecked: Boolean) {
        val isCheckedPrev = mIsActiveMap.getOrDefault(type, false)
        if (isCheckedPrev != isChecked) mIsActiveMap[type] = isChecked
        val index = mSensors.indexOfFirst { it.type == type }
        if (index >= 0) {
            val sensor = mSensors[index]
            val updatedSensor = ModelHomeSensor(sensor.type, sensor.sensor, sensor.info, sensor.valueRms, isChecked)
            mSensors[index] = updatedSensor
            mSensorsList[index] = updatedSensor
            updateActiveSensor(updatedSensor, isChecked)
        }
    }

    private fun updateActiveSensor(sensor: ModelHomeSensor, isChecked: Boolean = false) {
        val index = _mActiveSensorList.indexOfFirst { it.type == sensor.type }
        if (!isChecked && index >= 0) {
            val manager = mChartDataManagerMap.remove(sensor.type)
            manager?.destroy()
            detachPacketListener(sensor)
            _mActiveSensorList.removeAt(index)
            viewModelScope.launch { _mActiveSensorListFlow.emit(_mActiveSensorList) }
        } else if (isChecked && index < 0) {
            _mActiveSensorList.add(sensor)
            attachPacketListener(sensor)
            viewModelScope.launch { _mActiveSensorListFlow.emit(_mActiveSensorList) }
            getChartDataManager(type = sensor.type).runPeriodically()
        }
    }

    fun getChartDataManager(type: Int): MpChartDataManager {
        val chartDataManager = mChartDataManagerMap.getOrPut(type, defaultValue = { MpChartDataManager(type, onDestroy = { }) })
        Log.d("HomeViewModel", "getChartDataManager: $type")
        return chartDataManager
    }

    fun setActivePage(page: Int?) {
        viewModelScope.launch {
            if (page != null && _mActiveSensorList.size > 0) {
                if(_mActiveSensorList.size > page){
                    val sensor = _mActiveSensorList[page]
                    _uiState.emit(_uiState.value.copy(currentSensor = sensor, activeSensorCounts = _mActiveSensorList.size))
                }else{
                    val sensor = _mActiveSensorList[_mActiveSensorList.size-1]
                    _uiState.emit(_uiState.value.copy(currentSensor = sensor, activeSensorCounts = _mActiveSensorList.size))
                }
            } else {
                _uiState.emit(_uiState.value.copy(currentSensor = null, activeSensorCounts = _mActiveSensorList.size))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Close BLE on clear
        disconnectBle()
        mChartDataManagerMap.forEach { (_, mpChartDataManager) -> mpChartDataManager.destroy() }
    }

    // ===== BLE integration (migrated) =====
    val bleConnectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    private var bleSocket: BluetoothSocket? = null
    private var bleReceiverJob: Job? = null
    val bleLogging = MutableStateFlow(false)

    private var bleWriter: BufferedWriter? = null
    private var bleCurrentDateString: String? = null
    private fun bleFileForDate(dateStr: String): File = File(baseCsvDir(), "bluetooth_data_${dateStr}.csv")
    private fun bleOpenWriterForDate(dateStr: String) {
        val file = bleFileForDate(dateStr)
        val isNew = !file.exists()
        bleWriter = BufferedWriter(FileWriter(file, true))
        if (isNew) { bleWriter?.write("Time,Data\n") }
    }
    private fun bleRotateIfNeeded(nowMs: Long) {
        val today = dateFormatterIST.format(Date(nowMs))
        if (bleCurrentDateString == null || bleCurrentDateString != today || bleWriter == null) {
            try { bleWriter?.flush(); bleWriter?.close() } catch (_: Exception) {}
            bleCurrentDateString = today
            bleOpenWriterForDate(today)
        }
    }

    fun toggleBleLogging() { bleLogging.value = !bleLogging.value }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectBle(device: BluetoothDevice, onError: ((Exception) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Close any previous
                disconnectBle()
                val socket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                socket.connect()
                bleSocket = socket
                bleConnectedDevice.emit(device)
                bleReceiverJob = launch(Dispatchers.IO) { receiveBleData(socket) }
            } catch (e: Exception) {
                onError?.invoke(e as? Exception ?: RuntimeException("BLE error"))
            }
        }
    }

    fun disconnectBle() {
        try { bleReceiverJob?.cancel() } catch (_: Exception) {}
        bleReceiverJob = null
        try { bleSocket?.close() } catch (_: Exception) {}
        bleSocket = null
        viewModelScope.launch { bleConnectedDevice.emit(null) }
        try { bleWriter?.flush(); bleWriter?.close() } catch (_: Exception) {}
        bleWriter = null
        bleCurrentDateString = null
    }

    private suspend fun receiveBleData(socket: BluetoothSocket) {
        try {
            val input: InputStream = socket.inputStream
            val buffer = ByteArray(1024)
            var carry = ""
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead <= 0) continue
                val chunk = String(buffer, 0, bytesRead)
                val combined = carry + chunk
                val lines = combined.split('\n')
                for (i in 0 until lines.size - 1) { handleBleLine(lines[i].trim()) }
                carry = lines.last()
            }
        } catch (e: IOException) {
            Log.e("BLE", "receive error: ${e.message}")
        }
    }

    private fun handleBleLine(line: String) {
        if (line.isEmpty()) return
        if (bleLogging.value) {
            val now = System.currentTimeMillis()
            bleRotateIfNeeded(now)
            val timeStr = timeFormatterIST.format(Date(now))
            try { bleWriter?.write("$timeStr,$line\n") } catch (e: Exception) { Log.e("BLE", "write fail: ${e.message}") }
        }
    }

    companion object {
        // Simple factory so callers can use: viewModel(factory = HomeViewModel.Factory)
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel() as T
            }
        }
    }
}
