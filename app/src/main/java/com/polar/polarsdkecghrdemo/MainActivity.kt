package com.polar.polarsdkecghrdemo

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "Polar_MainActivity"
        private const val DEVICE_ID_KEY = "polar_device_id"
        private const val DEVICE_ID_PLACEHOLDER = "YOUR_H10_DEVICE_ID"
        private const val PARTICIPANT_ID_KEY = "participant_id"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var participantInput: EditText
    private var deviceId: String? = null

    private val bluetoothOnActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                result: ActivityResult ->
            if (result.resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "Bluetooth was not enabled")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getPreferences(MODE_PRIVATE)
        deviceId = sharedPreferences.getString(DEVICE_ID_KEY, DEVICE_ID_PLACEHOLDER)

        participantInput = findViewById(R.id.inputParticipantId)
        participantInput.setText(
            sharedPreferences.getString(PARTICIPANT_ID_KEY, "P001")
        )

        val setIdButton: Button = findViewById(R.id.buttonSetID)
        val recorderButton: Button = findViewById(R.id.buttonConnectEcg)
        val batterySettingsButton: Button =
            findViewById(R.id.buttonBatterySettings)

        checkBT()

        setIdButton.setOnClickListener { showDeviceIdDialog(it) }
        recorderButton.setOnClickListener { openResearchRecorder(it) }
        batterySettingsButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }

    private fun openResearchRecorder(view: View) {
        checkBT()

        val participantId = participantInput.text.toString().trim()
        if (participantId.isEmpty()) {
            participantInput.error = "Enter a participant ID"
            return
        }

        if (deviceId.isNullOrBlank() || deviceId == DEVICE_ID_PLACEHOLDER) {
            showDeviceIdDialog(view)
            showToast("Set your device ID, then tap the recorder button again.")
            return
        }

        sharedPreferences.edit()
            .putString(PARTICIPANT_ID_KEY, participantId)
            .apply()

        showToast("Connecting to $deviceId")
        val intent = Intent(this, ECGActivity::class.java).apply {
            putExtra("id", deviceId)
            putExtra("participant_id", participantId)
        }
        startActivity(intent)
    }

    private fun showDeviceIdDialog(view: View) {
        val dialog = AlertDialog.Builder(this, R.style.PolarTheme)
        dialog.setTitle("Enter the Polar device ID")

        val viewInflated = LayoutInflater.from(applicationContext)
            .inflate(
                R.layout.device_id_dialog_layout,
                view.rootView as ViewGroup,
                false
            )
        val input = viewInflated.findViewById<EditText>(R.id.input)
        if (!deviceId.isNullOrEmpty()) input.setText(deviceId)
        input.inputType = InputType.TYPE_CLASS_TEXT
        dialog.setView(viewInflated)

        dialog.setPositiveButton("OK") { _: DialogInterface?, _: Int ->
            deviceId = input.text.toString().trim().uppercase()
            sharedPreferences.edit()
                .putString(DEVICE_ID_KEY, deviceId)
                .apply()
            showToast("Polar device ID: $deviceId")
        }
        dialog.setNegativeButton("Cancel") {
                dialogInterface: DialogInterface,
                _: Int ->
            dialogInterface.cancel()
        }
        dialog.show()
    }

    private fun checkBT() {
        val btManager =
            applicationContext.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = btManager.adapter

        if (bluetoothAdapter == null) {
            showToast("This phone does not support Bluetooth.")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            bluetoothOnActivityResultLauncher.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            )
        }

        val permissions = mutableListOf<String>()

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                permissions += Manifest.permission.BLUETOOTH_SCAN
                permissions += Manifest.permission.BLUETOOTH_CONNECT
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                permissions += Manifest.permission.ACCESS_FINE_LOCATION
            }
            else -> {
                permissions += Manifest.permission.ACCESS_COARSE_LOCATION
            }
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        val missingPermissions = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != PERMISSION_REQUEST_CODE) return

        grantResults.forEachIndexed { index, result ->
            if (result == PackageManager.PERMISSION_DENIED) {
                Log.w(TAG, "Permission denied: ${permissions[index]}")
                showToast("A required permission was denied: ${permissions[index]}")
                return
            }
        }
        Log.d(TAG, "Required permissions granted")
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
