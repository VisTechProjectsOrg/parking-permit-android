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
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private MainPagerAdapter pagerAdapter;

    private final String[] tabTitles = {"Display Status", "History"};

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allGranted = true;
            for (Boolean granted : result.values()) {
                if (!granted) allGranted = false;
            }
            if (allGranted) {
                startBleService();
                checkBatteryOptimizationFirstLaunch();
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

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        viewPager = findViewById(R.id.viewPager);
        tabLayout = findViewById(R.id.tabLayout);

        pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        // Preload both tabs so History WebView loads in background
        viewPager.setOffscreenPageLimit(2);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            tab.setText(tabTitles[position]);
        }).attach();

        createNotificationChannels();
        checkBluetoothAndStart();
    }

    private void checkBatteryOptimizationFirstLaunch() {
        if (SamsungBatteryHelper.isBatteryOptimizationDisabled(this)) {
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Background Access Required")
            .setMessage("This app needs to run in the background to sync parking permits with your display.\n\nPlease tap Allow on the next screen.")
            .setPositiveButton("Continue", (d, w) -> {
                try {
                    Intent intent = SamsungBatteryHelper.getBatteryOptimizationIntent(this);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Could not request battery optimization exemption", e);
                    showBatterySettingsDialog();
                }
            })
            .setNegativeButton("Later", null)
            .show();
    }

    private void showBatterySettingsDialog() {
        String message = SamsungBatteryHelper.isSamsungDevice()
            ? "For reliable background operation, please set this app to 'Unrestricted' in battery settings.\n\nThis prevents Samsung from killing the BLE service."
            : "For reliable background operation, please disable battery optimization for this app.";

        new AlertDialog.Builder(this)
            .setTitle("Battery Settings")
            .setMessage(message)
            .setPositiveButton("Open Settings", (d, w) -> {
                try {
                    startActivity(SamsungBatteryHelper.getAppInfoIntent(this));
                } catch (Exception e) {
                    Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Later", null)
            .show();
    }

    private void checkBluetoothAndStart() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show();
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
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
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
            checkBatteryOptimizationFirstLaunch();
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

        // Update fragment status
        if (pagerAdapter.getBleStatusFragment() != null) {
            pagerAdapter.getBleStatusFragment().setBleRunning();
        }

        // Schedule daily sync
        AlarmReceiver.scheduleSync(this);

        // Do initial sync
        doInitialSync();
    }

    private void doInitialSync() {
        BleStatusFragment fragment = pagerAdapter.getBleStatusFragment();
        if (fragment != null) {
            // Fragment will handle the sync
        } else {
            // Fragment not ready, sync directly
            GitHubSyncTask syncTask = new GitHubSyncTask(this);
            syncTask.sync(new GitHubSyncTask.SyncCallback() {
                @Override
                public void onSuccess(PermitData permit, boolean isNew) {
                    Log.d(TAG, "Initial sync success");
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Initial sync failed: " + error);
                }
            });
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
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_notifications) {
            openNotificationSettings();
            return true;
        } else if (id == R.id.action_email_settings) {
            openEmailSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openNotificationSettings() {
        new AlertDialog.Builder(this)
            .setTitle("Notification Settings")
            .setMessage("Choose which notifications to configure:")
            .setPositiveButton("BLE Service", (d, w) -> openChannelSettings("ble_service_channel"))
            .setNegativeButton("Permit Updates", (d, w) -> openChannelSettings("permit_updates"))
            .setNeutralButton("All Settings", (d, w) -> openAllNotificationSettings())
            .show();
    }

    private void openChannelSettings(String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
            startActivity(intent);
        } else {
            openAllNotificationSettings();
        }
    }

    private void openAllNotificationSettings() {
        Intent intent = new Intent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
        }
        startActivity(intent);
    }

    private void openEmailSettings() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(android.net.Uri.parse("https://ilovekitty.ca/parking/settings/"));
        startActivity(intent);
    }

    @Override
    public void onBackPressed() {
        // Handle WebView back navigation
        WebViewFragment webViewFragment = pagerAdapter.getWebViewFragment();
        if (viewPager.getCurrentItem() == 1 && webViewFragment != null && webViewFragment.canGoBack()) {
            webViewFragment.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
