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
import android.content.Intent;
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
import java.util.UUID;

public class BleGattService extends Service {
    private static final String TAG = "BleGattService";

    // Broadcast actions for UI updates
    public static final String ACTION_SERVICE_RUNNING = "com.visproj.parkingpermitsync.SERVICE_RUNNING";
    public static final String ACTION_DEVICE_CONNECTED = "com.visproj.parkingpermitsync.DEVICE_CONNECTED";
    public static final String ACTION_DEVICE_DISCONNECTED = "com.visproj.parkingpermitsync.DEVICE_DISCONNECTED";
    public static final String ACTION_PERMIT_READ = "com.visproj.parkingpermitsync.PERMIT_READ";

    // BLE UUIDs - ESP32 will use these to find and read permit data
    // Using standard Bluetooth Base UUID format for better compatibility
    public static final UUID SERVICE_UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb");
    public static final UUID PERMIT_CHAR_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    public static final UUID SYNC_TYPE_CHAR_UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");

    // Sync types - ESP32 writes this before reading permit
    private static final byte SYNC_TYPE_AUTO = 1;    // Reboot/auto sync - no notification if same permit
    private static final byte SYNC_TYPE_MANUAL = 2;  // Button press - always show notification
    private static final byte SYNC_TYPE_FORCE = 3;   // Long press - always show notification

    private byte pendingSyncType = SYNC_TYPE_AUTO;

    private static final String CHANNEL_ID = "ble_service_channel";
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
            .setContentTitle("Parking Permit Sync")
            .setContentText("BLE server running")
            .setSmallIcon(R.drawable.ic_bluetooth)
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

            // Sync type characteristic - ESP32 writes 1=auto, 2=manual, 3=force before reading permit
            BluetoothGattCharacteristic syncTypeChar = new BluetoothGattCharacteristic(
                SYNC_TYPE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

            service.addCharacteristic(permitChar);
            service.addCharacteristic(syncTypeChar);
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

        AdvertiseData advertiseData = new AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(new ParcelUuid(SERVICE_UUID))
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
            isAdvertising = false;
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
                Log.d(TAG, "Device connected: " + deviceName + ", resetting pendingSyncType to AUTO (1)");
                pendingSyncType = SYNC_TYPE_AUTO; // Reset to auto on new connection
                sendBroadcast(ACTION_DEVICE_CONNECTED);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected: " + deviceName);
                sendBroadcast(ACTION_DEVICE_DISCONNECTED);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {

            if (PERMIT_CHAR_UUID.equals(characteristic.getUuid())) {
                PermitData permit = repository.getPermit();
                if (permit != null) {
                    permit.displayFlipped = repository.isDisplayFlipped();
                }
                String json = permit != null ? permit.toJson() : "{}";
                byte[] data = json.getBytes(StandardCharsets.UTF_8);

                Log.d(TAG, "Permit read request, sending " + data.length + " bytes");

                // Warn if permit data is incomplete (ESP32 will reject it)
                if (permit != null && !permit.isComplete()) {
                    Log.w(TAG, "WARNING: Permit data is incomplete - ESP32 may reject");
                }

                // Show notification and record sync time on first chunk (offset 0)
                if (offset == 0 && permit != null) {
                    // Check if permit is different from last synced
                    String lastSyncedPermit = repository.getDisplayPermitNumber();
                    // Only consider it "new" if we have a previous record AND it differs
                    boolean isNewPermit = lastSyncedPermit != null &&
                        !lastSyncedPermit.equals(permit.permitNumber);

                    byte syncType = pendingSyncType;
                    boolean isManualSync = syncType == SYNC_TYPE_MANUAL || syncType == SYNC_TYPE_FORCE;

                    // Get previous permit for price comparison before updating
                    PermitData previousPermit = repository.getDisplayPermit();

                    Log.d(TAG, "Sync decision: lastSynced=" + lastSyncedPermit +
                        ", current=" + permit.permitNumber +
                        ", isNewPermit=" + isNewPermit +
                        ", syncType=" + syncType +
                        ", isManualSync=" + isManualSync);

                    repository.setDisplayPermit(permit);

                    // Show notification if:
                    // - Manual sync (button press) - always notify
                    // - Force sync (long press) - always notify
                    // - New permit (permit number actually changed) - notify
                    // AUTO sync with same/unknown permit should be silent
                    if (isManualSync || isNewPermit) {
                        showSyncNotification(permit, previousPermit, isNewPermit, syncType);
                    }

                    // Reset sync type after handling
                    pendingSyncType = SYNC_TYPE_AUTO;
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

            if (SYNC_TYPE_CHAR_UUID.equals(characteristic.getUuid())) {
                if (value != null && value.length > 0) {
                    pendingSyncType = value[0];
                    Log.d(TAG, "Sync type set to: " + pendingSyncType);
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

    private void showSyncNotification(PermitData permit, PermitData previousPermit, boolean isNewPermit, byte syncType) {
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
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(0xFF2196F3)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(SYNC_NOTIFICATION_ID, notification);
    }

    private double parsePrice(String price) throws NumberFormatException {
        // Remove $ and any other non-numeric chars except decimal point
        String cleaned = price.replaceAll("[^0-9.]", "");
        return Double.parseDouble(cleaned);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);

        // BLE service channel (low importance, silent)
        NotificationChannel serviceChannel = new NotificationChannel(
            CHANNEL_ID,
            "BLE Service",
            NotificationManager.IMPORTANCE_LOW);
        serviceChannel.setDescription("Keeps BLE server running for ESP32 connection");
        manager.createNotificationChannel(serviceChannel);

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

        if (advertiser != null && isAdvertising) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception stopping advertising", e);
            }
        }

        if (gattServer != null) {
            try {
                gattServer.close();
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception closing GATT server", e);
            }
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
