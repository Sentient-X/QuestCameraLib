package com.samusynth.questcamera.io

import android.media.MediaRecorder
import android.view.Surface
import android.util.Log
import com.samusynth.questcamera.core.ISurfaceProvider
import java.io.File

/** MediaRecorder-backed surface for continuous camera video capture. */
class VideoRecorderSurfaceProvider(
    private val width: Int,
    private val height: Int,
    outputFilePath: String,
    private val frameRate: Int,
    private val bitrateMbps: Int,
    private val iFrameIntervalSeconds: Int,
    private val enableAudio: Boolean,
    private val audioBitrate: Int,
    private val audioSamplingRate: Int,
) : ISurfaceProvider, AutoCloseable {
    private val lock = Any()
    private var outputFile = File(outputFilePath)
    private var recorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var isRecording = false

    init { rebuildRecorderLocked() }

    override fun getSurface(): Surface = synchronized(lock) {
        return recorderSurface ?: error("Recorder surface is not initialized")
    }

    fun updateOutputFile(path: String) = synchronized(lock) {
        if (isRecording) error("Cannot change video output while recording")
        outputFile = File(path)
        rebuildRecorderLocked()
    }

    fun startRecording() = synchronized(lock) {
        val currentRecorder = recorder ?: return@synchronized
        currentRecorder.start()
        isRecording = true
    }

    fun stopRecording() = synchronized(lock) {
        if (!isRecording) return@synchronized
        try { recorder?.stop() } finally {
            isRecording = false
            releaseRecorderLocked()
        }
    }

    override fun close() = synchronized(lock) {
        if (isRecording) {
            try { recorder?.stop() } catch (e: Exception) { Log.w(TAG, "Recorder stop failed", e) }
            isRecording = false
        }
        releaseRecorderLocked()
    }

    private fun rebuildRecorderLocked() {
        releaseRecorderLocked()
        outputFile.parentFile?.mkdirs()
        val mediaRecorder = MediaRecorder()
        if (enableAudio) {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (enableAudio) {
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(audioBitrate)
            mediaRecorder.setAudioSamplingRate(audioSamplingRate)
        }
        mediaRecorder.setVideoSize(width, height)
        mediaRecorder.setVideoFrameRate(frameRate.coerceAtLeast(1))
        mediaRecorder.setVideoEncodingBitRate(bitrateMbps.coerceAtLeast(1) * 1_000_000)
        // Some Android SDK stubs omit this legacy MediaRecorder API even though
        // it is implemented by Quest's runtime. Invoke it when available.
        runCatching {
            MediaRecorder::class.java
                .getMethod("setVideoEncodingIFrameInterval", Int::class.javaPrimitiveType)
                .invoke(mediaRecorder, iFrameIntervalSeconds.coerceAtLeast(1))
        }.onFailure { Log.w(TAG, "I-frame interval API unavailable", it) }
        mediaRecorder.setOutputFile(outputFile.absolutePath)
        mediaRecorder.setOrientationHint(0)
        mediaRecorder.prepare()
        recorder = mediaRecorder
        recorderSurface = mediaRecorder.surface
    }

    private fun releaseRecorderLocked() {
        recorderSurface?.release()
        recorderSurface = null
        recorder?.reset()
        recorder?.release()
        recorder = null
    }

    companion object { private const val TAG = "VideoRecorderSurfaceProvider" }
}
