package com.samusynth.questcamera.io

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFailureTest {
    @Test
    fun theDescriptionNamesEveryCause() {
        val root = IllegalStateException("Encoded sample has no source exposure timestamp")
        val wrapped = IllegalStateException("Encoder output failed after 151 frames", root)
        assertEquals(
            "java.lang.IllegalStateException: Encoder output failed after 151 frames; " +
                "caused by java.lang.IllegalStateException: " +
                "Encoded sample has no source exposure timestamp",
            CaptureFailure.describe(wrapped),
        )
    }
}
