package com.samusynth.questcamera.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class EncoderFrameHandoffTest {
    /**
     * pod5 2026-11-16 session 20261116_125224, right eye: the encoder emitted frame 151 on the
     * encoder output thread before the camera thread returned from eglSwapBuffers, so the
     * sample found no source exposure ("Encoded sample has no source exposure timestamp"), the
     * eye ended 5 s into a 40 s episode, and stop reported only "Failed to stop fixed-rate
     * recording". Here the encoder emits the frame from inside the swap, on another thread.
     */
    @Test
    fun aSampleEncodedBeforeTheSwapReturnsFindsItsExposure() {
        val handoff = EncoderFrameHandoff()
        val seen = AtomicReference<Long?>()
        handoff.submit(1_000L) {
            val encoderOutput = Thread { seen.set(handoff.takeForEncodedSample()) }
            encoderOutput.start()
            encoderOutput.join()
        }
        assertEquals(1_000L, seen.get())
        assertEquals(0, handoff.pendingCount)
    }

    @Test
    fun samplesPairWithExposuresInSubmissionOrder() {
        val handoff = EncoderFrameHandoff()
        val seen = ArrayList<Long?>()
        val encoderOutput = Thread {
            repeat(500) {
                var ts: Long? = null
                while (ts == null) ts = handoff.takeForEncodedSample()
                synchronized(seen) { seen.add(ts) }
            }
        }
        encoderOutput.start()
        repeat(500) { i -> handoff.submit(i * 20_000_000L) {} }
        encoderOutput.join(5_000)
        assertEquals((0 until 500).map { it * 20_000_000L }, seen)
    }

    @Test
    fun aFailedSwapRetractsItsExposure() {
        val handoff = EncoderFrameHandoff()
        handoff.submit(1L) {}
        val thrown = runCatching { handoff.submit(2L) { error("Encoder eglSwapBuffers failed") } }
        assertTrue(thrown.isFailure)
        assertEquals(1L, handoff.takeForEncodedSample())
        assertNull(handoff.takeForEncodedSample())
    }
}
