package io.sensor_prod.sensor.ui.pages.home

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.edit
import androidx.core.util.Consumer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class CameraViewModel : ViewModel() {
    var isRecording = false
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var recording: Recording? = null
    // Use a single-thread executor to reduce scheduling overhead and ensure ordering
    private val executor = Executors.newSingleThreadExecutor()
    @SuppressLint("StaticFieldLeak")
    private lateinit var context: Context
    private var isTriggerRecordingInProgress = false

    // MediaStore paths and ring buffer (RELATIVE_PATH expects a trailing slash)
    private val CLIPS_RELATIVE_PATH = "Movies/sensors_clips/"
    private val TRIGGERS_RELATIVE_PATH = "Movies/trigger_recordings/"
    private val FRAMES_RELATIVE_PATH = "Pictures/trigger_frames/"
    private val RING_CAPACITY = 6

    // IST formatter for filenames
    private val tzIST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private val tsFormatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss.SSS", Locale.US).apply { timeZone = tzIST }

    // Utility functions
    fun initialize(context: Context, videoCapture: VideoCapture<Recorder>) {
        // Hold application context to avoid leaks
        this.context = context.applicationContext
        this.videoCapture = videoCapture
    }

    private var isClipping = false
    private var clippingJob: Job? = null

    fun startRecordingClips() {
        if (isClipping) {
            // Stop clipping
            isClipping = false
            clippingJob?.cancel()
            stopRecording()
        } else {
            // Start clipping (loop, one 1s segment at a time; wait for finalize before next)
            isClipping = true
            clippingJob = viewModelScope.launch {
                while (isActive && isClipping) {
                    startRecording()
                    // Target ~1s segments
                    delay(1000)
                    stopRecording()
                    // Wait until finalize callback flips the flag
                    var waited = 0
                    while (isRecording && waited < 2000 && isActive && isClipping) {
                        delay(50)
                        waited += 50
                    }
                }
            }
        }
    }

    fun triggerEventRecording() {
        if (isTriggerRecordingInProgress) return

        isTriggerRecordingInProgress = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Stop current recording to finalize the most recent segment
                if (isRecording) {
                    recording?.stop()
                }

                // Give the system a moment to finalize and index the clip
                delay(600)

                val timestampStr = tsFormatter.format(Date(System.currentTimeMillis()))

                // Copy the latest 5 clips from the clips folder with timestamped names
                val latestClipUris = queryLatestClipUris(context, CLIPS_RELATIVE_PATH, limit = 5)
                latestClipUris.forEachIndexed { index, srcUri ->
                    try {
                        context.contentResolver.openInputStream(srcUri)?.use { inputStream ->
                            val triggerFileName = "trigger_clip_${index}_${timestampStr}.mp4"

                            val contentValues = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, triggerFileName)
                                put(MediaStore.Video.Media.RELATIVE_PATH, TRIGGERS_RELATIVE_PATH)
                                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            }

                            val destUri = context.contentResolver.insert(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            )

                            if (destUri != null) {
                                context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                                Log.d("TriggerSave", "Saved trigger clip as: $triggerFileName")
                            } else {
                                Log.e("TriggerSave", "Failed to create destination for $triggerFileName")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("TriggerSave", "Failed copying clip index=$index", e)
                    }
                }

                // Extract 30 evenly spaced frames across the last ~5 seconds (6 per 1s segment)
                extractFramesFromClips(latestClipUris, timestampStr)

                // Optionally resume recording if clipping was not enabled
                if (!isRecording && !isClipping) {
                    startRecording()
                }
            } catch (e: Exception) {
                Log.e("TriggerSave", "Exception during trigger save: ${e.message}", e)
            } finally {
                delay(300)
                isTriggerRecordingInProgress = false
            }
        }
    }

    private fun extractFramesFromClips(urisDesc: List<Uri>, timestampTag: String) {
        if (urisDesc.isEmpty()) return
        // urisDesc are newest first; process oldest->newest for order
        val uris = urisDesc.asReversed()
        val retriever = MediaMetadataRetriever()
        try {
            uris.forEachIndexed { fileIdx, uri ->
                try {
                    retriever.setDataSource(context, uri)
                    val durationMsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationMsStr?.toLongOrNull() ?: 1000L
                    val framesPerFile = 6 // 6 * 5 files = 30 frames
                    for (i in 0 until framesPerFile) {
                        val tUs = ((i + 1) * (durationMs * 1000L) / (framesPerFile + 1)).coerceAtLeast(1L)
                        val bmp = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (bmp != null) {
                            saveFrameBitmap(bmp, "trigger_frame_${timestampTag}_${fileIdx}_${i}.jpg")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FrameExtract", "Failed on uri index=$fileIdx", e)
                }
            }
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun saveFrameBitmap(bitmap: Bitmap, fileName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.RELATIVE_PATH, FRAMES_RELATIVE_PATH)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.e("FrameSave", "Failed to create image uri for $fileName")
            bitmap.recycle()
            return
        }
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            Log.d("FrameSave", "Saved frame: $fileName")
        } catch (e: Exception) {
            Log.e("FrameSave", "Failed saving $fileName", e)
        } finally {
            bitmap.recycle()
        }
    }

    // Helper Functions

    private fun startRecording() {
        // Avoid duplicate starts
        if (isRecording) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = captureVideo(videoCapture, context)
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
        // Stop will trigger Finalize; don't flip isRecording here
        recording?.stop()
        recording = null
    }

    private fun captureVideo(
        videoCapture: VideoCapture<Recorder>,
        context: Context
    ): Pair<PendingRecording, Consumer<VideoRecordEvent>> {

        // Determine ring slot and file name
        val ringIndex = getRingIndex()
        val name = String.format(Locale.US, "clip_%02d.mp4", ringIndex)

        // Ensure previous file with the same name in our folder is removed to prevent  (1).mp4 suffixes
        findVideoUriByName(context, name, CLIPS_RELATIVE_PATH)?.let {
            kotlin.runCatching {
                context.contentResolver.delete(it, null, null)
                Log.d("CameraViewModel", "Deleted existing video: $name")
            }.onFailure { e ->
                Log.e("CameraViewModel", "Failed to delete existing video $name", e)
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.RELATIVE_PATH, CLIPS_RELATIVE_PATH)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val captureListener = Consumer<VideoRecordEvent> { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        Log.e("CameraViewModel", "Recording failed: ${event.cause}")
                    }
                    // Advance ring index on finalize so next clip uses the next slot
                    advanceRingIndex()
                    isRecording = false
                }
                else -> { /* no-op */ }
            }
        }

        val recording = videoCapture.output
            .prepareRecording(context, mediaStoreOutput)
            // Audio disabled to save power and avoid permission requirement
            //.withAudioEnabled()

        return Pair(recording, captureListener)
    }

    // Ring index stored in SharedPreferences
    private fun getRingIndex(): Int {
        val prefs = context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("clip_index", 0).coerceIn(0, RING_CAPACITY - 1)
    }

    private fun advanceRingIndex() {
        val prefs = context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
        val next = (getRingIndex() + 1) % RING_CAPACITY
        prefs.edit { putInt("clip_index", next) }
    }

    private fun queryLatestClipUris(context: Context, relativePath: String, limit: Int): List<Uri> {
        val result = mutableListOf<Uri>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(relativePath)
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                result.add(uri)
                count++
            }
        }
        return result
    }

    private fun findVideoUriByName(context: Context, fileName: String, relativePath: String): Uri? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, relativePath)
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
