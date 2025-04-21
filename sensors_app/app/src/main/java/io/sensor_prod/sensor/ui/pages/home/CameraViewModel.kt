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
    private val uriList: MutableList<Uri> = mutableListOf()
    private var recording: Recording? = null
    private val executor = Executors.newCachedThreadPool()
    @SuppressLint("StaticFieldLeak")
    private lateinit var context: Context
    private var isTriggerRecordingInProgress = false

    // Utility functions
    fun initialize(context: Context, videoCapture: VideoCapture<Recorder>) {
        this.context = context
        this.videoCapture = videoCapture
        initializeUriList(context, uriList)
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
        if (isTriggerRecordingInProgress) {
            // Optionally log or notify that a trigger is already in progress
            return
        }
        isTriggerRecordingInProgress = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Stop current recording safely
                if (isRecording) {
                    recording?.stop()
                }

                delay(500) // Small buffer to ensure video is finalized

                val copiedUris = uriList.takeLast(2)
                copiedUris.forEachIndexed { index, uri ->
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val timestamp = System.currentTimeMillis()
                        val fileName = "trigger_clip_${index}_$timestamp.mp4"

                        val contentValues = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/trigger_recordings")
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        }

                        val triggerUri = context.contentResolver.insert(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            contentValues
                        )

                        triggerUri?.let { destUri ->
                            context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                                inputStream.copyTo(outputStream)
                                Log.d("TriggerSave", "Saved trigger clip as: $fileName")
                            }
                            inputStream.close()
                        }
                    } else {
                        Log.e("TriggerSave", "Failed to open input stream for URI: $uri")
                    }
                }

                // Resume normal recording
                if (!isRecording && !isClipping) {
                    startRecording()
                }
            } catch (e: Exception) {
                Log.e("TriggerSave", "Exception during trigger clip save: ${e.message}", e)
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
                val result = captureVideo(videoCapture, context, uriList)

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
        context: Context,
        uriList: MutableList<Uri>
    ): Pair<PendingRecording, Consumer<VideoRecordEvent>> {

        val name = if (uriList.size >= 6) {
            deleteOldestVideo(context, uriList)
            renameVideos(context, uriList)
            getNextAvailableFileName(context)
        } else {
            getNextAvailableFileName(context)
        }

        val existingUri = findVideoUriByName(context, name)
        if (existingUri != null) {
            context.contentResolver.delete(existingUri, null, null)  // Delete existing file
            Log.d("CameraViewModel", "Deleted existing video: $name")
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
//                    Log.d("CameraViewModel", "Recording started")
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                        uriList.add(event.outputResults.outputUri)
                    } else {
                        Log.e("CameraViewModel", "Recording failed: ${event.cause}")
                        refreshUriList(context, uriList)
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
    private fun refreshUriList(context: Context, uriList: MutableList<Uri>) {
        uriList.clear()
        for (i in 0..5) {
            findVideoUriByName(context, "$i.mp4")?.let { uriList.add(it) }
        }
    }
    private fun getNextAvailableFileName(context: Context): String {
        val usedNames = mutableSetOf<String>()
        for (i in 0..5) {
            if (findVideoUriByName(context, "$i.mp4") != null) {
                usedNames.add("$i.mp4")
            }
        }
        for (i in 0..5) {
            val name = "$i.mp4"
            if (!usedNames.contains(name)) return name
        }
        return "0.mp4" // fallback
    }


    private fun deleteOldestVideo(context: Context, uriList: MutableList<Uri>) {
        if (uriList.isNotEmpty()) {
            val oldestUri = uriList.first()
            try {
                context.contentResolver.openInputStream(oldestUri)?.close()
                val deleted = context.contentResolver.delete(oldestUri, null, null)
                if (deleted > 0) {
                    Log.d("DeleteVideo", "Deleted: $oldestUri")
                    uriList.removeAt(0)
                } else {
                    Log.e("DeleteVideo", "Delete failed: $oldestUri")
                }
            } catch (e: Exception) {
                Log.e("DeleteVideo", "File not found, reinitializing list", e)
                refreshUriList(context, uriList)
            }
        }
    }
    private fun renameVideos(context: Context, uriList: MutableList<Uri>) {
        val updatedList = mutableListOf<Uri>()
        for ((index, uri) in uriList.withIndex()) {
            val newName = "$index.mp4"
            try {
                context.contentResolver.openInputStream(uri)?.close()
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, newName)
                }
                val updated = context.contentResolver.update(uri, contentValues, null, null)
                if (updated > 0) {
                    Log.d("RenameVideos", "Renamed to $newName")
                    findVideoUriByName(context, newName)?.let { updatedList.add(it) }
                }
            } catch (e: Exception) {
                Log.e("RenameVideos", "Failed to rename: $uri", e)
            }
        }
        uriList.clear()
        uriList.addAll(updatedList)
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
