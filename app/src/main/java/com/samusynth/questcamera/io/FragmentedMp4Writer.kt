package com.samusynth.questcamera.io

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A crash-safe H.264 MP4 writer: an empty-sample `moov` up front, then one `moof`+`mdat`
 * fragment per GOP, each written in one call and fsynced. A process killed mid-episode
 * leaves a playable file up to the last complete fragment (≈ one GOP), where
 * `MediaMuxer`'s plain MP4 leaves no `moov` and nothing playable.
 *
 * Fragments are written and fsynced on this writer's own I/O thread, never on the caller's:
 * the caller is the camera/EGL thread that consumes Camera2 frames, and an fsync there (tens
 * to hundreds of ms on a busy headset) let the SurfaceTexture queue overflow, losing 50 Hz
 * exposures for that eye — the 60–280 ms `head_video interior gap`s the seal refused. Fragments
 * reach the file in order; an I/O failure surfaces on the next [writeSample] or [finish];
 * [finish] and [close] wait for every queued fragment.
 *
 * On a clean [finish] the movie, track, media and fragment durations are patched into the
 * up-front boxes, so tools that read only the `moov` see the true duration as well.
 *
 * Samples arrive as MediaCodec emits them (Annex-B, start-code delimited) and are stored
 * length-prefixed (AVCC). Every sample lasts exactly one frame period: the PTS of frame n is
 * n / frameRate, the same normalization the pipeline applied at the `MediaMuxer` boundary.
 * No B-frames, so no composition offsets.
 *
 * Pure JVM (no android.* imports), so it is unit-tested on the host.
 */
