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
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

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
            // Check only Bluetooth permissions - notifications are optional
            boolean btGranted = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                btGranted = Boolean.TRUE.equals(result.get(Manifest.permission.BLUETOOTH_ADVERTISE))
                    && Boolean.TRUE.equals(result.get(Manifest.permission.BLUETOOTH_CONNECT))
                    && Boolean.TRUE.equals(result.get(Manifest.permission.BLUETOOTH_SCAN));
            }
            if (btGranted) {
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
            checkUnusedAppPause();
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

    private void checkUnusedAppPause() {
        if (SamsungBatteryHelper.isUnusedAppPauseDisabled(this)) {
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Keep Running")
            .setMessage("The display syncs without you opening this app. "
                + SamsungBatteryHelper.getUnusedAppPauseInstruction())
            .setPositiveButton("Continue", (d, w) -> {
                try {
                    startActivity(SamsungBatteryHelper.getUnusedAppPauseIntent(this));
                } catch (Exception e) {
                    startActivity(SamsungBatteryHelper.getAppInfoIntent(this));
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
            PermitSyncTask syncTask = new PermitSyncTask(this);
            syncTask.sync(new PermitSyncTask.SyncCallback() {
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
            "ble_service_channel", "Background sync", NotificationManager.IMPORTANCE_LOW);
        bleChannel.setDescription("Keeps the display in sync while the app is closed");

        NotificationChannel updateChannel = new NotificationChannel(
            "permit_updates", "Permit Updates", NotificationManager.IMPORTANCE_DEFAULT);
        updateChannel.setDescription("Notifications for new permits");

        NotificationChannel urgentChannel = new NotificationChannel(
            "permit_urgent", "Urgent Permit Reminders", NotificationManager.IMPORTANCE_HIGH);
        urgentChannel.setDescription("Persistent reminders when display is out of date");

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(bleChannel);
        manager.createNotificationChannel(updateChannel);
        manager.createNotificationChannel(urgentChannel);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_display_settings) {
            openDisplaySettings();
            return true;
        } else if (id == R.id.action_account) {
            openAccountSettings();
            return true;
        } else if (id == R.id.action_notifications) {
            openNotificationSettings();
            return true;
        } else if (id == R.id.action_email_settings) {
            openEmailSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // One row per paired display: tap the name to rename, flip switch, Forget
    private void openDisplaySettings() {
        PermitRepository repository = new PermitRepository(this);
        List<PermitRepository.DisplayInfo> displays = repository.getActiveDisplays();
        int pad = dp(20);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad, dp(8), pad, 0);

        AlertDialog[] holder = new AlertDialog[1];
        if (displays.isEmpty()) {
            // Nothing paired yet (or older firmware): just the flip setting it already had
            list.addView(flipRow(repository, "Flip display 180°"));
        }
        for (PermitRepository.DisplayInfo d : displays) {
            PermitRepository displayRepo = repository.forDisplay(d);

            TextView name = new TextView(this);
            name.setText(d.name + "  ✎");
            name.setTextSize(17);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setPadding(0, dp(12), 0, 0);
            name.setOnClickListener(v -> renameDisplay(d, () -> { holder[0].dismiss(); openDisplaySettings(); }));
            list.addView(name);

            long seen = displayRepo.getLastDisplaySyncTime();
            TextView sub = new TextView(this);
            sub.setText(seen > 0 ? "Checked in " + TimeUtils.getRelativeTime(seen).toLowerCase(java.util.Locale.US)
                : "Hasn't checked in yet");
            sub.setTextSize(12);
            sub.setTextColor(0xFF888888);
            list.addView(sub);

            list.addView(flipRow(displayRepo, "Flip 180°"));

            TextView forget = new TextView(this);
            forget.setText("Forget");
            forget.setTextColor(0xFFF44336);
            forget.setTextSize(14);
            forget.setPadding(0, dp(4), dp(16), dp(8));
            forget.setOnClickListener(v -> confirmForget(d, () -> { holder[0].dismiss(); openDisplaySettings(); }));
            list.addView(forget);
        }

        TextView help = new TextView(this);
        help.setText("To add a display, press its button near this phone.");
        help.setTextSize(12);
        help.setTextColor(0xFF888888);
        help.setPadding(0, dp(16), 0, dp(8));
        list.addView(help);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);

        holder[0] = new AlertDialog.Builder(this)
            .setTitle(displays.size() > 1 ? "Displays" : "Display Settings")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .setOnDismissListener(d -> refreshStatusTab())
            .show();
    }

    private View flipRow(PermitRepository displayRepo, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(15);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        SwitchCompat flip = new SwitchCompat(this);
        flip.setChecked(displayRepo.isDisplayFlipped());
        flip.setOnCheckedChangeListener((b, checked) -> {
            // The display picks this up at its next check-in (it sleeps in between)
            displayRepo.setDisplayFlipped(checked);
            Toast.makeText(this, "Saved. Press the display's button to apply it now.", Toast.LENGTH_LONG).show();
        });
        row.addView(flip);
        return row;
    }

    private void renameDisplay(PermitRepository.DisplayInfo d, Runnable done) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(d.name);
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});
        input.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Rename display")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("Enter a name");
                return;
            }
            new PermitRepository(this).renameDisplay(d.id, name);
            dialog.dismiss();
            done.run();
        }));
        dialog.show();
    }

    private void confirmForget(PermitRepository.DisplayInfo d, Runnable done) {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Forget " + d.name + "?")
            .setMessage("It unpairs and clears its permit the next time it checks in.")
            .setPositiveButton("Forget", (x, w) -> {
                new PermitRepository(this).forgetDisplay(d.id);
                done.run();
            })
            .setNegativeButton("Cancel", null)
            .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFFF44336);
    }

    // Where the permit comes from: the feed URL and an optional access token
    private void openAccountSettings() {
        PermitRepository repository = new PermitRepository(this);
        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);

        android.widget.EditText url = new android.widget.EditText(this);
        url.setHint("Permit feed URL");
        url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(repository.getFeedUrl());
        box.addView(url);

        android.widget.EditText token = new android.widget.EditText(this);
        token.setHint("Access token (optional)");
        token.setSingleLine(true);
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(repository.getFeedToken());
        box.addView(token);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Account")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset", null)
            .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                url.setText(PermitRepository.PERMIT_URL);
                token.setText("");
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String u = url.getText().toString().trim();
                okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(u);
                if (parsed == null || !parsed.isHttps()) {
                    url.setError("Enter an https:// address");
                    return;
                }
                repository.setAccount(u, token.getText().toString().trim());
                dialog.dismiss();
                checkFeedNow();
            });
        });
        dialog.show();
    }

    private void checkFeedNow() {
        new PermitSyncTask(this).sync(new PermitSyncTask.SyncCallback() {
            @Override
            public void onSuccess(PermitData permit, boolean isNew) {
                Toast.makeText(MainActivity.this, "Saved. Permit " + permit.permitNumber + " found.",
                    Toast.LENGTH_SHORT).show();
                refreshStatusTab();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(MainActivity.this, "Saved, but the feed didn't load: " + error,
                    Toast.LENGTH_LONG).show();
            }
        });
    }

    private void refreshStatusTab() {
        BleStatusFragment f = pagerAdapter.getBleStatusFragment();
        if (f != null) f.updateUI();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openNotificationSettings() {
        PermitRepository repository = new PermitRepository(this);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_notification_settings, null);
        SwitchCompat switchReminders = dialogView.findViewById(R.id.switchReminders);
        switchReminders.setChecked(repository.isRemindersEnabled());

        // Display check-in alert: Off / 1 / 2 / 3 / 7 days, saved on change like the switch
        final int[] checkinDays = {0, 1, 2, 3, 7};
        String[] checkinLabels = {"Off", "1 day", "2 days", "3 days", "7 days"};
        Spinner spinnerCheckin = dialogView.findViewById(R.id.spinnerCheckinAlert);
        ArrayAdapter<String> checkinAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, checkinLabels);
        checkinAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCheckin.setAdapter(checkinAdapter);
        int savedDays = repository.getCheckinAlertDays();
        for (int i = 0; i < checkinDays.length; i++) {
            if (checkinDays[i] == savedDays) spinnerCheckin.setSelection(i);
        }
        spinnerCheckin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                repository.setCheckinAlertDays(checkinDays[position]);
                if (checkinDays[position] == 0) {
                    getSystemService(NotificationManager.class).cancel(AlarmReceiver.NOTIFICATION_CHECKIN);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        switchReminders.setOnCheckedChangeListener((buttonView, isChecked) -> {
            repository.setRemindersEnabled(isChecked);
            // Dismiss any active reminder notification when disabling
            if (!isChecked) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                manager.cancel(3); // NOTIFICATION_REMINDER
            }
        });

        new AlertDialog.Builder(this)
            .setTitle("Notification Settings")
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .setNeutralButton("System Settings", (d, w) -> openAllNotificationSettings())
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
        WebViewFragment webViewFragment = pagerAdapter.getWebViewFragment();
        if (webViewFragment != null) {
            webViewFragment.loadUrl("https://fucktorontoparking.ca/settings/");
            viewPager.setCurrentItem(1);
        }
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
