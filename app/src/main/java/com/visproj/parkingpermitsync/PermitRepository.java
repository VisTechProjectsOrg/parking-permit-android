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
    private static final String KEY_GITHUB_URL = "github_url";
    private static final String KEY_DISPLAY_FLIPPED = "display_flipped";

    private static final String DEFAULT_GITHUB_URL =
        "https://raw.githubusercontent.com/VisTechProjects/parking_pass_display/permit/permit.json";

    private final SharedPreferences prefs;
    private final Gson gson;

    public PermitRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();

        // One-time seed: if no previous permit exists, seed with T6199100 for price comparison
        if (getPreviousPermit() == null) {
            PermitData seed = new PermitData();
            seed.permitNumber = "T6199100";
            seed.plateNumber = "DBXH751";
            seed.vehicleName = "Hooptie";
            seed.validFrom = "Dec 30, 2025: 00:00";
            seed.validTo = "Jan 06, 2026: 23:59";
            seed.barcodeValue = "6199100";
            seed.barcodeLabel = "00435";
            seed.price = "$48.38";
            setPreviousPermit(seed);
        }
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
            .apply();
    }

    public boolean isDisplayOutOfSync() {
        PermitData permit = getPermit();
        String displayPermit = getDisplayPermitNumber();
        if (permit == null || permit.permitNumber == null) return false;
        return displayPermit == null || !permit.permitNumber.equals(displayPermit);
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

    public String getGitHubUrl() {
        return prefs.getString(KEY_GITHUB_URL, DEFAULT_GITHUB_URL);
    }

    public void setGitHubUrl(String url) {
        prefs.edit().putString(KEY_GITHUB_URL, url).apply();
    }

    public boolean isDisplayFlipped() {
        return prefs.getBoolean(KEY_DISPLAY_FLIPPED, false);
    }

    public void setDisplayFlipped(boolean flipped) {
        prefs.edit().putBoolean(KEY_DISPLAY_FLIPPED, flipped).apply();
    }
}
