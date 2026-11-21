package com.visproj.parkingpermitsync;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Also after an app update, which kills the running service and clears its alarms
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed or app updated: " + intent.getAction());
            // Android also re-sends BOOT_COMPLETED to an app that was force-stopped, once it's
            // opened again, so only call it a phone restart if the phone just came up
            String what;
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                what = "App updated";
            } else if (android.os.SystemClock.elapsedRealtime() < 10 * 60 * 1000) {
                what = "Phone restarted";
            } else {
                what = "App started again after being stopped";
            }
            ActivityLog.log(context, what);

            // Check if Bluetooth permissions are granted before starting service
            if (!hasBluetoothPermissions(context)) {
                Log.d(TAG, "Bluetooth permissions not granted, skipping service start");
                ActivityLog.log(context, "Not starting: Bluetooth permission missing");
                return;
            }

            Log.d(TAG, "Starting BLE service");

            // Start BLE service
            Intent serviceIntent = new Intent(context, BleGattService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

            // Schedule daily sync
            AlarmReceiver.scheduleSync(context);
        }
    }

    private boolean hasBluetoothPermissions(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                    == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
}
