package com.visproj.parkingpermitsync;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

// First-run setup: welcome, permit feed, permissions, pair a display.
// With EXTRA_ADD_DISPLAY it opens straight on the pair step (Menu > Add a display).
public class SetupActivity extends AppCompatActivity {
    public static final String EXTRA_ADD_DISPLAY = "add_display";

    private static final int STEP_WELCOME = 0;
    private static final int STEP_ACCOUNT = 1;
    private static final int STEP_PERMISSIONS = 2;
    private static final int STEP_PAIR = 3;
    private static final long FADE_MS = 300;

    private static final int COLOR_TEXT = 0xFFE2E8F0;
    private static final int COLOR_MUTED = 0xFF8892A6;
    private static final int COLOR_OK = 0xFF4CAF50;
    private static final int COLOR_ERROR = 0xFFF44336;
    private static final int COLOR_BLUE = 0xFF2196F3;

    private PermitRepository repository;
    private boolean addDisplayOnly;
    private int step = -1;
    private View[] steps;
    private TextView tvStep;
    private Button btnNext;
    private TextView btnSkip;

    private EditText etFeedUrl;
    private EditText etFeedToken;
    private TextView tvAccountResult;
    private boolean checkingFeed;

    private LinearLayout permissionRows;
    private boolean flagBluetooth;  // Continue was tapped without it: mark the row

