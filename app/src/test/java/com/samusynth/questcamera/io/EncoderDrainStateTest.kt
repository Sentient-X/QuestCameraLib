package com.samusynth.questcamera.io

import com.samusynth.questcamera.io.EncoderDrainState.Pass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderDrainStateTest {
    /**
     * The shipped race (OQC #44, 2026-11-22, 1,552 stops): the poll that dequeues the
     * end-of-stream buffer began before the camera thread set the end-of-input flag. The next
     * pass must be DONE, not a bounded wait for an end of stream already consumed.
     */
    @Test
    fun anEndOfStreamDequeuedBeforeTheSignalLandsEndsTheLoop() {
        val state = EncoderDrainState()
        assertEquals(Pass.POLL, state.nextPass())
        // During that poll, on the camera thread, the end of input is signalled ...
        val cameraThread = Thread { state.signalEndOfInput() }
        cameraThread.start()
        cameraThread.join()
        // ... and the same poll dequeues the end-of-stream buffer.
        state.reachEndOfStream()
        assertEquals(Pass.DONE, state.nextPass())
        assertTrue(state.isEndOfStreamReached)
    }

    @Test
    fun theEndOfInputWithoutAnEndOfStreamTakesOneBoundedWait() {
        val state = EncoderDrainState()
        assertEquals(Pass.POLL, state.nextPass())
        state.signalEndOfInput()
        assertEquals(Pass.DRAIN_TO_END, state.nextPass())
        // The bounded wait saw the end of stream.
        state.reachEndOfStream()
        assertEquals(Pass.DONE, state.nextPass())
    }

    @Test
    fun aBoundedWaitThatNeverSeesTheEndOfStreamGivesUpOnce() {
        val state = EncoderDrainState()
        state.signalEndOfInput()
        assertEquals(Pass.DRAIN_TO_END, state.nextPass())
        assertEquals(Pass.GIVE_UP, state.nextPass())
        assertFalse(state.isEndOfStreamReached)
    }

    @Test
    fun resetForgetsTheLastRecording() {
        val state = EncoderDrainState()
        state.signalEndOfInput()
        state.reachEndOfStream()
        state.reset()
        assertEquals(Pass.POLL, state.nextPass())
        assertFalse(state.isEndOfStreamReached)
    }
}
