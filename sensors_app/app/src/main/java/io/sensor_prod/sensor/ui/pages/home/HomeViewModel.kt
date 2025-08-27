package io.sensor_prod.sensor.ui.pages.home

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import androidx.core.util.size
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


class HomeViewModel : ViewModel() {

//    private var mLogTimestamp: Long = 0

    private var mSensors: MutableList<ModelHomeSensor> = mutableListOf()
    private var lastDetectionTime = 0L
    private val cooldownMillis = 5000L // 5 seconds


    // Prophet vars
    private lateinit var interpreter: Interpreter
    private val buffer = ArrayDeque<Float>()
    private val WINDOW_SIZE = 10
    private val THRESH_MULTIPLIER = 2f
    private var threshold = 0f
    private val errorBuffer = mutableListOf<Float>()

    fun initModel(context: Context) {
        val model = loadModelFile(context, "model_rms.tflite")
        interpreter = Interpreter(model)
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }
    fun startMonitoringSensors() {
        viewModelScope.launch {
            while (isActive) {
                checkPotholeFromSensors()
                delay(500L)
            }
        }
    }


    private val gyroscopeValues = mutableListOf<Float>()

    private val sensorEventListener = object : SensorEventListener {
        @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]

                    // Calculate the magnitude of angular velocity
                    val magnitude = sqrt(x * x + y * y + z * z)

                    // Maintain a window of values
                    if (gyroscopeValues.size >= WINDOW_SIZE) {
                        gyroscopeValues.removeFirst()
                    }
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
        if (now - lastDetectionTime < cooldownMillis) return  // 🚫 Early return if in cooldown

        if (gyroscopeValues.size < WINDOW_SIZE) return

        // Step 1: Calculate RMS
        val rms = sqrt(gyroscopeValues.map { it * it }.average().toFloat())

        // Step 2: Update buffer
        if (buffer.size >= WINDOW_SIZE) buffer.removeFirst()
        buffer.addLast(rms)

        if (buffer.size == WINDOW_SIZE) {
            // Step 3: Prepare input
            val input = Array(1) { Array(WINDOW_SIZE) { FloatArray(1) } }
            buffer.forEachIndexed { i, value -> input[0][i][0] = value }

            // Step 4: Run model
            val output = Array(1) { FloatArray(1) }
            interpreter.run(input, output)

            val predicted = output[0][0]
            val error = abs(predicted - rms)

            // Step 5: Threshold logic
            updateThreshold(error)
            val potholeDetected = error > threshold

            if (potholeDetected) {
                lastDetectionTime = now  // ✅ Cooldown begins now
                _potholeDetected.value = true

                Log.d("POTHOLE", "Anomaly Detected! Predicted=$predicted, RMS=$rms, Error=$error, Threshold=$threshold")
            } else {
                _potholeDetected.value = false
            }
        }
    }

    private fun updateThreshold(latestError: Float) {
        errorBuffer.add(latestError)
        if (errorBuffer.size > 100) errorBuffer.removeAt(0)

        val mean = errorBuffer.average().toFloat()
        val std = sqrt(errorBuffer.map { (it - mean).pow(2) }.average().toFloat())
        threshold = mean + THRESH_MULTIPLIER * std
    }

    // Game UI state
    private val _uiState = MutableStateFlow(HomeUiState())

    // Backing property to avoid state updates from other classes
    val mUiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /* private val _uiPagerState = MutableStateFlow(HomeUiState())
     // Backing property to avoid state updates from other classes
     val mUiPagerState: StateFlow<HomeUiState> = _uiPagerState.asStateFlow()
 */

    private val _mSensorsList = mutableStateListOf<ModelHomeSensor>()
    val mSensorsList: SnapshotStateList<ModelHomeSensor> = _mSensorsList

  /*  private val _mUiCurrentSensorState = MutableStateFlow<ModelHomeSensor?>(null)
    val mUiCurrentSensorState: StateFlow<ModelHomeSensor?> = _mUiCurrentSensorState.asStateFlow()
*/



    private val _mActiveSensorListFlow = MutableStateFlow<MutableList<ModelHomeSensor>>(
        mutableListOf()
    )
    val mActiveSensorListFlow: StateFlow<MutableList<ModelHomeSensor>> = _mActiveSensorListFlow
    private val _mActiveSensorList = mutableListOf<ModelHomeSensor>()

    private val mIsActiveMap = mutableMapOf<Int, Boolean>(
        Pair(Sensor.TYPE_GYROSCOPE, true),
    )

    //    TODO use this in future private val mSensorPacketsMap = mutableMapOf<Int, ModelSensorPacket>()
    private val mChartDataManagerMap = mutableMapOf<Int, MpChartDataManager>()

  /*  private val _mSensorPacketFlow = MutableSharedFlow<ModelChartUiUpdate>(replay = 0)
    val mSensorPacketFlow = _mSensorPacketFlow.asSharedFlow()
*/

    init {
//        Log.d("HomeViewModel", "viewmodel init")

        viewModelScope.launch {
            startMonitoringSensors()
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
//                Log.d("HomeViewModel","${this@HomeViewModel} init sensors active  1: $mIsActiveMap")

//                Log.d("HomeViewModel", "sensors 2: $it")
                if (_mSensorsList.size == 0) {
                    _mSensorsList.addAll(mSensors)
                    var activeSensors = it.filter { modelHomeSensor -> modelHomeSensor.isActive }
//                     _mActiveSensorStateList.addAll(activeSensors)
                    _mActiveSensorList.addAll(activeSensors)
                    _mActiveSensorListFlow.emit(_mActiveSensorList)
                    getInitialChartData()
                    initializeFlow()
                }

            }

        }
