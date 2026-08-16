package com.samusynth.questcamera.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureFrameSelectorTest {
    @Test
    fun selectsFreshExposuresFromFiftyHertzSourceAtThirtyHertz() {
        val selector = ExposureFrameSelector(frameRate = 30)
        val selected = (0..100)
            .map { 1_000_000_000L + it * 20_000_000L }
            .filter(selector::select)

        val quality = requireNotNull(selector.qualityOrNull())

        assertEquals(61, selected.size)
        assertEquals(61L, quality.frameCount)
        assertEquals(30.0, quality.meanRateHz, 0.001)
        assertEquals(40_000_000L, quality.maxGapNs)
    }

    @Test
    fun passesEveryFreshExposureFromObservedThirtyHertzSource() {
        val selector = ExposureFrameSelector(frameRate = 30)
        selector.reset(passThroughSource = true)
        val timestamps = listOf(
            1_000_000_000L,
            1_020_000_000L,
            1_060_000_000L,
            1_100_000_000L,
            1_120_000_000L,
            1_160_000_000L,
        )

        assertEquals(timestamps, timestamps.filter(selector::select))
        assertEquals(40_000_000L, requireNotNull(selector.qualityOrNull()).maxGapNs)
    }

    @Test
    fun repeatedSourceCallbacksNeverProduceDuplicateOutput() {
        val selector = ExposureFrameSelector(frameRate = 30)

        assertTrue(selector.select(1_000_000_000L))
        assertFalse(selector.select(1_000_000_000L))
        assertFalse(selector.select(1_020_000_000L))
        assertTrue(selector.select(1_040_000_000L))
    }

    @Test
    fun reportsSlowSourceCadenceInsteadOfJudgingIt() {
        val selector = ExposureFrameSelector(frameRate = 30)
        (0..50)
            .map { 1_000_000_000L + it * 40_000_000L }
            .forEach(selector::select)

        val quality = requireNotNull(selector.qualityOrNull())
        assertEquals(51L, quality.frameCount)
        assertEquals(25.0, quality.meanRateHz, 0.001)
        assertEquals(40_000_000L, quality.maxGapNs)
    }

    @Test
    fun reportsExposureGapsAsMeasured() {
        val selector = ExposureFrameSelector(frameRate = 30)
        listOf(1_000_000_000L, 1_040_000_000L, 1_100_000_000L)
            .forEach(selector::select)

        assertEquals(60_000_000L, requireNotNull(selector.qualityOrNull()).maxGapNs)
    }

    @Test
    fun hasNoQualityBeforeTwoSelectedExposures() {
        val selector = ExposureFrameSelector(frameRate = 30)
        assertNull(selector.qualityOrNull())
        selector.select(1_000_000_000L)
        assertNull(selector.qualityOrNull())
    }

    @Test
    fun rejectsTimestampRegressionAsAnArgumentError() {
        val selector = ExposureFrameSelector(frameRate = 30)
        selector.select(1_000_000_000L)
        assertThrows(IllegalArgumentException::class.java) {
            selector.select(999_000_000L)
        }
    }
}
