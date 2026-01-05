package com.visproj.parkingpermitsync;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

public class SamsungBatteryHelper {
    private static final String TAG = "SamsungBatteryHelper";

    public static boolean isSamsungDevice() {
        return Build.MANUFACTURER.equalsIgnoreCase("samsung");
    }

    public static boolean isBatteryOptimizationDisabled(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static Intent getBatteryOptimizationIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    public static Intent getSamsungBatterySettingsIntent() {
        // Samsung-specific device care battery settings
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_BATTERY_SETTINGS");
        return intent;
    }

    public static Intent getAppInfoIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    public static boolean canOpenSamsungBatterySettings(Context context) {
        Intent intent = getSamsungBatterySettingsIntent();
        return intent.resolveActivity(context.getPackageManager()) != null;
    }

    public static void logBatteryStatus(Context context) {
        boolean optimizationDisabled = isBatteryOptimizationDisabled(context);
        Log.d(TAG, "Samsung device: " + isSamsungDevice());
        Log.d(TAG, "Battery optimization disabled: " + optimizationDisabled);
    }
}
