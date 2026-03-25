package com.visproj.parkingpermitsync;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    private static final int SYNC_HOUR = 3;      // 3 AM - fetch new permit data
    private static final int REMINDER_AM_HOUR = 9;  // 9 AM - morning reminder
    private static final int REMINDER_PM_HOUR = 21;  // 9 PM - evening reminder

    private static final int REQUEST_SYNC = 0;
    private static final int REQUEST_REMINDER_AM = 1;
    private static final int REQUEST_REMINDER_PM = 2;

    private static final String EXTRA_ALARM_TYPE = "alarm_type";
    private static final String TYPE_SYNC = "sync";
    private static final String TYPE_REMINDER = "reminder";

    private static final int NOTIFICATION_NEW_PERMIT = 2;
    private static final int NOTIFICATION_REMINDER = 3;

    // Days after which reminder priority escalates
    private static final int ESCALATION_DAYS = 2;

    @Override
    public void onReceive(Context context, Intent intent) {
        String type = intent.getStringExtra(EXTRA_ALARM_TYPE);
        if (type == null) type = TYPE_SYNC; // backwards compat

        if (TYPE_SYNC.equals(type)) {
            handleSync(context);
        } else if (TYPE_REMINDER.equals(type)) {
            handleReminder(context);
        }
    }

    private void handleSync(Context context) {
        Log.d(TAG, "3 AM sync triggered");

        GitHubSyncTask syncTask = new GitHubSyncTask(context);
        syncTask.sync(new GitHubSyncTask.SyncCallback() {
            @Override
            public void onSuccess(PermitData permit, boolean isNew) {
                Log.d(TAG, "Sync successful: " + permit.permitNumber + " (new=" + isNew + ")");
                if (isNew) {
                    PermitRepository repo = new PermitRepository(context);
                    repo.setNewPermitDetectedTime(System.currentTimeMillis());
                    showNewPermitNotification(context, permit);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Sync failed: " + error);
            }
        });

        // Reschedule for next 3 AM
        scheduleAlarm(context, SYNC_HOUR, REQUEST_SYNC, TYPE_SYNC);
    }

    private void handleReminder(Context context) {
        PermitRepository repo = new PermitRepository(context);

        if (!repo.isRemindersEnabled()) {
            Log.d(TAG, "Reminders disabled, skipping");
            return;
        }

        if (!repo.isDisplayOutOfSync()) {
            Log.d(TAG, "Display is in sync, no reminder needed");
            return;
        }

        long detectedTime = repo.getNewPermitDetectedTime();
        long daysSinceNew = 0;
        if (detectedTime > 0) {
            daysSinceNew = TimeUnit.MILLISECONDS.toDays(
                System.currentTimeMillis() - detectedTime);
        }

        PermitData permit = repo.getPermit();
        if (permit == null) return;

        boolean escalate = daysSinceNew >= ESCALATION_DAYS;
        Log.d(TAG, "Reminder: display out of sync for " + daysSinceNew + " days, escalated=" + escalate);

        showReminderNotification(context, permit, daysSinceNew, escalate);
    }

    // --- Scheduling ---

    public static void scheduleSync(Context context) {
        scheduleAlarm(context, SYNC_HOUR, REQUEST_SYNC, TYPE_SYNC);
        scheduleAlarm(context, REMINDER_AM_HOUR, REQUEST_REMINDER_AM, TYPE_REMINDER);
        scheduleAlarm(context, REMINDER_PM_HOUR, REQUEST_REMINDER_PM, TYPE_REMINDER);
        Log.d(TAG, "Scheduled: 3 AM sync, 9 AM reminder, 9 PM reminder");
    }

    private static void scheduleAlarm(Context context, int hour, int requestCode, String type) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra(EXTRA_ALARM_TYPE, type);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        long triggerTime = getNextTime(hour);

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
    }

    private static long getNextTime(int hour) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        return calendar.getTimeInMillis();
    }

    // --- Notifications ---

    private static void showNewPermitNotification(Context context, PermitData permit) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        Notification notification = new Notification.Builder(context, "permit_updates")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle("New Permit Available")
            .setContentText("Permit " + permit.permitNumber + " ready to sync")
            .setStyle(new Notification.BigTextStyle().bigText(
                "Permit " + permit.permitNumber + " is ready.\n" +
                "Power on the display, keep your phone nearby, and open the app to sync."))
            .setAutoCancel(true)
            .build();

        manager.notify(NOTIFICATION_NEW_PERMIT, notification);
    }

    private static void showReminderNotification(Context context, PermitData permit,
                                                  long daysSinceNew, boolean escalate) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        String title = "Display Update Needed";
        String shortText;
        String longText;
        if (daysSinceNew <= 0) {
            shortText = "Permit " + permit.permitNumber + " hasn't been synced";
            longText = "Permit " + permit.permitNumber + " hasn't been synced to the display yet.\n" +
                "Power on the display, keep your phone nearby, and open the app to sync.";
        } else if (daysSinceNew == 1) {
            shortText = "Display is 1 day behind";
            longText = "Permit " + permit.permitNumber + " - display is 1 day behind.\n" +
                "Power on the display, keep your phone nearby, and open the app to sync.";
        } else {
            shortText = "Display is " + daysSinceNew + " days behind";
            longText = "Permit " + permit.permitNumber + " - display is " + daysSinceNew + " days behind!\n" +
                "Power on the display, keep your phone nearby, and open the app to sync.";
        }

        Notification.Builder builder = new Notification.Builder(context, "permit_updates")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(title)
            .setContentText(shortText)
            .setStyle(new Notification.BigTextStyle().bigText(longText))
            .setAutoCancel(true);

        if (escalate) {
            builder.setCategory(Notification.CATEGORY_REMINDER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel urgentChannel = new android.app.NotificationChannel(
                    "permit_urgent", "Urgent Permit Reminders",
                    NotificationManager.IMPORTANCE_HIGH);
                urgentChannel.setDescription("Persistent reminders when display is out of date");
                manager.createNotificationChannel(urgentChannel);
                builder = new Notification.Builder(context, "permit_urgent")
                    .setSmallIcon(R.drawable.ic_bluetooth)
                    .setContentTitle(title)
                    .setContentText(shortText)
                    .setStyle(new Notification.BigTextStyle().bigText(longText))
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_REMINDER);
            }
        }

        manager.notify(NOTIFICATION_REMINDER, builder.build());
    }
}