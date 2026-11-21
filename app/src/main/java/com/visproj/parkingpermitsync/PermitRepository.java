package com.visproj.parkingpermitsync;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

public class PermitRepository {
    private static final String PREFS_NAME = "permit_data";
    private static final String KEY_PERMIT = "cached_permit";
    private static final String KEY_LAST_SYNC = "last_sync_time";
    private static final String KEY_LAST_DISPLAY_SYNC = "last_display_sync_time";
    private static final String KEY_DISPLAY_PERMIT_NUMBER = "display_permit_number";
    private static final String KEY_DISPLAY_PERMIT = "display_permit";
    private static final String KEY_PREVIOUS_PERMIT = "previous_permit";
    private static final String KEY_DISPLAY_FLIPPED = "display_flipped";
    private static final String KEY_NEW_PERMIT_DETECTED = "new_permit_detected_time";
    private static final String KEY_REMINDERS_ENABLED = "reminders_enabled";
    private static final String KEY_CONSECUTIVE_SYNC_FAILURES = "consecutive_sync_failures";
    private static final String KEY_DISPLAY_CACHED_PERMIT = "display_cached_permit";
    private static final String KEY_BATTERY_MV = "display_battery_mv";
    private static final String KEY_BATTERY_TIME = "display_battery_time";
    private static final String KEY_BATTERY_BASE_MV = "display_battery_base_mv";
    private static final String KEY_BATTERY_BASE_TIME = "display_battery_base_time";
    private static final String KEY_BATTERY_LOW_NOTIFIED = "display_battery_low_notified";

    public static final String PERMIT_URL = "https://fucktorontoparking.ca/api/permit";

    private final SharedPreferences prefs;
    private final Gson gson;

    public PermitRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public PermitData getPermit() {
        String json = prefs.getString(KEY_PERMIT, null);
        if (json == null) return null;

        try {
            return gson.fromJson(json, PermitData.class);
        } catch (Exception e) {
            return null;
        }
    }

    public void savePermit(PermitData permit) {
        // Save current permit as previous before overwriting (if it's a different permit)
        PermitData currentPermit = getPermit();
        if (currentPermit != null && currentPermit.permitNumber != null &&
            !currentPermit.permitNumber.equals(permit.permitNumber)) {
            setPreviousPermit(currentPermit);
        }

        prefs.edit()
            .putString(KEY_PERMIT, gson.toJson(permit))
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .apply();
    }

    public long getLastSyncTime() {
        return prefs.getLong(KEY_LAST_SYNC, 0);
    }

    public long getLastDisplaySyncTime() {
        return prefs.getLong(KEY_LAST_DISPLAY_SYNC, 0);
    }

    public void setLastDisplaySyncTime(long time) {
        prefs.edit().putLong(KEY_LAST_DISPLAY_SYNC, time).apply();
    }

    public String getDisplayPermitNumber() {
        return prefs.getString(KEY_DISPLAY_PERMIT_NUMBER, null);
    }

    public void setDisplayPermitNumber(String permitNumber) {
        prefs.edit()
            .putString(KEY_DISPLAY_PERMIT_NUMBER, permitNumber)
            .putLong(KEY_LAST_DISPLAY_SYNC, System.currentTimeMillis())
            .apply();
    }

    public PermitData getDisplayPermit() {
        String json = prefs.getString(KEY_DISPLAY_PERMIT, null);
        if (json == null) return null;
        try {
            return gson.fromJson(json, PermitData.class);
        } catch (Exception e) {
            return null;
        }
    }

    public void setDisplayPermit(PermitData permit) {
        prefs.edit()
            .putString(KEY_DISPLAY_PERMIT, gson.toJson(permit))
            .putString(KEY_DISPLAY_PERMIT_NUMBER, permit.permitNumber)
            .putLong(KEY_LAST_DISPLAY_SYNC, System.currentTimeMillis())
            .remove(KEY_NEW_PERMIT_DETECTED)
            .apply();
    }

    public boolean isDisplayOutOfSync() {
        PermitData permit = getPermit();
        String displayPermit = getDisplayPermitNumber();
        if (permit == null || permit.permitNumber == null) return false;
        // The battery display may still show the expiring permit (until midnight) while holding
        // the new one, which counts as in sync
        if (permit.permitNumber.equals(prefs.getString(KEY_DISPLAY_CACHED_PERMIT, null))) return false;
        return displayPermit == null || !permit.permitNumber.equals(displayPermit);
    }

    // ----- Status reported by the battery display after each sync: "shown,cached,mv,fw" -----

