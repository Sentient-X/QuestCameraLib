package com.samusynth.questcamera.io

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FrameTimestampSidecarTest {
    /** The bytes capture contract 1.4.3 recorders wrote through BufferedWriter.newLine(). */
    @Test
    fun bytesMatchTheBufferedWriterSidecar() {
        val text = StringBuilder(FrameTimestampSidecar.headerLine())
        FrameTimestampSidecar.appendRow(text, 0, 123_456_789_000L, 6_000_000L, 42L, 123_456_789_000L)
        FrameTimestampSidecar.appendRow(text, 1, 123_490_122_333L, -1L, -1L, 123_490_122_333L)
        val expected = "encoded_frame_index,sensor_timestamp_ns,exposure_time_ns," +
            "capture_frame_number,surface_timestamp_ns,is_duplicate\n" +
            "0,123456789000,6000000,42,123456789000,false\n" +
            "1,123490122333,-1,-1,123490122333,false\n"
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), text.toString().toByteArray(Charsets.UTF_8))
    }
}
