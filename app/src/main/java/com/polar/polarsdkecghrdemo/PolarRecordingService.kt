package com.polar.polarsdkecghrdemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl.defaultImplementation
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarEcgData
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.disposables.Disposable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Foreground owner of the Polar BLE connection and all acquisition state.
 *
 * The Activity is only a display/controller. Leaving it, locking the screen, or
 * switching apps does not dispose the Polar SDK or close the CSV files.
 */
class PolarRecordingService : Service() {
    companion object {
        private const val TAG = "PolarRecordingService"
        private const val CHANNEL_ID = "polar_research_recording"
        private const val NOTIFICATION_ID = 13010
        private const val PREFS = "polar_recorder_service"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_PARTICIPANT_ID = "participant_id"

        const val ACTION_CONNECT =
            "com.polar.polarsdkecghrdemo.action.CONNECT"
        const val ACTION_START_RECORDING =
            "com.polar.polarsdkecghrdemo.action.START_RECORDING"
        const val ACTION_STOP_RECORDING =
            "com.polar.polarsdkecghrdemo.action.STOP_RECORDING"
        const val ACTION_FORCE_RECONNECT =
            "com.polar.polarsdkecghrdemo.action.FORCE_RECONNECT"
        const val ACTION_DISCONNECT_AND_STOP =
            "com.polar.polarsdkecghrdemo.action.DISCONNECT_AND_STOP"

        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_PARTICIPANT_ID = "participant_id"

        private const val RECONNECT_BASE_DELAY_MS = 5_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val CONNECT_WATCHDOG_MS = 25_000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 2_000L

        fun connectIntent(
            context: Context,
            deviceId: String,
            participantId: String
        ): Intent = Intent(context, PolarRecordingService::class.java).apply {
            action = ACTION_CONNECT
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_PARTICIPANT_ID, participantId)
        }
    }

    inner class LocalBinder : Binder() {
        fun service(): PolarRecordingService = this@PolarRecordingService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<RecorderServiceListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PolarRecorderControl")
    }
    private val stateLock = Any()

    private lateinit var api: PolarBleApi
    private var ecgDisposable: Disposable? = null
    private var hrDisposable: Disposable? = null
    private var recorder: CsvSessionRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var desiredServiceRunning = false

    @Volatile
    private var connectInProgress = false

    @Volatile
    private var ecgStreaming = false

    @Volatile
    private var hrStreaming = false

    private var reconnectRunnable: Runnable? = null
    private var connectWatchdogRunnable: Runnable? = null
    private var lastNotificationUpdateElapsedMs = 0L

    private val ecgPacketIndex = AtomicLong(0L)
    private val hrPacketIndex = AtomicLong(0L)
    private val ecgSampleCount = AtomicLong(0L)
    private val rrCount = AtomicLong(0L)
    private val skippedDuplicateHrSamples = AtomicLong(0L)
    private val reconnectCount = AtomicLong(0L)
    private val connectionIdCounter = AtomicLong(0L)

    private var lastHrSignature: String? = null
    private var lastHrAcceptedElapsedNs: Long = Long.MIN_VALUE

    @Volatile
    private var snapshot = RecorderSnapshot()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createWakeLock()
        configurePolarApi()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A foreground service must publish its notification immediately.
        ensureForeground()

        when (intent?.action) {
            ACTION_CONNECT -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                    ?.trim()
                    ?.uppercase()
                    .orEmpty()
                val participantId = intent.getStringExtra(EXTRA_PARTICIPANT_ID)
                    ?.trim()
                    .orEmpty()

                if (deviceId.isNotBlank()) {
                    configureSession(
                        deviceId = deviceId,
                        participantId = participantId.ifBlank { "test" }
                    )
                }
            }

            ACTION_START_RECORDING -> requestStartRecording()
            ACTION_STOP_RECORDING -> requestStopRecording("NOTIFICATION_STOP")
            ACTION_FORCE_RECONNECT -> forceReconnect("NOTIFICATION_FORCE_RECONNECT")
            ACTION_DISCONNECT_AND_STOP -> disconnectAndStopService()

            null -> {
                // START_STICKY process recreation: reconnect but never silently
                // resume a recording whose file handles were lost with the process.
                val preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
                val deviceId = preferences.getString(PREF_DEVICE_ID, null)
                val participantId = preferences.getString(
                    PREF_PARTICIPANT_ID,
                    "test"
                )
                if (!deviceId.isNullOrBlank()) {
                    updateSnapshot {
                        it.copy(
                            status = "Service restarted; reconnecting. Start a new recording after connection.",
                            recording = false
                        )
                    }
                    configureSession(deviceId, participantId ?: "test")
                }
            }
        }

        return START_STICKY
    }

    fun registerListener(listener: RecorderServiceListener) {
        listeners += listener
        mainHandler.post {
            listener.onRecorderSnapshot(currentSnapshot())
        }
    }

    fun unregisterListener(listener: RecorderServiceListener) {
        listeners -= listener
    }

    fun currentSnapshot(): RecorderSnapshot = synchronized(stateLock) {
        snapshot.copy(rrValuesMs = snapshot.rrValuesMs.toList())
    }

    fun requestStartRecording() {
        val state = currentSnapshot()
        if (!state.connected || !state.streamingReady || state.recording) {
            updateSnapshot {
                it.copy(status = "Wait until ECG and HR/RR streams are active")
            }
            return
        }

        controlExecutor.execute {
            startRecordingInternal()
        }
    }

    fun requestStopRecording(reason: String = "USER_STOP") {
        val wasRecording = synchronized(stateLock) {
            if (!snapshot.recording) {
                false
            } else {
                snapshot = snapshot.copy(
                    recording = false,
                    status = "Stopping and flushing CSV files…"
                )
                true
            }
        }
        if (!wasRecording) return

        publishSnapshot()
        updateNotification(force = true)

        controlExecutor.execute {
            stopRecordingInternal(reason)
        }
    }

    fun forceReconnect(reason: String = "USER_FORCE_RECONNECT") {
        desiredServiceRunning = true

        val state = currentSnapshot()
        recorder?.appendEvent(
            event = "FORCE_RECONNECT_REQUESTED",
            detail = reason,
            connectionId = state.connectionId
        )

        cancelReconnect()
        cancelConnectWatchdog()
        connectInProgress = false
        disposeStreams()

        updateSnapshot {
            it.copy(
                connected = false,
                streamingReady = false,
                status = if (it.recording) {
                    "Manual reconnect: resetting Polar BLE; recording files stay open…"
                } else {
                    "Manual reconnect: resetting Polar BLE…"
                }
            )
        }
        updateNotification(force = true)

        mainHandler.post {
            disconnectQuietly(state.deviceId)
            try {
                api.shutDown()
            } catch (_: Throwable) {
            }

            configurePolarApi()

            mainHandler.postDelayed(
                {
                    if (desiredServiceRunning) {
                        connectNow()
                    }
                },
                1_500L
            )
        }
    }

    fun disconnectAndStopService() {
        if (currentSnapshot().recording) {
            requestStopRecording("USER_DISCONNECT")
            controlExecutor.execute {
                // Allow the stop task queued immediately before this one to finish.
                mainHandler.post { shutdownConnectionAndService() }
            }
        } else {
            shutdownConnectionAndService()
        }
    }

    private fun configureSession(deviceId: String, participantId: String) {
        desiredServiceRunning = true

        val previous = currentSnapshot()
        if (previous.recording) {
            if (
                previous.deviceId != deviceId ||
                previous.participantId != participantId
            ) {
                updateSnapshot {
                    it.copy(
                        status = "Recording is active for ${previous.participantId}; " +
                            "participant/device cannot be changed until Stop & Save"
                    )
                }
            }
            return
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(PREF_DEVICE_ID, deviceId)
            .putString(PREF_PARTICIPANT_ID, participantId)
            .apply()

        val deviceChanged = previous.deviceId.isNotBlank() &&
            previous.deviceId != deviceId

        updateSnapshot {
            it.copy(
                participantId = participantId,
                deviceId = deviceId,
                status = if (it.connected && !deviceChanged) {
                    "Connected; ECG and HR/RR streams active"
                } else {
                    "Preparing Polar H10 connection…"
                }
            )
        }

        if (deviceChanged) {
            disposeStreams()
            disconnectQuietly(previous.deviceId)
            updateSnapshot {
                it.copy(
                    connected = false,
                    streamingReady = false
                )
            }
        }

        if (!currentSnapshot().connected && !connectInProgress) {
            connectNow()
        }
        updateNotification(force = true)
    }

    private fun configurePolarApi() {
        api = defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO
            )
        )

        api.setApiLogger { message ->
            Log.d("PolarSDK", message)
        }

        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                if (!powered) {
                    updateSnapshot {
                        it.copy(
                            connected = false,
                            streamingReady = false,
                            status = "Phone Bluetooth is off"
                        )
                    }
                } else if (desiredServiceRunning && !currentSnapshot().connected) {
                    scheduleReconnect("BLUETOOTH_POWER_RESTORED")
                }
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                connectInProgress = true
                updateSnapshot {
                    it.copy(status = "Connecting: ${polarDeviceInfo.deviceId}")
                }
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                connectInProgress = false
                cancelReconnect()
                cancelConnectWatchdog()
                reconnectCount.set(0L)
                val connectionId = connectionIdCounter.incrementAndGet()

                recorder?.appendEvent(
                    event = "DEVICE_CONNECTED",
                    detail = polarDeviceInfo.deviceId,
                    connectionId = connectionId
                )

                updateSnapshot {
                    it.copy(
                        connected = true,
                        streamingReady = false,
                        connectionId = connectionId,
                        reconnectCount = 0L,
                        status = "Connected; preparing ECG and HR/RR streams…"
                    )
                }
                updateNotification(force = true)
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                connectInProgress = false
                disposeStreams()
                val state = currentSnapshot()

                recorder?.appendEvent(
                    event = "DEVICE_DISCONNECTED",
                    detail = polarDeviceInfo.deviceId,
                    connectionId = state.connectionId
                )

                updateSnapshot {
                    it.copy(
                        connected = false,
                        streamingReady = false,
                        status = if (it.recording) {
                            "Connection lost; recording files remain open while reconnecting…"
                        } else {
                            "Disconnected; reconnecting…"
                        }
                    )
                }
                updateNotification(force = true)

                if (desiredServiceRunning) {
                    scheduleReconnect("DEVICE_DISCONNECTED")
                }
            }

            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature
            ) {
                if (
                    feature ==
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING
                ) {
                    startEcgStream()
                    startHrStream()
                }
            }

            override fun disInformationReceived(
                identifier: String,
                uuid: UUID,
                value: String
            ) {
                if (
                    uuid == UUID.fromString(
                        "00002a28-0000-1000-8000-00805f9b34fb"
                    )
                ) {
                    updateSnapshot {
                        it.copy(firmwareVersion = value.trim())
                    }
                }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                updateSnapshot {
                    it.copy(batteryPercent = level)
                }
            }

            override fun hrNotificationReceived(
                identifier: String,
                data: PolarHrData.PolarHrSample
            ) {
                // SDK 5.x HR/RR is collected through startHrStreaming().
            }

            override fun polarFtpFeatureReady(identifier: String) = Unit

            override fun streamingFeaturesReady(
                identifier: String,
                features: Set<PolarBleApi.PolarDeviceDataType>
            ) = Unit

            override fun hrFeatureReady(identifier: String) = Unit
        })
    }

    private fun connectNow() {
        val deviceId = currentSnapshot().deviceId
        if (
            !desiredServiceRunning ||
            deviceId.isBlank() ||
            currentSnapshot().connected ||
            connectInProgress
        ) {
            return
        }

        connectInProgress = true
        updateSnapshot {
            it.copy(status = "Connecting to Polar H10 $deviceId…")
        }

        try {
            api.connectToDevice(deviceId)
            armConnectWatchdog()
        } catch (error: PolarInvalidArgument) {
            connectInProgress = false
            updateSnapshot {
                it.copy(status = "Invalid Polar device ID: $deviceId")
            }
        } catch (error: Throwable) {
            connectInProgress = false
            Log.e(TAG, "Connection request failed", error)
            updateSnapshot {
                it.copy(
                    status = "Connection request failed: ${error.message.orEmpty()}"
                )
            }
            scheduleReconnect("CONNECT_REQUEST_ERROR")
        }
    }

    private fun armConnectWatchdog() {
        cancelConnectWatchdog()
        val runnable = Runnable {
            if (connectInProgress && !currentSnapshot().connected) {
                connectInProgress = false
                recorder?.appendEvent(
                    "CONNECT_TIMEOUT",
                    "No connection callback within ${CONNECT_WATCHDOG_MS}ms",
                    currentSnapshot().connectionId
                )
                disconnectQuietly(currentSnapshot().deviceId)
                scheduleReconnect("CONNECT_TIMEOUT")
            }
        }
        connectWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, CONNECT_WATCHDOG_MS)
    }

    private fun cancelConnectWatchdog() {
        connectWatchdogRunnable?.let(mainHandler::removeCallbacks)
        connectWatchdogRunnable = null
    }

    private fun scheduleReconnect(reason: String) {
        if (!desiredServiceRunning || reconnectRunnable != null) return

        val attempt = reconnectCount.incrementAndGet()
        val exponent = min((attempt - 1L).toInt(), 3)
        val delay = min(
            RECONNECT_BASE_DELAY_MS * (1L shl exponent),
            RECONNECT_MAX_DELAY_MS
        )

        recorder?.appendEvent(
            event = "RECONNECT_SCHEDULED",
            detail = "$reason; attempt=$attempt; delay_ms=$delay",
            connectionId = currentSnapshot().connectionId
        )

        updateSnapshot {
            it.copy(
                reconnectCount = attempt,
                status = "Reconnecting in ${delay / 1000}s (attempt $attempt)…"
            )
        }

        val runnable = Runnable {
            reconnectRunnable = null
            connectNow()
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
        updateNotification(force = true)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun startEcgStream() {
        if (ecgDisposable?.isDisposed == false) return
        val deviceId = currentSnapshot().deviceId
        if (deviceId.isBlank()) return

        ecgDisposable = api
            .requestStreamSettings(
                deviceId,
                PolarBleApi.PolarDeviceDataType.ECG
            )
            .toFlowable()
            .flatMap { setting: PolarSensorSetting ->
                api.startEcgStreaming(deviceId, setting.maxSettings())
            }
            .subscribe(
                { data: PolarEcgData -> handleEcgData(data) },
                { error: Throwable -> handleStreamError("ECG", error) },
                {
                    ecgStreaming = false
                    recorder?.appendEvent(
                        "ECG_STREAM_COMPLETED",
                        connectionId = currentSnapshot().connectionId
                    )
                    refreshStreamingReady()
                }
            )
    }

    private fun handleEcgData(data: PolarEcgData) {
        ecgStreaming = true
        refreshStreamingReady()

        // Raw acquisition: every Polar sample is written exactly once with the
        // SDK-provided microvolt integer. No filtering, baseline correction,
        // resampling, interpolation, or R-peak detection is performed here.
        val rawSamples = data.samples.map { sample ->
            EcgCsvSample(
                sensorTimestampNs = sample.timeStamp,
                voltageMicrovolts = sample.voltage
            )
        }

        val state = currentSnapshot()
        if (state.recording) {
            val packet = ecgPacketIndex.incrementAndGet()
            ecgSampleCount.addAndGet(rawSamples.size.toLong())
            recorder?.appendEcgPacket(
                connectionId = state.connectionId,
                packetIndex = packet,
                samples = rawSamples
            )
            maybePublishCounts()
        }

        if (listeners.isNotEmpty()) {
            val display = rawSamples.map {
                it.voltageMicrovolts.toFloat() / 1000.0f
            }
            mainHandler.post {
                listeners.forEach { listener ->
                    listener.onEcgDisplaySamples(display)
                }
            }
        }
    }

    private fun startHrStream() {
        if (hrDisposable?.isDisposed == false) return
        val deviceId = currentSnapshot().deviceId
        if (deviceId.isBlank()) return

        hrDisposable = api.startHrStreaming(deviceId)
            .subscribe(
                { data: PolarHrData -> handleHrData(data) },
                { error: Throwable -> handleStreamError("HR_RR", error) },
                {
                    hrStreaming = false
                    recorder?.appendEvent(
                        "HR_RR_STREAM_COMPLETED",
                        connectionId = currentSnapshot().connectionId
                    )
                    refreshStreamingReady()
                }
            )
    }

    private fun handleHrData(data: PolarHrData) {
        hrStreaming = true
        refreshStreamingReady()

        // SDK 5.1.0 may expose an identical adjacent sample twice in a single
        // emission. Suppress only exact, sub-100 ms repeats; ECG stays entirely raw.
        val uniqueSamples = data.samples.distinctBy { sample ->
            sample.hr to sample.rrsMs.toList()
        }

        uniqueSamples.forEach { sample ->
            val rrValues = sample.rrsMs.toList()
            val signature = "${sample.hr}|${rrValues.joinToString(",")}" 
            val nowElapsedNs = SystemClock.elapsedRealtimeNanos()
            val rapidDuplicate =
                signature == lastHrSignature &&
                    lastHrAcceptedElapsedNs != Long.MIN_VALUE &&
                    nowElapsedNs - lastHrAcceptedElapsedNs < 100_000_000L

            if (rapidDuplicate) {
                skippedDuplicateHrSamples.incrementAndGet()
                return@forEach
            }

            lastHrSignature = signature
            lastHrAcceptedElapsedNs = nowElapsedNs

            val state = currentSnapshot()
            if (state.recording) {
                val packet = hrPacketIndex.incrementAndGet()
                recorder?.appendHrSample(
                    connectionId = state.connectionId,
                    packetIndex = packet,
                    hrBpm = sample.hr,
                    rrIntervalsMs = rrValues
                )
                if (rrValues.isNotEmpty()) {
                    rrCount.addAndGet(rrValues.size.toLong())
                }
            }

            updateSnapshot {
                it.copy(
                    hrBpm = sample.hr,
                    rrValuesMs = rrValues,
                    ecgSampleCount = ecgSampleCount.get(),
                    rrCount = rrCount.get(),
                    skippedDuplicateHrSamples =
                        skippedDuplicateHrSamples.get()
                )
            }
            updateNotification(force = false)
        }
    }

    private fun refreshStreamingReady() {
        val ready = ecgStreaming && hrStreaming && currentSnapshot().connected
        updateSnapshot {
            it.copy(
                streamingReady = ready,
                status = when {
                    ready && it.recording -> "RECORDING — ECG + HR + RR"
                    ready -> "Connected; ECG and HR/RR streams active"
                    it.connected -> "Connected; starting data streams…"
                    else -> it.status
                }
            )
        }
    }

    private fun handleStreamError(stream: String, error: Throwable) {
        Log.e(TAG, "$stream stream failed", error)
        val state = currentSnapshot()
        recorder?.appendEvent(
            event = "${stream}_STREAM_ERROR",
            detail = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            connectionId = state.connectionId
        )

        if (stream == "ECG") {
            ecgDisposable = null
            ecgStreaming = false
        } else {
            hrDisposable = null
            hrStreaming = false
        }

        updateSnapshot {
            it.copy(
                streamingReady = false,
                status = "$stream stream error; reconnecting…"
            )
        }
        restartConnection("${stream}_STREAM_ERROR")
    }

    private fun restartConnection(reason: String) {
        disposeStreams()
        disconnectQuietly(currentSnapshot().deviceId)
        updateSnapshot {
            it.copy(connected = false, streamingReady = false)
        }
        scheduleReconnect(reason)
    }

    private fun startRecordingInternal() {
        val state = currentSnapshot()
        if (!state.connected || !state.streamingReady || state.recording) return

        ecgPacketIndex.set(0L)
        hrPacketIndex.set(0L)
        ecgSampleCount.set(0L)
        rrCount.set(0L)
        skippedDuplicateHrSamples.set(0L)
        lastHrSignature = null
        lastHrAcceptedElapsedNs = Long.MIN_VALUE

        val newRecorder = CsvSessionRecorder(
            applicationContext,
            state.participantId,
            state.deviceId
        )

        try {
            newRecorder.start()
        } catch (error: IOException) {
            Log.e(TAG, "Cannot create recording files", error)
            updateSnapshot {
                it.copy(
                    status = "Cannot create CSV files: ${error.message.orEmpty()}"
                )
            }
            return
        }

        recorder = newRecorder
        newRecorder.appendEvent(
            event = "RECORDING_CONNECTION_CONTEXT",
            detail = "Foreground service recording started",
            connectionId = state.connectionId
        )
        acquireRecordingWakeLock()

        updateSnapshot {
            it.copy(
                recording = true,
                status = "RECORDING — ECG + HR + RR",
                ecgSampleCount = 0L,
                rrCount = 0L,
                skippedDuplicateHrSamples = 0L,
                sessionId = newRecorder.sessionId,
                saveDescription = newRecorder.directoryDescription,
                lastSavedFiles = ""
            )
        }
        updateNotification(force = true)
    }

    private fun stopRecordingInternal(reason: String) {
        val currentRecorder = recorder
        recorder = null
        val result = currentRecorder?.stop(reason, currentSnapshot().connectionId)
        releaseRecordingWakeLock()

        val saved = if (result == null) {
            ""
        } else {
            buildString {
                append(
                    if (result.closedCleanly) {
                        "Saved successfully"
                    } else {
                        "Stopped; file close was not fully confirmed"
                    }
                )
                append("\n${result.directoryDescription}")
                append("\n${result.ecgFileName}")
                append("\n${result.hrRrFileName}")
                append("\n${result.eventFileName}")
            }
        }

        updateSnapshot {
            it.copy(
                recording = false,
                status = when {
                    it.streamingReady ->
                        "Connected; streams continue, ready for another recording"
                    it.connected -> "Connected; waiting for streams"
                    else -> "Disconnected; reconnecting"
                },
                sessionId = null,
                lastSavedFiles = saved
            )
        }
        updateNotification(force = true)
    }

    private var lastCountPublishElapsedMs = 0L

    private fun maybePublishCounts() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCountPublishElapsedMs < 1_000L) return
        lastCountPublishElapsedMs = now
        updateSnapshot {
            it.copy(
                ecgSampleCount = ecgSampleCount.get(),
                rrCount = rrCount.get(),
                skippedDuplicateHrSamples =
                    skippedDuplicateHrSamples.get()
            )
        }
        updateNotification(force = false)
    }

    private fun disposeStreams() {
        ecgDisposable?.let { if (!it.isDisposed) it.dispose() }
        hrDisposable?.let { if (!it.isDisposed) it.dispose() }
        ecgDisposable = null
        hrDisposable = null
        ecgStreaming = false
        hrStreaming = false
    }

    private fun disconnectQuietly(deviceId: String) {
        if (deviceId.isBlank()) return
        try {
            api.disconnectFromDevice(deviceId)
        } catch (_: Throwable) {
        }
    }

    private fun shutdownConnectionAndService() {
        desiredServiceRunning = false
        cancelReconnect()
        cancelConnectWatchdog()
        disposeStreams()
        disconnectQuietly(currentSnapshot().deviceId)
        releaseRecordingWakeLock()
        updateSnapshot {
            it.copy(
                connected = false,
                streamingReady = false,
                recording = false,
                status = "Service stopped"
            )
        }
        stopForeground(true)
        stopSelf()
    }

    private fun updateSnapshot(
        transform: (RecorderSnapshot) -> RecorderSnapshot
    ) {
        synchronized(stateLock) {
            snapshot = transform(snapshot)
        }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val state = currentSnapshot()
        mainHandler.post {
            listeners.forEach { listener ->
                listener.onRecorderSnapshot(state)
            }
        }
    }

    private fun createWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:PolarContinuousRecording"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireRecordingWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) {
            lock.acquire()
        }
    }

    private fun releaseRecordingWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Polar ECG recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Polar H10 ECG, HR, and RR acquisition active"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureForeground() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun updateNotification(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (
            !force &&
            now - lastNotificationUpdateElapsedMs <
            NOTIFICATION_UPDATE_INTERVAL_MS
        ) {
            return
        }
        lastNotificationUpdateElapsedMs = now
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = currentSnapshot()

        val openIntent = Intent(this, ECGActivity::class.java).apply {
            putExtra("id", state.deviceId)
            putExtra("participant_id", state.participantId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopRecordingIntent = Intent(
            this,
            PolarRecordingService::class.java
        ).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopRecordingPendingIntent = PendingIntent.getService(
            this,
            101,
            stopRecordingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val forceReconnectIntent = Intent(
            this,
            PolarRecordingService::class.java
        ).apply {
            action = ACTION_FORCE_RECONNECT
        }
        val forceReconnectPendingIntent = PendingIntent.getService(
            this,
            103,
            forceReconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(
            this,
            PolarRecordingService::class.java
        ).apply {
            action = ACTION_DISCONNECT_AND_STOP
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            102,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (state.recording) {
            "Polar recording in progress"
        } else {
            "Polar recorder service"
        }
        val body = when {
            state.recording && state.connected ->
                "${state.participantId} · HR ${state.hrBpm ?: "--"} · " +
                    "ECG ${state.ecgSampleCount} samples"
            state.recording ->
                "${state.participantId} · reconnecting; files remain open"
            else -> state.status
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_pulse)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.recording) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Stop & save",
                stopRecordingPendingIntent
            )
        }
        builder.addAction(
            android.R.drawable.ic_popup_sync,
            "Reconnect",
            forceReconnectPendingIntent
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Disconnect",
            disconnectPendingIntent
        )

        return builder.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        recorder?.appendEvent(
            event = "APP_TASK_REMOVED",
            detail = "Foreground service continues",
            connectionId = currentSnapshot().connectionId
        )
        updateNotification(force = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        desiredServiceRunning = false
        cancelReconnect()
        cancelConnectWatchdog()

        val currentRecorder = recorder
        recorder = null
        if (currentRecorder?.isRecording() == true) {
            currentRecorder.stop("SERVICE_DESTROYED", currentSnapshot().connectionId)
        }

        disposeStreams()
        disconnectQuietly(currentSnapshot().deviceId)
        releaseRecordingWakeLock()
        controlExecutor.shutdown()

        try {
            api.shutDown()
        } catch (_: Throwable) {
        }

        super.onDestroy()
    }
}
