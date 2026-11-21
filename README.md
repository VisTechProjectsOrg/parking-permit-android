# Parking Permit Sync

Android companion app for the [parking-permit-display](https://github.com/VisTechProjectsOrg/parking-permit-display) ESP32 e-ink project.

## What It Does

1. Fetches the current and next permit from the [parking permit site](https://fucktorontoparking.ca/) (daily at 3 AM and when opened)
2. Serves them to the e-ink display over Bluetooth LE whenever the display checks in
3. Shows the display's battery level and warns when the car isn't covered

## Setup

1. Install the APK
2. Allow Bluetooth and notifications
3. Allow background access and turn off "Pause app activity if unused" when asked (Samsung: "Manage app if unused")
4. Press the display's button with the phone nearby

## Features

- Runs in the background and survives reboots, app updates and being swiped away
- Display battery icon with %; tap for time left and last sync times
- Alerts when no permit is bought for tomorrow or the permit has expired
- Low display battery notification
- Display flip setting (applies at the display's next check-in)

## Related Projects

- [parking-permit-display](https://github.com/VisTechProjectsOrg/parking-permit-display) - ESP32 e-ink firmware
- [parking-permit-buyer](https://github.com/VisTechProjectsOrg/parking-permit-buyer) - Automated permit purchasing
- [Parking permit website](https://fucktorontoparking.ca/) - Web dashboard
