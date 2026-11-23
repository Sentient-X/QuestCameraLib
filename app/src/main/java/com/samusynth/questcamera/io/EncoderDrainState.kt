package com.samusynth.questcamera.io

/**
 * When the encoder output thread stops draining: once the encoder's end-of-stream buffer has
 * been dequeued, or, when that buffer never comes after the camera thread signalled the end of
 * input, after one bounded wait.
 *
 * Reaching the end of stream is a state, never the result of one pass. The camera thread sets
 * the end-of-input flag after `signalEndOfInputStream()` returns, so the end-of-stream buffer
 * is usually dequeued by a poll that began before the flag was set; a loop that then re-read
 * only the flag ran a second, bounded pass waiting for an end of stream it had already
 * consumed, and that pass ran to its whole 5 s deadline. Measured over 1,552 stops in the
 * 2026-11-22 headset logs (builds 76/77/84): each eye's `stop_session` median 5,52x ms with p10
 * 27–518 ms, so about three stops in four paid the deadline per eye, in series (OQC #44).
 *
 * Pure JVM, so the cross-thread order is unit-tested on the host.
 */
internal class EncoderDrainState {
    enum class Pass {
        /** The input is still open: one short poll of the encoder output. */
        POLL,

        /** The input has ended and no end of stream was seen: wait for it, bounded. */
        DRAIN_TO_END,

        /** The end-of-stream buffer was dequeued: nothing more will come. */
        DONE,

        /** The bounded wait ended without an end of stream: finalize what was encoded. */
        GIVE_UP,
    }

    @Volatile
    private var endOfInputSignalled = false

    @Volatile
    private var endOfStreamReached = false

    // Encoder output thread only.
    private var boundedWaitTaken = false

    val isEndOfStreamReached: Boolean get() = endOfStreamReached

    /** Camera thread, at start, before the encoder output thread exists. */
    fun reset() {
        endOfInputSignalled = false
        endOfStreamReached = false
        boundedWaitTaken = false
    }

    /** Camera thread (or stop, when signalling failed): no more input follows. */
    fun signalEndOfInput() {
        endOfInputSignalled = true
    }

    /** Encoder output thread: a buffer carrying `BUFFER_FLAG_END_OF_STREAM` was dequeued. */
    fun reachEndOfStream() {
        endOfStreamReached = true
    }

    /** Encoder output thread, before each pass: what that pass is. */
    fun nextPass(): Pass = when {
        endOfStreamReached -> Pass.DONE
        boundedWaitTaken -> Pass.GIVE_UP
        endOfInputSignalled -> {
            boundedWaitTaken = true
            Pass.DRAIN_TO_END
        }
        else -> Pass.POLL
    }
}
