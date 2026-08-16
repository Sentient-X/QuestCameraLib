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
    fun twoSelectorsOnTheSharedLatticeSelectIdenticalTimestamps() {
        // Quest 3S at [60,60]: both cameras on one 20 ms lattice, identical
        // SENSOR_TIMESTAMPs. Each eye owns its own selector; both must agree.
        val lattice = twentyMillisecondLattice(originNs = 7_654_321_000L, count = 501)
        val leftEye = ExposureFrameSelector(frameRate = 30)
        val rightEye = ExposureFrameSelector(frameRate = 30)

        val leftSelected = lattice.filter(leftEye::select)
        val rightSelected = lattice.filter(rightEye::select)

        assertEquals(leftSelected, rightSelected)
        // 10 s of lattice crosses exactly 300 bin edges wherever it starts.
        assertEquals(301, leftSelected.size)
    }

    @Test
    fun selectsThreeOfFiveLatticeSlotsWithTwentyFortyFortyGapsAtThirtyHertz() {
        val selector = ExposureFrameSelector(frameRate = 30)
        val selected = twentyMillisecondLattice(originNs = 1_000_000_000L, count = 501)
            .filter(selector::select)
        val gaps = selected.zipWithNext { earlier, later -> later - earlier }

        assertEquals(301, selected.size)
        assertEquals(setOf(20_000_000L, 40_000_000L), gaps.toSet())
        // Every consecutive triple of gaps is one 100 ms cycle: 20 + 40 + 40.
        gaps.windowed(size = 3, step = 3).forEach { cycle ->
            assertEquals(cycle.toString(), 100_000_000L, cycle.sum())
            assertEquals(cycle.toString(), listOf(20_000_000L, 40_000_000L, 40_000_000L), cycle.sorted())
        }
        assertEquals(30.0, requireNotNull(selector.qualityOrNull()).meanRateHz, 0.001)
    }

    @Test
    fun selectorStartedMidLatticePicksTheSameAbsoluteSlotsAsOneStartedAtTheOrigin() {
        val lattice = twentyMillisecondLattice(originNs = 1_000_000_000L, count = 200)
        val fromOrigin = ExposureFrameSelector(frameRate = 30)
        val originSelected = lattice.filter(fromOrigin::select)

        for (startSlot in 1 until 10) {
            val lateStarter = ExposureFrameSelector(frameRate = 30)
            val lateSelected = lattice.drop(startSlot).filter(lateStarter::select)

            // The first frame after start is always taken (a recording begins
            // mid-bin without knowing it); every later pick is the absolute
            // grid's, exactly as chosen from the origin.
            assertEquals(lattice[startSlot], lateSelected.first())
            assertEquals(
                "start slot $startSlot",
                originSelected.filter { it > lateSelected.first() },
                lateSelected.drop(1),
            )
        }
    }

    @Test
    fun exposesTheNominalGridPeriod() {
        assertEquals(33_333_333L, ExposureFrameSelector(frameRate = 30).gridPeriodNs)
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

    /** The Quest 3S source at [60,60]: 50 fps, one SENSOR_TIMESTAMP every 20 ms. */
    private fun twentyMillisecondLattice(originNs: Long, count: Int): List<Long> =
        List(count) { originNs + it * 20_000_000L }
}
