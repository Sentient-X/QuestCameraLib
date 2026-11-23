package com.samusynth.questcamera.io

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
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
import java.io.File
import java.io.FileOutputStream
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
 * Threads (per eye): the *camera thread* (this worker, the SurfaceTexture's
 * consumer) only latches, selects and renders exposures into the encoder; it
 * never touches storage or drains the encoder, because blocking it lets the
 * SurfaceTexture queue overflow and loses camera exposures. The *encoder output
 * thread* drains MediaCodec, lays out MP4 fragments and joins sidecar rows with
 * their Camera2 results. The *durable I/O thread* ([DurableIoQueue], bounded and
 * ordered) is the only one that writes or fsyncs files while recording; when it
 * falls [FragmentedMp4Writer.DEFAULT_IO_QUEUE_CAPACITY] jobs behind, the eye
 * ends with a capture error instead of stalling the camera.
 *
 * Crash safety: the MP4 is fragmented ([FragmentedMp4Writer], one fsynced
 * fragment per GOP). A sidecar row is queued only behind the fragment holding
 * its frame and then fsynced, so a killed process leaves both readable up to the
 * last durable GOP and the sidecar never names a frame the MP4 lacks (it may lag
 * by rows still waiting on their capture result).
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

    // Every method Unity calls through JNI declares its return type: an expression body infers
    // it, and stopRecording's inferred Unit? compiled to ()Lkotlin/Unit; in a6e6bd3, so OQC's
    // Call("stopRecording") (()V) found no method at the first stop on pod5 (build 75, sx#4210).
    // UnityJniAbiTest holds each descriptor OQC calls.
    fun updateOutputFile(path: String, frameTimestampPath: String): Unit {
        pipeline.updateOutputFiles(path, frameTimestampPath)
    }

    fun startRecording(): Unit {
        pipeline.startRecording()
    }

    /** Pod setup's Scan QR: decode QR codes from this camera's frames while not recording. */
    fun startQrScan(): Unit {
        pipeline.startQrScan()
    }

    fun stopQrScan(): Unit {
        pipeline.stopQrScan()
    }

    /** The first QR text decoded since [startQrScan], or "" (null does not cross JNI cleanly). */
    fun getQrResult(): String = pipeline.qrResult() ?: ""

    fun requestStopRecording(): Unit {
        pipeline.requestStopRecording()
    }

    fun stopRecording(): Unit {
        pipeline.stopRecording()
    }

    fun getSourceFrameCount(): Long = pipeline.sourceFrameCount()

    fun getSourceDroppedFrameCount(): Long = pipeline.sourceDroppedFrameCount()

    fun getSelectedFrameCount(): Long = pipeline.selectedFrameCount()

    /**
     * The first failure that ended (or is ending) this recording, with every cause, as soon as
     * it happens on any recorder thread; "" while the recording is healthy (null does not
     * cross JNI cleanly). [stopRecording] still finalizes what was recorded and then throws
     * this same failure.
     */
    fun getCaptureError(): String = pipeline.captureError() ?: ""

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

    override fun close(): Unit {
        pipeline.close()
    }

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
    // Encoder-output-thread counters, read after stop.
    @Volatile
    private var captureResultMissingCount = 0L
    @Volatile
    private var gopViolationCount = 0L
    // Camera-thread counter.
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
    private var mp4: FragmentedMp4Writer? = null
    // Encoder output thread only.
    private val bufferInfo = MediaCodec.BufferInfo()
    private var submittedFrameCount = 0L
    @Volatile
    private var writtenFrameCount = 0L
    private val exposureSelector = ExposureFrameSelector(frameRate)
    // Camera thread publishes before each encoder swap; the encoder output thread takes.
    private val frameHandoff = EncoderFrameHandoff()
    private val pendingFrameTimestampRows = ArrayDeque<PendingFrameTimestampRow>()
    // Opened on the camera thread at start; written, fsynced and closed on the I/O and
    // encoder output threads (see runEncoderOutput).
    @Volatile
    private var frameTimestampStream: FileOutputStream? = null
    @Volatile
    private var captureFailure: Throwable? = null
    private var encoderOutputThread: Thread? = null
    // Camera thread signals the end of input; the encoder output thread records the end of
    // stream and decides each pass from both (see runEncoderOutput).
    private val drainState = EncoderDrainState()
    @Volatile
    private var encoderOutputFailure: Throwable? = null
    // The first failure of this recording on any thread, reported when it happens.
    private val firstFailure = AtomicReference<Throwable?>()
    @Volatile
    private var firstFailureText: String? = null
    // Durability evidence: the I/O queue's worst backlog and slowest fragment write + fsync.
    @Volatile
    private var ioMaxQueuedFragments = 0
    @Volatile
    private var ioMaxFragmentDurableNs = 0L
    // Per-stage worst case of the last recording, in capture_report.stage_max_ns. Each is
    // written by one thread and read after stop.
    private val stageMax = StageMaxima()

    @Volatile
    private var isRecording = false
    @Volatile
    private var recordingSessionActive = false
    @Volatile
    private var captureStatsActive = false
    @Volatile
    private var isClosed = false

    private val gopFrameCount = frameRate * iFrameIntervalSeconds
    // Camera thread: when the previous frame callback of this recording began.
    private var lastFrameCallbackNs = 0L

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
        encoderOutputThread?.let { previous ->
            check(!previous.isAlive) { "The previous recording's encoder output is still finishing" }
        }
        runOnWorkerBlocking("start fixed-rate recording") {
            if (codec == null) {
                // Defensive fallback for callers that start without first selecting
                // a session output through updateOutputFile().
                prepareEncoder(recordingFile)
            }
            submittedFrameCount = 0L
            writtenFrameCount = 0L
            frameHandoff.clear()
            pendingFrameTimestampRows.clear()
            captureFailure = null
            firstFailure.set(null)
            firstFailureText = null
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
            ioMaxQueuedFragments = 0
            ioMaxFragmentDurableNs = 0L
            stageMax.reset()
            lastFrameCallbackNs = 0L
            synchronized(captureStatsLock) {
                sourceFrameCount = 0L
                sourceDroppedFrameCount = 0L
                previousSourceFrameNumber = null
                sourceSequenceErrorCount = 0L
                exposureMissingCount = 0L
                captureStatsActive = true
            }
            val activeCodec = codec ?: error("Encoder is not prepared")
            val writer = mp4 ?: error("MP4 writer is not prepared")
            val sidecar = openFrameTimestampWriter(frameTimestampFile, writer)
            drainState.reset()
            encoderOutputFailure = null
            encoderOutputThread = Thread(
                { runEncoderOutput(activeCodec, writer, sidecar) },
                "EncoderOutput-${outputWidth}x${outputHeight}",
            ).apply { start() }
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

    fun captureError(): String? = firstFailureText

    /**
     * Records [failure] as this recording's failure if it is the first, and says so in the log
     * at once, with its causes, so the app can end the episode instead of learning it at stop.
     */
    private fun reportFailure(stage: String, failure: Throwable) {
        val described = IllegalStateException(
            "$stage after $writtenFrameCount frames: ${CaptureFailure.describe(failure)}",
            failure,
        )
        if (firstFailure.compareAndSet(null, described)) {
            firstFailureText = described.message
            Log.e(TAG, "Capture failed: ${described.message}", failure)
        }
    }

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
            "\"selected_max_gap_ns\":$selectedMaxGapNs," +
            "\"stage_max_ns\":${stageMax.toJson()}," +
            "\"io_max_queued_jobs\":${stageMax.ioMaxQueuedJobs}," +
            "\"io_queue_capacity\":${stageMax.ioQueueCapacity}" +
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

        var stopFailure: Throwable? = null
        try {
            // On the camera thread, so it follows the last encoder swap.
            runOnWorkerBlocking("signal the end of the encoder input") {
                try {
                    codec?.signalEndOfInputStream()
                } finally {
                    drainState.signalEndOfInput()
                }
            }
        } catch (failure: Throwable) {
            stopFailure = failure
            drainState.signalEndOfInput()
        }
        // The encoder output thread drains to end of stream, queues the last fragment and
        // sidecar rows, waits until they are durable and closes both files. Joined here, on
        // the caller, never on the camera thread.
        val output = encoderOutputThread
        output?.join(WORKER_TIMEOUT_SECONDS * 1_000L)
        val outputStillRunning = output?.isAlive == true
        if (outputStillRunning) {
            stopFailure = stopFailure ?: IllegalStateException(
                "Timed out waiting for the encoder output to become durable",
            )
        }

        // Finalizing always runs; a failure is thrown after it, as itself (the first one the
        // recording reported, with its causes), never wrapped in a stop failure.
        var finalFailure: Throwable? = null
        runOnWorkerBlocking("stop fixed-rate recording") {
            try {
                stopFailure?.let { reportFailure("Stop failed", it) }
                encoderOutputFailure?.let { reportFailure("Encoder output failed", it) }
                captureFailure?.let { reportFailure("Camera exposure capture failed", it) }
                exposureSelector.qualityOrNull()?.let { quality ->
                    selectedMeanRateHz = quality.meanRateHz
                    selectedMaxGapNs = quality.maxGapNs
                    if (quality.frameCount != writtenFrameCount) {
                        reportFailure(
                            "Stop found the encoder short",
                            IllegalStateException(
                                "Selected ${quality.frameCount} exposures but encoded " +
                                    "$writtenFrameCount frames",
                            ),
                        )
                    }
                }
                finalFailure = firstFailure.get()
            } finally {
                captureStatsActive = false
                recordingSessionActive = false
                // Kept while it still runs, so the next start refuses to share its state.
                if (!outputStillRunning) encoderOutputThread = null
                // The encoder output thread finished and closed both files; if it is still
                // running it still owns them, so they are detached here, never closed under it.
                mp4 = null
                frameTimestampStream = null
                releaseEncoderAndMuxer()
            }
            Log.i(
                TAG,
                "Stopped fixed-rate recording after $writtenFrameCount frames " +
                    "(fragment I/O: max queued $ioMaxQueuedFragments, slowest durable " +
                    "${"%.1f".format(java.util.Locale.ROOT, ioMaxFragmentDurableNs / 1e6)} ms; " +
                    "stage max ns ${stageMax.toJson()})",
            )
        }
        finalFailure?.let { throw it }
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
                // Camera thread: latch, select and render only. Draining the encoder and
                // every file write happen on the encoder output and I/O threads.
                val callbackStartNs = System.nanoTime()
                val recording = isRecording
                try {
                    makeCurrent(pbufferSurface)
                    availableTexture.updateTexImage()
                    val latchedNs = System.nanoTime()
                    availableTexture.getTransformMatrix(textureTransform)
                    if (firstTextureTransform == null) {
                        firstTextureTransform = textureTransform.copyOf()
                    }
                    val surfaceTimestampNs = availableTexture.timestamp
                    if (recording && selectExposure(surfaceTimestampNs)) {
                        val renderStartNs = System.nanoTime()
                        renderEncoderFrame(surfaceTimestampNs)
                        stageMax.encoderRender.record(System.nanoTime() - renderStartNs)
                    }
                    if (recording) stageMax.updateTexImage.record(latchedNs - callbackStartNs)
                    else if (qrScanActive) grabQrFrame()
                } catch (failure: Throwable) {
                    // Only encoder/EGL failures reach here.
                    if (!isClosed) {
                        captureFailure = failure
                        isRecording = false
                        reportFailure("Unable to encode camera exposure", failure)
                    }
                }
                if (recording) {
                    val endNs = System.nanoTime()
                    stageMax.cameraFrameCallback.record(endNs - callbackStartNs)
                    if (lastFrameCallbackNs != 0L) {
                        stageMax.cameraFrameInterval.record(callbackStartNs - lastFrameCallbackNs)
                    }
                    lastFrameCallbackNs = callbackStartNs
                }
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
            // MediaMuxer truncated the session file here; keep that. The writer fills it
            // from the encoder's first output format on.
            recordingFile.outputStream().close()
            mp4 = FragmentedMp4Writer(
                file = recordingFile,
                width = outputWidth,
                height = outputHeight,
                frameRate = frameRate,
                gopFrames = gopFrameCount,
            )
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
        // The exposure is published before the swap: the encoder can emit this frame on the
        // output thread before eglSwapBuffers returns.
        frameHandoff.submit(surfaceTimestampNs) {
            check(EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)) { "Encoder eglSwapBuffers failed" }
        }
        submittedFrameCount++
        makeCurrent(pbufferSurface)
    }

    /**
     * Encoder output thread, one per recording: drains the encoder until its end-of-stream
     * buffer is dequeued (the camera thread signals the end of input at stop), then queues the
     * last fragment and the remaining sidecar rows, waits until they are durable, and closes
     * both files. Never touches EGL.
     *
     * Each pass is decided by [EncoderDrainState]: the end of stream is usually dequeued by a
     * poll that began before the end-of-input flag was set, and it ends the loop at once. Only
     * an end of stream that never comes costs the bounded [EOS_TIMEOUT_MS] wait, once.
     */
    private fun runEncoderOutput(
        activeCodec: MediaCodec,
        writer: FragmentedMp4Writer,
        sidecar: FileOutputStream,
    ) {
        var failure: Throwable? = null
        try {
            while (true) {
                when (drainState.nextPass()) {
                    EncoderDrainState.Pass.DONE -> break
                    EncoderDrainState.Pass.POLL ->
                        drainEncoder(activeCodec, writer, sidecar, endOfStream = false)
                    EncoderDrainState.Pass.DRAIN_TO_END ->
                        drainEncoder(activeCodec, writer, sidecar, endOfStream = true)
                    EncoderDrainState.Pass.GIVE_UP -> {
                        // Recorded, not fatal: what the encoder emitted is finalized as is.
                        Log.w(
                            TAG,
                            "${outputFile.name}: the encoder sent no end of stream within " +
                                "$EOS_TIMEOUT_MS ms of the end of input; finalizing " +
                                "$writtenFrameCount frames",
                        )
                        break
                    }
                }
            }
            check(frameHandoff.pendingCount == 0) {
                "Encoder finalized with ${frameHandoff.pendingCount} unmatched frame timestamps"
            }
            writer.queuePendingFragment()
            queueFrameTimestampRows(writer, sidecar, force = true)
        } catch (thrown: Throwable) {
            failure = thrown
            // Loud: the camera thread stops feeding this eye and stop reports the cause.
            isRecording = false
            reportFailure("Encoder output failed", thrown)
        }
        try {
            writer.finish()
        } catch (thrown: Throwable) {
            failure = failure ?: thrown
            Log.e(TAG, "Unable to finalize fragmented MP4", thrown)
        } finally {
            writer.close()
            // finish()/close() ran every queued sidecar job, each already fsynced.
            runCatching { sidecar.close() }
                .onFailure { Log.e(TAG, "Unable to close frame timestamp sidecar", it) }
            ioMaxQueuedFragments = maxOf(ioMaxQueuedFragments, writer.maxQueuedFragments)
            ioMaxFragmentDurableNs = maxOf(ioMaxFragmentDurableNs, writer.maxFragmentDurableNs)
            stageMax.recordWriter(writer)
        }
        encoderOutputFailure = failure
    }

    private fun drainEncoder(
        activeCodec: MediaCodec,
        writer: FragmentedMp4Writer,
        sidecar: FileOutputStream,
        endOfStream: Boolean,
    ) {
        val deadlineMs = if (endOfStream) SystemClock.uptimeMillis() + EOS_TIMEOUT_MS else 0L

        while (true) {
            val outputIndex = activeCodec.dequeueOutputBuffer(
                bufferInfo,
                if (endOfStream) EOS_DEQUEUE_TIMEOUT_US else OUTPUT_POLL_TIMEOUT_US,
            )

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || SystemClock.uptimeMillis() >= deadlineMs) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!writer.isStarted) { "Encoder output format changed twice" }
                    val format = activeCodec.outputFormat
                    writer.start(
                        sps = format.csdBytes("csd-0"),
                        pps = format.csdBytes("csd-1"),
                    )
                }
                outputIndex >= 0 -> {
                    val sampleStartNs = System.nanoTime()
                    val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                        ?: error("Encoder output buffer $outputIndex is null")

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        check(writer.isStarted) { "Encoded sample arrived before the encoder format" }
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

                        // The writer stamps sample n at exactly n/frameRate, independent of
                        // any timestamp quantization performed by the hardware encoder.
                        val surfaceTimestampNs = frameHandoff.takeForEncodedSample()
                            ?: error("Encoded sample has no source exposure timestamp")
                        val queuedBefore = writer.durableSampleCount
                        writer.writeSample(outputBuffer, isSyncFrame)
                        pendingFrameTimestampRows.addLast(
                            PendingFrameTimestampRow(writtenFrameCount, surfaceTimestampNs),
                        )
                        writtenFrameCount++
                        if (writer.durableSampleCount != queuedBefore) {
                            // A fragment was just queued: its rows go behind it.
                            queueFrameTimestampRows(writer, sidecar, force = false)
                        }
                    }

                    val reachedEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    activeCodec.releaseOutputBuffer(outputIndex, false)
                    stageMax.encoderOutput.record(System.nanoTime() - sampleStartNs)
                    if (reachedEos) {
                        drainState.reachEndOfStream()
                        return
                    }
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

        // Only a writer no recording used reaches here; a recording's encoder output thread
        // finishes its own (see stopRecording).
        mp4?.let { writer ->
            runCatching { writer.finish() }
                .onFailure { Log.e(TAG, "Unable to finalize fragmented MP4", it) }
            writer.close()
        }
        mp4 = null
        // frameHandoff and pendingFrameTimestampRows belong to the encoder output
        // thread while it runs; startRecording clears them before starting the next one.
    }

    // ── QR scan (pod setup): only while not recording, at most every QR_SCAN_INTERVAL_MS ──

    @Volatile
    private var qrScanActive = false
    @Volatile
    private var qrResult: String? = null
    @Volatile
    private var qrDecoding = false
    private var lastQrGrabMs = 0L
    private var qrFramebuffer = 0
    private var qrTexture = 0
    private val qrDecoder = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var qrTexCoordBuffer: FloatBuffer

    fun startQrScan() {
        qrResult = null
        qrScanActive = true
    }

    fun stopQrScan() {
        qrScanActive = false
    }

    /** The first QR text decoded since [startQrScan], or null. */
    fun qrResult(): String? = qrResult

    /**
     * Renders the centre of the camera frame (where a phone held up shows its code) at
     * QR_SCAN_SIZE into an offscreen texture, reads it back and decodes it off this thread.
     * Never runs while recording, so the recorder's cadence is untouched.
     */
    private fun grabQrFrame() {
        val now = SystemClock.uptimeMillis()
        if (qrDecoding || qrResult != null || now - lastQrGrabMs < QR_SCAN_INTERVAL_MS) return
        lastQrGrabMs = now
        if (qrFramebuffer == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            qrTexture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, qrTexture)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, QR_SCAN_SIZE, QR_SCAN_SIZE, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glGenFramebuffers(1, ids, 0)
            qrFramebuffer = ids[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, qrFramebuffer)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, qrTexture, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "QR framebuffer incomplete"
            }
            val lo = 0.5f - QR_SCAN_CROP / 2f
            val hi = 0.5f + QR_SCAN_CROP / 2f
            qrTexCoordBuffer = floatBufferOf(lo, lo, hi, lo, lo, hi, hi, hi)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, qrFramebuffer)
        GLES20.glViewport(0, 0, QR_SCAN_SIZE, QR_SCAN_SIZE)
        GLES20.glUseProgram(shaderProgram)
        vertexBuffer.position(0)
        qrTexCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, qrTexCoordBuffer)
        GLES20.glUniformMatrix4fv(texMatrixLocation, 1, false, textureTransform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val pixels = ByteBuffer.allocateDirect(QR_SCAN_SIZE * QR_SCAN_SIZE * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, QR_SCAN_SIZE, QR_SCAN_SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val rgba = ByteArray(pixels.capacity())
        pixels.position(0)
        pixels.get(rgba)
        qrDecoding = true
        qrDecoder.execute {
            try {
                QrDecoder.decode(rgba, QR_SCAN_SIZE, QR_SCAN_SIZE)?.let { text ->
                    if (qrScanActive && qrResult == null) {
                        qrResult = text
                        Log.i(TAG, "QR code decoded (${text.length} chars)")
                    }
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "QR decode failed", failure)
            } finally {
                qrDecoding = false
            }
        }
    }

    private fun releaseGl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        makeCurrent(pbufferSurface)
        qrScanActive = false
        qrDecoder.shutdownNow()
        if (qrFramebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(qrFramebuffer), 0)
        if (qrTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(qrTexture), 0)
        qrFramebuffer = 0
        qrTexture = 0

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

    /**
     * Camera thread, at start: opens (truncates) the sidecar and queues its header's write and
     * fsync first on the recording's I/O queue, so it is durable before any fragment and a
     * killed session that kept a fragment never leaves a 0-byte sidecar.
     */
    private fun openFrameTimestampWriter(file: File, writer: FragmentedMp4Writer): FileOutputStream {
        closeFrameTimestampWriter()
        file.parentFile?.mkdirs()
        val stream = FileOutputStream(file)
        frameTimestampStream = stream
        queueSidecarBytes(writer, stream, FrameTimestampSidecar.headerLine())
        return stream
    }

    /**
     * Encoder output thread: formats the rows of frames already in a queued fragment and
     * queues their write + fsync behind that fragment, so a row is on disk only once its frame
     * is. See [takeFrameTimestampRows] for rows still waiting on a capture result.
     */
    private fun queueFrameTimestampRows(
        writer: FragmentedMp4Writer,
        sidecar: FileOutputStream,
        force: Boolean,
    ) {
        val rows = takeFrameTimestampRows(force, queuedFrameCount = writer.durableSampleCount)
        if (rows.isNotEmpty()) queueSidecarBytes(writer, sidecar, rows)
    }

    /**
     * Queues an append + fsync of [text] on the I/O thread. A sidecar write failure is
     * recorded and surfaced at stop, never thrown into the MP4's queue: losing sidecar rows
     * must not stop the encoder.
     */
    private fun queueSidecarBytes(writer: FragmentedMp4Writer, stream: FileOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writer.enqueueIo {
            try {
                val startNs = System.nanoTime()
                stream.write(bytes)
                val writtenNs = System.nanoTime()
                stream.fd.sync()
                stageMax.sidecarWrite.record(writtenNs - startNs)
                stageMax.sidecarFsync.record(System.nanoTime() - writtenNs)
            } catch (failure: java.io.IOException) {
                captureFailure = captureFailure ?: failure
                reportFailure("Unable to write frame timestamp sidecar", failure)
            }
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
     * Encoder output thread: the sidecar rows of frames already in a queued fragment
     * (index < [queuedFrameCount]), in encoded order. Rows wait a bounded number of frames
     * for their Camera2 result (results normally trail buffers by ~2 frames); a row whose
     * result never arrives is written with -1 enrichment and counted, so one missing result
     * can neither block nor lose the sidecar. With [force] (stop), the stragglers share one
     * [CAPTURE_RESULT_TIMEOUT_MS] grace, then the same treatment: a fresh grace per straggler
     * made a stop whose results had stopped arriving wait 500 ms per pending row (pod5
     * 2026-11-15, right eye: five rows, 2.5 s of a 7.5 s stop).
     */
    private fun takeFrameTimestampRows(force: Boolean, queuedFrameCount: Long): String {
        val rows = StringBuilder()
        val deadlineMs = if (force) SystemClock.uptimeMillis() + CAPTURE_RESULT_TIMEOUT_MS else 0L
        while (pendingFrameTimestampRows.isNotEmpty()) {
            val pending = pendingFrameTimestampRows.first()
            if (pending.index >= queuedFrameCount) break
            var capture = captureResults.remove(pending.surfaceTimestampNs)
            if (capture == null) {
                val framesBehind = writtenFrameCount - pending.index
                if (!force && framesBehind < CAPTURE_RESULT_MAX_LAG_FRAMES) break
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
            }
            FrameTimestampSidecar.appendRow(
                rows,
                index = pending.index,
                sensorTimestampNs = capture.sensorTimestampNs,
                exposureTimeNs = capture.exposureTimeNs,
                captureFrameNumber = capture.captureFrameNumber,
                surfaceTimestampNs = pending.surfaceTimestampNs,
            )
            pendingFrameTimestampRows.removeFirst()
        }
        return rows.toString()
    }

    /** Closes a sidecar no encoder output thread owns (a recording that never started). */
    private fun closeFrameTimestampWriter() {
        runCatching { mp4?.awaitDurable() }
            .onFailure { Log.e(TAG, "Unable to wait for queued MP4 fragments", it) }
        frameTimestampStream?.let { stream ->
            runCatching { stream.close() }
                .onFailure { Log.e(TAG, "Unable to close frame timestamp sidecar", it) }
        }
        frameTimestampStream = null
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
        failure.get()?.let {
            throw IllegalStateException("Failed to $operation: ${CaptureFailure.describe(it)}", it)
        }
    }

    companion object {
        private const val TAG = "FixedFrameRateVideo"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val WORKER_TIMEOUT_SECONDS = 15L
        private const val EOS_TIMEOUT_MS = 5_000L
        private const val EOS_DEQUEUE_TIMEOUT_US = 10_000L
        // The encoder output thread's poll: bounds how late it notices the end of input.
        private const val OUTPUT_POLL_TIMEOUT_US = 10_000L
        private const val CAPTURE_RESULT_TIMEOUT_MS = 500L
        private const val CAPTURE_RESULT_RETENTION_NS = 5_000_000_000L
        private const val CAPTURE_RESULT_MAX_LAG_FRAMES = 30L
        private const val UNKNOWN = -1L
        private const val SOURCE_RATE_WINDOW_FRAMES = 60
        private const val MIN_SOURCE_RATE_WINDOW_FRAMES = 10
        private const val PASS_THROUGH_SOURCE_MAX_HZ = 35.0
        private const val NANOS_PER_SECOND = 1_000_000_000L
        // QR scan: the centre 60 % of the frame at 768 px, five tries a second.
        private const val QR_SCAN_SIZE = 768
        private const val QR_SCAN_CROP = 0.6f
        private const val QR_SCAN_INTERVAL_MS = 200L

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

/** A codec-specific-data buffer (`csd-0`/`csd-1`) as bytes, start code included. */
private fun MediaFormat.csdBytes(key: String): ByteArray {
    val buffer = getByteBuffer(key) ?: error("Encoder output format has no $key")
    val bytes = ByteArray(buffer.remaining())
    buffer.duplicate().get(bytes)
    return bytes
}

private data class CameraFrameTimestamp(
    val captureFrameNumber: Long,
    val sensorTimestampNs: Long,
    val exposureTimeNs: Long,
)

/**
 * Worst case of each recording stage, for `capture_report.stage_max_ns`. Each [Max] has one
 * writer thread; values are read after stop.
 */
private class StageMaxima {
    class Max {
        @Volatile
        var ns = 0L
            private set

        fun record(elapsedNs: Long) {
            if (elapsedNs > ns) ns = elapsedNs
        }

        fun reset() {
            ns = 0L
        }
    }

    // Camera thread.
    val cameraFrameCallback = Max()
    val cameraFrameInterval = Max()
    val updateTexImage = Max()
    val encoderRender = Max()
    // Encoder output thread.
    val encoderOutput = Max()
    // I/O thread.
    val sidecarWrite = Max()
    val sidecarFsync = Max()
    @Volatile
    var fragmentWriteNs = 0L
    @Volatile
    var fragmentFsyncNs = 0L
    @Volatile
    var ioMaxQueuedJobs = 0
    @Volatile
    var ioQueueCapacity = FragmentedMp4Writer.DEFAULT_IO_QUEUE_CAPACITY

    fun reset() {
        listOf(
            cameraFrameCallback, cameraFrameInterval, updateTexImage, encoderRender,
            encoderOutput, sidecarWrite, sidecarFsync,
        ).forEach(Max::reset)
        fragmentWriteNs = 0L
        fragmentFsyncNs = 0L
        ioMaxQueuedJobs = 0
    }

    fun recordWriter(writer: FragmentedMp4Writer) {
        fragmentWriteNs = maxOf(fragmentWriteNs, writer.maxFragmentWriteNs)
        fragmentFsyncNs = maxOf(fragmentFsyncNs, writer.maxFragmentSyncNs)
        ioMaxQueuedJobs = maxOf(ioMaxQueuedJobs, writer.maxQueuedIoJobs)
        ioQueueCapacity = writer.ioQueueCapacity
    }

    fun toJson(): String = "{" +
        "\"camera_frame_callback\":${cameraFrameCallback.ns}," +
        "\"camera_frame_interval\":${cameraFrameInterval.ns}," +
        "\"update_tex_image\":${updateTexImage.ns}," +
        "\"encoder_render\":${encoderRender.ns}," +
        "\"encoder_output\":${encoderOutput.ns}," +
        "\"fragment_write\":$fragmentWriteNs," +
        "\"fragment_fsync\":$fragmentFsyncNs," +
        "\"sidecar_write\":${sidecarWrite.ns}," +
        "\"sidecar_fsync\":${sidecarFsync.ns}" +
        "}"
}

private data class PendingFrameTimestampRow(
    val index: Long,
    val surfaceTimestampNs: Long,
)
