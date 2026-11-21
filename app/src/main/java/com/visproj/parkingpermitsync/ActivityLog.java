package com.visproj.parkingpermitsync;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// What the app and the displays did, for troubleshooting a real-world trial: check-ins,
// battery, permit fetches, alerts, service restarts. One line per event ("epochMillis<TAB>text")
// in the app's files; anything older than KEEP_DAYS is dropped when the file grows.
public final class ActivityLog {
    private static final String TAG = "ActivityLog";
    private static final String FILE = "activity.log";
    private static final long KEEP_DAYS = 30;
    private static final long TRIM_AT_BYTES = 256 * 1024;

    private ActivityLog() {}

    public static synchronized void log(Context context, String text) {
        Log.d(TAG, text);
        File f = new File(context.getFilesDir(), FILE);
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(System.currentTimeMillis() + "\t" + text.replace('\n', ' ') + "\n");
        } catch (IOException e) {
            Log.e(TAG, "Could not write the activity log", e);
            return;
        }
        if (f.length() > TRIM_AT_BYTES) trim(f);
    }

    // Newest first, formatted for reading. withYear for a shared copy (it gets filed away).
    public static synchronized List<String> read(Context context, boolean withYear) {
        List<String> out = new ArrayList<>();
        File f = new File(context.getFilesDir(), FILE);
        if (!f.exists()) return out;
        String thisYear = new SimpleDateFormat("yyyy", Locale.US).format(new Date());
        SimpleDateFormat year = new SimpleDateFormat("yyyy", Locale.US);
        SimpleDateFormat full = new SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.US);
        SimpleDateFormat shortFmt = new SimpleDateFormat("MMM d, h:mm:ss a", Locale.US);
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                Date d = new Date(Long.parseLong(line.substring(0, tab)));
                boolean showYear = withYear || !year.format(d).equals(thisYear);
                out.add(0, (showYear ? full : shortFmt).format(d) + "  " + line.substring(tab + 1));
            }
        } catch (IOException | NumberFormatException e) {
            Log.e(TAG, "Could not read the activity log", e);
        }
        return out;
    }

    private static void trim(File f) {
        long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000;
        List<String> keep = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0 && Long.parseLong(line.substring(0, tab)) >= cutoff) keep.add(line);
            }
        } catch (IOException | NumberFormatException e) {
            Log.e(TAG, "Could not trim the activity log", e);
            return;
        }
        // Still over the limit with only recent lines: keep the newest half
        if (keep.size() > 1 && keep.stream().mapToLong(String::length).sum() > TRIM_AT_BYTES) {
            keep = keep.subList(keep.size() / 2, keep.size());
        }
        try (FileWriter w = new FileWriter(f, false)) {
            for (String line : keep) w.write(line + "\n");
        } catch (IOException e) {
            Log.e(TAG, "Could not rewrite the activity log", e);
        }
    }
}
