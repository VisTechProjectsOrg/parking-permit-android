package com.visproj.parkingpermitsync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BleStatusFragment extends Fragment {

    private static final int COLOR_BLUE = 0xFF2196F3;

    private TextView tvStatus;
    private TextView tvPermitNumber;
    private TextView tvPermitPrice;
    private TextView tvPermitBadge;
    private TextView tvPermitDates;
    private TextView tvPermitVehicle;
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
    private TextView tvScheduledBadge;
    private LinearLayout displaySyncWarning;
    private TextView tvCoverageWarning;
    private BatteryView batteryView;
    private TextView tvDisplayBattery;

    private PermitRepository repository;
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
                    break;
                case BleGattService.ACTION_DEVICE_DISCONNECTED:
                    hideConnectionStatus();
                    updateUI();
                    break;
                case BleGattService.ACTION_DISPLAY_STATUS:
                    updateUI();
                    break;
                case BleGattService.ACTION_PERMIT_READ:
                    showConnectionStatus("Display updating...", "#4caf50");
                    boolean isNewPermit = intent.getBooleanExtra("isNewPermit", false);
                    handler.postDelayed(() -> {
                        hideConnectionStatus();
                        // The service already recorded what the display took (and what it
                        // reported back); just refresh
                        updateUI();
                        PermitData p = repository.getPermit();
                        String msg;
                        if (p != null && p.permitNumber != null) {
                            msg = isNewPermit
                                ? "New permit synced: " + p.permitNumber
                                : "Synced to display: " + p.permitNumber;
                        } else {
                            msg = "Display updated!";
                        }
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                    }, 4000);
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
        tvScheduledBadge = view.findViewById(R.id.tvScheduledBadge);
        displaySyncWarning = view.findViewById(R.id.displaySyncWarning);
        tvCoverageWarning = view.findViewById(R.id.tvCoverageWarning);
        batteryView = view.findViewById(R.id.batteryView);
        tvDisplayBattery = view.findViewById(R.id.tvDisplayBattery);
        view.findViewById(R.id.displayBatteryGroup).setOnClickListener(v -> showDisplayDetails());

        btnSync.setOnClickListener(v -> syncNow());
        btnBattery.setOnClickListener(v -> openBatterySettings());

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
        filter.addAction(BleGattService.ACTION_DISPLAY_STATUS);
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
        setButtonEnabled(btnSync, false, COLOR_BLUE);
        tvStatus.setText("Checking for new permit...");
        setStatusIndicatorColor("#ff9800");

        PermitSyncTask syncTask = new PermitSyncTask(requireContext());
        syncTask.sync(new PermitSyncTask.SyncCallback() {
            @Override
            public void onSuccess(PermitData permit, boolean isNew) {
                if (!isAdded()) return;
                setButtonEnabled(btnSync, true, COLOR_BLUE);
                tvStatus.setText("BLE Server Running");
                setStatusIndicatorColor("#4caf50");
                updateUI();

                String msg = isNew ? "New permit found" : "Permit is up to date";
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                setButtonEnabled(btnSync, true, COLOR_BLUE);
                tvStatus.setText("Couldn't reach the site");
                setStatusIndicatorColor("#f44336");
            }
        });
    }

    private void setStatusIndicatorColor(String color) {
        GradientDrawable drawable = (GradientDrawable) statusIndicator.getBackground();
        drawable.setColor(android.graphics.Color.parseColor(color));
    }

    private void setButtonEnabled(Button button, boolean enabled, int enabledColor) {
        button.setEnabled(enabled);
        if (enabled) {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(enabledColor));
        } else {
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#666666")));
        }
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

        PermitData latestPermit = repository.getPermit();
        PermitData displayPermit = repository.getDisplayPermit();
        boolean isOutOfSync = repository.isDisplayOutOfSync();
        long now = System.currentTimeMillis();

        // Current = what's on the display. If it hasn't taken the latest permit yet, that's the
        // old one. Otherwise pick by date like the display does: after the 4 PM flip on the last
        // day the feed lists next week's permit first and the expiring one as "next".
        PermitData currentPermit = latestPermit;
        PermitData upcomingPermit = latestPermit;
        if (isOutOfSync && displayPermit != null && displayPermit.isValid()) {
            currentPermit = displayPermit;
        } else if (latestPermit != null && latestPermit.next != null && latestPermit.next.isValid()) {
            PermitData other = latestPermit.next;
            if (other.isValidAt(now) && !latestPermit.isValidAt(now)) {
                // Prefer the stored display permit for it: it has the price and vehicle name
                currentPermit = displayPermit != null && other.permitNumber.equals(displayPermit.permitNumber)
                    ? displayPermit : other;
            } else if (other.startsAfter(now)) {
                upcomingPermit = other;
            }
        }

        if (currentPermit != null && currentPermit.isValid()) {
            tvPermitNumber.setText(currentPermit.permitNumber);
            if (currentPermit.price != null && !currentPermit.price.isEmpty()) {
                String priceText = currentPermit.price;
                // Show price change compared to previous permit (week-over-week)
                PermitData previousPermit = repository.getPreviousPermit();
                String change = null;
                int changeColor = 0;
                if (previousPermit != null && previousPermit.price != null && !previousPermit.price.isEmpty()) {
                    double[] result = getPriceChangeValue(currentPermit.price, previousPermit.price);
                    if (result != null) {
                        double diff = result[0];
                        String sign = diff > 0 ? "+" : "";
                        change = String.format("(%s$%.2f)", sign, diff);
                        // Red for increase (paying more), green for decrease (saving money)
                        changeColor = diff > 0 ? Color.parseColor("#f44336") : Color.parseColor("#4caf50");
                    }
                }
                if (change != null) {
                    // Format: "+$2.40 $50.78" with change colored
                    String fullText = change + " " + priceText;
                    SpannableString spannable = new SpannableString(fullText);
                    spannable.setSpan(new ForegroundColorSpan(changeColor), 0, change.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    tvPermitPrice.setText(spannable);
                } else {
                    tvPermitPrice.setText(priceText);
                }
                tvPermitPrice.setVisibility(View.VISIBLE);
            } else {
                tvPermitPrice.setVisibility(View.GONE);
            }
            tvPermitVehicle.setText(vehicleLabel(currentPermit));

            // Format dates nicely
            String dateRange = formatDateRange(currentPermit.validFrom, currentPermit.validTo);
            tvPermitDates.setText(dateRange);

            // Set badge based on current status
            updatePermitBadge(currentPermit);

            // Show the next permit if one is already bought
            updateScheduledPermit(currentPermit, upcomingPermit, displayPermit != null);
        } else {
            tvPermitNumber.setText("No permit");
            tvPermitPrice.setText("");
            tvPermitDates.setText("--");
            tvPermitVehicle.setText("--");
            tvPermitBadge.setText("None");
            tvPermitBadge.setBackgroundResource(R.drawable.badge_orange);
            scheduledPermitCard.setVisibility(View.GONE);
        }

        // Coverage comes from the latest fetched permit, not what the display shows
        String coverageMsg = latestPermit != null && latestPermit.isValid()
            ? PermitData.coverageMessage(latestPermit.getCoverage()) : null;
        tvCoverageWarning.setText(coverageMsg);
        tvCoverageWarning.setVisibility(coverageMsg != null ? View.VISIBLE : View.GONE);

        updateSyncTimes();
        updateBatteryButton();
    }

    // Build the "Name (PLATE)" label from the permit's own vehicleName (falls back if missing).
    private String vehicleLabel(PermitData p) {
        String name = (p.vehicleName != null && !p.vehicleName.isEmpty()) ? p.vehicleName : "Vehicle";
        return String.format("%s (%s)", name, p.plateNumber);
    }

    private void updateScheduledPermit(PermitData currentPermit, PermitData nextPermit, boolean hasDisplayPermit) {
        // Show the next permit if it's a different one from what's on the display
        if (nextPermit != null && nextPermit.isValid() &&
            !nextPermit.permitNumber.equals(currentPermit.permitNumber)) {

            // Next permit already bought - show it as scheduled
            tvScheduledNumber.setText(nextPermit.permitNumber);
            String dateRange = formatDateRange(nextPermit.validFrom, nextPermit.validTo);
            tvScheduledDates.setText(dateRange);
            tvScheduledVehicle.setText(vehicleLabel(nextPermit));

            if (nextPermit.price != null && !nextPermit.price.isEmpty()) {
                tvScheduledPrice.setText(nextPermit.price);
                tvScheduledPrice.setVisibility(View.VISIBLE);
            } else {
                tvScheduledPrice.setVisibility(View.GONE);
            }

            // Stored on the display = it switches to it by itself when it starts
            if (nextPermit.permitNumber.equals(repository.getDisplayCachedPermitNumber())) {
                setScheduledBadge("On display", R.drawable.badge_green);
            } else {
                setScheduledBadge("Scheduled", R.drawable.badge_blue);
            }

            scheduledPermitCard.setVisibility(View.VISIBLE);
            return;
        }

        // If no display permit yet, don't show estimated future permit
        // (we're already showing the latest permit as current)
        if (!hasDisplayPermit) {
            scheduledPermitCard.setVisibility(View.GONE);
            return;
        }

        // No next permit bought yet - if current is expiring soon, estimate the next one
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
                setScheduledBadge("Not bought yet", R.drawable.badge_orange);
                String dateRange = outputFormat.format(nextStart) + " - " + outputFormat.format(nextEnd);
                tvScheduledDates.setText(dateRange);
                tvScheduledVehicle.setText(vehicleLabel(currentPermit));

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

    private void setScheduledBadge(String text, int background) {
        tvScheduledBadge.setText(text);
        tvScheduledBadge.setBackgroundResource(background);
    }

    private String formatDateRange(String from, String to) {
        try {
            // Input format: "Dec 30, 2025: 00:00" or similar
            SimpleDateFormat inputFormat = new SimpleDateFormat("MMM dd, yyyy: HH:mm", Locale.US);
            SimpleDateFormat monthYearFormat = new SimpleDateFormat("MMyyyy", Locale.US);
            SimpleDateFormat yearFormat = new SimpleDateFormat("yyyy", Locale.US);
            SimpleDateFormat dayFormat = new SimpleDateFormat("d", Locale.US);
            SimpleDateFormat monthDayFormat = new SimpleDateFormat("MMM d", Locale.US);

            Date fromDate = inputFormat.parse(from);
            Date toDate = inputFormat.parse(to);

            if (fromDate != null && toDate != null) {
                boolean sameYear = yearFormat.format(fromDate).equals(yearFormat.format(toDate));
                boolean sameMonthYear = monthYearFormat.format(fromDate).equals(monthYearFormat.format(toDate));

                if (sameMonthYear) {
                    // Same month/year: "Jan 7 - 14, 2026"
                    return monthDayFormat.format(fromDate) + " - " + dayFormat.format(toDate) + ", " +
                           yearFormat.format(toDate);
                } else if (sameYear) {
                    // Different month, same year: "Dec 30 - Jan 6, 2026"
                    return monthDayFormat.format(fromDate) + " - " + monthDayFormat.format(toDate) + ", " +
                           yearFormat.format(toDate);
                } else {
                    // Different year: "Dec 30, 2025 - Jan 6, 2026"
                    return monthDayFormat.format(fromDate) + ", " + yearFormat.format(fromDate) + " - " +
                           monthDayFormat.format(toDate) + ", " + yearFormat.format(toDate);
                }
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

    // Bolt only right after a full reading: the display can't see the charger, so 4.15V+
    // reported in the last 30 minutes is the best sign that it's on USB right now
    private static final int CHARGING_MV = 4150;
    private static final long CHARGING_FRESH_MS = 30 * 60 * 1000;

    private void updateSyncTimes() {
        if (!isAdded() || repository == null) return;

        displaySyncWarning.setVisibility(repository.isDisplayOutOfSync() ? View.VISIBLE : View.GONE);

        int mv = repository.getDisplayBatteryMv();
        if (mv > 0) {
            int pct = PermitRepository.batteryPercent(mv);
            batteryView.setLevel(pct, isDisplayCharging(mv));
            tvDisplayBattery.setText(pct + "%");
        } else {
            batteryView.setLevel(-1, false);
            tvDisplayBattery.setText("--");
        }
    }

    private boolean isDisplayCharging(int mv) {
        return mv >= CHARGING_MV
            && System.currentTimeMillis() - repository.getDisplayBatteryTime() < CHARGING_FRESH_MS;
    }

    private void showDisplayDetails() {
        int mv = repository.getDisplayBatteryMv();
        StringBuilder msg = new StringBuilder();
        if (mv > 0) {
            int pct = PermitRepository.batteryPercent(mv);
            int days = repository.estimateBatteryDaysLeft();
            msg.append("Battery: ").append(pct).append("%");
            if (isDisplayCharging(mv)) msg.append(", charging");
            msg.append("\n");
            if (days > 60) msg.append("About ").append(days / 30).append(" months left\n");
            else if (days >= 0) msg.append("About ").append(days).append(days == 1 ? " day left\n" : " days left\n");
            msg.append(String.format(Locale.US, "%.2f V, read %s\n\n", mv / 1000.0,
                TimeUtils.getRelativeTime(repository.getDisplayBatteryTime()).toLowerCase(Locale.US)));
        } else {
            msg.append("No battery reading yet\n\n");
        }
        msg.append("Display synced: ").append(repository.isDisplayOutOfSync() ? "out of sync"
            : TimeUtils.getRelativeTime(repository.getLastDisplaySyncTime()).toLowerCase(Locale.US)).append("\n");
        msg.append("Permit checked: ")
            .append(TimeUtils.getRelativeTime(repository.getLastSyncTime()).toLowerCase(Locale.US));

        new AlertDialog.Builder(requireContext())
            .setTitle("Display")
            .setMessage(msg.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    private void updateBatteryButton() {
        if (!isAdded()) return;
        if (!SamsungBatteryHelper.isBatteryOptimizationDisabled(requireContext())) {
            btnBattery.setVisibility(View.VISIBLE);
            btnBattery.setText(SamsungBatteryHelper.isSamsungDevice()
                ? "Fix Battery Settings" : "Disable Battery Optimization");
        } else if (!SamsungBatteryHelper.isUnusedAppPauseDisabled(requireContext())) {
            btnBattery.setVisibility(View.VISIBLE);
            btnBattery.setText("Keep Running When Not Opened");
        } else {
            btnBattery.setVisibility(View.GONE);
        }
    }

    private void openUnusedAppPauseSettings() {
        new AlertDialog.Builder(requireContext())
            .setTitle("Keep Running")
            .setMessage(SamsungBatteryHelper.getUnusedAppPauseInstruction())
            .setPositiveButton("Open Settings", (d, w) -> {
                try {
                    startActivity(SamsungBatteryHelper.getUnusedAppPauseIntent(requireContext()));
                } catch (Exception e) {
                    startActivity(SamsungBatteryHelper.getAppInfoIntent(requireContext()));
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private double[] getPriceChangeValue(String currentPrice, String previousPrice) {
        try {
            double current = Double.parseDouble(currentPrice.replaceAll("[^0-9.]", ""));
            double previous = Double.parseDouble(previousPrice.replaceAll("[^0-9.]", ""));
            double diff = current - previous;
            if (Math.abs(diff) >= 0.01) {
                return new double[]{diff};
            }
        } catch (NumberFormatException e) {
            // Ignore if price parsing fails
        }
        return null;
    }

    private void openBatterySettings() {
        if (SamsungBatteryHelper.isBatteryOptimizationDisabled(requireContext())
                && !SamsungBatteryHelper.isUnusedAppPauseDisabled(requireContext())) {
            openUnusedAppPauseSettings();
            return;
        }
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
