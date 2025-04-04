package io.sensor_prod.sensor.ui.pages.home

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.SensorManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

private fun initializeUriList(context: Context, uriList: MutableList<Uri>) {
    // Clear the list to avoid duplicates
    uriList.clear()

    // Check for files named 0.mp4 to 5.mp4
    for (i in 0..5) {
        val fileName = "$i.mp4"
        val fileUri = findVideoUriByName(context, fileName)

        // If a file exists, add it to the uriList
        if (fileUri != null) {
            uriList.add(fileUri)
            Log.d("InitializeUriList", "Added existing video: $fileName")
        }
    }
    Log.d("InitializeUriList", "URI list initialized with ${uriList.size} items.")
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewScreen(
    sensorManager: SensorManager,
    fusedLocationClient: FusedLocationProviderClient,
    Modifier: Modifier
) {
    val uriList : MutableList<Uri> = mutableListOf()

    var lensFacing = CameraSelector.LENS_FACING_BACK
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    initializeUriList(context, uriList)
    val cameraxSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
    var recording by remember { mutableStateOf<PendingRecording?>(null) }
    var onRecording by remember { mutableStateOf<Recording?>(null) }
    val recBuilder = Recorder.Builder()
    val qualitySelector = QualitySelector.fromOrderedList(
        listOf(Quality.FHD, Quality.HD, Quality.HIGHEST)
    )

    val recorder = recBuilder.setQualitySelector(qualitySelector).build()
    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)

    LaunchedEffect(lensFacing) {
        val cameraProvider = context.getCameraProvider()
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraxSelector, videoCapture)
    }
    val scope = rememberCoroutineScope()
    val scaffoldState = rememberBottomSheetScaffoldState()

    val executor = Executors.newCachedThreadPool()
    var captureListener : Consumer<VideoRecordEvent>

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                while (true) {
                    async {
                        val result = captureVideo(videoCapture, context, uriList)

                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "starting", Toast.LENGTH_SHORT).show()
                        }

                        captureListener = result.second
                        recording = result.first
                        onRecording = recording?.start(
                            executor,
                            captureListener
                        )

                        delay(10000)

                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "stopping", Toast.LENGTH_SHORT).show()
                        }

                        onRecording?.stop()

                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "stopped", Toast.LENGTH_SHORT).show()
                        }

                        delay(1000)
                    }.await()
                }
            } catch (e: Exception) {
                Log.e("CameraPreviewScreen", "Error starting video recording", e)
            }
        }
    }


    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 0.dp,
        sheetContent = {
            SensorSheetContent2(sensorManager = sensorManager, fusedLocationClient = fusedLocationClient, modifier = Modifier)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column{
                IconButton(
                    onClick = {
                        lensFacing =
                            if (lensFacing == LENS_FACING_BACK) {
                                LENS_FACING_FRONT
                            } else LENS_FACING_BACK
                    },
                    modifier = Modifier.offset(16.dp, 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Switch camera"
                    )
                }
                Spacer(modifier = Modifier.size(20.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            scaffoldState.bottomSheetState.expand()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Open gallery"
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            onRecording?.stop() // Stop the current recording

                            delay(1000) // Small delay to ensure stop completes

                            if (uriList.size >= 6) {
                                val uri = uriList[0]
                                val contentResolver = context.contentResolver
                                val deleted = contentResolver.delete(uriList[0], null, null)
                                if (deleted > 0) {
                                    Log.d("DeleteVideo", "Video deleted successfully: $uri")
                                } else {
                                    Log.e("DeleteVideo", "Failed to delete video: $uri")
                                }
                                uriList.removeAt(0)
                            }

                            // Add the last recorded video to the list (handled in the captureListener already)
                            val result = captureVideo(videoCapture, context, uriList)
                            captureListener = result.second
                            recording = result.first

                            // Start a new recording
                            onRecording = recording?.start(
                                executor,
                                captureListener
                            )
                        }

                        val currentTime = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(Date())
                        val dcimDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                        val subfolder = File(dcimDirectory, "RS-TM")
                        // Ensure the directory exists
                        if (!subfolder.exists()) {
                            subfolder.mkdirs()
                        }

                        val outputPath = File(subfolder, "Video_$currentTime.mp4").absolutePath

                        val inputs = uriList.joinToString("|") { "-i $it" }
                        val filter = uriList.indices.joinToString(";") { "[${it}:v:0]" } + "concat=n=${uriList.size}:v=1:a=0[outv]"
                        val command = "$inputs -filter_complex \"$filter\" -map \"[outv]\" $outputPath"

                        Log.e("sdaf", "_____________________________${uriList.size}    : $uriList")
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Record Last 30"
                    )
                }
            }
        }
    }
}

