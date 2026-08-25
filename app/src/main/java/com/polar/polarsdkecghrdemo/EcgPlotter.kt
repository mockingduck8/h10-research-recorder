package com.polar.polarsdkecghrdemo

import com.androidplot.xy.AdvancedLineAndPointRenderer
import com.androidplot.xy.SimpleXYSeries
import com.androidplot.xy.XYSeries

class EcgPlotter(title: String, ecgFrequency: Int) {
    companion object {
        private const val SECONDS_TO_PLOT = 5
    }

    private var listener: PlotterListener? = null
    private val plotNumbers: MutableList<Number?>
    val formatter: AdvancedLineAndPointRenderer.Formatter
    private val series: XYSeries
    private var dataIndex = 0

    init {
        val ySamplesSize = ecgFrequency * SECONDS_TO_PLOT
        plotNumbers = MutableList(ySamplesSize) { null }
        formatter = AdvancedLineAndPointRenderer.Formatter()
        formatter.isLegendIconEnabled = false
        series = SimpleXYSeries(
            plotNumbers,
            SimpleXYSeries.ArrayFormat.Y_VALS_ONLY,
            title
        )
    }

    fun getSeries(): SimpleXYSeries {
        return series as SimpleXYSeries
    }

    /**
     * Updates an entire BLE packet and redraws once.
     * The original demo redrew for every ECG sample (~130 redraws/s), which is
     * unnecessary and can compete with BLE callbacks and file recording.
     */
    fun sendSamples(samplesMillivolts: List<Float>) {
        if (samplesMillivolts.isEmpty()) return

        samplesMillivolts.forEach { mV ->
            plotNumbers[dataIndex] = mV

            if (dataIndex >= plotNumbers.size - 1) {
                dataIndex = 0
            } else {
                dataIndex++
            }

            plotNumbers[dataIndex] = null
        }

        (series as SimpleXYSeries).setModel(
            plotNumbers,
            SimpleXYSeries.ArrayFormat.Y_VALS_ONLY
        )
        listener?.update()
    }

    fun sendSingleSample(mV: Float) {
        sendSamples(listOf(mV))
    }

    fun setListener(listener: PlotterListener) {
        this.listener = listener
    }
}
