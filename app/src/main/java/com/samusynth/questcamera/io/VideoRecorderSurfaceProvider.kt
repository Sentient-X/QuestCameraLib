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
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.samusynth.questcamera.core.ISurfaceProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Camera2 surface provider backed by a fixed-rate MediaCodec/EGL pipeline.
 *
 * Quest camera frames arrive on a 50 Hz timestamp lattice even when Camera2 is
 * requested at 30 FPS. Camera2 therefore targets a SurfaceTexture rather than
 * the encoder directly. The EGL worker samples the latest texture at exactly
 * [frameRate], duplicating or dropping camera frames as needed, and writes an
 * exact n/frameRate presentation timeline into the MP4.
 */
class VideoRecorderSurfaceProvider(
    private val width: Int,
    private val height: Int,
    outputFilePath: String,
    frameRate: Int,
    bitrateMbps: Int,
    iFrameIntervalSeconds: Int,
    enableAudio: Boolean,
    audioBitrate: Int,
    audioSamplingRate: Int,
) : ISurfaceProvider, AutoCloseable {
    private val pipeline = FixedFrameRateVideoPipeline(
        width = width,
        height = height,
        outputFilePath = outputFilePath,
        frameRate = frameRate.coerceAtLeast(1),
        bitrateMbps = bitrateMbps.coerceAtLeast(1),
        iFrameIntervalSeconds = iFrameIntervalSeconds.coerceAtLeast(1),
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

    fun updateOutputFile(path: String) = pipeline.updateOutputFile(path)

    fun startRecording() = pipeline.startRecording()

    fun stopRecording() = pipeline.stopRecording()

    override fun close() = pipeline.close()

    companion object {
        private const val TAG = "VideoRecorderSurfaceProvider"
    }
}

private class FixedFrameRateVideoPipeline(
    private val width: Int,
    private val height: Int,
    outputFilePath: String,
    private val frameRate: Int,
    private val bitrateMbps: Int,
    private val iFrameIntervalSeconds: Int,
) : AutoCloseable {
    private val stateLock = Any()
    private var outputFile = File(outputFilePath)
    private val workerThread = HandlerThread("FixedRateVideo-$width-$height").apply { start() }
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
    private var hasCameraFrame = false

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
    private var recordingStartNs = 0L
    private var nextFrameTargetNs = 0L

    @Volatile
    private var isRecording = false
    @Volatile
    private var isClosed = false

    private val framePeriodNs = 1_000_000_000L / frameRate
    private val gopFrameCount = frameRate * iFrameIntervalSeconds

    private val frameTick = object : Runnable {
        override fun run() {
            if (!isRecording || isClosed) return

            try {
                var nowNs = System.nanoTime()
                var renderedThisTick = 0

                // Catch up if Android scheduled this worker late. A small cap avoids
                // starving SurfaceTexture callbacks while preserving elapsed duration.
                while (hasCameraFrame && nowNs >= nextFrameTargetNs && renderedThisTick < 3) {
                    renderEncoderFrame()
                    nextFrameTargetNs = recordingStartNs + submittedFrameCount * framePeriodNs
                    renderedThisTick++
                    nowNs = System.nanoTime()
                }

                drainEncoder(endOfStream = false)

                val delayNs = max(0L, nextFrameTargetNs - System.nanoTime())
                worker.postDelayed(this, max(1L, delayNs / 1_000_000L))
            } catch (failure: Throwable) {
                Log.e(TAG, "Fixed-rate encoder frame tick failed", failure)
                isRecording = false
                releaseEncoderAndMuxer()
            }
        }
    }

    init {
        runOnWorkerBlocking("initialize EGL camera surface") {
            initializeGl()
        }
    }

    fun updateOutputFile(path: String) = synchronized(stateLock) {
        check(!isClosed) { "Video pipeline is closed" }
        check(!isRecording) { "Cannot change video output while recording" }
        outputFile = File(path)
        runOnWorkerBlocking("prepare fixed-rate recording output") {
            prepareEncoder(outputFile)
        }
    }

    fun startRecording() = synchronized(stateLock) {
        check(!isClosed) { "Video pipeline is closed" }
        if (isRecording) return@synchronized

        val recordingFile = outputFile
        runOnWorkerBlocking("start fixed-rate recording") {
            if (codec == null) {
                // Defensive fallback for callers that start without first selecting
                // a session output through updateOutputFile().
                prepareEncoder(recordingFile)
            }
            submittedFrameCount = 0L
            writtenFrameCount = 0L
            recordingStartNs = System.nanoTime()
            nextFrameTargetNs = recordingStartNs
            isRecording = true
            worker.removeCallbacks(frameTick)
            worker.post(frameTick)
            Log.i(TAG, "Started ${width}x${height} H.264 at fixed $frameRate FPS: ${recordingFile.absolutePath}")
        }
    }

    fun stopRecording() = synchronized(stateLock) {
        if (!isRecording) return@synchronized

        runOnWorkerBlocking("stop fixed-rate recording") {
            isRecording = false
            worker.removeCallbacks(frameTick)
            try {
                codec?.signalEndOfInputStream()
                drainEncoder(endOfStream = true)
            } finally {
                releaseEncoderAndMuxer()
            }
            Log.i(TAG, "Stopped fixed-rate recording after $writtenFrameCount frames")
        }
    }

    override fun close() = synchronized(stateLock) {
        if (isClosed) return@synchronized

        if (isRecording) {
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
            texture.setDefaultBufferSize(width, height)
            texture.setOnFrameAvailableListener({ availableTexture ->
                if (isClosed) return@setOnFrameAvailableListener
                try {
                    makeCurrent(pbufferSurface)
                    availableTexture.updateTexImage()
                    availableTexture.getTransformMatrix(textureTransform)
                    hasCameraFrame = true
                } catch (failure: Throwable) {
                    if (!isClosed) Log.e(TAG, "Unable to consume camera texture", failure)
                }
            }, worker)
        }
        cameraInputSurface = Surface(surfaceTexture)
    }

    private fun prepareEncoder(recordingFile: File) {
        releaseEncoderAndMuxer()
        recordingFile.parentFile?.mkdirs()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
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

    private fun renderEncoderFrame() {
        val activeCodec = codec ?: error("Encoder is not prepared")
        check(encoderEglSurface != EGL14.EGL_NO_SURFACE) { "Encoder EGL surface is unavailable" }

        if (submittedFrameCount % gopFrameCount == 0L) {
            activeCodec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }

        makeCurrent(encoderEglSurface)
        GLES20.glViewport(0, 0, width, height)
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

        val presentationNs = submittedFrameCount * framePeriodNs
        check(EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, presentationNs)) {
            "eglPresentationTimeANDROID failed"
        }
        check(EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)) { "Encoder eglSwapBuffers failed" }
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
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        // Normalize at the mux boundary as a final guarantee that MP4
                        // packet PTS values are exactly n/frameRate, independent of any
                        // timestamp quantization performed by the hardware encoder.
                        bufferInfo.presentationTimeUs = writtenFrameCount * 1_000_000L / frameRate
                        muxer?.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
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
    }

    private fun releaseGl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        makeCurrent(pbufferSurface)

        surfaceTexture?.setOnFrameAvailableListener(null)
        cameraInputSurface.release()
        surfaceTexture?.release()
        surfaceTexture = null
        hasCameraFrame = false

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
