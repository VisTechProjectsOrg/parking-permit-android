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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BleGattService extends Service {
    private static final String TAG = "BleGattService";

    // BLE UUIDs - ESP32 will use these to find and read permit data
    public static final UUID SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0");
    public static final UUID PERMIT_CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1");

    private static final String CHANNEL_ID = "ble_service_channel";
    private static final int NOTIFICATION_ID = 1;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private PermitRepository repository;

    private boolean isAdvertising = false;

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

            service.addCharacteristic(permitChar);
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
        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)  // Advertise indefinitely
            .build();

        AdvertiseData data = new AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(new ParcelUuid(SERVICE_UUID))
            .build();

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting advertising", e);
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            isAdvertising = true;
            Log.d(TAG, "BLE advertising started");
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
                Log.d(TAG, "Device connected: " + deviceName);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected: " + deviceName);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {

            if (PERMIT_CHAR_UUID.equals(characteristic.getUuid())) {
                PermitData permit = repository.getPermit();
                String json = permit != null ? permit.toJson() : "{}";
                byte[] data = json.getBytes(StandardCharsets.UTF_8);

                Log.d(TAG, "Permit read request, sending " + data.length + " bytes");

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

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "BLE Service",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps BLE server running for ESP32 connection");

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");

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