class FragmentedMp4Writer(
    private val file: File,
    private val width: Int,
    private val height: Int,
    frameRate: Int,
    /** A fragment is cut at the first sync sample after this many samples, or at twice it. */
    private val gopFrames: Int = frameRate,
    /**
     * Called on the caller's thread just before a fragment is queued: the pipeline writes its
     * timestamp sidecar rows to the OS here, so the fsync after the fragment covers them.
     */
    private val onFragmentQueued: () -> Unit = {},
    /** Called on the I/O thread after each fragment is durable; the pipeline fsyncs its sidecar here. */
    private val onFragmentDurable: () -> Unit = {},
) : AutoCloseable {
    init {
        require(width in 1..0xFFFF && height in 1..0xFFFF) { "Invalid video size ${width}x$height" }
        require(frameRate > 0) { "Frame rate must be positive" }
    }

    private val timescale = frameRate * TICKS_PER_FRAME
    private val sampleDuration = TICKS_PER_FRAME
    private var raf: RandomAccessFile? = null
    private var started = false
    private var sequenceNumber = 0
    private var samplesWritten = 0L
    private val pendingSizes = ArrayList<Int>()
    private val pendingSync = ArrayList<Boolean>()
    private val pendingData = ByteArrayOutputStream()
    private var pendingBaseSample = 0L
    // Where the next fragment lands; fragments are laid out on the caller's thread, written on io.
    private var fileEnd = 0L
    private val io: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Mp4Durability-${file.name}").apply { isDaemon = true }
    }
    @Volatile
    private var ioFailure: Throwable? = null
    private val queuedFragments = AtomicInteger(0)
    @Volatile
    private var maxQueued = 0
    @Volatile
    private var maxDurableNs = 0L

    // Absolute file offsets of the duration fields patched at finish().
    private var mvhdDurationOffset = -1L
    private var tkhdDurationOffset = -1L
    private var mdhdDurationOffset = -1L
    private var mehdDurationOffset = -1L

    val isStarted: Boolean get() = started

    /** Samples handed to durable storage (written, or queued on the I/O thread). */
    val durableSampleCount: Long get() = samplesWritten

    /** Most fragments ever waiting on the I/O thread at once (1 = never more than the one in flight). */
    val maxQueuedFragments: Int get() = maxQueued

    /** Longest write + fsync of one fragment, sidecar fsync included, in ns. */
    val maxFragmentDurableNs: Long get() = maxDurableNs

    /**
     * Writes `ftyp` + `moov` from the codec's parameter sets (`csd-0` = SPS, `csd-1` = PPS,
     * each with or without a start code).
     */
    fun start(sps: ByteArray, pps: ByteArray) {
        check(!started) { "FragmentedMp4Writer already started" }
        val spsNal = stripStartCode(sps)
        val ppsNal = stripStartCode(pps)
        require(spsNal.size >= 4) { "SPS too short (${spsNal.size} bytes)" }
        require(ppsNal.isNotEmpty()) { "PPS is empty" }

        file.parentFile?.mkdirs()
        val out = RandomAccessFile(file, "rw")
        out.setLength(0)
        raf = out

        val header = ByteArrayOutputStream()
        val ftyp = box("ftyp") {
            writeAscii("isom")
            writeInt(0x200)
            listOf("isom", "iso5", "iso6", "avc1", "mp41").forEach { writeAscii(it) }
        }
        header.write(ftyp)
        val moovStart = header.size().toLong()
        val moov = buildMoov(spsNal, ppsNal)
        header.write(moov.bytes)
        mvhdDurationOffset = moovStart + moov.mvhdDuration
        tkhdDurationOffset = moovStart + moov.tkhdDuration
        mdhdDurationOffset = moovStart + moov.mdhdDuration
        mehdDurationOffset = moovStart + moov.mehdDuration

        out.write(header.toByteArray())
        out.fd.sync()
        fileEnd = out.length()
        started = true
    }

    /** Appends one encoded access unit (Annex-B). Cuts and syncs a fragment at GOP boundaries. */
    fun writeSample(data: ByteBuffer, isSync: Boolean) {
        check(started) { "FragmentedMp4Writer not started" }
        throwIfIoFailed()
        val pendingCount = pendingSizes.size
        if (pendingCount > 0 && ((isSync && pendingCount >= gopFrames) || pendingCount >= 2 * gopFrames)) {
            flushFragment()
        }
        val avcc = annexBToAvcc(data)
        pendingSizes.add(avcc.size)
        pendingSync.add(isSync)
        pendingData.write(avcc)
    }

    /** Blocks until every queued fragment is written and fsynced (or the I/O thread failed). */
    fun awaitDurable() {
        if (io.isShutdown) return
        io.submit {}.get()
    }

    /** Writes the last fragment, patches durations into the `moov`, fsyncs and closes. */
    fun finish() {
        val out = raf ?: run { stopIo(); return }
        try {
            if (started) {
                flushFragment()
                awaitDurable()
                throwIfIoFailed()
                val totalTicks = samplesWritten * sampleDuration
                patchU32(out, mvhdDurationOffset, totalTicks)
                patchU32(out, tkhdDurationOffset, totalTicks)
                patchU32(out, mdhdDurationOffset, totalTicks)
                patchU32(out, mehdDurationOffset, totalTicks)
                out.fd.sync()
            }
        } finally {
            stopIo()
            out.close()
            raf = null
        }
    }

    /** Closes without patching; queued fragments are still written, so complete fragments stay readable. */
    override fun close() {
        stopIo()
        val out = raf ?: return
        runCatching { out.close() }
        raf = null
    }

    private fun stopIo() {
        io.shutdown()
        if (!io.awaitTermination(IO_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            io.shutdownNow()
        }
    }

    private fun throwIfIoFailed() {
        ioFailure?.let { throw IOException("Writing an MP4 fragment failed", it) }
    }

    private fun flushFragment() {
        val out = raf ?: return
        val count = pendingSizes.size
        if (count == 0) return
        sequenceNumber++

        val payload = pendingData.toByteArray()
        val moof = buildMoof(count)
        val mdatHeader = ByteBuffer.allocate(8).putInt(8 + payload.size).put("mdat".toByteArray()).array()

        // One write per fragment: a kill mid-write can only tear the trailing fragment.
        val fragment = ByteArray(moof.size + mdatHeader.size + payload.size)
        System.arraycopy(moof, 0, fragment, 0, moof.size)
        System.arraycopy(mdatHeader, 0, fragment, moof.size, mdatHeader.size)
        System.arraycopy(payload, 0, fragment, moof.size + mdatHeader.size, payload.size)
        val offset = fileEnd
        fileEnd += fragment.size

        samplesWritten += count
        pendingBaseSample = samplesWritten
        pendingSizes.clear()
        pendingSync.clear()
        pendingData.reset()
        onFragmentQueued()

        val depth = queuedFragments.incrementAndGet()
        if (depth > maxQueued) maxQueued = depth
        io.execute {
            try {
                // After a failure later fragments would leave a hole; the next writeSample throws.
                if (ioFailure != null) return@execute
                val startNs = System.nanoTime()
                val buffer = ByteBuffer.wrap(fragment)
                var position = offset
                while (buffer.hasRemaining()) position += out.channel.write(buffer, position)
                out.fd.sync()
                onFragmentDurable()
                val elapsedNs = System.nanoTime() - startNs
                if (elapsedNs > maxDurableNs) maxDurableNs = elapsedNs
            } catch (failure: Throwable) {
                if (ioFailure == null) ioFailure = failure
            } finally {
                queuedFragments.decrementAndGet()
            }
        }
    }

    private fun buildMoof(count: Int): ByteArray {
        // trun: data-offset | sample-size | sample-flags
        val trunFlags = 0x000001 or 0x000200 or 0x000400
        val trunSize = 8 + 4 + 4 + 4 + count * 8
        val tfhdSize = 8 + 4 + 4 + 4
        val tfdtSize = 8 + 4 + 8
        val trafSize = 8 + tfhdSize + tfdtSize + trunSize
        val mfhdSize = 8 + 4 + 4
        val moofSize = 8 + mfhdSize + trafSize

        val buffer = ByteBuffer.allocate(moofSize)
        buffer.putInt(moofSize).put("moof".toByteArray())
        buffer.putInt(mfhdSize).put("mfhd".toByteArray()).putInt(0).putInt(sequenceNumber)
        buffer.putInt(trafSize).put("traf".toByteArray())
        // tfhd: default-base-is-moof | default-sample-duration
        buffer.putInt(tfhdSize).put("tfhd".toByteArray()).putInt(0x020008).putInt(TRACK_ID).putInt(sampleDuration)
        buffer.putInt(tfdtSize).put("tfdt".toByteArray()).putInt(0x01000000)
            .putLong(pendingBaseSample * sampleDuration)
        buffer.putInt(trunSize).put("trun".toByteArray()).putInt(trunFlags).putInt(count)
            .putInt(moofSize + 8) // data offset: from moof start to the first mdat payload byte
        for (i in 0 until count) {
            buffer.putInt(pendingSizes[i])
            buffer.putInt(if (pendingSync[i]) SYNC_SAMPLE_FLAGS else NON_SYNC_SAMPLE_FLAGS)
        }
        check(buffer.remaining() == 0) { "moof size mismatch" }
        return buffer.array()
    }

    private class Moov(
        val bytes: ByteArray,
        val mvhdDuration: Int,
        val tkhdDuration: Int,
        val mdhdDuration: Int,
        val mehdDuration: Int,
    )

    private fun buildMoov(sps: ByteArray, pps: ByteArray): Moov {
        val avcC = box("avcC") {
            writeByte(1)
            writeByte(sps[1].toInt())
            writeByte(sps[2].toInt())
            writeByte(sps[3].toInt())
            writeByte(0xFF) // 4-byte NAL lengths
            writeByte(0xE1) // one SPS
            writeShort(sps.size)
            write(sps)
            writeByte(1)
            writeShort(pps.size)
            write(pps)
        }
        val avc1 = box("avc1") {
            write(ByteArray(6))
            writeShort(1) // data_reference_index
            writeShort(0)
            writeShort(0)
            write(ByteArray(12))
            writeShort(width)
            writeShort(height)
            writeInt(0x00480000)
            writeInt(0x00480000)
            writeInt(0)
            writeShort(1) // frame_count
            write(ByteArray(32)) // compressorname
            writeShort(0x0018)
            writeShort(0xFFFF)
            write(avcC)
        }
        val stsd = fullBox("stsd", 0, 0) { writeInt(1); write(avc1) }
        val stts = fullBox("stts", 0, 0) { writeInt(0) }
        val stsc = fullBox("stsc", 0, 0) { writeInt(0) }
        val stsz = fullBox("stsz", 0, 0) { writeInt(0); writeInt(0) }
        val stco = fullBox("stco", 0, 0) { writeInt(0) }
        val stbl = box("stbl") { write(stsd); write(stts); write(stsc); write(stsz); write(stco) }
        val dinf = box("dinf") {
            write(fullBox("dref", 0, 0) { writeInt(1); write(fullBox("url ", 0, 1) {}) })
        }
        val vmhd = fullBox("vmhd", 0, 1) { writeShort(0); writeShort(0); writeShort(0); writeShort(0) }
        val minf = box("minf") { write(vmhd); write(dinf); write(stbl) }
        val hdlr = fullBox("hdlr", 0, 0) {
            writeInt(0)
            writeAscii("vide")
            write(ByteArray(12))
            write("VideoHandler".toByteArray())
            writeByte(0)
        }
        // mdhd: version/flags(4) ctime(4) mtime(4) timescale(4) duration(4) lang(2) pre(2)
        val mdhd = fullBox("mdhd", 0, 0) {
            writeInt(0); writeInt(0); writeInt(timescale); writeInt(0)
            writeShort(0x55C4); writeShort(0)
        }
        val mdia = box("mdia") { write(mdhd); write(hdlr); write(minf) }
        // tkhd: ver/flags(4) ctime mtime trackId reserved duration(@+20 after header) ...
        val tkhd = fullBox("tkhd", 0, 0x000003) {
            writeInt(0); writeInt(0); writeInt(TRACK_ID); writeInt(0); writeInt(0)
            write(ByteArray(8))
            writeShort(0); writeShort(0); writeShort(0); writeShort(0)
            writeMatrix()
            writeInt(width shl 16)
            writeInt(height shl 16)
        }
        val trak = box("trak") { write(tkhd); write(mdia) }
        val mvhd = fullBox("mvhd", 0, 0) {
            writeInt(0); writeInt(0); writeInt(timescale); writeInt(0)
            writeInt(0x00010000); writeShort(0x0100); writeShort(0); write(ByteArray(8))
            writeMatrix()
            write(ByteArray(24))
            writeInt(TRACK_ID + 1)
        }
        val mehd = fullBox("mehd", 0, 0) { writeInt(0) }
        val trex = fullBox("trex", 0, 0) {
            writeInt(TRACK_ID); writeInt(1); writeInt(sampleDuration); writeInt(0); writeInt(0)
        }
        val mvex = box("mvex") { write(mehd); write(trex) }
        val moov = box("moov") { write(mvhd); write(trak); write(mvex) }

        // Field offsets within moov: box header 8, full-box header 4.
        val mvhdAt = 8
        val trakAt = mvhdAt + mvhd.size
        val tkhdAt = trakAt + 8
        val mdiaAt = tkhdAt + tkhd.size
        val mdhdAt = mdiaAt + 8
        val mvexAt = trakAt + trak.size
        val mehdAt = mvexAt + 8
        return Moov(
            bytes = moov,
            mvhdDuration = mvhdAt + 12 + 4 + 4 + 4,
            tkhdDuration = tkhdAt + 12 + 4 + 4 + 4 + 4,
            mdhdDuration = mdhdAt + 12 + 4 + 4 + 4,
            mehdDuration = mehdAt + 12,
        )
    }

    companion object {
        private const val IO_DRAIN_TIMEOUT_SECONDS = 30L
        private const val TRACK_ID = 1
        private const val TICKS_PER_FRAME = 1000
        private const val SYNC_SAMPLE_FLAGS = 0x02000000
        private const val NON_SYNC_SAMPLE_FLAGS = 0x01010000

        private fun patchU32(out: RandomAccessFile, offset: Long, value: Long) {
            check(offset > 0) { "duration offset unset" }
            out.seek(offset)
            out.writeInt(value.coerceAtMost(0xFFFFFFFFL).toInt())
        }

        private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
            val content = ByteArrayOutputStream()
            DataOutputStream(content).body()
            val bytes = content.toByteArray()
            return ByteBuffer.allocate(8 + bytes.size)
                .putInt(8 + bytes.size).put(type.toByteArray()).put(bytes).array()
        }

        private fun fullBox(
            type: String,
            version: Int,
            flags: Int,
            body: DataOutputStream.() -> Unit,
        ): ByteArray = box(type) {
            writeInt((version shl 24) or (flags and 0xFFFFFF))
            body()
        }

        private fun DataOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

        private fun DataOutputStream.writeMatrix() {
            listOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000).forEach { writeInt(it) }
        }

        /** The NAL unit payload of a parameter set, without any leading start code. */
        internal fun stripStartCode(nal: ByteArray): ByteArray {
            val skip = when {
                nal.size >= 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
                    nal[2].toInt() == 0 && nal[3].toInt() == 1 -> 4
                nal.size >= 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 1 -> 3
                else -> 0
            }
            return nal.copyOfRange(skip, nal.size)
        }

        /**
         * Annex-B access unit → AVCC (4-byte big-endian NAL lengths). Input without a start
         * code is taken as one NAL already. Does not consume [data]'s position.
         */
        internal fun annexBToAvcc(data: ByteBuffer): ByteArray {
            val bytes = ByteArray(data.remaining())
            data.duplicate().get(bytes)
            val starts = ArrayList<Int>() // index of first payload byte of each NAL
            val codeStarts = ArrayList<Int>() // index of the start code itself
            var i = 0
            while (i + 2 < bytes.size) {
                if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) {
                    if (bytes[i + 2].toInt() == 1) {
                        codeStarts.add(i); starts.add(i + 3); i += 3; continue
                    }
                    if (i + 3 < bytes.size && bytes[i + 2].toInt() == 0 && bytes[i + 3].toInt() == 1) {
                        codeStarts.add(i); starts.add(i + 4); i += 4; continue
                    }
                }
                i++
            }
            if (starts.isEmpty()) {
                return ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()
            }
            val out = ByteArrayOutputStream(bytes.size + 4 * starts.size)
            val lengthBytes = ByteBuffer.allocate(4)
            for (n in starts.indices) {
                val begin = starts[n]
                var end = if (n + 1 < starts.size) codeStarts[n + 1] else bytes.size
                // Trailing zero bytes are zero_byte/trailing_zero_8bits, never NAL payload
                // (a NAL ends with rbsp_stop_one_bit).
                while (end > begin && bytes[end - 1].toInt() == 0) end--
                val length = end - begin
                if (length <= 0) continue
                lengthBytes.clear()
                out.write(lengthBytes.putInt(length).array())
                out.write(bytes, begin, length)
            }
            return out.toByteArray()
        }
    }
}
