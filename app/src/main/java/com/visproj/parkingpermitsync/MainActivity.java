package com.visproj.parkingpermitsync;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private TextView tvStatus;
    private TextView tvPermit;
    private TextView tvLastSync;
    private Button btnSync;
    private Button btnBattery;

    private PermitRepository repository;

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allGranted = true;
            for (Boolean granted : result.values()) {
                if (!granted) allGranted = false;
            }
            if (allGranted) {
                startBleService();
            } else {
                Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show();
            }
        });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                checkAndRequestPermissions();
            } else {
                Toast.makeText(this, "Bluetooth must be enabled", Toast.LENGTH_LONG).show();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = new PermitRepository(this);

        tvStatus = findViewById(R.id.tvStatus);
        tvPermit = findViewById(R.id.tvPermit);
        tvLastSync = findViewById(R.id.tvLastSync);
        btnSync = findViewById(R.id.btnSync);
        btnBattery = findViewById(R.id.btnBattery);

        btnSync.setOnClickListener(v -> syncNow());
        btnBattery.setOnClickListener(v -> openBatterySettings());

        createNotificationChannels();
        updateUI();
        checkBluetoothAndStart();
    }

    private void checkBluetoothAndStart() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null) {
            tvStatus.setText("Bluetooth not supported");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            try {
                enableBluetoothLauncher.launch(enableIntent);
            } catch (SecurityException e) {
                Toast.makeText(this, "Cannot request Bluetooth enable", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (needed.isEmpty()) {
            startBleService();
        } else {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void startBleService() {
        Log.d(TAG, "Starting BLE service");
        Intent serviceIntent = new Intent(this, BleGattService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        tvStatus.setText("BLE Server Running");

        // Schedule daily sync
        AlarmReceiver.scheduleSync(this);

        // Do initial sync
        syncNow();
    }

    private void syncNow() {
        btnSync.setEnabled(false);
        tvStatus.setText("Syncing...");

        GitHubSyncTask syncTask = new GitHubSyncTask(this);
        syncTask.sync(new GitHubSyncTask.SyncCallback() {
            @Override
            public void onSuccess(PermitData permit, boolean isNew) {
                btnSync.setEnabled(true);
                tvStatus.setText("BLE Server Running");
                updateUI();

                String msg = isNew ? "New permit synced!" : "Permit up to date";
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                btnSync.setEnabled(true);
                tvStatus.setText("Sync failed: " + error);
            }
        });
    }

    private void updateUI() {
        PermitData permit = repository.getPermit();

        if (permit != null && permit.isValid()) {
            tvPermit.setText(String.format(
                "Permit: %s\nPlate: %s\nValid: %s\n    to %s",
                permit.permitNumber, permit.plateNumber,
                permit.validFrom, permit.validTo));
        } else {
            tvPermit.setText("No permit data");
        }

        long lastSync = repository.getLastSyncTime();
        if (lastSync > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());
            tvLastSync.setText("Last sync: " + sdf.format(new Date(lastSync)));
        } else {
            tvLastSync.setText("Never synced");
        }

        // Update battery button
        if (SamsungBatteryHelper.isSamsungDevice()) {
            btnBattery.setVisibility(View.VISIBLE);
            if (SamsungBatteryHelper.isBatteryOptimizationDisabled(this)) {
                btnBattery.setText("Battery: Unrestricted");
            } else {
                btnBattery.setText("Fix Battery Settings");
            }
        } else {
            if (!SamsungBatteryHelper.isBatteryOptimizationDisabled(this)) {
                btnBattery.setVisibility(View.VISIBLE);
                btnBattery.setText("Disable Battery Optimization");
            } else {
                btnBattery.setVisibility(View.GONE);
            }
        }
    }

    private void openBatterySettings() {
        if (SamsungBatteryHelper.isSamsungDevice()) {
            new AlertDialog.Builder(this)
                .setTitle("Samsung Battery Settings")
                .setMessage("To keep the app running:\n\n" +
                    "1. Tap 'Open Settings' below\n" +
                    "2. Find 'Parking Permit Sync'\n" +
                    "3. Set to 'Unrestricted'\n\n" +
                    "This prevents Samsung from killing the app.")
                .setPositiveButton("Open Settings", (d, w) -> {
                    try {
                        startActivity(SamsungBatteryHelper.getAppInfoIntent(this));
                    } catch (Exception e) {
                        Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        } else {
            try {
                startActivity(SamsungBatteryHelper.getBatteryOptimizationIntent(this));
            } catch (Exception e) {
                Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void createNotificationChannels() {
        NotificationChannel bleChannel = new NotificationChannel(
            "ble_service_channel", "BLE Service", NotificationManager.IMPORTANCE_LOW);
        bleChannel.setDescription("Keeps BLE server running");

        NotificationChannel updateChannel = new NotificationChannel(
            "permit_updates", "Permit Updates", NotificationManager.IMPORTANCE_DEFAULT);
        updateChannel.setDescription("Notifications for new permits");

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(bleChannel);
        manager.createNotificationChannel(updateChannel);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }
}
