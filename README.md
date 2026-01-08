# Parking Permit Sync

Android companion app for the [parking-permit-display](https://github.com/VisTechProjects/parking-permit-display) ESP32 e-ink project.

## What It Does

1. Fetches your Toronto parking permit data
2. Syncs to ESP32 display via Bluetooth LE
3. Runs in background for automatic updates

## Related Projects

- [parking-permit-display](https://github.com/VisTechProjects/parking-permit-display) - ESP32 e-ink firmware
- [parking-permit-buyer](https://github.com/VisTechProjects/parking-permit-buyer) - Automated permit purchasing
- [Parking permit website](https://ilovekitty.ca/parking/) - Web dashboard

## Setup

1. Install APK on Android phone
2. Grant Bluetooth permissions
3. Open app near ESP32 display
4. They will find each other automatically

## Features

- BLE sync to ESP32 display
- Flip display orientation setting
- Manual sync button
- Background operation
