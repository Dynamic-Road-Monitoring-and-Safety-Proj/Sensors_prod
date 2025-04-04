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
import androidx.core.util.Consumer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class CameraViewModel : ViewModel() {
    private var isRecording = false
    private lateinit var videoCapture: VideoCapture<Recorder>
    private val uriList: MutableList<Uri> = mutableListOf()
    private var recording: Recording? = null
    private val executor = Executors.newCachedThreadPool()
    @SuppressLint("StaticFieldLeak")
    private lateinit var context: Context

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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = captureVideo(videoCapture, context, uriList)

                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(context, "Recording started", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "Recording stopped", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun captureVideo(
        videoCapture: VideoCapture<Recorder>,
        context: Context,
        uriList: MutableList<Uri>
    ): Pair<PendingRecording, Consumer<VideoRecordEvent>> {
        val name: String

        if (uriList.size >= 6) {
            // Circular buffer: when there are 6 videos, delete the oldest and rename the others
            deleteOldestVideo(context, uriList)
            renameVideos(context, uriList)  // Renames from 0.mp4 to 4.mp4
            name = "5.mp4"  // New video will be named "5.mp4"
        } else {
            // If the list size is less than 6, name videos sequentially from "0.mp4" to "4.mp4"
            name = "${uriList.size}.mp4"
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
                    Log.d("CameraViewModel", "Recording started")
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                        Log.d("CameraViewModel", "Video recording succeeded: ${event.outputResults.outputUri}")

                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, "URI is ${event.outputResults.outputUri}", Toast.LENGTH_LONG).show()
                        }

                        val videoUri = event.outputResults.outputUri
                        // Now add this new video to the list
                        uriList.add(videoUri)
                    } else {
                        Log.e("CameraViewModel", "Video recording failed: ${event.cause}")
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
