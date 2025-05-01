package io.sensor_prod.sensor.ui.pages.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.util.Consumer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CameraViewModel : ViewModel() {
    var isRecording = false
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var recording: Recording? = null
    private val executor = Executors.newCachedThreadPool()
    @SuppressLint("StaticFieldLeak")
    private lateinit var context: Context
    private var isTriggerRecordingInProgress = false

    // Utility functions
    fun initialize(context: Context, videoCapture: VideoCapture<Recorder>) {
        this.context = context
        this.videoCapture = videoCapture
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleVideoRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
        isRecording = !isRecording
    }

    private var isClipping = false
    private var clippingJob: Job? = null
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecordingClips() {
        if (isClipping) {
            // Stop clipping
            isClipping = false
            clippingJob?.cancel()
            stopRecording()
        } else {
            // Start clipping
            isClipping = true
            clippingJob = viewModelScope.launch {
                while (isActive && isClipping) {
                    startRecording()
                    delay(6000)
                    stopRecording()
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun triggerEventRecording() {
        if (isTriggerRecordingInProgress) return

        isTriggerRecordingInProgress = true
        viewModelScope.launch(Dispatchers.IO) {
            startRecordingClips() // this one stops the process
            try {
                if (isRecording) {
                    recording?.stop()
                }

                delay(500)

                val targetFolder = "Movies/trigger_recordings"

                // Ensure target folder is recognized/mounted
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID),
                    "${MediaStore.Video.Media.RELATIVE_PATH} = ?",
                    arrayOf(targetFolder),
                    null
                )?.close()

                val latestUris = getLatestVideos(context, 2)

                latestUris.forEachIndexed { index, uri ->
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HH:mm:ss", Locale.US).format(Date())
                        val triggerFileName = "trigger_${index}_$timestamp.mp4"

                        val contentValues = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, triggerFileName)
                            put(MediaStore.Video.Media.RELATIVE_PATH, targetFolder)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        }

                        val triggerUri = context.contentResolver.insert(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            contentValues
                        )

                        triggerUri?.let { destUri ->
                            context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                                inputStream.copyTo(outputStream)
                                Log.d("TriggerSave", "Saved trigger clip as: $triggerFileName")
                            }
                            inputStream.close()
                        }
                    } else {
                        Log.e("TriggerSave", "Failed to open input stream for video at index $index")
                    }
                }

                if (!isRecording && !isClipping) {
                    startRecordingClips()
                }
            } catch (e: Exception) {
                Log.e("TriggerSave", "Exception during trigger save: ${e.message}", e)
            } finally {
                delay(2000)
                isTriggerRecordingInProgress = false
            }
        }
    }

    // Helper Functions

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if(isRecording == true) {
                    recording?.stop()
                }
                val result = captureVideo(videoCapture, context)

                CoroutineScope(Dispatchers.Main).launch {
//                    Toast.makeText(context, "Recording started", Toast.LENGTH_SHORT).show()
                }

                val pendingRecording = result.first
                val captureListener = result.second

                recording = pendingRecording.start(executor, captureListener)
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error starting video recording", e)
                isRecording = false
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null

        CoroutineScope(Dispatchers.Main).launch {
//            Toast.makeText(context, "Recording stopped", Toast.LENGTH_SHORT).show()
        }
    }
    private fun getLatestVideos(context: Context, count: Int): List<Uri> {
        val uris = mutableListOf<Uri>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("Movies/dashcam_/")


        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC" // ✅ Removed LIMIT

        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (it.moveToNext() && uris.size < count) {
                val id = it.getLong(idIndex)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                uris.add(uri)
            }
        }

        return uris
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun captureVideo(
        videoCapture: VideoCapture<Recorder>,
        context: Context
    ): Pair<PendingRecording, Consumer<VideoRecordEvent>> {

        val timestamp = SimpleDateFormat("yyyyMMdd_HH:mm:ss", Locale.US).format(Date())
        val fileName = "clip_$timestamp.mp4"
        val targetFolder = "Movies/dashcam_"

        deleteOldestVideosIfNeeded(context, targetFolder, maxFiles = 15)

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, targetFolder)
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val captureListener = Consumer<VideoRecordEvent> { event ->
            if (event is VideoRecordEvent.Finalize) {
                if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                    Log.e("CameraViewModel", "Recording failed: ${event.cause}")
                } else {
                    Log.d("CameraViewModel", "Saved recording: $fileName")
                }
            }
        }

        val recording = videoCapture.output
            .prepareRecording(context, mediaStoreOutput)
            .withAudioEnabled()

        return Pair(recording, captureListener)
    }
    private fun deleteOldestVideosIfNeeded(context: Context, relativePath: String, maxFiles: Int) {
        val videoList = mutableListOf<Triple<Uri, Long, String>>() // Triple: URI, date, name

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DISPLAY_NAME
        )

        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("$relativePath/")

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} ASC" // Oldest first

        val query = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        query?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val date = cursor.getLong(dateCol)
                val name = cursor.getString(nameCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                videoList.add(Triple(uri, date, name))
            }
        }
        
        if (videoList.size > maxFiles) {
            val toDeleteCount = minOf(10, videoList.size - maxFiles)
            val toDelete = videoList.take(toDeleteCount)

            toDelete.forEach {
                context.contentResolver.delete(it.first, null, null)
                Log.d("CameraViewModel", "Deleted old video: ${it.third}") // file name
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdown()
    }
    @Suppress("UNCHECKED_CAST")
    class Factory() : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CameraViewModel() as T
        }
    }
}
