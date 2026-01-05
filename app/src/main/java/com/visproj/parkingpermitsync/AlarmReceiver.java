package com.visproj.parkingpermitsync;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";
    private static final long SYNC_INTERVAL = 24 * 60 * 60 * 1000; // 24 hours

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm triggered - syncing from GitHub");

        GitHubSyncTask syncTask = new GitHubSyncTask(context);
        syncTask.sync(new GitHubSyncTask.SyncCallback() {
            @Override
            public void onSuccess(PermitData permit, boolean isNew) {
                Log.d(TAG, "Sync successful: " + permit.permitNumber + " (new=" + isNew + ")");
                if (isNew) {
                    showNotification(context, permit);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Sync failed: " + error);
            }
        });

        // Reschedule for next day
        scheduleSync(context);
    }

    public static void scheduleSync(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        long triggerTime = System.currentTimeMillis() + SYNC_INTERVAL;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        }

        Log.d(TAG, "Next sync scheduled in 24 hours");
    }

    private void showNotification(Context context, PermitData permit) {
        android.app.NotificationManager manager =
            (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        android.app.Notification notification = new android.app.Notification.Builder(context, "permit_updates")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle("New Permit Available")
            .setContentText("Permit " + permit.permitNumber + " for " + permit.plateNumber)
            .setAutoCancel(true)
            .build();

        manager.notify(2, notification);
    }
}
