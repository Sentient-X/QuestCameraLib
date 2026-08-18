package com.samusynth.questcamera.io

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.samusynth.questcamera.core.CameraSessionManager
import com.samusynth.questcamera.core.ISurfaceProvider
import java.io.BufferedWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Camera2 surface provider backed by a fixed-rate MediaCodec/EGL pipeline.
 *
 * Camera2 is requested at [CameraSessionManager.SOURCE_FPS_RANGE] ([60,60]);
 * the Quest 3S HAL answers with 50 fps on one 20 ms lattice shared by both
 * cameras, whose SENSOR_TIMESTAMPs are identical. Camera2 therefore targets a
 * SurfaceTexture rather than the encoder directly. The EGL worker passes an
 * observed ≤35 Hz source through, or selects the first fresh exposure in each
 * absolute 1/frameRate bin of the sensor clock (so both eyes encode the same
 * instants), never manufactures a duplicate, and writes an exact n/frameRate
 * presentation timeline into the MP4.
 *
 * Bookkeeping never stops the encoder: every encoded frame gets a sidecar row
 * stamped with its SurfaceTexture (sensor) timestamp; exposure duration and the
 * Camera2 frame number are enrichment joined from the TotalCaptureResult when
 * the HAL delivered one, and written as -1 (unknown) with a counter otherwise.
 * Only encoder/EGL failures end a recording early.
 *
 * Geometry (capture contract 1.4.3): Camera2 is asked for the *source* stream
 * size — the SurfaceTexture default buffer size, which the caller must pick
 * from the camera's listed output sizes, because the camera service silently
 * rounds an unlisted size to its nearest listed one — and the encoder runs at
 * the *output* size. The full-quad draw resamples the whole source into the
 * output, isotropically when the two aspects match; the caller guarantees the
 * match. [getStreamGeometryJson] states the sizes and the SurfaceTexture
 * transform for the metadata sidecar.
 */
class VideoRecorderSurfaceProvider(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    outputFilePath: String,
    frameTimestampFilePath: String,
    frameRate: Int,
    bitrateMbps: Int,
    iFrameIntervalSeconds: Int,
    enableAudio: Boolean,
    audioBitrate: Int,
    audioSamplingRate: Int,
) : ISurfaceProvider, AutoCloseable {
    private val pipeline = FixedFrameRateVideoPipeline(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        outputFilePath = outputFilePath,
        frameTimestampFilePath = frameTimestampFilePath,
        frameRate = frameRate.also {
            require(it == CAPTURE_CONTRACT_FRAME_RATE) {
                "OpenQuest capture contract requires exactly $CAPTURE_CONTRACT_FRAME_RATE FPS"
            }
        },
        bitrateMbps = bitrateMbps.coerceAtLeast(1),
        iFrameIntervalSeconds = iFrameIntervalSeconds.also {
            require(it == CAPTURE_CONTRACT_GOP_SECONDS) {
                "OpenQuest capture contract requires a one-second GOP"
            }
        },
    )

    init {
        require(!enableAudio) {
            "The fixed-frame-rate MediaCodec pipeline is video-only"
        }
        if (audioBitrate > 0 || audioSamplingRate > 0) {
            Log.d(TAG, "Audio settings ignored because audio is disabled")
        }
    }

    override fun getSurface(): Surface = pipeline.cameraInputSurface

    override fun onCaptureResult(
        frameNumber: Long,
        sensorTimestampNs: Long,
        exposureTimeNs: Long,
    ) = pipeline.onCaptureResult(frameNumber, sensorTimestampNs, exposureTimeNs)

    fun updateOutputFile(path: String, frameTimestampPath: String) =
        pipeline.updateOutputFiles(path, frameTimestampPath)

    fun startRecording() = pipeline.startRecording()

    fun requestStopRecording() = pipeline.requestStopRecording()

    fun stopRecording() = pipeline.stopRecording()

    fun getSourceFrameCount(): Long = pipeline.sourceFrameCount()

    fun getSourceDroppedFrameCount(): Long = pipeline.sourceDroppedFrameCount()

    fun getSelectedFrameCount(): Long = pipeline.selectedFrameCount()

    /** JSON object of capture-quality counters for the recording metadata sidecar. */
    fun getCaptureReportJson(): String = pipeline.captureReportJson()

    /**
     * JSON object of the stream geometry for the recording metadata sidecar:
     * `requested_stream_size` (what Camera2 was asked for), `source_stream_size`
     * (the SurfaceTexture default buffer size — the same value by construction,
     * written so the sidecar states it), `output_size` (the encoder) and
     * `texture_transform` (the first SurfaceTexture transform matrix, 16 floats
     * column-major, or null when no frame has arrived).
     */
    fun getStreamGeometryJson(): String = pipeline.streamGeometryJson()

    override fun close() = pipeline.close()

    companion object {
        private const val CAPTURE_CONTRACT_FRAME_RATE = 30
        private const val CAPTURE_CONTRACT_GOP_SECONDS = 1
        private const val TAG = "VideoRecorderSurfaceProvider"
    }
}

