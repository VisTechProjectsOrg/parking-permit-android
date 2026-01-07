package com.visproj.parkingpermitsync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BleStatusFragment extends Fragment {

    private TextView tvStatus;
    private TextView tvPermitNumber;
    private TextView tvPermitPrice;
    private TextView tvPermitBadge;
    private TextView tvPermitDates;
    private TextView tvPermitVehicle;
    private TextView tvGitHubSync;
    private TextView tvDisplaySync;
    private TextView tvConnectionStatus;
    private LinearLayout connectionCard;
    private View statusIndicator;
    private Button btnSync;
    private Button btnBattery;
    private TextView tvSyncStatus;
    private LinearLayout scheduledPermitCard;
    private TextView tvScheduledNumber;
    private TextView tvScheduledDates;
    private TextView tvScheduledVehicle;
    private TextView tvScheduledPrice;
    private LinearLayout displaySyncWarning;
    private Button btnUpdateDisplay;

    private PermitRepository repository;
    private DisplaySyncHelper displaySyncHelper;
    private Handler handler;
    private Runnable updateRunnable;
    private boolean pendingBleRunning = false;

    private final BroadcastReceiver bleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case BleGattService.ACTION_SERVICE_RUNNING:
                    setBleRunning();
                    break;
                case BleGattService.ACTION_DEVICE_CONNECTED:
                    showConnectionStatus("Display connected", "#ff9800");
                    // Also update warning banner button
                    btnUpdateDisplay.setEnabled(false);
                    btnUpdateDisplay.setText("Connecting...");
                    break;
                case BleGattService.ACTION_DEVICE_DISCONNECTED:
                    hideConnectionStatus();
                    btnUpdateDisplay.setEnabled(true);
                    btnUpdateDisplay.setText("Update");
                    updateUI();
                    break;
                case BleGattService.ACTION_PERMIT_READ:
                    showConnectionStatus("Display updating...", "#4caf50");
                    btnUpdateDisplay.setText("Updating...");
                    handler.postDelayed(() -> {
                        hideConnectionStatus();
                        btnUpdateDisplay.setEnabled(true);
                        btnUpdateDisplay.setText("Update");
                        // Save the permit as display permit
                        PermitData permit = repository.getPermit();
                        if (permit != null && permit.permitNumber != null) {
                            repository.setDisplayPermit(permit);
                        }
                        updateUI();
                        PermitData p = repository.getPermit();
                        String msg = p != null && p.permitNumber != null
                            ? "New permit synced to display: " + p.permitNumber
                            : "Display updated!";
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                    }, 2000);
                    break;
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_ble_status, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        repository = new PermitRepository(requireContext());
        handler = new Handler(Looper.getMainLooper());

        tvStatus = view.findViewById(R.id.tvStatus);
        tvPermitNumber = view.findViewById(R.id.tvPermitNumber);
        tvPermitPrice = view.findViewById(R.id.tvPermitPrice);
        tvPermitBadge = view.findViewById(R.id.tvPermitBadge);
        tvPermitDates = view.findViewById(R.id.tvPermitDates);
        tvPermitVehicle = view.findViewById(R.id.tvPermitVehicle);
        tvGitHubSync = view.findViewById(R.id.tvGitHubSync);
        tvDisplaySync = view.findViewById(R.id.tvDisplaySync);
        tvConnectionStatus = view.findViewById(R.id.tvConnectionStatus);
        connectionCard = view.findViewById(R.id.connectionCard);
        statusIndicator = view.findViewById(R.id.statusIndicator);
        btnSync = view.findViewById(R.id.btnSync);
        btnBattery = view.findViewById(R.id.btnBattery);
        tvSyncStatus = view.findViewById(R.id.tvSyncStatus);
        scheduledPermitCard = view.findViewById(R.id.scheduledPermitCard);
        tvScheduledNumber = view.findViewById(R.id.tvScheduledNumber);
        tvScheduledDates = view.findViewById(R.id.tvScheduledDates);
        tvScheduledVehicle = view.findViewById(R.id.tvScheduledVehicle);
        tvScheduledPrice = view.findViewById(R.id.tvScheduledPrice);
        displaySyncWarning = view.findViewById(R.id.displaySyncWarning);
        btnUpdateDisplay = view.findViewById(R.id.btnUpdateDisplay);

        displaySyncHelper = new DisplaySyncHelper(requireContext());

        btnSync.setOnClickListener(v -> showSyncMenu(v));
        btnBattery.setOnClickListener(v -> openBatterySettings());
        btnUpdateDisplay.setOnClickListener(v -> updateDisplay(false));

        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateSyncTimes();
                handler.postDelayed(this, 10000);
            }
        };

        updateUI();

        // Apply pending BLE status if it was set before view was created
        if (pendingBleRunning) {
            setBleRunning();
            pendingBleRunning = false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleGattService.ACTION_SERVICE_RUNNING);
        filter.addAction(BleGattService.ACTION_DEVICE_CONNECTED);
        filter.addAction(BleGattService.ACTION_DEVICE_DISCONNECTED);
        filter.addAction(BleGattService.ACTION_PERMIT_READ);
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(bleReceiver, filter);

        handler.post(updateRunnable);
        updateUI();

        if (BleGattService.isServiceRunning()) {
            setBleRunning();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(bleReceiver);
        handler.removeCallbacks(updateRunnable);
    }

    private void showConnectionStatus(String message, String color) {
        connectionCard.setVisibility(View.VISIBLE);
        tvConnectionStatus.setText(message);
        tvConnectionStatus.setTextColor(android.graphics.Color.parseColor(color));
    }

    private void hideConnectionStatus() {
        connectionCard.setVisibility(View.GONE);
    }

    private void syncNow() {
        btnSync.setEnabled(false);
        tvStatus.setText("Syncing from GitHub...");
        setStatusIndicatorColor("#ff9800");

        GitHubSyncTask syncTask = new GitHubSyncTask(requireContext());
        syncTask.sync(new GitHubSyncTask.SyncCallback() {
            @Override
            public void onSuccess(PermitData permit, boolean isNew) {
                if (!isAdded()) return;
                btnSync.setEnabled(true);
                tvStatus.setText("BLE Server Running");
                setStatusIndicatorColor("#4caf50");
                updateUI();

                String msg = isNew ? "New permit synced!" : "Permit up to date";
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                btnSync.setEnabled(true);
                tvStatus.setText("Sync failed");
                setStatusIndicatorColor("#f44336");
            }
        });
    }

    private void showSyncMenu(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(0, 1, 0, "Sync from GitHub");
        popup.getMenu().add(0, 2, 1, "Update Display");
        popup.getMenu().add(0, 3, 2, "Force Update Display");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    syncNow();
                    return true;
                case 2:
                    updateDisplay(false);
                    return true;
                case 3:
                    updateDisplay(true);
                    return true;
            }
            return false;
        });

        popup.show();
    }

    private void updateDisplay(boolean force) {
        btnSync.setEnabled(false);
        btnUpdateDisplay.setEnabled(false);
        btnUpdateDisplay.setText("Updating...");
        tvSyncStatus.setVisibility(View.VISIBLE);
        tvSyncStatus.setText(force ? "Force updating display..." : "Scanning for display...");

        displaySyncHelper.syncDisplay(force, new DisplaySyncHelper.SyncCallback() {
            @Override
            public void onStatus(String status) {
                if (!isAdded()) return;
                tvSyncStatus.setText(status);
            }

            @Override
            public void onSuccess() {
                if (!isAdded()) return;
                btnSync.setEnabled(true);
                btnUpdateDisplay.setEnabled(true);
                btnUpdateDisplay.setText("Update");
                tvSyncStatus.setVisibility(View.GONE);

                // Track which permit is now on the display (save full permit data)
                PermitData permit = repository.getPermit();
                if (permit != null && permit.permitNumber != null) {
                    repository.setDisplayPermit(permit);
                }
                updateUI();
                String msg = permit != null && permit.permitNumber != null
                    ? "New permit synced to display: " + permit.permitNumber
                    : "Display updated!";
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                btnSync.setEnabled(true);
                btnUpdateDisplay.setEnabled(true);
                btnUpdateDisplay.setText("Update");
                tvSyncStatus.setText(error);
                tvSyncStatus.setTextColor(android.graphics.Color.parseColor("#f44336"));

                // Hide after delay
                handler.postDelayed(() -> {
                    if (isAdded()) {
                        tvSyncStatus.setVisibility(View.GONE);
                        tvSyncStatus.setTextColor(android.graphics.Color.parseColor("#8892a6"));
                    }
                }, 3000);
            }
        });
    }

    private void setStatusIndicatorColor(String color) {
        GradientDrawable drawable = (GradientDrawable) statusIndicator.getBackground();
        drawable.setColor(android.graphics.Color.parseColor(color));
    }

    public void setBleRunning() {
        if (tvStatus != null && statusIndicator != null) {
            tvStatus.setText("BLE Server Running");
            setStatusIndicatorColor("#4caf50");
        } else {
            pendingBleRunning = true;
        }
    }

    private void updateUI() {
        if (!isAdded() || repository == null) return;

        PermitData githubPermit = repository.getPermit();
        PermitData displayPermit = repository.getDisplayPermit();
        boolean isOutOfSync = repository.isDisplayOutOfSync();

        // If display is out of sync and we have a display permit, show the OLD one as current
        // (because that's what's actually on the display right now)
        // Otherwise show the github permit
        PermitData currentPermit;
        if (isOutOfSync && displayPermit != null && displayPermit.isValid()) {
            currentPermit = displayPermit;
        } else {
            currentPermit = githubPermit;
        }

        if (currentPermit != null && currentPermit.isValid()) {
            tvPermitNumber.setText(currentPermit.permitNumber);
            if (currentPermit.price != null && !currentPermit.price.isEmpty()) {
                tvPermitPrice.setText(currentPermit.price);
                tvPermitPrice.setVisibility(View.VISIBLE);
            } else {
                tvPermitPrice.setVisibility(View.GONE);
            }
            tvPermitVehicle.setText(String.format("Hooptie (%s)", currentPermit.plateNumber));

            // Format dates nicely
            String dateRange = formatDateRange(currentPermit.validFrom, currentPermit.validTo);
            tvPermitDates.setText(dateRange);

            // Set badge based on current status
            updatePermitBadge(currentPermit);

            // Show scheduled permit if GitHub has a newer one
            updateScheduledPermit(currentPermit, githubPermit, displayPermit != null);
        } else {
            tvPermitNumber.setText("No permit");
            tvPermitPrice.setText("");
            tvPermitDates.setText("--");
            tvPermitVehicle.setText("--");
            tvPermitBadge.setText("None");
            tvPermitBadge.setBackgroundResource(R.drawable.badge_orange);
            scheduledPermitCard.setVisibility(View.GONE);
        }

        updateSyncTimes();
        updateBatteryButton();
    }

    private void updateScheduledPermit(PermitData currentPermit, PermitData githubPermit, boolean hasDisplayPermit) {
        // Show scheduled card if GitHub has a different (newer) permit than what's on display
        if (githubPermit != null && githubPermit.isValid() &&
            !githubPermit.permitNumber.equals(currentPermit.permitNumber)) {

            // GitHub has a newer permit - show it as scheduled
            tvScheduledNumber.setText(githubPermit.permitNumber);
            String dateRange = formatDateRange(githubPermit.validFrom, githubPermit.validTo);
            tvScheduledDates.setText(dateRange);
            tvScheduledVehicle.setText(String.format("Hooptie (%s)", githubPermit.plateNumber));

            if (githubPermit.price != null && !githubPermit.price.isEmpty()) {
                tvScheduledPrice.setText(githubPermit.price);
                tvScheduledPrice.setVisibility(View.VISIBLE);
            } else {
                tvScheduledPrice.setVisibility(View.GONE);
            }

            scheduledPermitCard.setVisibility(View.VISIBLE);
            return;
        }

        // If no display permit yet, don't show estimated future permit
        // (we're already showing the GitHub permit as current)
        if (!hasDisplayPermit) {
            scheduledPermitCard.setVisibility(View.GONE);
            return;
        }

        // No newer permit from GitHub - check if current is expiring soon and estimate next
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("MMM dd, yyyy: HH:mm", Locale.US);
            SimpleDateFormat outputFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.US);
            SimpleDateFormat dayFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);

            Date toDate = inputFormat.parse(currentPermit.validTo);
            Date now = new Date();

            if (toDate == null) {
                scheduledPermitCard.setVisibility(View.GONE);
                return;
            }

            // Calculate days until expiry
            boolean isToday = dayFormat.format(toDate).equals(dayFormat.format(now));
            long daysRemaining = (toDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24);

            // Show estimated permit if expiring today or within 2 days
            if (isToday || daysRemaining <= 2) {
                // Calculate next permit dates
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(toDate);
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
                Date nextStart = cal.getTime();
                cal.add(java.util.Calendar.DAY_OF_MONTH, 6);
                Date nextEnd = cal.getTime();

                tvScheduledNumber.setText("Pending");
                String dateRange = outputFormat.format(nextStart) + " - " + outputFormat.format(nextEnd);
                tvScheduledDates.setText(dateRange);
                tvScheduledVehicle.setText(String.format("Hooptie (%s)", currentPermit.plateNumber));

                // Estimate price based on current
                if (currentPermit.price != null && !currentPermit.price.isEmpty()) {
                    tvScheduledPrice.setText("~" + currentPermit.price);
                    tvScheduledPrice.setVisibility(View.VISIBLE);
                } else {
                    tvScheduledPrice.setVisibility(View.GONE);
                }

                scheduledPermitCard.setVisibility(View.VISIBLE);
            } else {
                scheduledPermitCard.setVisibility(View.GONE);
            }
        } catch (ParseException e) {
            scheduledPermitCard.setVisibility(View.GONE);
        }
    }

    private String formatDateRange(String from, String to) {
        try {
            // Input format: "Dec 30, 2025: 00:00" or similar
            SimpleDateFormat inputFormat = new SimpleDateFormat("MMM dd, yyyy: HH:mm", Locale.US);
            SimpleDateFormat outputFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.US);

            Date fromDate = inputFormat.parse(from);
            Date toDate = inputFormat.parse(to);

            if (fromDate != null && toDate != null) {
                return outputFormat.format(fromDate) + " - " + outputFormat.format(toDate);
            }
        } catch (ParseException e) {
            // Try alternate format without time
            try {
                SimpleDateFormat altInput = new SimpleDateFormat("MMM dd, yyyy", Locale.US);
                Date fromDate = altInput.parse(from.split(":")[0].trim());
                Date toDate = altInput.parse(to.split(":")[0].trim());
                if (fromDate != null && toDate != null) {
                    return altInput.format(fromDate) + " - " + altInput.format(toDate);
                }
            } catch (Exception ex) {
                // Fall through to default
            }
        }
        // Fallback: just return as-is but cleaned up
        String cleanFrom = from.contains(":") ? from.substring(0, from.lastIndexOf(":")).trim() : from;
        String cleanTo = to.contains(":") ? to.substring(0, to.lastIndexOf(":")).trim() : to;
        return cleanFrom + " - " + cleanTo;
    }

    private void updatePermitBadge(PermitData permit) {
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat("MMM dd, yyyy: HH:mm", Locale.US);
            SimpleDateFormat dayFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
            Date toDate = inputFormat.parse(permit.validTo);
            Date now = new Date();

            if (toDate != null) {
                boolean isToday = dayFormat.format(toDate).equals(dayFormat.format(now));
                long daysRemaining = (toDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24);

                if (now.after(toDate)) {
                    tvPermitBadge.setText("Expired");
                    tvPermitBadge.setBackgroundResource(R.drawable.badge_red);
                } else if (isToday) {
                    tvPermitBadge.setText("Expiring Today");
                    tvPermitBadge.setBackgroundResource(R.drawable.badge_red);
                } else if (daysRemaining <= 1) {
                    tvPermitBadge.setText("Expiring");
                    tvPermitBadge.setBackgroundResource(R.drawable.badge_orange);
                } else {
                    tvPermitBadge.setText("Current");
                    tvPermitBadge.setBackgroundResource(R.drawable.badge_green);
                }
            }
        } catch (ParseException e) {
            tvPermitBadge.setText("Current");
            tvPermitBadge.setBackgroundResource(R.drawable.badge_green);
        }
    }

    private void updateSyncTimes() {
        if (!isAdded() || repository == null) return;
        tvGitHubSync.setText(TimeUtils.getRelativeTime(repository.getLastSyncTime()));

        // Show out-of-sync warning if display has old permit
        if (repository.isDisplayOutOfSync()) {
            tvDisplaySync.setText("Out of sync");
            tvDisplaySync.setTextColor(android.graphics.Color.parseColor("#f44336"));
            displaySyncWarning.setVisibility(View.VISIBLE);
        } else {
            tvDisplaySync.setText(TimeUtils.getRelativeTime(repository.getLastDisplaySyncTime()));
            tvDisplaySync.setTextColor(android.graphics.Color.parseColor("#64b5f6"));
            displaySyncWarning.setVisibility(View.GONE);
        }
    }

    private void updateBatteryButton() {
        if (!isAdded()) return;
        if (!SamsungBatteryHelper.isBatteryOptimizationDisabled(requireContext())) {
            btnBattery.setVisibility(View.VISIBLE);
            btnBattery.setText(SamsungBatteryHelper.isSamsungDevice()
                ? "Fix Battery Settings" : "Disable Battery Optimization");
        } else {
            btnBattery.setVisibility(View.GONE);
        }
    }

    private void openBatterySettings() {
        if (SamsungBatteryHelper.isSamsungDevice()) {
            new AlertDialog.Builder(requireContext())
                .setTitle("Samsung Battery Settings")
                .setMessage("To keep the app running:\n\n" +
                    "1. Tap 'Open Settings' below\n" +
                    "2. Find 'Parking Permit Sync'\n" +
                    "3. Set to 'Unrestricted'\n\n" +
                    "This prevents Samsung from killing the app.")
                .setPositiveButton("Open Settings", (d, w) -> {
                    try {
                        startActivity(SamsungBatteryHelper.getAppInfoIntent(requireContext()));
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "Could not open settings", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        } else {
            try {
                startActivity(SamsungBatteryHelper.getBatteryOptimizationIntent(requireContext()));
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Could not open battery settings", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
