package io.sensor_prod.sensor.ui.pages.home

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Environment
import android.util.Log
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


class HomeViewModel : ViewModel() {

//    private var mLogTimestamp: Long = 0

    private var mSensors: MutableList<ModelHomeSensor> = mutableListOf()

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
//        Pair(Sensor.TYPE_ACCELEROMETER, true)
        Pair(Sensor.TYPE_MAGNETIC_FIELD, true)
    )

    //    TODO use this in future private val mSensorPacketsMap = mutableMapOf<Int, ModelSensorPacket>()
    private val mChartDataManagerMap = mutableMapOf<Int, MpChartDataManager>()

  /*  private val _mSensorPacketFlow = MutableSharedFlow<ModelChartUiUpdate>(replay = 0)
    val mSensorPacketFlow = _mSensorPacketFlow.asSharedFlow()
*/

    init {
//        Log.d("HomeViewModel", "viewmodel init")

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
//                mSensorsList.emit(_mSensorsList)

                /* emitUiState()*/

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
    private fun startCsvLogging() {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (!dir.exists()) dir.mkdirs() // Ensure directory exists

        csvFile = File(dir, "sensor_data.csv")
        val isNewFile = !csvFile!!.exists()

        writer = BufferedWriter(FileWriter(csvFile, true)) // Append mode
        if (isNewFile) {
            val header = StringBuilder("Date,Timestamp,SensorType")

            var maxAxisCount = 3
            for (i in 0 until SensorsConstants.MAP_TYPE_TO_AXIS_COUNT.size) {
                val axisCount = SensorsConstants.MAP_TYPE_TO_AXIS_COUNT.valueAt(i)
                if (axisCount > maxAxisCount) {
                    maxAxisCount = axisCount
                }
            }
            for (i in 0 until maxAxisCount) {
                header.append(",Value${i+1}")
            }
            header.append("\n")
            writer?.write(header.toString())
        }

        isLogging.value = true
        Log.d("CSV", "Logging Started: ${csvFile?.absolutePath}")
    }

    private fun stopCsvLogging() {
        isLogging.value = false
        writer?.flush()
        writer?.close()
        Log.d("CSV", "Logging Stopped")
    }
    @SuppressLint("DefaultLocale")
    private fun logSensorData(sensorType: Int, values: FloatArray?) {
        if (isLogging.value && values != null) {
            val timestamp = System.currentTimeMillis()
            val sensorName = SensorsConstants.MAP_TYPE_TO_NAME[sensorType] ?: "Unknown"
            val valuesString = values.joinToString(",") { String.format("%.6f", it) }
            val axisCount = SensorsConstants.MAP_TYPE_TO_AXIS_COUNT[sensorType]

            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd,HH:mm:ss.SSS", java.util.Locale.getDefault())
            val formattedDate = dateFormat.format(java.util.Date(timestamp))

            val csvLine = StringBuilder()
            csvLine.append("$formattedDate,$sensorName")

            for (i in 0 until axisCount) {
                csvLine.append(",${if (i < values.size) values[i] else ""}")
            }

            csvLine.append("\n")

            writer?.write(csvLine.toString())
            writer?.flush()

            Log.d("CSV", "Logging: $csvLine")
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
//            Log.d("HomeViewModel", "onSensorChecked: Index: $index $isChecked")
            var sensor = mSensors[index]
            var updatedSensor =
                ModelHomeSensor(sensor.type, sensor.sensor, sensor.info, sensor.valueRms, isChecked)
            mSensors[index] = updatedSensor

            mSensorsList[index] = updatedSensor
            updateActiveSensor(updatedSensor, isChecked)

        }
        /*viewModelScope.launch {
            emitUiState()

        }*/

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

    fun toggleVideoRecording() {
        if (isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
        isRecording.value = !isRecording.value
    }

    private fun startRecording() {
        // Implement CameraX or MediaRecorder logic
        Log.d("Video", "Recording Started")
    }

    private fun stopRecording() {
        // Stop MediaRecorder
        Log.d("Video", "Recording Stopped")
    }


    @Suppress("UNCHECKED_CAST")
    class Factory() : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel() as T
        }
    }


}