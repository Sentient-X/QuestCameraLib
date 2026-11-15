package com.samusynth.questcamera.io

/**
 * The bytes of the frame timestamp sidecar (`frame_timestamps_schema` of capture contract
 * 1.4.x): one header line, then one row per encoded frame. Rows were written through
 * `BufferedWriter.newLine()`, which is "\n" on Android; this keeps those bytes exactly.
 * Pure JVM, so the format is unit-tested on the host.
 */
internal object FrameTimestampSidecar {
    const val HEADER =
        "encoded_frame_index,sensor_timestamp_ns,exposure_time_ns," +
            "capture_frame_number,surface_timestamp_ns,is_duplicate"
    const val LINE_END = "\n"

    fun headerLine(): String = HEADER + LINE_END

    /** Appends one row; -1 is the unknown exposure time or capture frame number. */
    fun appendRow(
        out: StringBuilder,
        index: Long,
        sensorTimestampNs: Long,
        exposureTimeNs: Long,
        captureFrameNumber: Long,
        surfaceTimestampNs: Long,
    ): StringBuilder = out.append(index).append(',')
        .append(sensorTimestampNs).append(',')
        .append(exposureTimeNs).append(',')
        .append(captureFrameNumber).append(',')
        .append(surfaceTimestampNs).append(",false").append(LINE_END)
}
