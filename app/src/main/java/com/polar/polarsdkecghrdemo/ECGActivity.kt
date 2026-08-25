package com.polar.polarsdkecghrdemo

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.androidplot.xy.BoundaryMode
import com.androidplot.xy.StepMode
import com.androidplot.xy.XYPlot

/**
 * Display/controller for [PolarRecordingService].
 *
 * No BLE connection or file writer belongs to this Activity. Therefore screen
 * lock, Home, app switching, and Activity recreation do not stop acquisition.
 */
class ECGActivity : AppCompatActivity(), PlotterListener,
    RecorderServiceListener {

    private lateinit var deviceId: String
    private lateinit var participantId: String

    private lateinit var textViewParticipant: TextView
    private lateinit var textViewDeviceId: TextView
    private lateinit var textViewStatus: TextView
    private lateinit var textViewHR: TextView
    private lateinit var textViewRR: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var textViewFwVersion: TextView
    private lateinit var textViewCounts: TextView
    private lateinit var textViewSaveLocation: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var plot: XYPlot
    private lateinit var ecgPlotter: EcgPlotter

    private var recordingService: PolarRecordingService? = null
    private var serviceBound = false
    private var latestSnapshot = RecorderSnapshot()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as PolarRecordingService.LocalBinder
            recordingService = localBinder.service()
            serviceBound = true
            recordingService?.registerListener(this@ECGActivity)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            recordingService = null
            serviceBound = false
            latestSnapshot = latestSnapshot.copy(
                connected = false,
                streamingReady = false,
                status = "Recorder service disconnected"
            )
            renderSnapshot(latestSnapshot)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecg)

        deviceId = intent.getStringExtra("id")
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
            ?: "YOUR_H10_DEVICE_ID"
        participantId = intent.getStringExtra("participant_id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "test"

        bindViews()
        configurePlot()
        configureButtons()

        textViewParticipant.text = "Participant: $participantId"
        textViewDeviceId.text = "Polar H10: $deviceId"
        textViewStatus.text = "Status: Starting foreground recorder service…"
        updateButtonState()

        ContextCompat.startForegroundService(
            this,
            PolarRecordingService.connectIntent(
                this,
                deviceId,
                participantId
            )
        )
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, PolarRecordingService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        if (serviceBound) {
            recordingService?.unregisterListener(this)
            unbindService(serviceConnection)
            serviceBound = false
            recordingService = null
        }
        super.onStop()
    }

    private fun bindViews() {
        textViewParticipant = findViewById(R.id.participant)
        textViewDeviceId = findViewById(R.id.deviceId)
        textViewStatus = findViewById(R.id.connection_status)
        textViewHR = findViewById(R.id.hr)
        textViewRR = findViewById(R.id.rr)
        textViewBattery = findViewById(R.id.battery_level)
        textViewFwVersion = findViewById(R.id.fw_version)
        textViewCounts = findViewById(R.id.recording_counts)
        textViewSaveLocation = findViewById(R.id.save_location)
        startButton = findViewById(R.id.buttonStartRecording)
        stopButton = findViewById(R.id.buttonStopRecording)
        reconnectButton = findViewById(R.id.buttonForceReconnect)
        disconnectButton = findViewById(R.id.buttonDisconnectService)
        plot = findViewById(R.id.plot)
    }

    private fun configurePlot() {
        ecgPlotter = EcgPlotter("ECG", 130)
        ecgPlotter.setListener(this)

        plot.addSeries(ecgPlotter.getSeries(), ecgPlotter.formatter)
        plot.setRangeBoundaries(-1.5, 1.5, BoundaryMode.FIXED)
        plot.setRangeStep(StepMode.INCREMENT_BY_FIT, 0.25)
        plot.setDomainStep(StepMode.INCREMENT_BY_VAL, 130.0)
        plot.setDomainBoundaries(0, 650, BoundaryMode.FIXED)
        plot.linesPerRangeLabel = 2
    }

    private fun configureButtons() {
        startButton.setOnClickListener {
            recordingService?.requestStartRecording()
                ?: showToast("Recorder service is not bound yet")
        }
        stopButton.setOnClickListener {
            recordingService?.requestStopRecording("USER_STOP")
        }
        reconnectButton.setOnClickListener {
            if (latestSnapshot.recording) {
                AlertDialog.Builder(this)
                    .setTitle("Force reconnect H10")
                    .setMessage(
                        "Recording will continue in the same CSV files, but the " +
                            "reconnection will create a missing-data interval and a new " +
                            "connection_id. Continue?"
                    )
                    .setPositiveButton("Reconnect") { _, _ ->
                        recordingService?.forceReconnect("USER_FORCE_RECONNECT")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                recordingService?.forceReconnect("USER_FORCE_RECONNECT")
            }
        }
        disconnectButton.setOnClickListener {
            confirmDisconnect()
        }
    }

    private fun confirmDisconnect() {
        val message = if (latestSnapshot.recording) {
            "Stop and save the active recording, disconnect the H10, and close the background service?"
        } else {
            "Disconnect the H10 and close the background service?"
        }

        AlertDialog.Builder(this)
            .setTitle("Disconnect Polar recorder")
            .setMessage(message)
            .setPositiveButton("Disconnect") { _, _ ->
                recordingService?.disconnectAndStopService()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRecorderSnapshot(snapshot: RecorderSnapshot) {
        latestSnapshot = snapshot
        renderSnapshot(snapshot)
    }

    private fun renderSnapshot(snapshot: RecorderSnapshot) {
        runOnUiThread {
            if (snapshot.participantId.isNotBlank()) {
                textViewParticipant.text =
                    "Participant: ${snapshot.participantId}"
            }
            if (snapshot.deviceId.isNotBlank()) {
                textViewDeviceId.text = "Polar H10: ${snapshot.deviceId}"
            }

            textViewStatus.text = "Status: ${snapshot.status}"
            textViewHR.text = snapshot.hrBpm?.let { "HR: $it bpm" }
                ?: "HR: -- bpm"
            textViewRR.text = if (snapshot.rrValuesMs.isEmpty()) {
                "RR: waiting…"
            } else {
                "RR: ${snapshot.rrValuesMs.joinToString(" ms, ")} ms"
            }
            textViewBattery.text = snapshot.batteryPercent?.let {
                "Battery: $it%"
            } ?: "Battery: --"
            textViewFwVersion.text = snapshot.firmwareVersion?.let {
                "Firmware: $it"
            } ?: "Firmware: --"

            textViewCounts.text =
                "ECG samples: ${snapshot.ecgSampleCount}    " +
                    "RR intervals: ${snapshot.rrCount}    " +
                    "connection: ${snapshot.connectionId}    " +
                    "reconnects: ${snapshot.reconnectCount}"

            textViewSaveLocation.text = when {
                snapshot.recording ->
                    "Background recording active\n" +
                        "${snapshot.saveDescription}\n" +
                        "Session: ${snapshot.sessionId.orEmpty()}\n" +
                        "You may lock the screen or switch apps."
                snapshot.lastSavedFiles.isNotBlank() ->
                    snapshot.lastSavedFiles
                else ->
                    "Files: ${snapshot.saveDescription}\n" +
                        "Foreground service keeps acquisition active after " +
                        "screen lock or app switching."
            }

            updateButtonState()
        }
    }

    override fun onEcgDisplaySamples(samplesMillivolts: List<Float>) {
        runOnUiThread {
            ecgPlotter.sendSamples(samplesMillivolts)
        }
    }

    private fun updateButtonState() {
        startButton.isEnabled = serviceBound &&
            latestSnapshot.connected &&
            latestSnapshot.streamingReady &&
            !latestSnapshot.recording
        stopButton.isEnabled = serviceBound && latestSnapshot.recording
        reconnectButton.isEnabled = serviceBound
        disconnectButton.isEnabled = serviceBound
    }

    override fun update() {
        runOnUiThread {
            plot.redraw()
        }
    }

    @Deprecated("Deprecated in Android API, retained for minSdk compatibility")
    override fun onBackPressed() {
        if (latestSnapshot.recording) {
            showToast(
                "Recording continues in the background. Use the persistent " +
                    "notification to return or stop and save."
            )
        }
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
