package io.sensor_prod.sensor.ui.pages.home

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.camera.video.FileOutputOptions
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    // private val CLIPS_RELATIVE_PATH = "Movies/sensors_clips/"
    private val TRIGGERS_RELATIVE_PATH = "Movies/trigger_recordings/"
    // private val FRAMES_RELATIVE_PATH = "Pictures/trigger_frames/"
    private val RING_CAPACITY = 6

    // IST formatter for filenames
    private val tzIST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private val tsFormatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss.SSS", Locale.US).apply { timeZone = tzIST }

    // Utility functions
    fun initialize(context: Context, videoCapture: VideoCapture<Recorder>) {
        // Hold application context to avoid leaks
        this.context = context.applicationContext
        this.videoCapture = videoCapture
        ensureClipsDir().mkdirs()
    }

    private fun ensureClipsDir(): File = File(context.externalCacheDir, "video_ring")

    private var isClipping = false
    private var clippingJob: Job? = null

    fun startRecordingClips() {
        if (isClipping) {
            // Stop clipping
            isClipping = false
            clippingJob?.cancel()
            stopRecording()
        } else {
            // Start clipping using precise 1s segments: wait for Start -> delay -> Stop -> wait Finalize
            isClipping = true
            clippingJob = viewModelScope.launch {
                while (isActive && isClipping) {
                    try {
                        recordSegment(durationMs = 1000L)
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Segment error", e)
                        // Small backoff to avoid tight loop on repeated errors
                        delay(100)
                    }
                }
            }
        }
    }

    private suspend fun recordSegment(durationMs: Long) {
        if (isRecording) return
        val startSignal = CompletableDeferred<Unit>()
        val finalizeSignal = CompletableDeferred<Unit>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pending = preparePendingRecording()
                recording = pending.start(executor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> { isRecording = true; if (!startSignal.isCompleted) startSignal.complete(Unit) }
                        is VideoRecordEvent.Finalize -> {
                            if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) { Log.e("CameraViewModel", "Recording failed: ${event.cause}") }
                            advanceRingIndex(); isRecording = false; if (!finalizeSignal.isCompleted) finalizeSignal.complete(Unit)
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error preparing recording", e)
                if (!startSignal.isCompleted) startSignal.completeExceptionally(e)
                if (!finalizeSignal.isCompleted) finalizeSignal.completeExceptionally(e)
            }
        }
        startSignal.await(); delay(durationMs); stopRecording(); finalizeSignal.await()
    }

    private fun currentRingFile(): File {
        val ringIndex = getRingIndex()
        val name = String.format(Locale.US, "clip_%02d.mp4", ringIndex)
        return File(ensureClipsDir(), name)
    }

    private fun preparePendingRecording(): PendingRecording {
        val outFile = currentRingFile()
        if (outFile.exists()) outFile.delete()
        val fileOutput = FileOutputOptions.Builder(outFile).build()
        return videoCapture.output.prepareRecording(context, fileOutput)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun triggerEventRecording() {
        if (isTriggerRecordingInProgress) return
        isTriggerRecordingInProgress = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isRecording) recording?.stop()
                delay(600)
                val timestampStr = tsFormatter.format(Date(System.currentTimeMillis()))
                val segments = latestClipFiles(limit = 5)
                if (segments.isEmpty()) { Log.w("TriggerSave", "No segments to combine"); return@launch }

                val triggerName = "trigger_${timestampStr}.mp4"
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, triggerName)
                    put(MediaStore.Video.Media.RELATIVE_PATH, TRIGGERS_RELATIVE_PATH)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                }
                val destUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                if (destUri != null) {
                    combineSegmentsToUri(segments, destUri)
                    Log.d("TriggerSave", "Saved trigger: $triggerName")
                } else {
                    Log.e("TriggerSave", "Failed to create trigger in MediaStore")
                }
            } catch (e: Exception) {
                Log.e("TriggerSave", "Exception: ${e.message}", e)
            } finally { isTriggerRecordingInProgress = false }
        }
    }

    private fun latestClipFiles(limit: Int): List<File> {
        val dir = ensureClipsDir()
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("clip_") && f.name.endsWith(".mp4") }?.toList() ?: emptyList()
        return files.filter { it.exists() && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .sortedBy { it.lastModified() }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun combineSegmentsToUri(segments: List<File>, destUri: Uri) {
        val pfd = context.contentResolver.openFileDescriptor(destUri, "rw") ?: return
        val fd = pfd.fileDescriptor
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var haveAudio = false
            var timeOffsetUs = 0L
            segments.forEachIndexed { _, file ->
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                var inVideoTrack = -1
                var inAudioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/") && inVideoTrack == -1) {
                        inVideoTrack = i
                        if (videoTrackIndex == -1) videoTrackIndex = muxer.addTrack(format)
                    } else if (mime.startsWith("audio/") && inAudioTrack == -1) {
                        inAudioTrack = i; haveAudio = true
                        if (audioTrackIndex == -1) audioTrackIndex = muxer.addTrack(format)
                    }
                }
                if (videoTrackIndex == -1 && inVideoTrack == -1) { extractor.release(); return@forEachIndexed }
                if (timeOffsetUs == 0L) muxer.start()
                val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
                val info = MediaCodec.BufferInfo()
                if (inVideoTrack != -1) {
                    extractor.selectTrack(inVideoTrack)
                    var eos = false
                    while (!eos) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { eos = true } else {
                            info.size = size; info.offset = 0; info.flags = extractor.sampleFlags
                            val pts = extractor.sampleTime
                            info.presentationTimeUs = (if (pts > 0) pts else 0) + timeOffsetUs
                            muxer.writeSampleData(videoTrackIndex, buffer, info)
                            extractor.advance()
                        }
                    }
                    extractor.unselectTrack(inVideoTrack)
                }
                if (haveAudio && inAudioTrack != -1) {
                    extractor.selectTrack(inAudioTrack)
                    var eos = false
                    while (!eos) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { eos = true } else {
                            info.size = size; info.offset = 0; info.flags = extractor.sampleFlags
                            val pts = extractor.sampleTime
                            info.presentationTimeUs = (if (pts > 0) pts else 0) + timeOffsetUs
                            muxer.writeSampleData(audioTrackIndex, buffer, info)
                            extractor.advance()
                        }
                    }
                    extractor.unselectTrack(inAudioTrack)
                }
                timeOffsetUs += estimateDurationUs(file)
                extractor.release()
            }
        } catch (e: Exception) { Log.e("Muxer", "combine error", e) }
        finally { try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}; try { pfd.close() } catch (_: Exception) {} }
    }

    private fun estimateDurationUs(file: File): Long {
        return try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val dMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L
            mmr.release(); dMs * 1000L
        } catch (e: Exception) { 1_000_000L }
    }

    // Remove frame extraction, image saving, and MediaStore-based ring helpers
    // private fun extractFramesFromClips(...) { }
    // private fun saveFrameBitmap(...) { }
    // private fun queryLatestClipUris(...) { }
    // private fun findVideoUriByName(...) { }
    // private fun captureVideo(...) { }
    // private fun startRecording() { }

    private fun stopRecording() { recording?.stop(); recording = null }

    private fun getRingIndex(): Int {
        val prefs = context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("clip_index", 0).coerceIn(0, RING_CAPACITY - 1)
    }

    private fun advanceRingIndex() {
        val prefs = context.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
        val next = (getRingIndex() + 1) % RING_CAPACITY
        prefs.edit { putInt("clip_index", next) }
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