private class FixedFrameRateVideoPipeline(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val outputWidth: Int,
    private val outputHeight: Int,
    outputFilePath: String,
    frameTimestampFilePath: String,
    private val frameRate: Int,
    private val bitrateMbps: Int,
    private val iFrameIntervalSeconds: Int,
) : AutoCloseable {
    init {
        // Checked before any thread or EGL resource exists, so a refused geometry
        // leaks nothing.
        require(sourceWidth > 0 && sourceHeight > 0 && outputWidth > 0 && outputHeight > 0) {
            "Stream sizes must be positive: source ${sourceWidth}x${sourceHeight}, " +
                "output ${outputWidth}x${outputHeight}"
        }
        require(sourceWidth.toLong() * outputHeight == sourceHeight.toLong() * outputWidth) {
            "The encoder output must resample the source isotropically: " +
                "source ${sourceWidth}x${sourceHeight}, output ${outputWidth}x${outputHeight}"
        }
    }

    private val stateLock = Any()
    private var outputFile = File(outputFilePath)
    private var frameTimestampFile = File(frameTimestampFilePath)
    private val workerThread =
        HandlerThread("FixedRateVideo-${sourceWidth}x${sourceHeight}-to-${outputWidth}x${outputHeight}")
            .apply { start() }
    private val worker = Handler(workerThread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var externalTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    lateinit var cameraInputSurface: Surface
        private set
    private val textureTransform = FloatArray(16)
    // The first SurfaceTexture transform seen, copied once and kept for the sidecar.
    @Volatile
    private var firstTextureTransform: FloatArray? = null
    private val captureResults = ConcurrentHashMap<Long, CameraFrameTimestamp>()
    private val captureStatsLock = Any()
    private var sourceFrameCount = 0L
    private var sourceDroppedFrameCount = 0L
    private var previousSourceFrameNumber: Long? = null
    private var sourceSequenceErrorCount = 0L
    private var exposureMissingCount = 0L
    private val recentSourceTimestampsNs = ArrayDeque<Long>()
    // Worker-thread counters (frame callback / stop only).
    private var captureResultMissingCount = 0L
    private var gopViolationCount = 0L
    private var timestampRegressionCount = 0L
    private var selectedMeanRateHz = 0.0
    private var selectedMaxGapNs = 0L
    // Fixed at start: the source cadence observed then, and the selection mode it chose.
    private var observedSourceRateHz: Double? = null
    private var passThroughSource = false

    private var shaderProgram = 0
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var texMatrixLocation = -1
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer

    private var codec: MediaCodec? = null
    private var codecInputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var muxerTrackIndex = -1
    private val bufferInfo = MediaCodec.BufferInfo()
    private var submittedFrameCount = 0L
    private var writtenFrameCount = 0L
    private val exposureSelector = ExposureFrameSelector(frameRate)
    private val submittedFrameTimestamps = ArrayDeque<Long>()
    private val pendingFrameTimestampRows = ArrayDeque<PendingFrameTimestampRow>()
    private var frameTimestampWriter: BufferedWriter? = null
    private var captureFailure: Throwable? = null

    @Volatile
    private var isRecording = false
    @Volatile
    private var recordingSessionActive = false
    @Volatile
    private var captureStatsActive = false
    @Volatile
    private var isClosed = false

    private val gopFrameCount = frameRate * iFrameIntervalSeconds

    init {
        runOnWorkerBlocking("initialize EGL camera surface") {
            initializeGl()
        }
    }

    fun onCaptureResult(frameNumber: Long, sensorTimestampNs: Long, exposureTimeNs: Long) {
        if (sensorTimestampNs < 0L || isClosed) return
        synchronized(captureStatsLock) {
            val latestTimestampNs = recentSourceTimestampsNs.peekLast()
            if (latestTimestampNs == null || sensorTimestampNs > latestTimestampNs) {
                recentSourceTimestampsNs.addLast(sensorTimestampNs)
                while (recentSourceTimestampsNs.size > SOURCE_RATE_WINDOW_FRAMES) {
                    recentSourceTimestampsNs.removeFirst()
                }
            }
        }
        if (captureStatsActive) {
            synchronized(captureStatsLock) {
                if (captureStatsActive) {
                    previousSourceFrameNumber?.let { previous ->
                        when {
                            frameNumber <= previous -> sourceSequenceErrorCount++
                            frameNumber > previous + 1L -> {
                                sourceDroppedFrameCount += frameNumber - previous - 1L
                            }
                        }
                    }
                    previousSourceFrameNumber = frameNumber
                    sourceFrameCount++
                    if (exposureTimeNs <= 0L) exposureMissingCount++
                }
            }
        }
        captureResults[sensorTimestampNs] = CameraFrameTimestamp(
            captureFrameNumber = frameNumber,
            sensorTimestampNs = sensorTimestampNs,
            exposureTimeNs = if (exposureTimeNs > 0L) exposureTimeNs else UNKNOWN,
        )
        // Retain results by age, never by count: a row still waiting for its
        // result must not lose it because the source runs fast.
        val horizonNs = sensorTimestampNs - CAPTURE_RESULT_RETENTION_NS
        captureResults.keys.removeAll { it < horizonNs }
        worker.post { flushFrameTimestampRows(force = false) }
    }

    fun updateOutputFiles(path: String, frameTimestampPath: String) = synchronized(stateLock) {
        check(!isClosed) { "Video pipeline is closed" }
        check(!isRecording) { "Cannot change video output while recording" }
        outputFile = File(path)
        frameTimestampFile = File(frameTimestampPath)
        runOnWorkerBlocking("prepare fixed-rate recording output") {
            prepareEncoder(outputFile)
        }
    }

    fun startRecording() = synchronized(stateLock) {
        check(!isClosed) { "Video pipeline is closed" }
        if (recordingSessionActive) return@synchronized

        val recordingFile = outputFile
        runOnWorkerBlocking("start fixed-rate recording") {
            if (codec == null) {
                // Defensive fallback for callers that start without first selecting
                // a session output through updateOutputFile().
                prepareEncoder(recordingFile)
            }
            submittedFrameCount = 0L
            writtenFrameCount = 0L
            submittedFrameTimestamps.clear()
            pendingFrameTimestampRows.clear()
            captureFailure = null
            val sourceRateHz = measureSourceRateHz()
            observedSourceRateHz = sourceRateHz
            // An unmeasured source is not a slow one: the session requests
            // SOURCE_FPS_RANGE (a 50 Hz lattice), so unknown selects onto the
            // absolute grid rather than encoding every slot on the n/30 timeline.
            passThroughSource = sourceRateHz != null && sourceRateHz <= PASS_THROUGH_SOURCE_MAX_HZ
            exposureSelector.reset(passThroughSource = passThroughSource)
            // Results that arrived just before start may belong to frames the
            // SurfaceTexture delivers after start; they are retained by age.
            captureResultMissingCount = 0L
            gopViolationCount = 0L
            timestampRegressionCount = 0L
            selectedMeanRateHz = 0.0
            selectedMaxGapNs = 0L
            synchronized(captureStatsLock) {
                sourceFrameCount = 0L
                sourceDroppedFrameCount = 0L
                previousSourceFrameNumber = null
                sourceSequenceErrorCount = 0L
                exposureMissingCount = 0L
                captureStatsActive = true
            }
            openFrameTimestampWriter(frameTimestampFile)
            recordingSessionActive = true
            isRecording = true
            val sourceRate = sourceRateHz?.let { "%.3f Hz".format(it) } ?: "unknown"
            Log.i(
                TAG,
                "Started ${sourceWidth}x${sourceHeight} -> ${outputWidth}x${outputHeight} H.264 " +
                    "at fixed $frameRate FPS " +
                    "(${selectionMode()} from $sourceRate source): ${recordingFile.absolutePath}",
            )
        }
    }

    fun requestStopRecording() = synchronized(stateLock) {
        if (!recordingSessionActive) return@synchronized
        isRecording = false
    }

    fun sourceFrameCount(): Long = synchronized(captureStatsLock) { sourceFrameCount }

    fun sourceDroppedFrameCount(): Long =
        synchronized(captureStatsLock) { sourceDroppedFrameCount }

    fun selectedFrameCount(): Long = writtenFrameCount

    fun captureReportJson(): String {
        val (sequenceErrors, exposureMissing) = synchronized(captureStatsLock) {
            sourceSequenceErrorCount to exposureMissingCount
        }
        val fpsRange = CameraSessionManager.SOURCE_FPS_RANGE
        val observedSourceFps = observedSourceRateHz
            ?.let { "%.4f".format(java.util.Locale.ROOT, it) } ?: "null"
        return "{" +
            "\"requested_fps_range\":[${fpsRange.lower},${fpsRange.upper}]," +
            "\"observed_source_fps\":$observedSourceFps," +
            "\"selection_mode\":\"${selectionMode()}\"," +
            "\"selection_grid_ns\":${exposureSelector.gridPeriodNs}," +
            "\"exposure_missing_frame_count\":$exposureMissing," +
            "\"capture_result_missing_frame_count\":$captureResultMissingCount," +
            "\"source_sequence_error_count\":$sequenceErrors," +
            "\"timestamp_regression_count\":$timestampRegressionCount," +
            "\"gop_violation_count\":$gopViolationCount," +
            "\"selected_mean_fps\":${"%.4f".format(java.util.Locale.ROOT, selectedMeanRateHz)}," +
            "\"selected_max_gap_ns\":$selectedMaxGapNs" +
            "}"
    }

    fun streamGeometryJson(): String {
        val transformJson = firstTextureTransform
            ?.joinToString(prefix = "[", postfix = "]") { it.toString() }
            ?: "null"
        return "{" +
            "\"requested_stream_size\":{\"width\":$sourceWidth,\"height\":$sourceHeight}," +
            "\"source_stream_size\":{\"width\":$sourceWidth,\"height\":$sourceHeight}," +
            "\"output_size\":{\"width\":$outputWidth,\"height\":$outputHeight}," +
            "\"texture_transform\":$transformJson" +
            "}"
    }

    /** Wire name of the selector mode chosen at start; the same word appears in the start log. */
    private fun selectionMode(): String = if (passThroughSource) "pass_through" else "absolute_grid"

    private fun measureSourceRateHz(): Double? = synchronized(captureStatsLock) {
        if (recentSourceTimestampsNs.size < MIN_SOURCE_RATE_WINDOW_FRAMES) {
            return@synchronized null
        }
        val durationNs = recentSourceTimestampsNs.last() - recentSourceTimestampsNs.first()
        if (durationNs <= 0L) return@synchronized null
        (recentSourceTimestampsNs.size - 1L) * NANOS_PER_SECOND.toDouble() / durationNs
    }

    fun stopRecording() = synchronized(stateLock) {
        if (!recordingSessionActive) return@synchronized
        isRecording = false

        runOnWorkerBlocking("stop fixed-rate recording") {
            try {
                codec?.signalEndOfInputStream()
                drainEncoder(endOfStream = true)
                check(submittedFrameTimestamps.isEmpty()) {
                    "Encoder finalized with ${submittedFrameTimestamps.size} unmatched frame timestamps"
                }
                flushFrameTimestampRows(force = true)
                exposureSelector.qualityOrNull()?.let { quality ->
                    selectedMeanRateHz = quality.meanRateHz
                    selectedMaxGapNs = quality.maxGapNs
                    check(quality.frameCount == writtenFrameCount) {
                        "Selected ${quality.frameCount} exposures but encoded $writtenFrameCount frames"
                    }
                }
                captureFailure?.let { throw IllegalStateException("Camera exposure capture failed", it) }
            } finally {
                captureStatsActive = false
                recordingSessionActive = false
                closeFrameTimestampWriter()
                releaseEncoderAndMuxer()
            }
            Log.i(TAG, "Stopped fixed-rate recording after $writtenFrameCount frames")
        }
    }

    override fun close() = synchronized(stateLock) {
        if (isClosed) return@synchronized

        if (recordingSessionActive) {
            stopRecording()
        }

        runOnWorkerBlocking("release fixed-rate video pipeline") {
            isClosed = true
            worker.removeCallbacksAndMessages(null)
            releaseEncoderAndMuxer()
            releaseGl()
        }

        workerThread.quitSafely()
        if (Thread.currentThread() !== workerThread) {
            workerThread.join()
        }
    }

    private fun initializeGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
        check(EGL14.eglInitialize(eglDisplay, null, 0, null, 0)) { "Unable to initialize EGL" }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, configCount, 0)) {
            "Unable to choose EGL config"
        }
        check(configCount[0] > 0 && configs[0] != null) { "No recordable EGL config found" }
        eglConfig = configs[0]

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }

        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(pbufferSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL pbuffer" }
        makeCurrent(pbufferSurface)

        externalTextureId = createExternalTexture()
        shaderProgram = createShaderProgram()
        positionLocation = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
        texCoordLocation = GLES20.glGetAttribLocation(shaderProgram, "aTexCoord")
        texMatrixLocation = GLES20.glGetUniformLocation(shaderProgram, "uTexMatrix")
        check(positionLocation >= 0 && texCoordLocation >= 0 && texMatrixLocation >= 0) {
            "Unable to resolve video shader variables"
        }

        vertexBuffer = floatBufferOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f,
        )
        texCoordBuffer = floatBufferOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        surfaceTexture = SurfaceTexture(externalTextureId).also { texture ->
            // The Camera2 stream size. Must be one the camera lists, or the
            // camera service rounds it and the buffer content is a crop.
            texture.setDefaultBufferSize(sourceWidth, sourceHeight)
            texture.setOnFrameAvailableListener({ availableTexture ->
                if (isClosed) return@setOnFrameAvailableListener
                try {
                    makeCurrent(pbufferSurface)
                    availableTexture.updateTexImage()
                    availableTexture.getTransformMatrix(textureTransform)
                    if (firstTextureTransform == null) {
                        firstTextureTransform = textureTransform.copyOf()
                    }
                    val surfaceTimestampNs = availableTexture.timestamp
                    if (isRecording && selectExposure(surfaceTimestampNs)) {
                        renderEncoderFrame(surfaceTimestampNs)
                    }
                    if (isRecording) drainEncoder(endOfStream = false)
                } catch (failure: Throwable) {
                    // Only encoder/EGL failures reach here; sidecar bookkeeping is
                    // counted, never thrown.
                    if (!isClosed) {
                        captureFailure = failure
                        isRecording = false
                        Log.e(TAG, "Unable to encode camera exposure", failure)
                    }
                }
                flushFrameTimestampRows(force = false)
            }, worker)
        }
        cameraInputSurface = Surface(surfaceTexture)
    }

    private fun prepareEncoder(recordingFile: File) {
        releaseEncoderAndMuxer()
        recordingFile.parentFile?.mkdirs()

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            outputWidth,
            outputHeight,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        }

        val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val newInputSurface = newCodec.createInputSurface()
            val newEglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                newInputSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(newEglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create encoder EGL surface" }

            codec = newCodec
            codecInputSurface = newInputSurface
            encoderEglSurface = newEglSurface
            muxer = MediaMuxer(recordingFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false
            muxerTrackIndex = -1
            newCodec.start()
        } catch (failure: Throwable) {
            runCatching { newCodec.release() }
            releaseEncoderAndMuxer()
            throw failure
        }
    }

    private fun renderEncoderFrame(surfaceTimestampNs: Long) {
        check(codec != null) { "Encoder is not prepared" }
        check(encoderEglSurface != EGL14.EGL_NO_SURFACE) { "Encoder EGL surface is unavailable" }

        // KEY_I_FRAME_INTERVAL owns the one-second GOP. Do not also request sync
        // frames here: racing the codec's automatic IDR with a per-frame manual
        // request can yield a second adjacent IDR, which correctly trips the
        // fixed-GOP validator below and truncates only that eye.

        // The whole source texture onto the whole encoder surface: an isotropic
        // resample, since the constructor requires equal aspects.
        makeCurrent(encoderEglSurface)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(shaderProgram)

        vertexBuffer.position(0)
        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glUniformMatrix4fv(texMatrixLocation, 1, false, textureTransform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val presentationNs = submittedFrameCount * 1_000_000_000L / frameRate
        check(EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, presentationNs)) {
            "eglPresentationTimeANDROID failed"
        }
        check(EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)) { "Encoder eglSwapBuffers failed" }
        submittedFrameTimestamps.addLast(surfaceTimestampNs)
        submittedFrameCount++
        makeCurrent(pbufferSurface)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val activeCodec = codec ?: return
        val deadlineMs = if (endOfStream) SystemClock.uptimeMillis() + EOS_TIMEOUT_MS else 0L

        while (true) {
            val outputIndex = activeCodec.dequeueOutputBuffer(
                bufferInfo,
                if (endOfStream) EOS_DEQUEUE_TIMEOUT_US else 0L,
            )

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || SystemClock.uptimeMillis() >= deadlineMs) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder output format changed twice" }
                    val activeMuxer = muxer ?: error("Muxer is unavailable")
                    muxerTrackIndex = activeMuxer.addTrack(activeCodec.outputFormat)
                    activeMuxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                        ?: error("Encoder output buffer $outputIndex is null")

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        check(muxerStarted) { "Encoded sample arrived before muxer format" }
                        val isSyncFrame =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val expectedSyncFrame = writtenFrameCount % gopFrameCount == 0L
                        if (isSyncFrame != expectedSyncFrame) {
                            // Recorded, not fatal: the converter validates the GOP it
                            // actually received; truncating the eye here would lose data.
                            gopViolationCount++
                            Log.w(
                                TAG,
                                "Encoder deviated from fixed GOP $gopFrameCount at frame " +
                                    "$writtenFrameCount (sync=$isSyncFrame)",
                            )
                        }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        // Normalize at the mux boundary as a final guarantee that MP4
                        // packet PTS values are exactly n/frameRate, independent of any
                        // timestamp quantization performed by the hardware encoder.
                        bufferInfo.presentationTimeUs = writtenFrameCount * 1_000_000L / frameRate
                        muxer?.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                        val surfaceTimestampNs = submittedFrameTimestamps.pollFirst()
                            ?: error("Encoded sample has no source exposure timestamp")
                        pendingFrameTimestampRows.addLast(
                            PendingFrameTimestampRow(writtenFrameCount, surfaceTimestampNs),
                        )
                        writtenFrameCount++
                    }

                    val reachedEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    activeCodec.releaseOutputBuffer(outputIndex, false)
                    if (reachedEos) return
                }
            }
        }
    }

    private fun releaseEncoderAndMuxer() {
        closeFrameTimestampWriter()
        if (encoderEglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            makeCurrent(pbufferSurface)
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            encoderEglSurface = EGL14.EGL_NO_SURFACE
        }

        codecInputSurface?.release()
        codecInputSurface = null
        codec?.let { activeCodec ->
            runCatching { activeCodec.stop() }
            runCatching { activeCodec.release() }
        }
        codec = null

        muxer?.let { activeMuxer ->
            if (muxerStarted) runCatching { activeMuxer.stop() }
                .onFailure { Log.e(TAG, "Unable to finalize MP4 muxer", it) }
            runCatching { activeMuxer.release() }
        }
        muxer = null
        muxerStarted = false
        muxerTrackIndex = -1
        submittedFrameTimestamps.clear()
        pendingFrameTimestampRows.clear()
    }

    private fun releaseGl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        makeCurrent(pbufferSurface)

        surfaceTexture?.setOnFrameAvailableListener(null)
        cameraInputSurface.release()
        surfaceTexture?.release()
        surfaceTexture = null
        captureResults.clear()

        if (shaderProgram != 0) GLES20.glDeleteProgram(shaderProgram)
        if (externalTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(externalTextureId), 0)
        shaderProgram = 0
        externalTextureId = 0

        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            pbufferSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        check(ids[0] != 0) { "Unable to create external OES texture" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return ids[0]
    }

    private fun createShaderProgram(): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        check(program != 0) { "Unable to create GL shader program" }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        val linkLog = GLES20.glGetProgramInfoLog(program)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        check(linkStatus[0] == GLES20.GL_TRUE) { "Unable to link video shader: $linkLog" }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Unable to create GL shader" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        val compileLog = GLES20.glGetShaderInfoLog(shader)
        check(compileStatus[0] == GLES20.GL_TRUE) { "Unable to compile video shader: $compileLog" }
        return shader
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun openFrameTimestampWriter(file: File) {
        closeFrameTimestampWriter()
        file.parentFile?.mkdirs()
        frameTimestampWriter = file.bufferedWriter().also { writer ->
            writer.write(FRAME_TIMESTAMP_HEADER)
            writer.newLine()
        }
    }

    /** Select a fresh exposure; a regressing SurfaceTexture timestamp is counted and skipped. */
    private fun selectExposure(surfaceTimestampNs: Long): Boolean {
        return try {
            exposureSelector.select(surfaceTimestampNs)
        } catch (regression: IllegalArgumentException) {
            timestampRegressionCount++
            Log.w(TAG, "Skipping camera exposure: ${regression.message}")
            false
        }
    }

    /**
     * Write sidecar rows in encoded order. Rows wait a bounded number of frames
     * for their Camera2 result (results normally trail buffers by ~2 frames);
     * a row whose result never arrives is written with -1 enrichment and
     * counted, so one missing result can neither block nor lose the sidecar.
     * With [force] (stop), stragglers get [CAPTURE_RESULT_TIMEOUT_MS] then the
     * same treatment.
     */
    private fun flushFrameTimestampRows(force: Boolean) {
        val writer = frameTimestampWriter ?: return
        var deadlineMs = if (force) SystemClock.uptimeMillis() + CAPTURE_RESULT_TIMEOUT_MS else 0L
        try {
            while (pendingFrameTimestampRows.isNotEmpty()) {
                val pending = pendingFrameTimestampRows.first()
                var capture = captureResults.remove(pending.surfaceTimestampNs)
                if (capture == null) {
                    val framesBehind = writtenFrameCount - pending.index
                    if (!force && framesBehind < CAPTURE_RESULT_MAX_LAG_FRAMES) return
                    if (force && SystemClock.uptimeMillis() < deadlineMs) {
                        Thread.sleep(1L)
                        continue
                    }
                    captureResultMissingCount++
                    Log.w(
                        TAG,
                        "No Camera2 capture result for encoded exposure " +
                            "${pending.surfaceTimestampNs} ns at frame ${pending.index}",
                    )
                    capture = CameraFrameTimestamp(
                        captureFrameNumber = UNKNOWN,
                        sensorTimestampNs = pending.surfaceTimestampNs,
                        exposureTimeNs = UNKNOWN,
                    )
                    // The next straggler gets a fresh (short) grace period.
                    if (force) deadlineMs = SystemClock.uptimeMillis() + CAPTURE_RESULT_TIMEOUT_MS
                }
                writer.write(
                    "${pending.index},${capture.sensorTimestampNs},${capture.exposureTimeNs}," +
                        "${capture.captureFrameNumber},${pending.surfaceTimestampNs},false",
                )
                writer.newLine()
                pendingFrameTimestampRows.removeFirst()
            }
        } catch (failure: java.io.IOException) {
            // Losing sidecar rows must never stop the encoder; surface it at stop.
            captureFailure = captureFailure ?: failure
            Log.e(TAG, "Unable to write frame timestamp sidecar", failure)
        }
    }

    private fun closeFrameTimestampWriter() {
        frameTimestampWriter?.let { writer ->
            runCatching {
                writer.flush()
                writer.close()
            }.onFailure { Log.e(TAG, "Unable to finalize frame timestamp sidecar", it) }
        }
        frameTimestampWriter = null
    }

    private fun runOnWorkerBlocking(operation: String, block: () -> Unit) {
        if (Thread.currentThread() === workerThread) {
            block()
            return
        }

        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        check(worker.post {
            try {
                block()
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                finished.countDown()
            }
        }) { "Unable to schedule operation: $operation" }

        check(finished.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out waiting to $operation"
        }
        failure.get()?.let { throw IllegalStateException("Failed to $operation", it) }
    }

    companion object {
        private const val TAG = "FixedFrameRateVideo"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val WORKER_TIMEOUT_SECONDS = 15L
        private const val EOS_TIMEOUT_MS = 5_000L
        private const val EOS_DEQUEUE_TIMEOUT_US = 10_000L
        private const val CAPTURE_RESULT_TIMEOUT_MS = 500L
        private const val CAPTURE_RESULT_RETENTION_NS = 5_000_000_000L
        private const val CAPTURE_RESULT_MAX_LAG_FRAMES = 30L
        private const val UNKNOWN = -1L
        private const val SOURCE_RATE_WINDOW_FRAMES = 60
        private const val MIN_SOURCE_RATE_WINDOW_FRAMES = 10
        private const val PASS_THROUGH_SOURCE_MAX_HZ = 35.0
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val FRAME_TIMESTAMP_HEADER =
            "encoded_frame_index,sensor_timestamp_ns,exposure_time_ns," +
                "capture_frame_number,surface_timestamp_ns,is_duplicate"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}

private data class CameraFrameTimestamp(
    val captureFrameNumber: Long,
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long,
)

private data class PendingFrameTimestampRow(
    val index: Long,
    val surfaceTimestampNs: Long,
)
