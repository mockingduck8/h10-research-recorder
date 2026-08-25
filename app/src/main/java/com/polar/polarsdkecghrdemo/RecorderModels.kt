package com.polar.polarsdkecghrdemo

/** Immutable UI snapshot published by the foreground recording service. */
data class RecorderSnapshot(
    val participantId: String = "",
    val deviceId: String = "",
    val status: String = "Service not started",
    val connected: Boolean = false,
    val streamingReady: Boolean = false,
    val recording: Boolean = false,
    val hrBpm: Int? = null,
    val rrValuesMs: List<Int> = emptyList(),
    val batteryPercent: Int? = null,
    val firmwareVersion: String? = null,
    val ecgSampleCount: Long = 0L,
    val rrCount: Long = 0L,
    val skippedDuplicateHrSamples: Long = 0L,
    val reconnectCount: Long = 0L,
    val connectionId: Long = 0L,
    val sessionId: String? = null,
    val saveDescription: String = "Documents/PolarExperiment",
    val lastSavedFiles: String = ""
)

interface RecorderServiceListener {
    fun onRecorderSnapshot(snapshot: RecorderSnapshot)

    /**
     * Samples are converted to mV only for plotting. The CSV writer receives the
     * unmodified Polar SDK microvolt integer values separately.
     */
    fun onEcgDisplaySamples(samplesMillivolts: List<Float>)
}