    public void saveDisplayStatus(String shown, String cached, int batteryMv) {
        SharedPreferences.Editor e = prefs.edit()
            .putString(KEY_DISPLAY_PERMIT_NUMBER, shown)
            .putString(KEY_DISPLAY_CACHED_PERMIT, cached)
            .putLong(KEY_LAST_DISPLAY_SYNC, System.currentTimeMillis());

        // Keep the full permit the display shows, for the UI
        PermitData latest = getPermit();
        if (latest != null) {
            PermitData match = shown.equals(latest.permitNumber) ? latest
                : (latest.next != null && shown.equals(latest.next.permitNumber)) ? latest.next : null;
            if (match != null) e.putString(KEY_DISPLAY_PERMIT, gson.toJson(match));
        }

        if (batteryMv > 0) {
            long now = System.currentTimeMillis();
            int lastMv = prefs.getInt(KEY_BATTERY_MV, 0);
            // A clear rise means it was charged: restart the drain estimate from here
            if (lastMv == 0 || batteryMv > lastMv + 100) {
                e.putInt(KEY_BATTERY_BASE_MV, batteryMv).putLong(KEY_BATTERY_BASE_TIME, now);
                e.putBoolean(KEY_BATTERY_LOW_NOTIFIED, false);
            }
            e.putInt(KEY_BATTERY_MV, batteryMv).putLong(KEY_BATTERY_TIME, now);
        }
        e.apply();
    }

    // The next permit the display is holding (switches to it on its own when it starts), or null
    public String getDisplayCachedPermitNumber() {
        String cached = prefs.getString(KEY_DISPLAY_CACHED_PERMIT, null);
        return cached == null || cached.isEmpty() ? null : cached;
    }

    public int getDisplayBatteryMv() {
        return prefs.getInt(KEY_BATTERY_MV, 0);
    }

    public long getDisplayBatteryTime() {
        return prefs.getLong(KEY_BATTERY_TIME, 0);
    }

    // Rough single-cell LiPo state of charge under light load
    public static int batteryPercent(int mv) {
        int[][] curve = {{4150, 100}, {4000, 85}, {3900, 72}, {3800, 55}, {3750, 45},
                         {3700, 32}, {3650, 20}, {3550, 10}, {3450, 5}, {3300, 0}};
        if (mv >= curve[0][0]) return 100;
        for (int i = 1; i < curve.length; i++) {
            if (mv >= curve[i][0]) {
                int[] hi = curve[i - 1], lo = curve[i];
                return lo[1] + (mv - lo[0]) * (hi[1] - lo[1]) / (hi[0] - lo[0]);
            }
        }
        return 0;
    }

    // Days left from the drain since the last charge, or -1 until there are 2+ days of data
    public int estimateBatteryDaysLeft() {
        int baseMv = prefs.getInt(KEY_BATTERY_BASE_MV, 0);
        long baseTime = prefs.getLong(KEY_BATTERY_BASE_TIME, 0);
        int mv = getDisplayBatteryMv();
        long t = getDisplayBatteryTime();
        if (baseMv == 0 || t - baseTime < 2L * 24 * 3600 * 1000) return -1;
        double usedPct = batteryPercent(baseMv) - batteryPercent(mv);
        if (usedPct <= 0) return -1;
        double days = (t - baseTime) / (24.0 * 3600 * 1000);
        return (int) (batteryPercent(mv) / (usedPct / days));
    }

    public boolean isBatteryLowNotified() {
        return prefs.getBoolean(KEY_BATTERY_LOW_NOTIFIED, false);
    }

    public void setBatteryLowNotified() {
        prefs.edit().putBoolean(KEY_BATTERY_LOW_NOTIFIED, true).apply();
    }

    public PermitData getPreviousPermit() {
        String json = prefs.getString(KEY_PREVIOUS_PERMIT, null);
        if (json == null) return null;
        try {
            return gson.fromJson(json, PermitData.class);
        } catch (Exception e) {
            return null;
        }
    }

    public void setPreviousPermit(PermitData permit) {
        prefs.edit()
            .putString(KEY_PREVIOUS_PERMIT, gson.toJson(permit))
            .apply();
    }

    public boolean isDisplayFlipped() {
        return prefs.getBoolean(KEY_DISPLAY_FLIPPED, false);
    }

    public void setDisplayFlipped(boolean flipped) {
        prefs.edit().putBoolean(KEY_DISPLAY_FLIPPED, flipped).apply();
    }

    public long getNewPermitDetectedTime() {
        return prefs.getLong(KEY_NEW_PERMIT_DETECTED, 0);
    }

    public void setNewPermitDetectedTime(long time) {
        prefs.edit().putLong(KEY_NEW_PERMIT_DETECTED, time).apply();
    }

    public void clearNewPermitDetectedTime() {
        prefs.edit().remove(KEY_NEW_PERMIT_DETECTED).apply();
    }

    public boolean isRemindersEnabled() {
        return prefs.getBoolean(KEY_REMINDERS_ENABLED, true);
    }

    public void setRemindersEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_REMINDERS_ENABLED, enabled).apply();
    }

    public int getConsecutiveSyncFailures() {
        return prefs.getInt(KEY_CONSECUTIVE_SYNC_FAILURES, 0);
    }

    public void incrementSyncFailures() {
        prefs.edit().putInt(KEY_CONSECUTIVE_SYNC_FAILURES,
            getConsecutiveSyncFailures() + 1).apply();
    }

    public void resetSyncFailures() {
        prefs.edit().putInt(KEY_CONSECUTIVE_SYNC_FAILURES, 0).apply();
    }
}
