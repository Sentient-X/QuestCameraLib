package com.samusynth.questcamera.io

internal data class ExposureSelectionQuality(
    val frameCount: Long,
    val meanRateHz: Double,
    val maxGapNs: Long,
)

/**
 * Selects fresh camera exposures onto an exact-rate encoder timeline.
 *
 * Selection is a function of the absolute sensor timestamp, not of when the
 * selector started: the sensor clock is cut into bins of 1/frameRate s
 * (bin k covers [k/frameRate, (k+1)/frameRate) s) and the first fresh
 * exposure seen in each bin is selected. The Quest 3S delivers both cameras
 * on one 20 ms lattice with identical SENSOR_TIMESTAMPs, so two selectors —
 * one per eye — make identical decisions and encode the same instants.
 */
internal class ExposureFrameSelector(private val frameRate: Int) {
    private var passThroughSource = false
    private var firstSelectedTimestampNs = NO_TIMESTAMP
    private var lastObservedTimestampNs = NO_TIMESTAMP
    private var lastSelectedTimestampNs = NO_TIMESTAMP
    private var lastSelectedBin = NO_BIN
    private var selectedFrameCount = 0L
    private var maxSelectedGapNs = 0L

    init {
        require(frameRate > 0) { "Frame rate must be positive" }
    }

    /** Nominal grid period on the sensor clock (33_333_333 ns at 30 Hz); bin edges are exact k/frameRate s. */
    val gridPeriodNs: Long = NANOS_PER_SECOND / frameRate

    fun reset(passThroughSource: Boolean = false) {
        this.passThroughSource = passThroughSource
        firstSelectedTimestampNs = NO_TIMESTAMP
        lastObservedTimestampNs = NO_TIMESTAMP
        lastSelectedTimestampNs = NO_TIMESTAMP
        lastSelectedBin = NO_BIN
        selectedFrameCount = 0L
        maxSelectedGapNs = 0L
    }

    fun select(sensorTimestampNs: Long): Boolean {
        require(sensorTimestampNs > 0L) { "Camera exposure timestamp must be positive" }
        if (lastObservedTimestampNs != NO_TIMESTAMP) {
            require(sensorTimestampNs >= lastObservedTimestampNs) {
                "Camera exposure timestamps moved backwards"
            }
            if (sensorTimestampNs == lastObservedTimestampNs) return false
        }
        lastObservedTimestampNs = sensorTimestampNs

        if (passThroughSource) {
            recordSelection(sensorTimestampNs)
            return true
        }

        val bin = gridBin(sensorTimestampNs)
        if (bin <= lastSelectedBin) return false

        lastSelectedBin = bin
        recordSelection(sensorTimestampNs)
        return true
    }

    /** Measured cadence of the selected timeline, or null before two exposures were selected. */
    fun qualityOrNull(): ExposureSelectionQuality? {
        if (selectedFrameCount < 2L) return null
        val durationNs = lastSelectedTimestampNs - firstSelectedTimestampNs
        if (durationNs <= 0L) return null
        return ExposureSelectionQuality(
            frameCount = selectedFrameCount,
            meanRateHz = (selectedFrameCount - 1L) * NANOS_PER_SECOND.toDouble() / durationNs,
            maxGapNs = maxSelectedGapNs,
        )
    }

    /**
     * Index of the absolute grid bin holding [sensorTimestampNs]:
     * floor(ts * frameRate / 1e9), in integer arithmetic (no float drift) and
     * split at whole seconds so the product cannot overflow a Long.
     */
    private fun gridBin(sensorTimestampNs: Long): Long {
        val wholeSeconds = sensorTimestampNs / NANOS_PER_SECOND
        val remainderNs = sensorTimestampNs % NANOS_PER_SECOND
        return wholeSeconds * frameRate + remainderNs * frameRate / NANOS_PER_SECOND
    }

    private fun recordSelection(sensorTimestampNs: Long) {
        if (lastSelectedTimestampNs != NO_TIMESTAMP) {
            maxSelectedGapNs = maxOf(maxSelectedGapNs, sensorTimestampNs - lastSelectedTimestampNs)
        } else {
            firstSelectedTimestampNs = sensorTimestampNs
        }
        lastSelectedTimestampNs = sensorTimestampNs
        selectedFrameCount++
    }

    companion object {
        private const val NO_TIMESTAMP = -1L
        private const val NO_BIN = -1L
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