//        Log.d("HomeViewModel", "viewmodel init 2")
        SensorsProvider.getInstance().listenSensors()


        /*
        TODO use this for packets
        SensorPacketsProvider.getInstance().mSensorPacketFlow.map { value ->
            mSensorPacketsMap.put(value.type, value)
        }*/
    }

    private fun getInitialChartData() {
        for (sensor in _mActiveSensorList) {
//            Log.d("HomeViewModel", "getInitialChartData")
            getChartDataManager(sensor.type)
        }
    }

    // Logging to CSV

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
            val header = StringBuilder("Date,Time,SensorType")
            var maxAxisCount = 3
            for (i in 0 until SensorsConstants.MAP_TYPE_TO_AXIS_COUNT.size) {
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
            // Close old
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
            // Open new
            currentDateString = today
            openWriterForDate(today)
        }
    }

    private fun startCsvLogging() {
        // Initialize for today
        val now = System.currentTimeMillis()
        rotateIfNeeded(now)
        isLogging.value = true
    }

    private fun stopCsvLogging() {
        isLogging.value = false
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
        csvFile = null
        Log.d("CSV", "Logging Stopped")
    }

    private fun logSensorData(sensorType: Int, values: FloatArray?) {
        if (isLogging.value && values != null) {
            val now = System.currentTimeMillis()
            rotateIfNeeded(now)

            val dateStr = dateFormatterIST.format(Date(now))
            val timeStr = timeFormatterIST.format(Date(now))
            val sensorName = SensorsConstants.MAP_TYPE_TO_NAME[sensorType] ?: "Unknown"
            val axisCount = SensorsConstants.MAP_TYPE_TO_AXIS_COUNT[sensorType]

            val csvLine = StringBuilder()
            csvLine.append("$dateStr,$timeStr,$sensorName")
            for (i in 0 until axisCount) {
                val v = if (i < values.size) String.format(Locale.US, "%.6f", values[i]) else ""
                csvLine.append(",").append(v)
            }
            csvLine.append("\n")

            writer?.write(csvLine.toString())
        }
    }

    fun toggleCsvLogging() {
        if (isLogging.value) stopCsvLogging() else startCsvLogging()
    }

    private fun initializeFlow() {

        var sensorPacketFlow =
            SensorPacketsProvider.getInstance().mSensorPacketFlow

        for (sensor in _mActiveSensorList) {
            attachPacketListener(sensor);
        }


        viewModelScope.launch {
            sensorPacketFlow.collect {
//                    Log.d("SensorViewModel", "init mSensorPacketFlow 2: ")
//                    Log.d("SensorViewModel", "addEntry: ${it.timestamp}")

                /* if( it.timestamp - mLogTimestamp < 50){
                     Log.d("SensorViewModel", "addEntry: ${it.timestamp}")

                 }*/

//                mLogTimestamp = it.timestamp
                logSensorData(it.type, it.values)
                mChartDataManagerMap[it.type]?.addEntry(it)
//                mChartDataManager?.addEntry(it)
            }
        }


//        Log.d("HomeViewModel", "map size: ${mChartDataManagerMap.size}")
        mChartDataManagerMap.forEach { (_, mpChartDataManager) ->
            viewModelScope.launch {
                mpChartDataManager.runPeriodically()
//                Log.d("HomeViewModel", "map size 2222: ${mChartDataManagerMap.size}")

                /*   mpChartDataManager.mSensorPacketFlow.collect {

   //                Log.d("SensorViewModel", "init mSensorPacketFlow: ${it.timestamp} ${it.size} ")
                       _mSensorPacketFlow.emit(it)
                   }*/
            }
            /* for (chartDataManager in mChartDataManagerMap.values.iterator()) {

             }*/

        }
    }

    private fun attachPacketListener(sensor: ModelHomeSensor) {

//        Log.d("HomeViewModel", "attachPacketListener: $sensor")
        SensorPacketsProvider.getInstance().attachSensor(
            SensorPacketConfig(sensor.type, SensorManager.SENSOR_DELAY_NORMAL)
        )
    }

    private fun detachPacketListener(sensor: ModelHomeSensor) {
        SensorPacketsProvider.getInstance().detachSensor(
            sensor.type
        )
    }

    fun onSensorChecked(type: Int, isChecked: Boolean) {
        var isCheckedPrev = mIsActiveMap.getOrDefault(type, false)

        if (isCheckedPrev != isChecked) {
            mIsActiveMap[type] = isChecked
        }

        var index = mSensors.indexOfFirst { it.type == type }
        if (index >= 0) {
            var sensor = mSensors[index]
            var updatedSensor =
                ModelHomeSensor(sensor.type, sensor.sensor, sensor.info, sensor.valueRms, isChecked)
            mSensors[index] = updatedSensor

            mSensorsList[index] = updatedSensor
            updateActiveSensor(updatedSensor, isChecked)

        }
    }

    private fun updateActiveSensor(sensor: ModelHomeSensor, isChecked: Boolean = false) {
        var index = _mActiveSensorList.indexOfFirst { it.type == sensor.type }

        if (!isChecked && index >= 0) {
            var manager = mChartDataManagerMap.remove(sensor.type)
            manager?.destroy()
//            _mActiveSensorStateList.removeAt(index)
            detachPacketListener(sensor)


            _mActiveSensorList.removeAt(index)
            viewModelScope.launch {

                _mActiveSensorListFlow.emit(_mActiveSensorList)
            }

        } else if (isChecked && index < 0) {
//            _mActiveSensorStateList.add(sensor)

            _mActiveSensorList.add(sensor)
            attachPacketListener(sensor)
            viewModelScope.launch {

                _mActiveSensorListFlow.emit(_mActiveSensorList)
            }
            getChartDataManager(type = sensor.type).runPeriodically()
        }
    }


    fun getChartDataManager(type: Int): MpChartDataManager {
        var chartDataManager = mChartDataManagerMap.getOrPut(type, defaultValue = {
            MpChartDataManager(type, onDestroy = {
            })
        })
        Log.d("HomeViewModel", "getChartDataManager: $type")
        return chartDataManager
    }



    fun setActivePage(page: Int?) {

        viewModelScope.launch {
//            Log.d("HomeViewModel", "page: $page")
            if (page != null && _mActiveSensorList.size > 0) {
                if(_mActiveSensorList.size > page){
                    var sensor = _mActiveSensorList[page]
//                _mUiCurrentSensorState.emit(sensor)
                    _uiState.emit(
                        _uiState.value.copy(
                            currentSensor = sensor,
                            activeSensorCounts = _mActiveSensorList.size
                        )
                    )
                }else{
                    var sensor = _mActiveSensorList[_mActiveSensorList.size-1]
                    _uiState.emit(
                        _uiState.value.copy(
                            currentSensor = sensor,
                            activeSensorCounts = _mActiveSensorList.size
                        )
                    )
                }

            } else {
//                _mUiCurrentSensorState.emit(null)
                _uiState.emit(
                    _uiState.value.copy(
                        currentSensor = null,
                        activeSensorCounts = _mActiveSensorList.size
                    )
                )

            }
        }
    }

    override fun onCleared() {
        super.onCleared()

//        Log.d("HomeViewModel", "onCleared")
        mChartDataManagerMap.forEach { (_, mpChartDataManager) -> mpChartDataManager.destroy() }
    }

    private var isRecording = MutableStateFlow(false)



    @Suppress("UNCHECKED_CAST")
    class Factory() : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel() as T
        }
    }
}

