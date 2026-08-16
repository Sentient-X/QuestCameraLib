package com.samusynth.questcamera.io

internal data class ExposureSelectionQuality(
    val frameCount: Long,
    val meanRateHz: Double,
    val maxGapNs: Long,
)

/** Selects fresh camera exposures onto an exact-rate encoder timeline. */
internal class ExposureFrameSelector(private val frameRate: Int) {
    private var passThroughSource = false
    private var firstSelectedTimestampNs = NO_TIMESTAMP
    private var lastObservedTimestampNs = NO_TIMESTAMP
    private var lastSelectedTimestampNs = NO_TIMESTAMP
    private var selectedFrameCount = 0L
    private var maxSelectedGapNs = 0L

    init {
        require(frameRate > 0) { "Frame rate must be positive" }
    }

    fun reset(passThroughSource: Boolean = false) {
        this.passThroughSource = passThroughSource
        firstSelectedTimestampNs = NO_TIMESTAMP
        lastObservedTimestampNs = NO_TIMESTAMP
        lastSelectedTimestampNs = NO_TIMESTAMP
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

        if (firstSelectedTimestampNs == NO_TIMESTAMP) {
            recordSelection(sensorTimestampNs)
            return true
        }

        val nextTargetTimestampNs =
            firstSelectedTimestampNs + selectedFrameCount * NANOS_PER_SECOND / frameRate
        if (sensorTimestampNs < nextTargetTimestampNs) return false

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
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
