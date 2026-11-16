package com.samusynth.questcamera.io

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * The source exposure of every frame the camera thread hands the encoder, in submission
 * order, for the encoder output thread to pair with each encoded sample.
 *
 * Pure JVM, so the cross-thread order is unit-tested on the host.
 */
internal class EncoderFrameHandoff {
    private val timestamps = ConcurrentLinkedDeque<Long>()

    val pendingCount: Int get() = timestamps.size

    fun clear() = timestamps.clear()

    /**
     * Camera thread: hands one rendered frame to the encoder. [swap] is what queues it
     * (eglSwapBuffers on the encoder surface), and the encoder may emit the encoded sample on
     * the output thread before the swap returns, so the exposure is published first. A swap
     * that throws handed the encoder nothing, so its exposure is retracted.
     *
     * Publishing after the swap lost that race on pod5 2026-11-16 (session 20261116_125224,
     * right eye, frame 151: "Encoded sample has no source exposure timestamp").
     */
    fun submit(surfaceTimestampNs: Long, swap: () -> Unit) {
        timestamps.addLast(surfaceTimestampNs)
        try {
            swap()
        } catch (failure: Throwable) {
            timestamps.removeLastOccurrence(surfaceTimestampNs)
            throw failure
        }
    }

    /** Encoder output thread: the source exposure of the next encoded sample, or null. */
    fun takeForEncodedSample(): Long? = timestamps.pollFirst()
}
