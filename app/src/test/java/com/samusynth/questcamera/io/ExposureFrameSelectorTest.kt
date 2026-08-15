package com.samusynth.questcamera.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val quality = selector.requireDeliveryQuality()

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
        assertEquals(40_000_000L, selector.quality().maxGapNs)
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
    fun rejectsSourceThatCannotSupplyThirtyFreshExposuresPerSecond() {
        val selector = ExposureFrameSelector(frameRate = 30)
        (0..50)
            .map { 1_000_000_000L + it * 40_000_000L }
            .forEach(selector::select)

        assertThrows(IllegalArgumentException::class.java) {
            selector.requireDeliveryQuality()
        }
    }

    @Test
    fun rejectsDeliveredExposureGapOverFiftyMilliseconds() {
        val selector = ExposureFrameSelector(frameRate = 30)
        listOf(1_000_000_000L, 1_040_000_000L, 1_100_000_000L)
            .forEach(selector::select)

        assertThrows(IllegalArgumentException::class.java) {
            selector.requireDeliveryQuality()
        }
    }
}
