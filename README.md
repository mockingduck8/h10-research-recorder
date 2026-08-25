# H10 Research Recorder

[中文说明](README.zh-CN.md)

H10 Research Recorder is an independent Android research-recording example. It uses Polar BLE SDK 5.1.0 to acquire ECG, heart-rate (HR), and RR-interval data from compatible devices, and writes each recording session to CSV files.

> **Unofficial project.** This project is not a Polar product and is not sponsored, endorsed, or approved by Polar. “Polar” and “H10” are trademarks of Polar Electro Oy and are used here only to identify the SDK dependency and device compatibility.

## Features

- Foreground-service recording designed to continue through screen lock, Home, app switching, and Activity recreation.
- ECG, HR, and RR acquisition with session CSV output.
- Connection events and `connection_id` values to identify reconnect boundaries.
- Manual reconnect control when a device is stuck connecting or streams do not resume.
- Raw ECG storage: `ecg_uV` records the integer value returned by the SDK without filtering, smoothing, normalization, resampling, interpolation, artifact repair, R-peak detection, or HRV calculation.

## Requirements

- Android Studio with JDK 17.
- An Android device with Bluetooth and the required runtime permissions.
- A compatible device and your own device ID.

## Getting started

1. Open this project folder in Android Studio and wait for Gradle sync to finish.
2. Build and run the `app` module on an Android device.
3. Grant nearby-devices/Bluetooth and notification permissions when requested.
4. Enter a participant ID and your device ID. `YOUR_H10_DEVICE_ID` is only a placeholder and must be replaced locally; never commit a real device ID.
5. Open the ECG + HR/RR recorder, wait for the streams to become active, then select **START RECORDING**.

Recordings are written under `Documents/PolarExperiment`. These files can contain research or personal data and are intentionally excluded by `.gitignore`.

## Important operational notes

- Allow notifications and exempt the app from battery optimization before a real recording session.
- Do not force-stop the app, disable Bluetooth, or use another app that takes over the device while recording.
- A reconnect can create a real gap in the data. Use `events.csv` and `connection_id` when analysing recordings.

## License and attribution

- The Polar BLE SDK and related materials are governed by their original license, reproduced verbatim in [Polar_SDK_License.txt](Polar_SDK_License.txt).
- Original copyright and license notices in the source and SDK materials must be retained.
- You are responsible for confirming that your use complies with the SDK license, applicable privacy/data-protection law, research ethics requirements, and device limitations. This application is not intended for medical, life-critical, or life-support use.

## Documentation

For the complete Chinese usage guide, including CSV field definitions, test recommendations, and reconnection behaviour, see [README.zh-CN.md](README.zh-CN.md).