    private View pairWaiting;
    private View pairDone;
    private TextView tvPaired;
    private EditText etDisplayName;
    private String pairedId;

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
            result -> buildPermissionRows());

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
            result -> buildPermissionRows());

    // The display reports its status after every sync; the first one during the pair step is ours
    private final BroadcastReceiver displayReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (step == STEP_PAIR && pairedId == null) {
                showPaired(intent.getStringExtra(BleGattService.EXTRA_DISPLAY_ID));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        repository = new PermitRepository(this);
        addDisplayOnly = getIntent().getBooleanExtra(EXTRA_ADD_DISPLAY, false);
        if (!addDisplayOnly && !repository.isSetupDone()) repository.setSetupStarted();

        tvStep = findViewById(R.id.tvStep);
        btnNext = findViewById(R.id.btnNext);
        btnSkip = findViewById(R.id.btnSkip);
        steps = new View[]{findViewById(R.id.stepWelcome), findViewById(R.id.stepAccount),
            findViewById(R.id.stepPermissions), findViewById(R.id.stepPair)};

        etFeedUrl = findViewById(R.id.etFeedUrl);
        etFeedToken = findViewById(R.id.etFeedToken);
        tvAccountResult = findViewById(R.id.tvAccountResult);
        etFeedUrl.setText(repository.getFeedUrl());
        etFeedToken.setText(repository.getFeedToken());

        permissionRows = findViewById(R.id.permissionRows);
        // So the "Hide background notification" row has a channel to open before the service starts
        BleGattService.createBackgroundChannel(getSystemService(android.app.NotificationManager.class));

        pairWaiting = findViewById(R.id.pairWaiting);
        pairDone = findViewById(R.id.pairDone);
        tvPaired = findViewById(R.id.tvPaired);
        etDisplayName = findViewById(R.id.etDisplayName);

        btnNext.setOnClickListener(v -> next());
        btnSkip.setOnClickListener(v -> finishSetup());

        showStep(addDisplayOnly ? STEP_PAIR : STEP_WELCOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(displayReceiver,
            new IntentFilter(BleGattService.ACTION_DISPLAY_STATUS));
        // Back from a settings screen: re-check what was granted
        if (step == STEP_PERMISSIONS) buildPermissionRows();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(displayReceiver);
    }

    @Override
    public void onBackPressed() {
        if (!addDisplayOnly && step > STEP_WELCOME && !checkingFeed) {
            showStep(step - 1);
        } else {
            super.onBackPressed();
        }
    }

    private void showStep(int newStep) {
        View from = step >= 0 ? steps[step] : null;
        View to = steps[newStep];
        step = newStep;

        if (from != null && from != to) {
            from.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> {
                from.setVisibility(View.GONE);
                to.setAlpha(0f);
                to.setVisibility(View.VISIBLE);
                to.animate().alpha(1f).setDuration(FADE_MS / 2).start();
            }).start();
        } else {
            // First step shown (welcome, or pair for Add a display): hide the rest
            for (View v : steps) v.setVisibility(v == to ? View.VISIBLE : View.GONE);
        }

        tvStep.setVisibility(addDisplayOnly || step == STEP_WELCOME ? View.INVISIBLE : View.VISIBLE);
        tvStep.setText("Step " + step + " of 3");
        btnSkip.setVisibility(step == STEP_PAIR ? View.VISIBLE : View.GONE);

        switch (step) {
            case STEP_WELCOME:
                btnNext.setText("Get started");
                break;
            case STEP_ACCOUNT:
                btnNext.setText("Continue");
                break;
            case STEP_PERMISSIONS:
                btnNext.setText("Continue");
                flagBluetooth = false;
                buildPermissionRows();
                break;
            case STEP_PAIR:
                startPairing();
                break;
        }
    }

    private void next() {
        switch (step) {
            case STEP_WELCOME:
                showStep(STEP_ACCOUNT);
                break;
            case STEP_ACCOUNT:
                checkFeed();
                break;
            case STEP_PERMISSIONS:
                if (!hasBluetooth()) {
                    flagBluetooth = true;
                    buildPermissionRows();
                    return;
                }
                showStep(STEP_PAIR);
                break;
            case STEP_PAIR:
                if (pairedId == null) {
                    Toast.makeText(this, "Press the button on your display first", Toast.LENGTH_SHORT).show();
                    return;
                }
                String name = etDisplayName.getText().toString().trim();
                if (!name.isEmpty() && repository.findDisplay(pairedId) != null) {
                    repository.renameDisplay(pairedId, name);
                }
                finishSetup();
                break;
        }
    }

    // ----- Step 2: permit feed -----

    private void checkFeed() {
        if (checkingFeed) return;
        String url = etFeedUrl.getText().toString().trim();
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
        if (parsed == null || !parsed.isHttps()) {
            etFeedUrl.setError("Enter an https:// address");
            return;
        }
        repository.setAccount(url, etFeedToken.getText().toString().trim());

        checkingFeed = true;
        btnNext.setText("Checking...");
        tvAccountResult.setVisibility(View.GONE);
        new PermitSyncTask(this).sync(new PermitSyncTask.SyncCallback() {
            @Override
            public void onSuccess(PermitData permit, boolean isNew) {
                checkingFeed = false;
                btnNext.setText("Continue");
                tvAccountResult.setText("Found permit " + permit.permitNumber + " for " + permit.plateNumber);
                tvAccountResult.setTextColor(COLOR_OK);
                tvAccountResult.setVisibility(View.VISIBLE);
                btnNext.postDelayed(() -> {
                    if (step == STEP_ACCOUNT) showStep(STEP_PERMISSIONS);
                }, 1000);
            }

            @Override
            public void onError(String error) {
                checkingFeed = false;
                btnNext.setText("Continue");
                tvAccountResult.setText("Couldn't load the permit: " + error);
                tvAccountResult.setTextColor(COLOR_ERROR);
                tvAccountResult.setVisibility(View.VISIBLE);
            }
        });
    }

    // ----- Step 3: permissions -----

    private boolean hasBluetooth() {
        BluetoothAdapter adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (adapter == null || !adapter.isEnabled()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (String p : bluetoothPermissions()) {
                if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false;
            }
        }
        return true;
    }

    private String[] bluetoothPermissions() {
        return new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
    }

    private boolean hasNotifications() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetooth() {
        BluetoothAdapter adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (adapter == null) {
            Toast.makeText(this, "This phone has no Bluetooth", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (String p : bluetoothPermissions()) {
                if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(bluetoothPermissions());
                    return;
                }
            }
        }
        if (!adapter.isEnabled()) {
            try {
                enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (SecurityException e) {
                Toast.makeText(this, "Turn on Bluetooth in Settings", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openSettings(Intent primary) {
        try {
            startActivity(primary);
        } catch (Exception e) {
            startActivity(SamsungBatteryHelper.getAppInfoIntent(this));
        }
    }

    private void buildPermissionRows() {
        permissionRows.removeAllViews();
        boolean bt = hasBluetooth();
        addPermissionRow("Bluetooth",
            flagBluetooth && !bt ? "Required to reach the display" : "Talks to the display",
            flagBluetooth && !bt, bt, this::requestBluetooth);
        addPermissionRow("Notifications", "New permits, low battery, missed check-ins", false,
            hasNotifications(), () -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});
                }
            });
        addPermissionRow("Battery", "Stops Android from pausing the sync", false,
            SamsungBatteryHelper.isBatteryOptimizationDisabled(this),
            () -> openSettings(SamsungBatteryHelper.getBatteryOptimizationIntent(this)));
        addPermissionRow("Keep running", SamsungBatteryHelper.getUnusedAppPauseInstruction(), false,
            SamsungBatteryHelper.isUnusedAppPauseDisabled(this),
            () -> openSettings(SamsungBatteryHelper.getUnusedAppPauseIntent(this)));
        // Optional: Android 13+ keeps the sync running with this notification switched off.
        // Only once notifications are allowed: before that nothing shows anyway, and the
        // settings page would open on the app-wide switch instead of this channel's.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hasNotifications()) {
            addPermissionRow("Hide background notification", "Optional. Turn off Background sync; alerts still show.",
                false, BleGattService.isBackgroundNotificationHidden(this),
                () -> openSettings(BleGattService.backgroundNotificationSettingsIntent(this)), "Hide");
        }
    }

    private void addPermissionRow(String title, String subtitle, boolean flagged, boolean granted, Runnable allow) {
        addPermissionRow(title, subtitle, flagged, granted, allow, "Allow");
    }

    private void addPermissionRow(String title, String subtitle, boolean flagged, boolean granted, Runnable allow,
                                  String buttonText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_background);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(10);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(COLOR_TEXT);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(13);
        s.setTextColor(flagged ? COLOR_ERROR : COLOR_MUTED);
        text.addView(t);
        text.addView(s);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (granted) {
            TextView ok = new TextView(this);
            ok.setText("✓");
            ok.setTextSize(22);
            ok.setTextColor(COLOR_OK);
            ok.setPadding(dp(12), 0, dp(8), 0);
            row.addView(ok);
        } else {
            Button b = new Button(this);
            b.setText(buttonText);
            b.setTextColor(0xFFFFFFFF);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_BLUE));
            b.setOnClickListener(v -> allow.run());
            row.addView(b);
        }
        permissionRows.addView(row, rowParams);
    }

    // ----- Step 4: pair -----

    private void startPairing() {
        pairedId = null;
        pairWaiting.setVisibility(View.VISIBLE);
        pairDone.setVisibility(View.GONE);
        btnNext.setText("Finish");
        // The phone has to be advertising for the display to find it
        BleGattService.ensureRunning(this);
        AlarmReceiver.scheduleSync(this);
    }

    private void showPaired(String id) {
        pairedId = id != null ? id : "";
        PermitRepository.DisplayInfo d = id != null ? repository.findDisplay(id) : null;
        tvPaired.setText(d != null ? "✓ Paired with " + d.name : "✓ Display connected");
        etDisplayName.setText(d != null ? d.name : "");
        // Older firmware has no ID, so there's nothing to rename
        int nameVisibility = d != null ? View.VISIBLE : View.GONE;
        etDisplayName.setVisibility(nameVisibility);
        findViewById(R.id.tvNameLabel).setVisibility(nameVisibility);

        pairDone.setAlpha(0f);
        pairWaiting.setVisibility(View.GONE);
        pairDone.setVisibility(View.VISIBLE);
        pairDone.animate().alpha(1f).setDuration(FADE_MS).start();
        btnSkip.setVisibility(View.GONE);
    }

    private void finishSetup() {
        if (!addDisplayOnly) {
            repository.setSetupDone();
            startActivity(new Intent(this, MainActivity.class));
        }
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
