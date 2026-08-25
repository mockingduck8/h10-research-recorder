package com.polar.polarsdkecghrdemo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class EcgCsvSample(
    val sensorTimestampNs: Long,
    val voltageMicrovolts: Int
)

/**
 * Incremental, single-threaded CSV recorder.
 *
 * BLE callbacks never write to storage directly. They only enqueue compact text
 * batches. One dedicated writer thread serializes all writes, preserving order
 * while keeping the UI/BLE callback threads responsive.
 */
class CsvSessionRecorder(
    private val context: Context,
    participantIdInput: String,
    deviceIdInput: String
) {
    companion object {
        private const val DIRECTORY_NAME = "PolarExperiment"
        private const val ECG_SAMPLE_RATE_HZ = 130L
        private const val ECG_SAMPLE_PERIOD_NS =
            1_000_000_000L / ECG_SAMPLE_RATE_HZ
        private const val FLUSH_INTERVAL_NS = 2_000_000_000L
    }

    data class StopResult(
        val sessionId: String,
        val directoryDescription: String,
        val ecgFileName: String,
        val hrRrFileName: String,
        val eventFileName: String,
        val closedCleanly: Boolean
    )

    private data class CsvTarget(
        val fileName: String,
        val writer: BufferedWriter,
        val uri: Uri?,
        val pathDescription: String
    )

    private val participantId = cleanField(participantIdInput)
    private val deviceId = cleanField(deviceIdInput)
    val sessionId: String =
        "${safeFilePart(participantId)}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"

    private val active = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PolarCsvWriter").apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    private val baseUnixNs = System.currentTimeMillis() * 1_000_000L
    private val baseElapsedNs = SystemClock.elapsedRealtimeNanos()

    private lateinit var ecgTarget: CsvTarget
    private lateinit var hrRrTarget: CsvTarget
    private lateinit var eventTarget: CsvTarget

    private var lastFlushElapsedNs = SystemClock.elapsedRealtimeNanos()
    private val globalEcgSampleIndex = AtomicLong(0L)
    private val globalRrIndex = AtomicLong(0L)

    // The first packet anchors H10 sensor time to the phone's Unix clock.
    // Later ECG times follow the H10 sample timestamps rather than packet
    // arrival jitter. Fallback state is retained for malformed timestamps.
    private var ecgSensorToUnixOffsetNs: Long? = null
    private var lastSensorTimestampNs: Long? = null
    private var lastEstimatedEcgUnixNs: Long? = null
    private var lastEcgConnectionId: Long? = null

    val directoryDescription: String
        get() = "Documents/$DIRECTORY_NAME"

    @Throws(IOException::class)
    fun start() {
        check(!active.get()) { "Recorder has already started." }
        check(!stopped.get()) { "Recorder cannot be restarted after stop()." }

        val prefix = sessionId
        ecgTarget = createCsvTarget("${prefix}_ecg.csv")
        hrRrTarget = createCsvTarget("${prefix}_hr_rr.csv")
        eventTarget = createCsvTarget("${prefix}_events.csv")

        ecgTarget.writer.write(
            "participant_id,session_id,device_id,connection_id,packet_index,sample_index," +
                "packet_received_unix_ns,packet_received_unix_ms," +
                "sensor_timestamp_ns,estimated_sample_unix_ns," +
                "estimated_sample_unix_ms,timestamp_source,ecg_uV\n"
        )
        hrRrTarget.writer.write(
            "participant_id,session_id,device_id,connection_id,packet_index,rr_index_in_packet," +
                "global_rr_index,packet_received_unix_ns,packet_received_unix_ms," +
                "estimated_beat_unix_ns,estimated_beat_unix_ms,hr_bpm," +
                "rr_available,rr_ms\n"
        )
        eventTarget.writer.write(
            "participant_id,session_id,device_id,connection_id,event_unix_ns,event_unix_ms,event,detail\n"
        )

        ecgTarget.writer.flush()
        hrRrTarget.writer.flush()
        eventTarget.writer.flush()

        active.set(true)
        appendEvent("RECORDING_STARTED", "Files opened successfully", 0L)
    }

    fun isRecording(): Boolean = active.get()

    /**
     * Uses a monotonic Android clock anchored to Unix time at session start.
     * This avoids timestamp jumps if the phone clock is adjusted mid-session.
     */
    fun nowUnixNs(): Long {
        return baseUnixNs + (SystemClock.elapsedRealtimeNanos() - baseElapsedNs)
    }

    @Synchronized
    fun appendEcgPacket(
        connectionId: Long,
        packetIndex: Long,
        samples: List<EcgCsvSample>
    ) {
        if (!active.get() || samples.isEmpty()) return

        val packetReceivedUnixNs = nowUnixNs()

        // A reconnect is a new timing segment. Never stretch the fixed-rate
        // fallback across the missing interval, and re-anchor Polar sensor time
        // to the phone clock for the new connection.
        if (lastEcgConnectionId != connectionId) {
            ecgSensorToUnixOffsetNs = null
            lastSensorTimestampNs = null
            lastEstimatedEcgUnixNs = null
            lastEcgConnectionId = connectionId
        }

        val sensorTimesAreUsable =
            samples.all { it.sensorTimestampNs > 0L } &&
                samples.zipWithNext().all { (first, second) ->
                    second.sensorTimestampNs > first.sensorTimestampNs
                } &&
                (
                    lastSensorTimestampNs == null ||
                        samples.first().sensorTimestampNs >
                        lastSensorTimestampNs!!
                    )

        if (
            sensorTimesAreUsable &&
            ecgSensorToUnixOffsetNs == null
        ) {
            // Anchor the newest sample in the first recorded packet to the
            // phone receipt time. Absolute latency remains, but subsequent
            // intervals follow the sensor clock and are not distorted by BLE
            // packet-arrival jitter.
            ecgSensorToUnixOffsetNs =
                packetReceivedUnixNs - samples.last().sensorTimestampNs
        }

        val rows = StringBuilder(samples.size * 190)

        samples.forEach { sample ->
            val sampleIndex = globalEcgSampleIndex.incrementAndGet()

            val estimatedUnixNs: Long
            val timestampSource: String

            if (
                sensorTimesAreUsable &&
                ecgSensorToUnixOffsetNs != null
            ) {
                estimatedUnixNs =
                    sample.sensorTimestampNs + ecgSensorToUnixOffsetNs!!
                timestampSource = "POLAR_SENSOR_RELATIVE"
            } else {
                estimatedUnixNs =
                    if (lastEstimatedEcgUnixNs == null) {
                        packetReceivedUnixNs -
                            (samples.size - 1L) * ECG_SAMPLE_PERIOD_NS
                    } else {
                        lastEstimatedEcgUnixNs!! + ECG_SAMPLE_PERIOD_NS
                    }
                timestampSource = "FIXED_130HZ_FALLBACK"
            }

            rows.append(csvText(participantId)).append(',')
                .append(csvText(sessionId)).append(',')
                .append(csvText(deviceId)).append(',')
                .append(connectionId).append(',')
                .append(packetIndex).append(',')
                .append(sampleIndex).append(',')
                .append(packetReceivedUnixNs).append(',')
                .append(formatMs(packetReceivedUnixNs)).append(',')
                .append(sample.sensorTimestampNs).append(',')
                .append(estimatedUnixNs).append(',')
                .append(formatMs(estimatedUnixNs)).append(',')
                .append(csvText(timestampSource)).append(',')
                .append(sample.voltageMicrovolts)
                .append('\n')

            lastSensorTimestampNs = sample.sensorTimestampNs
            lastEstimatedEcgUnixNs = estimatedUnixNs
        }

        enqueueWrite(ecgTarget.writer, rows.toString())
    }

    /**
     * Saves one row for every RR interval. If a HR packet contains no RR value,
     * it still saves one HR-only row with blank RR fields.
     *
     * Estimated beat times are reconstructed backwards from packet receipt time.
     * They are explicitly named "estimated" because BLE delivery latency remains.
     */
    @Synchronized
    fun appendHrSample(
        connectionId: Long,
        packetIndex: Long,
        hrBpm: Int,
        rrIntervalsMs: List<Int>
    ) {
        if (!active.get()) return

        val packetReceivedUnixNs = nowUnixNs()
        val rows = StringBuilder(maxOf(1, rrIntervalsMs.size) * 180)

        if (rrIntervalsMs.isEmpty()) {
            rows.append(csvText(participantId)).append(',')
                .append(csvText(sessionId)).append(',')
                .append(csvText(deviceId)).append(',')
                .append(connectionId).append(',')
                .append(packetIndex).append(',')
                .append(0).append(',')
                .append("").append(',')
                .append(packetReceivedUnixNs).append(',')
                .append(formatMs(packetReceivedUnixNs)).append(',')
                .append("").append(',')
                .append("").append(',')
                .append(hrBpm).append(',')
                .append(false).append(',')
                .append("")
                .append('\n')
        } else {
            val estimatedBeatUnixNs = LongArray(rrIntervalsMs.size)
            var timeAfterCurrentBeatNs = 0L

            for (index in rrIntervalsMs.indices.reversed()) {
                estimatedBeatUnixNs[index] = packetReceivedUnixNs - timeAfterCurrentBeatNs
                timeAfterCurrentBeatNs += rrIntervalsMs[index].toLong() * 1_000_000L
            }

            rrIntervalsMs.forEachIndexed { index, rrMs ->
                val globalRr = globalRrIndex.incrementAndGet()
                val beatUnixNs = estimatedBeatUnixNs[index]

                rows.append(csvText(participantId)).append(',')
                    .append(csvText(sessionId)).append(',')
                    .append(csvText(deviceId)).append(',')
                    .append(connectionId).append(',')
                    .append(packetIndex).append(',')
                    .append(index + 1).append(',')
                    .append(globalRr).append(',')
                    .append(packetReceivedUnixNs).append(',')
                    .append(formatMs(packetReceivedUnixNs)).append(',')
                    .append(beatUnixNs).append(',')
                    .append(formatMs(beatUnixNs)).append(',')
                    .append(hrBpm).append(',')
                    .append(true).append(',')
                    .append(rrMs)
                    .append('\n')
            }
        }

        enqueueWrite(hrRrTarget.writer, rows.toString())
    }

    @Synchronized
    fun appendEvent(
        event: String,
        detail: String = "",
        connectionId: Long = 0L
    ) {
        if (!active.get()) return

        val unixNs = nowUnixNs()
        val row = buildString {
            append(csvText(participantId)).append(',')
            append(csvText(sessionId)).append(',')
            append(csvText(deviceId)).append(',')
            append(connectionId).append(',')
            append(unixNs).append(',')
            append(formatMs(unixNs)).append(',')
            append(csvText(event)).append(',')
            append(csvText(detail)).append('\n')
        }
        enqueueWrite(eventTarget.writer, row)
    }

    /**
     * Stops accepting new samples, drains all already-enqueued batches, flushes,
     * closes every file, and waits for completion.
     */
    @Synchronized
    fun stop(reason: String = "USER_STOP", connectionId: Long = 0L): StopResult {
        if (stopped.getAndSet(true)) {
            return currentStopResult(closedCleanly = true)
        }

        if (!active.getAndSet(false)) {
            shutdownExecutor()
            return currentStopResult(closedCleanly = true)
        }

        val stopUnixNs = nowUnixNs()
        val stopRow = buildString {
            append(csvText(participantId)).append(',')
            append(csvText(sessionId)).append(',')
            append(csvText(deviceId)).append(',')
            append(connectionId).append(',')
            append(stopUnixNs).append(',')
            append(formatMs(stopUnixNs)).append(',')
            append(csvText("RECORDING_STOPPED")).append(',')
            append(csvText(reason)).append('\n')
        }

        val closedLatch = CountDownLatch(1)
        var cleanClose = true

        try {
            writerExecutor.execute {
                try {
                    eventTarget.writer.write(stopRow)
                    flushAll()
                    closeQuietly(ecgTarget.writer)
                    closeQuietly(hrRrTarget.writer)
                    closeQuietly(eventTarget.writer)
                } catch (_: Throwable) {
                    cleanClose = false
                } finally {
                    closedLatch.countDown()
                }
            }
        } catch (_: RejectedExecutionException) {
            cleanClose = false
            closedLatch.countDown()
        }

        shutdownExecutor()
        val completed = try {
            closedLatch.await(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        return currentStopResult(closedCleanly = cleanClose && completed)
    }

    private fun enqueueWrite(writer: BufferedWriter, text: String) {
        try {
            writerExecutor.execute {
                writer.write(text)
                flushIfDue()
            }
        } catch (_: RejectedExecutionException) {
            // The recorder is already stopping. New data are intentionally rejected.
        }
    }

    private fun flushIfDue() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastFlushElapsedNs >= FLUSH_INTERVAL_NS) {
            flushAll()
            lastFlushElapsedNs = now
        }
    }

    private fun flushAll() {
        ecgTarget.writer.flush()
        hrRrTarget.writer.flush()
        eventTarget.writer.flush()
    }

    private fun shutdownExecutor() {
        writerExecutor.shutdown()
    }

    private fun currentStopResult(closedCleanly: Boolean): StopResult {
        return StopResult(
            sessionId = sessionId,
            directoryDescription = directoryDescription,
            ecgFileName = if (::ecgTarget.isInitialized) ecgTarget.fileName else "",
            hrRrFileName = if (::hrRrTarget.isInitialized) hrRrTarget.fileName else "",
            eventFileName = if (::eventTarget.isInitialized) eventTarget.fileName else "",
            closedCleanly = closedCleanly
        )
    }

    @Throws(IOException::class)
    private fun createCsvTarget(fileName: String): CsvTarget {
        val output: OutputStream
        val uri: Uri?
        val pathDescription: String

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOCUMENTS}/$DIRECTORY_NAME"
                )
            }

            uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"),
                values
            ) ?: throw IOException("Unable to create $fileName in MediaStore")

            output = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("Unable to open $fileName for writing")
            pathDescription = directoryDescription
        } else {
            @Suppress("DEPRECATION")
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                DIRECTORY_NAME
            )
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Unable to create ${directory.absolutePath}")
            }
            val file = File(directory, fileName)
            output = FileOutputStream(file, false)
            uri = null
            pathDescription = file.absolutePath
        }

        val writer = BufferedWriter(
            OutputStreamWriter(output, Charsets.UTF_8),
            64 * 1024
        )
        return CsvTarget(fileName, writer, uri, pathDescription)
    }

    private fun closeQuietly(writer: BufferedWriter) {
        try {
            writer.close()
        } catch (_: Throwable) {
        }
    }

    private fun formatMs(unixNs: Long): String {
        val wholeMs = unixNs / 1_000_000L
        val fractionalNs = unixNs % 1_000_000L
        return String.format(Locale.US, "%d.%06d", wholeMs, fractionalNs)
    }

    private fun csvText(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun cleanField(value: String): String {
        return value.trim().replace(Regex("[\\r\\n\\t]"), " ")
    }

    private fun safeFilePart(value: String): String {
        val cleaned = value.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
        return if (cleaned.isBlank()) "test" else cleaned.take(48)
    }
}
