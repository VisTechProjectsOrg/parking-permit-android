package com.visproj.parkingpermitsync;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

public class PermitRepository {
    private static final String PREFS_NAME = "permit_data";
    private static final String KEY_PERMIT = "cached_permit";
    private static final String KEY_LAST_SYNC = "last_sync_time";
    private static final String KEY_GITHUB_URL = "github_url";

    private static final String DEFAULT_GITHUB_URL =
        "https://raw.githubusercontent.com/VisTechProjects/parking_pass_display/permit/permit.json";

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
        prefs.edit()
            .putString(KEY_PERMIT, gson.toJson(permit))
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .apply();
    }

    public long getLastSyncTime() {
        return prefs.getLong(KEY_LAST_SYNC, 0);
    }

    public String getGitHubUrl() {
        return prefs.getString(KEY_GITHUB_URL, DEFAULT_GITHUB_URL);
    }

    public void setGitHubUrl(String url) {
        prefs.edit().putString(KEY_GITHUB_URL, url).apply();
    }
}
