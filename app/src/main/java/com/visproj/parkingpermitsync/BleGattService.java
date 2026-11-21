package com.visproj.parkingpermitsync;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BleGattService extends Service {
    private static final String TAG = "BleGattService";

    // Broadcast actions for UI updates
    public static final String ACTION_SERVICE_RUNNING = "com.visproj.parkingpermitsync.SERVICE_RUNNING";
    public static final String ACTION_DEVICE_CONNECTED = "com.visproj.parkingpermitsync.DEVICE_CONNECTED";
    public static final String ACTION_DEVICE_DISCONNECTED = "com.visproj.parkingpermitsync.DEVICE_DISCONNECTED";
    public static final String ACTION_PERMIT_READ = "com.visproj.parkingpermitsync.PERMIT_READ";
    public static final String ACTION_DISPLAY_STATUS = "com.visproj.parkingpermitsync.DISPLAY_STATUS";
    public static final String EXTRA_DISPLAY_ID = "display_id";

    // BLE UUIDs - ESP32 will use these to find and read permit data
    // Using standard Bluetooth Base UUID format for better compatibility
    public static final UUID SERVICE_UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb");
    public static final UUID PERMIT_CHAR_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    public static final UUID SYNC_TYPE_CHAR_UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");

    // Sync types - ESP32 writes this before reading permit
    private static final byte SYNC_TYPE_AUTO = 1;    // Reboot/auto sync - no notification if same permit
    private static final byte SYNC_TYPE_MANUAL = 2;  // Button press - always show notification
    private static final byte SYNC_TYPE_FORCE = 3;   // Long press - always show notification

    // One per connected display, keyed by its BLE address: several can sync at once
    private static class Session {
        byte syncType = SYNC_TYPE_AUTO;
        PermitRepository.DisplayInfo display;
        byte[] permitJson;
    }
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    // Battery display writes "shownPermit,cachedPermit,batteryMv,fwVersion" after each sync
    public static final UUID STATUS_CHAR_UUID = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb");
    private static final int BATTERY_NOTIFICATION_ID = 6;

    // Silent channel for the foreground service. Android raises a service's channel to at least
    // LOW whatever the app asks for (tested: MIN and NONE both end up LOW), so only the user can
    // turn it off; on Android 13+ the service keeps running when they do. See
    // isBackgroundNotificationHidden().
    // Older channel IDs are deleted (OLD_CHANNEL_IDS).
    public static final String CHANNEL_ID = "background_service";
    private static final String[] OLD_CHANNEL_IDS = {"ble_service_channel", "background_sync"};
    private static final int NOTIFICATION_ID = 1;
    private static final int SYNC_NOTIFICATION_ID = 2;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private PermitRepository repository;

    private boolean isAdvertising = false;
    private static boolean isRunning = false;

    public static boolean isServiceRunning() {
        return isRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        repository = new PermitRepository(this);
        bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        createNotificationChannel();

        // Bluetooth toggles (airplane mode, quick settings) kill the GATT server and advertising
        registerReceiver(bluetoothStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    // Start the service if it isn't running, or nudge it to restart a dead server/advertiser.
    // Called from the daily alarms so a killed service comes back without opening the app.
    public static void ensureRunning(Context context) {
        try {
            Intent intent = new Intent(context, BleGattService.class);
            context.startForegroundService(intent);
        } catch (Exception e) {
            // Android 12+ can refuse a background start (e.g. inexact alarm); next app open restarts it
            Log.e(TAG, "Could not start BLE service", e);
        }
    }

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                Log.d(TAG, "Bluetooth on, restarting BLE server");
                startBleServer();
            } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                Log.d(TAG, "Bluetooth off, tearing down BLE server");
                stopBleServer();
            }
        }
    };

    private void stopBleServer() {
        if (advertiser != null && isAdvertising) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping advertising", e);
            }
        }
        isAdvertising = false;
        if (gattServer != null) {
            try {
                gattServer.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing GATT server", e);
            }
            gattServer = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");
        isRunning = true;
        sendBroadcast(ACTION_SERVICE_RUNNING);

        startForeground();
        startBleServer();

        return START_STICKY;
    }

    private void startForeground() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Keeping your display up to date")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startBleServer() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth not available or disabled");
            return;
        }

        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions");
            return;
        }

        // Already up (repeat start from the app or an alarm): only make sure we're advertising
        if (gattServer != null) {
            startAdvertising();
            return;
        }

        // Start GATT server
        try {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback);
            if (gattServer == null) {
                Log.e(TAG, "Failed to open GATT server");
                return;
            }

            // Create service with permit characteristic
            BluetoothGattService service = new BluetoothGattService(
                SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

            BluetoothGattCharacteristic permitChar = new BluetoothGattCharacteristic(
                PERMIT_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);

            // Sync type characteristic - ESP32 writes 1=auto, 2=manual, 3=force before reading permit,
            // followed by its 12-char display ID (firmware 3+)
            BluetoothGattCharacteristic syncTypeChar = new BluetoothGattCharacteristic(
                SYNC_TYPE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

            BluetoothGattCharacteristic statusChar = new BluetoothGattCharacteristic(
                STATUS_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

            service.addCharacteristic(permitChar);
            service.addCharacteristic(syncTypeChar);
            service.addCharacteristic(statusChar);
            gattServer.addService(service);

            Log.d(TAG, "GATT server started");
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting GATT server", e);
            return;
        }

        // Start advertising
        startAdvertising();
    }

    private void startAdvertising() {
        if (isAdvertising) {
            Log.d(TAG, "Already advertising");
            return;
        }

        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)  // Advertise indefinitely
            .build();

        // Owner ID as service data: paired displays only sync with this phone. The device name
        // is left out, a long one would push the packet past 31 bytes.
        AdvertiseData advertiseData = new AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(new ParcelUuid(SERVICE_UUID))
            .addServiceData(new ParcelUuid(SERVICE_UUID), repository.getOwnerId())
            .build();

        AdvertiseData scanResponse = new AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(new ParcelUuid(SERVICE_UUID))
            .build();

        Log.d(TAG, "Starting BLE advertising with UUID: " + SERVICE_UUID.toString());

        try {
            advertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting advertising", e);
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            isAdvertising = true;
            isRunning = true;
            Log.d(TAG, "BLE advertising started");
            sendBroadcast(ACTION_SERVICE_RUNNING);
        }

        @Override
        public void onStartFailure(int errorCode) {
            isAdvertising = errorCode == ADVERTISE_FAILED_ALREADY_STARTED;
            Log.e(TAG, "BLE advertising failed: " + errorCode);
        }
    };

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            String deviceName = "unknown";
            try {
                deviceName = device.getName();
                if (deviceName == null) deviceName = device.getAddress();
            } catch (SecurityException e) {
                deviceName = "no-permission";
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Device connected: " + deviceName);
                sessions.put(device.getAddress(), new Session());
                sendBroadcast(ACTION_DEVICE_CONNECTED);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected: " + deviceName);
                sessions.remove(device.getAddress());
                sendBroadcast(ACTION_DEVICE_DISCONNECTED);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {

            if (PERMIT_CHAR_UUID.equals(characteristic.getUuid())) {
                Session session = sessionFor(device);
                PermitRepository.DisplayInfo display = session.display;
                PermitRepository displayRepo = repository.forDisplay(display);
                PermitData permit = display.unpair ? null : repository.getPermit();
                // Built once per read: a long read comes in several offset requests and the
                // JSON carries the current time, so rebuilding would splice two versions
                if (offset == 0 || session.permitJson == null) {
                    String json;
                    if (display.unpair) {
                        json = "{\"unpair\":true}";
                        Log.d(TAG, "Telling forgotten display " + display.id + " to unpair");
                        repository.removeDisplay(display.id);
                    } else {
                        json = permit != null ? permit.toDisplayJson(displayRepo.isDisplayFlipped()) : "{}";
                    }
                    session.permitJson = json.getBytes(StandardCharsets.UTF_8);
                }
                byte[] data = session.permitJson;

                Log.d(TAG, "Permit read request, sending " + data.length + " bytes");

                // Warn if permit data is incomplete (ESP32 will reject it)
                if (permit != null && !permit.isComplete()) {
                    Log.w(TAG, "WARNING: Permit data is incomplete - ESP32 may reject");
                }

                // Show notification and record sync time on first chunk (offset 0)
                if (offset == 0 && permit != null) {
                    // Check if permit is different from last synced
                    String lastSyncedPermit = displayRepo.getDisplayPermitNumber();
                    // Only consider it "new" if we have a previous record AND it differs
                    boolean isNewPermit = lastSyncedPermit != null &&
                        !lastSyncedPermit.equals(permit.permitNumber);

                    byte syncType = session.syncType;
                    boolean isManualSync = syncType == SYNC_TYPE_MANUAL || syncType == SYNC_TYPE_FORCE;

                    // Get previous permit for price comparison before updating
                    PermitData previousPermit = displayRepo.getDisplayPermit();

                    Log.d(TAG, "Sync decision: lastSynced=" + lastSyncedPermit +
                        ", current=" + permit.permitNumber +
                        ", isNewPermit=" + isNewPermit +
                        ", syncType=" + syncType +
                        ", isManualSync=" + isManualSync);

                    displayRepo.setDisplayPermit(permit);

                    // Show notification if:
                    // - Manual sync (button press) - always notify
                    // - Force sync (long press) - always notify
                    // - New permit (permit number actually changed) - notify
                    // AUTO sync with same/unknown permit should be silent
                    if (isManualSync || isNewPermit) {
                        showSyncNotification(display, permit, previousPermit, isNewPermit, syncType);
                    }

                    // Reset sync type after handling
                    session.syncType = SYNC_TYPE_AUTO;
                    sendBroadcast(ACTION_PERMIT_READ, isNewPermit);
                }

                try {
                    if (offset >= data.length) {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, new byte[0]);
                    } else {
                        byte[] response = new byte[Math.min(data.length - offset, 512)];
                        System.arraycopy(data, offset, response, 0, response.length);
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response);
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception sending response", e);
                }
            } else {
                try {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception sending failure response", e);
                }
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {

            if (STATUS_CHAR_UUID.equals(characteristic.getUuid())) {
                if (value != null && value.length > 0) {
                    handleDisplayStatus(sessionFor(device).display, new String(value, StandardCharsets.UTF_8));
                }
                if (responseNeeded) {
                    try {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                    } catch (SecurityException e) {
                        Log.e(TAG, "Security exception sending write response", e);
                    }
                }
            } else if (SYNC_TYPE_CHAR_UUID.equals(characteristic.getUuid())) {
                if (value != null && value.length > 0) {
                    Session session = sessions.get(device.getAddress());
                    if (session == null) {
                        session = new Session();
                        sessions.put(device.getAddress(), session);
                    }
                    session.syncType = value[0];
                    if (value.length > 1) {
                        String id = new String(value, 1, value.length - 1, StandardCharsets.US_ASCII).trim();
                        if (!id.isEmpty()) session.display = repository.registerDisplay(id);
                    }
                    Log.d(TAG, "Sync type " + session.syncType + " from display "
                        + (session.display != null ? session.display.id : "(no ID)"));
                }

                if (responseNeeded) {
                    try {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                    } catch (SecurityException e) {
                        Log.e(TAG, "Security exception sending write response", e);
                    }
                }
            } else {
                if (responseNeeded) {
                    try {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                    } catch (SecurityException e) {
                        Log.e(TAG, "Security exception sending failure response", e);
                    }
                }
            }
        }
    };

    // The connection's session. Older firmware sends no ID: it uses the single-display status
    // without being registered, so after a reflash it doesn't linger as a second display.
    private Session sessionFor(BluetoothDevice device) {
        String address = device.getAddress();
        Session session = sessions.get(address);
        if (session == null) {
            session = new Session();
            sessions.put(address, session);
        }
        if (session.display == null) {
            PermitRepository.DisplayInfo legacy = new PermitRepository.DisplayInfo();
            legacy.id = address;
            legacy.name = "Display";
            legacy.keyPrefix = "";
            session.display = legacy;
        }
        return session;
    }

    // Name the display in notifications only when there's more than one
    private String displayPrefix(PermitRepository.DisplayInfo display) {
        return repository.getActiveDisplays().size() > 1 ? display.name + ": " : "";
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void sendBroadcast(String action) {
        Intent intent = new Intent(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void sendBroadcast(String action, boolean isNewPermit) {
        Intent intent = new Intent(action);
        intent.putExtra("isNewPermit", isNewPermit);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void showSyncNotification(PermitRepository.DisplayInfo display, PermitData permit,
            PermitData previousPermit, boolean isNewPermit, byte syncType) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Build message based on sync type
        String message;
        if (isNewPermit) {
            message = "New permit synced to display";
        } else if (syncType == SYNC_TYPE_FORCE) {
            message = "Force refresh complete";
        } else if (syncType == SYNC_TYPE_MANUAL) {
            message = "Manual refresh complete";
        } else {
            message = "Permit synced to display";
        }

        String vehicleName = (permit.vehicleName != null && !permit.vehicleName.isEmpty())
            ? permit.vehicleName
            : permit.plateNumber;

        // Build expanded text with details
        message = displayPrefix(display) + message;
        StringBuilder details = new StringBuilder();
        details.append(message);
        details.append("\nPermit ").append(permit.permitNumber).append(" • ").append(permit.plateNumber);
        if (permit.validFrom != null && !permit.validFrom.isEmpty() &&
            permit.validTo != null && !permit.validTo.isEmpty()) {
            String from = permit.validFrom;
            String to = permit.validTo;
            // Strip redundant year/month from first date
            // Format: "Jan 7, 2026: 16:00" or "Jan 7, 2026"
            String fromBase = from.contains(":") ? from.split(":")[0].trim() : from;
            String toBase = to.contains(":") ? to.split(":")[0].trim() : to;
            // Check same year
            if (fromBase.length() > 6 && toBase.length() > 6) {
                String fromYear = fromBase.substring(fromBase.length() - 4);
                String toYear = toBase.substring(toBase.length() - 4);
                if (fromYear.equals(toYear) && fromYear.matches("\\d{4}")) {
                    fromBase = fromBase.substring(0, fromBase.length() - 6); // Remove ", 2026"
                    // Check same month (first 3 chars) -> "Jan 7 - 14, 2026"
                    if (fromBase.length() >= 3 && toBase.length() >= 3 &&
                        fromBase.substring(0, 3).equals(toBase.substring(0, 3))) {
                        // Extract just the day from toBase (e.g., "Jan 14, 2026" -> "14, 2026")
                        toBase = toBase.substring(4);
                    }
                }
            }
            details.append("\n").append(fromBase).append(" - ").append(toBase);
        }
        if (permit.price != null && !permit.price.isEmpty()) {
            details.append("\nPaid: ").append(permit.price);
            // Show price change if we have a previous permit with price
            if (isNewPermit && previousPermit != null && previousPermit.price != null && !previousPermit.price.isEmpty()) {
                try {
                    double currentPrice = parsePrice(permit.price);
                    double prevPrice = parsePrice(previousPermit.price);
                    double diff = currentPrice - prevPrice;
                    if (Math.abs(diff) >= 0.01) {
                        String sign = diff > 0 ? "+" : "";
                        details.append(String.format(" (%s$%.2f)", sign, diff));
                    }
                } catch (NumberFormatException e) {
                    // Ignore if price parsing fails
                }
            }
        }

        Notification notification = new NotificationCompat.Builder(this, "permit_updates")
            .setContentTitle(vehicleName)
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText(details.toString()))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(0xFF2196F3)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(SYNC_NOTIFICATION_ID, notification);
    }

    private void handleDisplayStatus(PermitRepository.DisplayInfo display, String status) {
        Log.d(TAG, "Display " + display.id + " status: " + status);
        PermitRepository displayRepo = repository.forDisplay(display);
        String[] parts = status.split(",");
        if (parts.length < 3) return;
        int mv;
        try {
            mv = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            return;
        }
        displayRepo.saveDisplayStatus(parts[0].trim(), parts[1].trim(), mv);
        Intent statusIntent = new Intent(ACTION_DISPLAY_STATUS);
        // null for older firmware, which isn't registered
        if (repository.findDisplay(display.id) != null) statusIntent.putExtra(EXTRA_DISPLAY_ID, display.id);
        LocalBroadcastManager.getInstance(this).sendBroadcast(statusIntent);
        // It's checking in again: clear any "hasn't checked in" alert
        getSystemService(NotificationManager.class).cancel(AlarmReceiver.NOTIFICATION_CHECKIN);

        if (mv > 0) checkBatteryAlert(display, displayRepo, mv);
    }

    // Up to three low-battery alerts per discharge (see batteryAlertLevel), each replacing the
    // last; the final one goes on the urgent channel. Charging resets them.
    private void checkBatteryAlert(PermitRepository.DisplayInfo display, PermitRepository displayRepo, int mv) {
        int pct = PermitRepository.batteryPercent(mv);
        int days = displayRepo.estimateBatteryDaysLeft();
        int level = PermitRepository.batteryAlertLevel(pct, days);
        if (level <= displayRepo.getBatteryAlertLevel()) return;
        displayRepo.setBatteryAlertLevel(level);

        boolean several = repository.getActiveDisplays().size() > 1;
        String who = several ? display.name : "Display";
        String left = days < 0 ? "" : days == 0 ? ", less than a day left"
            : ", about " + days + (days == 1 ? " day left" : " days left");
        boolean urgent = level >= 3;
        String text = urgent
            ? who + " battery is almost empty (" + pct + "%" + left + "). Charge it today."
            : who + " battery is at " + pct + "%" + left + ". Charge it soon.";

        if (urgent) {
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                "permit_urgent", "Urgent Permit Reminders", NotificationManager.IMPORTANCE_HIGH));
        }
        Notification n = new NotificationCompat.Builder(this, urgent ? "permit_urgent" : "permit_updates")
            .setContentTitle(several ? "Charge " + display.name : "Charge the display")
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build();
        // One per display, so two low units don't replace each other's notice
        getSystemService(NotificationManager.class).notify(BATTERY_NOTIFICATION_ID + display.id.hashCode(), n);
    }

    private double parsePrice(String price) throws NumberFormatException {
        // Remove $ and any other non-numeric chars except decimal point
        String cleaned = price.replaceAll("[^0-9.]", "");
        return Double.parseDouble(cleaned);
    }

    // The user switched the always-on notification off (it stays hidden, sync keeps running)
    public static boolean isBackgroundNotificationHidden(Context context) {
        NotificationChannel c = context.getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL_ID);
        return c != null && c.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    // Opens the switch for the always-on notification
    public static Intent backgroundNotificationSettingsIntent(Context context) {
        return new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.getPackageName())
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID);
    }

    // The always-on notification that keeps the service alive: silent, no badge
    public static void createBackgroundChannel(NotificationManager manager) {
        for (String old : OLD_CHANNEL_IDS) {
            try {
                manager.deleteNotificationChannel(old);
            } catch (SecurityException e) {
                // Still in use by a running foreground notification; it goes on the next start
                Log.w(TAG, "Old notification channel still in use: " + old, e);
            }
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Background sync", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the display in sync while the app is closed");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);

        createBackgroundChannel(manager);

        // Permit updates channel (default importance, shows notifications)
        NotificationChannel updatesChannel = new NotificationChannel(
            "permit_updates",
            "Permit Updates",
            NotificationManager.IMPORTANCE_DEFAULT);
        updatesChannel.setDescription("Notifications when permits are synced to display");
        manager.createNotificationChannel(updatesChannel);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        isRunning = false;
        unregisterReceiver(bluetoothStateReceiver);
        stopBleServer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
