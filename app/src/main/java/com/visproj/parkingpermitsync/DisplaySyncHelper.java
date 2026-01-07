package com.visproj.parkingpermitsync;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DisplaySyncHelper {
    private static final String TAG = "DisplaySyncHelper";

    // UUIDs must match ESP32 server UUIDs
    private static final UUID DISPLAY_SERVICE_UUID = UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb");
    private static final UUID COMMAND_CHAR_UUID = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb");

    private static final String CMD_SYNC = "SYNC";
    private static final String CMD_FORCE = "FORCE";

    private static final long SCAN_TIMEOUT = 10000; // 10 seconds

    private final Context context;
    private final Handler handler;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private SyncCallback callback;
    private boolean isScanning = false;
    private String pendingCommand;

    public interface SyncCallback {
        void onStatus(String status);
        void onSuccess();
        void onError(String error);
    }

    public DisplaySyncHelper(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    public void syncDisplay(boolean force, SyncCallback callback) {
        this.callback = callback;
        this.pendingCommand = force ? CMD_FORCE : CMD_SYNC;

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            callback.onError("Bluetooth not available");
            return;
        }

        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            callback.onError("BLE scanner not available");
            return;
        }

        callback.onStatus("Scanning for display...");
        startScan();
    }

    private void startScan() {
        try {
            List<ScanFilter> filters = new ArrayList<>();
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(DISPLAY_SERVICE_UUID))
                    .build());

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            isScanning = true;
            scanner.startScan(filters, settings, scanCallback);

            // Stop scanning after timeout
            handler.postDelayed(() -> {
                if (isScanning) {
                    stopScan();
                    if (callback != null) {
                        callback.onError("Display not found");
                    }
                }
            }, SCAN_TIMEOUT);

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting scan", e);
            callback.onError("Bluetooth permission denied");
        }
    }

    private void stopScan() {
        if (isScanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception stopping scan", e);
            }
            isScanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            stopScan();

            BluetoothDevice device = result.getDevice();
            String name = "unknown";
            try {
                name = device.getName();
                if (name == null) name = device.getAddress();
            } catch (SecurityException e) {
                name = device.getAddress();
            }

            Log.d(TAG, "Found display: " + name);
            if (callback != null) {
                callback.onStatus("Connecting to display...");
            }

            connectToDevice(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed: " + errorCode);
            isScanning = false;
            if (callback != null) {
                callback.onError("Scan failed");
            }
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        try {
            gatt = device.connectGatt(context, false, gattCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception connecting", e);
            if (callback != null) {
                callback.onError("Connection permission denied");
            }
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to display");
                handler.post(() -> {
                    if (callback != null) callback.onStatus("Connected, sending command...");
                });
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception discovering services", e);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from display");
                cleanup();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(DISPLAY_SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic commandChar = service.getCharacteristic(COMMAND_CHAR_UUID);
                    if (commandChar != null) {
                        sendCommand(gatt, commandChar);
                    } else {
                        handler.post(() -> {
                            if (callback != null) callback.onError("Command characteristic not found");
                        });
                        cleanup();
                    }
                } else {
                    handler.post(() -> {
                        if (callback != null) callback.onError("Display service not found");
                    });
                    cleanup();
                }
            } else {
                handler.post(() -> {
                    if (callback != null) callback.onError("Service discovery failed");
                });
                cleanup();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Command sent successfully");
                handler.post(() -> {
                    if (callback != null) callback.onSuccess();
                });
            } else {
                Log.e(TAG, "Command write failed: " + status);
                handler.post(() -> {
                    if (callback != null) callback.onError("Command failed");
                });
            }
            cleanup();
        }
    };

    private void sendCommand(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try {
            characteristic.setValue(pendingCommand.getBytes());
            gatt.writeCharacteristic(characteristic);
            Log.d(TAG, "Sending command: " + pendingCommand);
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception writing command", e);
            handler.post(() -> {
                if (callback != null) callback.onError("Write permission denied");
            });
            cleanup();
        }
    }

    private void cleanup() {
        if (gatt != null) {
            try {
                gatt.close();
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception closing gatt", e);
            }
            gatt = null;
        }
    }

    public void cancel() {
        stopScan();
        cleanup();
    }
}
