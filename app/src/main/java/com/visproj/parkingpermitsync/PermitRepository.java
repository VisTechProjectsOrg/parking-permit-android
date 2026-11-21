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
    private static final String KEY_CHECKIN_ALERT_DAYS = "checkin_alert_days";
    private static final String KEY_SHOWN_SINCE = "display_shown_since";
    private static final String KEY_SHOWN_REPORTED = "display_shown_reported";
    private static final String KEY_BATTERY_MV = "display_battery_mv";
    private static final String KEY_BATTERY_TIME = "display_battery_time";
    private static final String KEY_BATTERY_BASE_MV = "display_battery_base_mv";
    private static final String KEY_BATTERY_BASE_TIME = "display_battery_base_time";
    private static final String KEY_BATTERY_LOW_NOTIFIED = "display_battery_low_notified";

    private static final String KEY_DISPLAYS = "displays";
    private static final String KEY_SELECTED_DISPLAY = "selected_display";
    private static final String KEY_OWNER_ID = "owner_id";
    private static final String KEY_FEED_URL = "feed_url";
    private static final String KEY_FEED_TOKEN = "feed_token";
    private static final String KEY_SETUP_DONE = "setup_done";
    private static final String KEY_SETUP_STARTED = "setup_started";

    public static final String PERMIT_URL = "https://fucktorontoparking.ca/api/permit";

    // A display paired with this phone. Its status lives in prefs keys prefixed with keyPrefix;
    // the first display ever registered gets "" and so adopts the single-display keys.
    public static class DisplayInfo {
        public String id;
        public String name;
        public String keyPrefix;
        public boolean unpair;  // forgotten in the app: told to unpair at its next check-in
    }

    private final SharedPreferences prefs;
    private final Gson gson;
    private final String scope;

    // Scoped to the display picked in the app (or the only one)
    public PermitRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
        DisplayInfo d = getSelectedDisplay();
        scope = d != null ? d.keyPrefix : "";
    }

    private PermitRepository(SharedPreferences prefs, String scope) {
        this.prefs = prefs;
        this.gson = new Gson();
        this.scope = scope;
    }

    public PermitRepository forDisplay(DisplayInfo d) {
        return new PermitRepository(prefs, d.keyPrefix);
    }

    private String dk(String key) {
        return scope + key;
    }

    // ----- Displays paired with this phone -----

    public java.util.List<DisplayInfo> getDisplays() {
        String json = prefs.getString(KEY_DISPLAYS, null);
        if (json == null) return new java.util.ArrayList<>();
        try {
            DisplayInfo[] arr = gson.fromJson(json, DisplayInfo[].class);
            return new java.util.ArrayList<>(java.util.Arrays.asList(arr));
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    private void saveDisplays(java.util.List<DisplayInfo> list) {
        prefs.edit().putString(KEY_DISPLAYS, gson.toJson(list)).apply();
    }

    public DisplayInfo findDisplay(String id) {
        for (DisplayInfo d : getDisplays()) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    // The display with this ID, added on its first check-in
    public synchronized DisplayInfo registerDisplay(String id) {
        java.util.List<DisplayInfo> list = getDisplays();
        for (DisplayInfo d : list) {
            if (d.id.equals(id)) return d;
        }
        DisplayInfo d = new DisplayInfo();
        d.id = id;
        String suffix = id.length() >= 4 ? id.substring(id.length() - 4) : id;
        d.name = "Display " + suffix.toUpperCase(java.util.Locale.US);
        d.keyPrefix = list.isEmpty() ? "" : "d_" + id + "_";
        list.add(d);
        saveDisplays(list);
        return d;
    }

    public void renameDisplay(String id, String name) {
        java.util.List<DisplayInfo> list = getDisplays();
        for (DisplayInfo d : list) {
            if (d.id.equals(id)) d.name = name;
        }
        saveDisplays(list);
    }

    // Hidden from the app now; at its next check-in it's told to unpair, then dropped
    public void forgetDisplay(String id) {
        java.util.List<DisplayInfo> list = getDisplays();
        for (DisplayInfo d : list) {
            if (d.id.equals(id)) d.unpair = true;
        }
        saveDisplays(list);
        if (id.equals(prefs.getString(KEY_SELECTED_DISPLAY, null))) {
            prefs.edit().remove(KEY_SELECTED_DISPLAY).apply();
        }
    }

    // The display took the unpair: drop its record and status
    public void removeDisplay(String id) {
        java.util.List<DisplayInfo> list = getDisplays();
        DisplayInfo gone = null;
        for (DisplayInfo d : list) {
            if (d.id.equals(id)) gone = d;
        }
        if (gone == null) return;
        list.remove(gone);
        saveDisplays(list);
        SharedPreferences.Editor e = prefs.edit();
        for (String key : new String[]{KEY_LAST_DISPLAY_SYNC, KEY_DISPLAY_PERMIT_NUMBER,
                KEY_DISPLAY_PERMIT, KEY_DISPLAY_FLIPPED, KEY_DISPLAY_CACHED_PERMIT, KEY_SHOWN_SINCE,
                KEY_SHOWN_REPORTED, KEY_BATTERY_MV, KEY_BATTERY_TIME, KEY_BATTERY_BASE_MV,
                KEY_BATTERY_BASE_TIME, KEY_BATTERY_LOW_NOTIFIED}) {
            e.remove(gone.keyPrefix + key);
        }
        e.apply();
    }

    // Paired displays the app shows (forgotten ones waiting to unpair are left out)
    public java.util.List<DisplayInfo> getActiveDisplays() {
        java.util.List<DisplayInfo> out = new java.util.ArrayList<>();
        for (DisplayInfo d : getDisplays()) {
            if (!d.unpair) out.add(d);
        }
        return out;
    }

    public DisplayInfo getSelectedDisplay() {
        java.util.List<DisplayInfo> active = getActiveDisplays();
        String id = prefs.getString(KEY_SELECTED_DISPLAY, null);
        for (DisplayInfo d : active) {
            if (d.id.equals(id)) return d;
        }
        return active.isEmpty() ? null : active.get(0);
    }

    public void setSelectedDisplay(String id) {
        prefs.edit().putString(KEY_SELECTED_DISPLAY, id).apply();
    }

    // Random ID this phone advertises; a paired display only syncs with the phone sending it
    public synchronized byte[] getOwnerId() {
        String hex = prefs.getString(KEY_OWNER_ID, null);
        if (hex == null) {
            byte[] id = new byte[8];
            new java.security.SecureRandom().nextBytes(id);
            StringBuilder sb = new StringBuilder();
            for (byte b : id) sb.append(String.format("%02x", b));
            hex = sb.toString();
            prefs.edit().putString(KEY_OWNER_ID, hex).apply();
        }
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    // First-run setup finished. Installs from before setup existed (a permit or a display
    // already saved) count as done, but not once setup has started: its feed step saves a
    // permit, and a restart mid-setup must land back in setup.
    public boolean isSetupDone() {
        if (prefs.getBoolean(KEY_SETUP_DONE, false)) return true;
        if (prefs.getBoolean(KEY_SETUP_STARTED, false)) return false;
        return getPermit() != null || !getDisplays().isEmpty();
    }

    public void setSetupStarted() {
        prefs.edit().putBoolean(KEY_SETUP_STARTED, true).apply();
    }

    public void setSetupDone() {
        prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply();
    }

    // ----- Account: where the permit comes from -----

    public String getFeedUrl() {
        return prefs.getString(KEY_FEED_URL, PERMIT_URL);
    }

    // Empty token = no Authorization header (the current site doesn't need one)
    public String getFeedToken() {
        return prefs.getString(KEY_FEED_TOKEN, "");
    }

    public void setAccount(String url, String token) {
        prefs.edit().putString(KEY_FEED_URL, url).putString(KEY_FEED_TOKEN, token).apply();
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
        return prefs.getLong(dk(KEY_LAST_DISPLAY_SYNC), 0);
    }

    public void setLastDisplaySyncTime(long time) {
        prefs.edit().putLong(dk(KEY_LAST_DISPLAY_SYNC), time).apply();
    }

    public String getDisplayPermitNumber() {
        return prefs.getString(dk(KEY_DISPLAY_PERMIT_NUMBER), null);
    }

    public void setDisplayPermitNumber(String permitNumber) {
        prefs.edit()
            .putString(dk(KEY_DISPLAY_PERMIT_NUMBER), permitNumber)
            .putLong(dk(KEY_LAST_DISPLAY_SYNC), System.currentTimeMillis())
            .apply();
    }

    public PermitData getDisplayPermit() {
        String json = prefs.getString(dk(KEY_DISPLAY_PERMIT), null);
        if (json == null) return null;
        try {
            return gson.fromJson(json, PermitData.class);
        } catch (Exception e) {
            return null;
        }
    }

    public void setDisplayPermit(PermitData permit) {
        prefs.edit()
            .putString(dk(KEY_DISPLAY_PERMIT), gson.toJson(permit))
            .putString(dk(KEY_DISPLAY_PERMIT_NUMBER), permit.permitNumber)
            .putLong(dk(KEY_LAST_DISPLAY_SYNC), System.currentTimeMillis())
            .remove(KEY_NEW_PERMIT_DETECTED)
            .apply();
    }

    public boolean isDisplayOutOfSync() {
        PermitData permit = getPermit();
        String displayPermit = getDisplayPermitNumber();
        if (permit == null || permit.permitNumber == null) return false;
        // The battery display may still show the expiring permit (until midnight) while holding
        // the new one, which counts as in sync
        if (permit.permitNumber.equals(prefs.getString(dk(KEY_DISPLAY_CACHED_PERMIT), null))) return false;
        return displayPermit == null || !permit.permitNumber.equals(displayPermit);
    }

    // ----- Status reported by the battery display after each sync: "shown,cached,mv,fw" -----

    public void saveDisplayStatus(String shown, String cached, int batteryMv) {
        SharedPreferences.Editor e = prefs.edit();
        // First report of a new permit on screen = when it went on the display
        // (own key: the permit read already updates the display permit number before this)
        if (!shown.isEmpty() && !shown.equals(prefs.getString(dk(KEY_SHOWN_REPORTED), null))) {
            e.putLong(dk(KEY_SHOWN_SINCE), System.currentTimeMillis()).putString(dk(KEY_SHOWN_REPORTED), shown);
        }
        e
            .putString(dk(KEY_DISPLAY_PERMIT_NUMBER), shown)
            .putString(dk(KEY_DISPLAY_CACHED_PERMIT), cached)
            .putLong(dk(KEY_LAST_DISPLAY_SYNC), System.currentTimeMillis());

        // Keep the full permit the display shows, for the UI
        PermitData latest = getPermit();
        if (latest != null) {
            PermitData match = shown.equals(latest.permitNumber) ? latest
                : (latest.next != null && shown.equals(latest.next.permitNumber)) ? latest.next : null;
            if (match != null) e.putString(dk(KEY_DISPLAY_PERMIT), gson.toJson(match));
        }

        if (batteryMv > 0) {
            long now = System.currentTimeMillis();
            int lastMv = prefs.getInt(dk(KEY_BATTERY_MV), 0);
            // A clear rise means it was charged: restart the drain estimate from here
            if (lastMv == 0 || batteryMv > lastMv + 100) {
                e.putInt(dk(KEY_BATTERY_BASE_MV), batteryMv).putLong(dk(KEY_BATTERY_BASE_TIME), now);
                e.putBoolean(dk(KEY_BATTERY_LOW_NOTIFIED), false);
            }
            e.putInt(dk(KEY_BATTERY_MV), batteryMv).putLong(dk(KEY_BATTERY_TIME), now);
        }
        e.apply();
    }

    // When the permit now on the display was first reported there, or 0 if unknown
    public long getShownSince() {
        return prefs.getLong(dk(KEY_SHOWN_SINCE), 0);
    }

    // The next permit the display is holding (switches to it on its own when it starts), or null
    public String getDisplayCachedPermitNumber() {
        String cached = prefs.getString(dk(KEY_DISPLAY_CACHED_PERMIT), null);
        return cached == null || cached.isEmpty() ? null : cached;
    }

    public int getDisplayBatteryMv() {
        return prefs.getInt(dk(KEY_BATTERY_MV), 0);
    }

    public long getDisplayBatteryTime() {
        return prefs.getLong(dk(KEY_BATTERY_TIME), 0);
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
        int baseMv = prefs.getInt(dk(KEY_BATTERY_BASE_MV), 0);
        long baseTime = prefs.getLong(dk(KEY_BATTERY_BASE_TIME), 0);
        int mv = getDisplayBatteryMv();
        long t = getDisplayBatteryTime();
        if (baseMv == 0 || t - baseTime < 2L * 24 * 3600 * 1000) return -1;
        double usedPct = batteryPercent(baseMv) - batteryPercent(mv);
        if (usedPct <= 0) return -1;
        double days = (t - baseTime) / (24.0 * 3600 * 1000);
        return (int) (batteryPercent(mv) / (usedPct / days));
    }

    public boolean isBatteryLowNotified() {
        return prefs.getBoolean(dk(KEY_BATTERY_LOW_NOTIFIED), false);
    }

    public void setBatteryLowNotified() {
        prefs.edit().putBoolean(dk(KEY_BATTERY_LOW_NOTIFIED), true).apply();
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
        return prefs.getBoolean(dk(KEY_DISPLAY_FLIPPED), false);
    }

    public void setDisplayFlipped(boolean flipped) {
        prefs.edit().putBoolean(dk(KEY_DISPLAY_FLIPPED), flipped).apply();
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

    // Days without a display check-in before alerting; 0 = off. The display checks in twice a day.
    public int getCheckinAlertDays() {
        return prefs.getInt(KEY_CHECKIN_ALERT_DAYS, 2);
    }

    public void setCheckinAlertDays(int days) {
        prefs.edit().putInt(KEY_CHECKIN_ALERT_DAYS, days).apply();
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
