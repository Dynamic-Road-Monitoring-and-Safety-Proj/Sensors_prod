package io.sensor_prod.sensor.ui.pages.home

import android.Manifest
import android.annotation.SuppressLint
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
    fun ensureFolderExists(context: Context, relativePath: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ".nomedia")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }

        val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.close()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun triggerEventRecording() {
        ensureFolderExists(context, "Movies/trigger_recordings")

        if (isTriggerRecordingInProgress) return

        isTriggerRecordingInProgress = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isRecording) {
                    recording?.stop()
                }

                delay(500)

                val targetFolder = "Movies/trigger_recordings"

                // (Optional) Pre-trigger a query to help ensure the folder exists/mounts correctly
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID),
                    "${MediaStore.Video.Media.RELATIVE_PATH} = ?",
                    arrayOf(targetFolder),
                    null
                )?.close()

                listOf("4.mp4", "5.mp4").forEachIndexed { index, fileName ->
                    val uri = findVideoUriByName(context, fileName)
                    if (uri != null) {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            val timestamp = System.currentTimeMillis()
                            val triggerFileName = "trigger_clip_${index}_$timestamp.mp4"

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
                            Log.e("TriggerSave", "Failed to open input stream for $fileName")
                        }
                    } else {
                        Log.e("TriggerSave", "URI not found for $fileName")
                    }
                }

                if (!isRecording && !isClipping) {
                    startRecording()
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun captureVideo(
        videoCapture: VideoCapture<Recorder>,
        context: Context
    ): Pair<PendingRecording, Consumer<VideoRecordEvent>> {

        val name = if (getUsedFileNames(context).size >= 6) {
            deleteOldestVideo(context)
            renameVideos(context)
            getNextAvailableFileName(context)
        } else {
            getNextAvailableFileName(context)
        }

        findVideoUriByName(context, name)?.let {
            context.contentResolver.delete(it, null, null)
            Log.d("CameraViewModel", "Deleted existing video: $name")
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val captureListener = Consumer<VideoRecordEvent> { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> {
                    if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        Log.e("CameraViewModel", "Recording failed: ${event.cause}")
                    }
                }
                else -> { /* Handle if needed */ }
            }
        }

        val recording = videoCapture.output
            .prepareRecording(context, mediaStoreOutput)
            .withAudioEnabled()

        return Pair(recording, captureListener)
    }

    private fun deleteOldestVideo(context: Context) {
        val oldestUri = (0..5).firstNotNullOfOrNull { findVideoUriByName(context, "$it.mp4") }
        oldestUri?.let {
            try {
                context.contentResolver.openInputStream(it)?.close()
                val deleted = context.contentResolver.delete(it, null, null)
                if (deleted > 0) Log.d("DeleteVideo", "Deleted: $it")
                else Log.e("DeleteVideo", "Delete failed: $it")
            } catch (e: Exception) {
                Log.e("DeleteVideo", "Error deleting video: $it", e)
            }
        }
    }

    private fun renameVideos(context: Context) {
        val existingUris = (0..5).mapNotNull { findVideoUriByName(context, "$it.mp4") }

        existingUris.forEachIndexed { index, uri ->
            try {
                val newName = "$index.mp4"
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, newName)
                }
                val updated = context.contentResolver.update(uri, contentValues, null, null)
                if (updated > 0) Log.d("RenameVideos", "Renamed to $newName")
            } catch (e: Exception) {
                Log.e("RenameVideos", "Failed to rename: $uri", e)
            }
        }
    }

    private fun getNextAvailableFileName(context: Context): String {
        val usedNames = getUsedFileNames(context)
        return (0..5).map { "$it.mp4" }.firstOrNull { it !in usedNames } ?: "0.mp4"
    }

    private fun getUsedFileNames(context: Context): Set<String> =
        (0..5).mapNotNull { i ->
            val name = "$i.mp4"
            if (findVideoUriByName(context, name) != null) name else null
        }.toSet()

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