@Composable
fun SensorSheetContent2(sensorManager: SensorManager, fusedLocationClient : FusedLocationProviderClient, modifier: Modifier) {
    // Initialize sensor Data class
    val sensorData = SensorData()

    fun changeGyroData(x: Float, y: Float, z: Float) { //also time
        sensorData.gyroscopeData = Triple(x, y, z)
        sensorData.timestamp = Timestamp(System.currentTimeMillis())
    }
    fun changeAccData(x: Float, y: Float, z: Float) {
        sensorData.accelerometerData = Triple(x, y, z)
    }
    fun changeMagData(x: Float, y: Float, z: Float) {
        sensorData.magneticData = Triple(x, y, z)
    }
    fun changeLightData(light: Float) {
        sensorData.lightData = light
    }
    fun changeLocationData(location: android.location.Location) {
        sensorData.locationData = location
    }
    GyroscopeScreen(modifier = modifier, sensorManager = sensorManager, function = ::changeGyroData)
    AccelerometerScreen(modifier = modifier, sensorManager, ::changeAccData)
    LightScreenComp(
        modifier = modifier,
        sensorManager = sensorManager,
        function = :: changeLightData
    )
    LocationScreen(fusedLocationClient = fusedLocationClient, function = ::changeLocationData)
    MagFieldScreen(modifier, sensorManager, ::changeMagData)
}
private fun deleteOldestVideo(context: Context, uriList: MutableList<Uri>) {
    if (uriList.isNotEmpty()) {
        val oldestUri = uriList[0]
        val deleted = context.contentResolver.delete(oldestUri, null, null)
        if (deleted > 0) {
            Log.d("DeleteVideo", "Deleted oldest video: $oldestUri")
            uriList.removeAt(0)
        } else {
            Log.e("DeleteVideo", "Failed to delete oldest video: $oldestUri")
        }
    }
}
private fun renameVideos(context: Context, uriList: MutableList<Uri>) {
    for (i in uriList.indices) {
        val oldUri = uriList[i]
        val newName = "$i.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, newName)
        }

        try {
            // Ensure the file exists before renaming
            context.contentResolver.openInputStream(oldUri)?.close() ?: run {
                Log.e("RenameVideos", "File does not exist, skipping rename: $oldUri")
            }

            context.contentResolver.update(oldUri, contentValues, null, null)
            Log.d("RenameVideos", "Renamed file: $oldUri to $newName")
        } catch (e: Exception) {
            Log.e("RenameVideos", "Error renaming video: $oldUri", e)
        }
    }
}

private fun findVideoUriByName(context: Context, fileName: String): Uri? {
    val projection = arrayOf(MediaStore.Video.Media._ID)
    val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ?"
    val selectionArgs = arrayOf(fileName)

    val queryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
            return Uri.withAppendedPath(queryUri, id.toString())
        }
    }
    return null
}

@SuppressLint("MissingPermission")
private fun captureVideo(
    videoCapture: VideoCapture<Recorder>,
    context: Context,
    URIlist: MutableList<Uri>
): Pair<PendingRecording, Consumer<VideoRecordEvent>> {

    val name: String

    if (URIlist.size >= 6) {
        // Circular buffer: when there are 6 videos, delete the oldest and rename the others
        deleteOldestVideo(context, URIlist)
        renameVideos(context, URIlist)  // Renames from 0.mp4 to 4.mp4
        name = "5.mp4"  // New video will be named "5.mp4"
    } else {
        // If the list size is less than 6, name videos sequentially from "0.mp4" to "4.mp4"
        name = "${URIlist.size}.mp4"
    }

    val existingUri = findVideoUriByName(context, name)
    if (existingUri != null) {
        context.contentResolver.delete(existingUri, null, null)  // Delete existing file
        Log.d("CameraScreen", "Deleted existing video: $name")
    }

    val contentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
    }

    val mediaStoreOutput = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )
        .setContentValues(contentValues)
        .build()

    val captureListener = Consumer<VideoRecordEvent> { event ->
        when (event) {
            is VideoRecordEvent.Start -> {
                Log.d("CameraScreen", "Recording started")
            }
            is VideoRecordEvent.Finalize -> {
                if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                    Log.d("CameraScreen", "Video recording succeeded: ${event.outputResults.outputUri}")

                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(context, "URI is ${event.outputResults.outputUri}", Toast.LENGTH_LONG).show()
                    }

                    val videoUri = event.outputResults.outputUri
                    // Now add this new video to the list
                    URIlist.add(videoUri)
                } else {
                    Log.e("CameraScreen", "Video recording failed: ${event.cause}")
                }
            }
            else -> {
                // Handle other events if needed
            }
        }
    }

    val recording = videoCapture.output
        .prepareRecording(context, mediaStoreOutput)
        .withAudioEnabled()
    return Pair(recording, captureListener)
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { continuation ->
        ProcessCameraProvider.getInstance(this).also { cameraProvider ->
            cameraProvider.addListener({
                continuation.resume(cameraProvider.get())
            }, ContextCompat.getMainExecutor(this))
        }
    }