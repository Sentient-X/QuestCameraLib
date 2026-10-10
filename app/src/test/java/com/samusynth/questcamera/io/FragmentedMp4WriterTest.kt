package com.samusynth.questcamera.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * The fixture is 90 frames of 128x128 x264 High profile, GOP 30, no B-frames, with AUDs,
 * in Annex-B — the shape MediaCodec emits. Frames are fed the way the pipeline feeds them:
 * SPS/PPS once as codec config, then access units without parameter sets.
 */
class FragmentedMp4WriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Stream(val sps: ByteArray, val pps: ByteArray, val frames: List<Pair<ByteArray, Boolean>>)

    private fun loadFixture(): Stream {
        val bytes = javaClass.classLoader!!.getResourceAsStream("gop30_90frames_128.h264")!!.readBytes()
        val nals = splitNals(bytes)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val frames = ArrayList<Pair<ByteArray, Boolean>>()
        var current = ByteArrayOutputStream()
        var currentSync = false
        var currentHasSlice = false
        fun endFrame() {
            if (currentHasSlice) frames.add(current.toByteArray() to currentSync)
            current = ByteArrayOutputStream(); currentSync = false; currentHasSlice = false
        }
        for (nal in nals) {
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = sps ?: nal
                8 -> pps = pps ?: nal
                9 -> endFrame()
                else -> {
                    val type = nal[0].toInt() and 0x1F
                    if (type == 5) currentSync = true
                    if (type == 1 || type == 5) currentHasSlice = true
                    current.write(byteArrayOf(0, 0, 0, 1)); current.write(nal)
                }
            }
        }
        endFrame()
        return Stream(byteArrayOf(0, 0, 0, 1) + sps!!, byteArrayOf(0, 0, 0, 1) + pps!!, frames)
    }

    private fun splitNals(bytes: ByteArray): List<ByteArray> {
        val avcc = FragmentedMp4Writer.annexBToAvcc(ByteBuffer.wrap(bytes))
        val buffer = ByteBuffer.wrap(avcc)
        val out = ArrayList<ByteArray>()
        while (buffer.remaining() >= 4) {
            val n = buffer.int
            val nal = ByteArray(n); buffer.get(nal); out.add(nal)
        }
        return out
    }

    private fun write(file: File, stream: Stream, frames: Int, finish: Boolean, onDurable: () -> Unit = {}) {
        val writer = FragmentedMp4Writer(file, 128, 128, 30, onFragmentDurable = onDurable)
        writer.start(stream.sps, stream.pps)
        stream.frames.take(frames).forEach { (data, sync) -> writer.writeSample(ByteBuffer.wrap(data), sync) }
        if (finish) writer.finish() else writer.close()
    }

    private fun topLevelBoxes(file: File): List<String> {
        val types = ArrayList<String>()
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            while (pos + 8 <= raf.length()) {
                raf.seek(pos)
                val size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { raf.readFully(it) }
                if (size < 8 || pos + size > raf.length()) { types.add("torn"); break }
                types.add(String(type)); pos += size
            }
        }
        return types
    }

    @Test
    fun annexBToAvccHandlesThreeAndFourByteStartCodes() {
        val input = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 0, 0, 1, 0x41, 3)
        val avcc = FragmentedMp4Writer.annexBToAvcc(ByteBuffer.wrap(input))
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 3), avcc)
    }

    @Test
    fun cleanFinishWritesOneFragmentPerGop() {
        val stream = loadFixture()
        assertEquals(90, stream.frames.size)
        val file = tmp.newFile("clean.mp4")
        var durable = 0
        write(file, stream, 90, finish = true) { durable++ }
        assertEquals(listOf("ftyp", "moov", "moof", "mdat", "moof", "mdat", "moof", "mdat"), topLevelBoxes(file))
        assertEquals(3, durable)
        ffprobeFrames(file)?.let { (frames, duration) ->
            assertEquals(90, frames)
            assertEquals(3.0, duration, 0.001)
        }
        // The fields the sx Quest converter reads from stream metadata. nb_frames is N/A for
        // a fragmented file; the converter derives the interval from avg_frame_rate first.
        val stream0 = ffprobe(file, "stream=duration,avg_frame_rate")
        assertEquals(3.0, stream0.getValue("duration").toDouble(), 0.001)
        assertEquals("30/1", stream0["avg_frame_rate"])
    }

    @Test
    fun killedWriterLeavesEveryCompleteFragmentReadable() {
        val stream = loadFixture()
        val file = tmp.newFile("killed.mp4")
        // 75 frames, never finished: the third GOP is still in memory when the process dies.
        write(file, stream, 75, finish = false)
        assertEquals(listOf("ftyp", "moov", "moof", "mdat", "moof", "mdat"), topLevelBoxes(file))
        ffprobeFrames(file)?.let { (frames, _) -> assertEquals(60, frames) }
    }

    @Test
    fun tornTrailingFragmentLosesOnlyThatFragment() {
        val stream = loadFixture()
        val file = tmp.newFile("torn.mp4")
        write(file, stream, 90, finish = true)
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 1000) }
        assertEquals("torn", topLevelBoxes(file).last())
        // Every sample of the two intact fragments decodes; ffmpeg also salvages the torn
        // fragment's samples whose bytes landed, never the ones cut.
        ffprobeFrames(file)?.let { (frames, _) -> assertTrue("decoded $frames", frames in 60 until 90) }
    }

    /** (decoded frame count, format duration) via ffprobe, or null when ffprobe is absent. */
    private fun ffprobeFrames(file: File): Pair<Int, Double>? {
        val probe = runCatching {
            ProcessBuilder("ffprobe", "-version").redirectErrorStream(true).start().also {
                it.inputStream.readBytes(); it.waitFor(10, TimeUnit.SECONDS)
            }.exitValue() == 0
        }.getOrDefault(false)
        assumeTrue("ffprobe not on PATH", probe)
        val fields = ffprobe(file, "stream=nb_read_frames:format=duration", "-count_frames")
        return fields.getValue("nb_read_frames").toInt() to (fields["duration"]?.toDoubleOrNull() ?: -1.0)
    }

    private fun ffprobe(file: File, entries: String, vararg extra: String): Map<String, String> {
        val process = ProcessBuilder(
            listOf("ffprobe", "-v", "error") + extra + listOf(
                "-select_streams", "v:0", "-show_entries", entries, "-of", "default=nw=1",
                file.absolutePath,
            ),
        ).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return output.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
