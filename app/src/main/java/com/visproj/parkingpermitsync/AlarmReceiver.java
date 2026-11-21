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
    private static final int NOTIFICATION_SYNC_FAILURE = 4;
    private static final int NOTIFICATION_COVERAGE = 5;
    public static final int NOTIFICATION_CHECKIN = 7;

    // Notify after this many consecutive sync failures
    private static final int FAILURE_THRESHOLD = 3;

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

        // Reschedule first so a crash below can't end the daily chain
        scheduleAlarm(context, SYNC_HOUR, REQUEST_SYNC, TYPE_SYNC);
        BleGattService.ensureRunning(context);

        // Keep the process alive until the network fetch finishes
        final PendingResult pending = goAsync();
        PermitRepository repo = new PermitRepository(context);
        PermitSyncTask syncTask = new PermitSyncTask(context);
        syncTask.sync(new PermitSyncTask.SyncCallback() {
            @Override
            public void onSuccess(PermitData permit, boolean isNew) {
                Log.d(TAG, "Sync successful: " + permit.permitNumber + " (new=" + isNew + ")");
                repo.resetSyncFailures();
                if (isNew) {
                    repo.setNewPermitDetectedTime(System.currentTimeMillis());
                    showNewPermitNotification(context, permit);
                }
                pending.finish();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Sync failed: " + error);
                repo.incrementSyncFailures();
                int failures = repo.getConsecutiveSyncFailures();
                if (failures >= FAILURE_THRESHOLD) {
                    showSyncFailureNotification(context, failures);
                }
                pending.finish();
            }
        });
    }

    private void handleReminder(Context context) {
        // Reschedule both reminders first: returning early used to end the chain for good
        scheduleAlarm(context, REMINDER_AM_HOUR, REQUEST_REMINDER_AM, TYPE_REMINDER);
        scheduleAlarm(context, REMINDER_PM_HOUR, REQUEST_REMINDER_PM, TYPE_REMINDER);
        BleGattService.ensureRunning(context);

        PermitRepository repo = new PermitRepository(context);

        // Missed purchase alert is independent of the display reminder toggle
        PermitData latest = repo.getPermit();
        if (latest != null && latest.isValid()) {
            String coverageMsg = PermitData.coverageMessage(latest.getCoverage());
            if (coverageMsg != null) {
                showCoverageNotification(context, latest, coverageMsg);
            }
        }

        // Each paired display (before any has checked in with an ID: the single-display status)
        java.util.List<PermitRepository.DisplayInfo> displays = repo.getActiveDisplays();
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.List<PermitRepository> repos = new java.util.ArrayList<>();
        for (PermitRepository.DisplayInfo d : displays) {
            names.add(d.name);
            repos.add(repo.forDisplay(d));
        }
        if (repos.isEmpty()) {
            names.add("Display");
            repos.add(repo);
        }
        boolean several = repos.size() > 1;

        // Display gone quiet (dead battery, out of range): it normally checks in twice a day
        int alertDays = repo.getCheckinAlertDays();
        java.util.List<String> quiet = new java.util.ArrayList<>();
        long oldestCheckin = 0;
        for (int i = 0; i < repos.size(); i++) {
            long lastCheckin = repos.get(i).getLastDisplaySyncTime();
            if (alertDays > 0 && lastCheckin > 0
                    && System.currentTimeMillis() - lastCheckin > TimeUnit.DAYS.toMillis(alertDays)) {
                quiet.add(names.get(i));
                if (oldestCheckin == 0 || lastCheckin < oldestCheckin) oldestCheckin = lastCheckin;
            }
        }
        if (!quiet.isEmpty()) {
            showCheckinNotification(context, oldestCheckin, several ? quiet : null);
        }

        if (!repo.isRemindersEnabled()) {
            Log.d(TAG, "Reminders disabled, skipping");
            return;
        }

        java.util.List<String> behind = new java.util.ArrayList<>();
        for (int i = 0; i < repos.size(); i++) {
            if (repos.get(i).isDisplayOutOfSync()) behind.add(names.get(i));
        }
        if (behind.isEmpty()) {
            Log.d(TAG, "Displays are in sync, no reminder needed");
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

        showReminderNotification(context, permit, daysSinceNew, escalate, several ? behind : null);
    }

    // "Driveway and Garage", "A, B and C"
    private static String joinNames(java.util.List<String> names) {
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
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
            .setContentTitle("New permit bought")
            .setContentText("Permit " + permit.permitNumber)
            .setStyle(new Notification.BigTextStyle().bigText(
                "Permit " + permit.permitNumber + " is ready.\n" +
                "The display picks it up on its next check-in when your phone is nearby."))
            .setAutoCancel(true)
            .build();

        manager.notify(NOTIFICATION_NEW_PERMIT, notification);
    }

    // names: the displays that are behind, or null with a single display
    private static void showReminderNotification(Context context, PermitData permit,
                                                  long daysSinceNew, boolean escalate,
                                                  java.util.List<String> names) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        String title = names == null ? "Display Update Needed"
            : joinNames(names) + (names.size() == 1 ? " needs an update" : " need an update");
        String shortText;
        String longText;
        if (daysSinceNew <= 0) {
            shortText = "Permit " + permit.permitNumber + " hasn't been synced";
            longText = "Permit " + permit.permitNumber + " hasn't been synced to the display yet.\n" +
                "Press the display's button with your phone nearby.";
        } else if (daysSinceNew == 1) {
            shortText = "Display is 1 day behind";
            longText = "Permit " + permit.permitNumber + " - display is 1 day behind.\n" +
                "Press the display's button with your phone nearby.";
        } else {
            shortText = "Display is " + daysSinceNew + " days behind";
            longText = "Permit " + permit.permitNumber + " - display is " + daysSinceNew + " days behind!\n" +
                "Press the display's button with your phone nearby.";
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

    private static void showCoverageNotification(Context context, PermitData permit, String message) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new android.app.NotificationChannel(
            "permit_urgent", "Urgent Permit Reminders", NotificationManager.IMPORTANCE_HIGH));

        String vehicle = (permit.vehicleName != null && !permit.vehicleName.isEmpty())
            ? permit.vehicleName : permit.plateNumber;
        Notification notification = new Notification.Builder(context, "permit_urgent")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(vehicle + ": not covered")
            .setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build();

        manager.notify(NOTIFICATION_COVERAGE, notification);
    }

    // names: the quiet displays, or null with a single display
    private static void showCheckinNotification(Context context, long lastCheckin, java.util.List<String> names) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String since = new java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
            .format(new java.util.Date(lastCheckin));
        String title = names == null ? "Display hasn't checked in"
            : joinNames(names) + (names.size() == 1 ? " hasn't checked in" : " haven't checked in");
        Notification notification = new Notification.Builder(context, "permit_updates")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(title)
            .setContentText("Last seen " + since)
            .setStyle(new Notification.BigTextStyle().bigText("Last seen " + since + ".\n" +
                "Check its battery, or press its button with your phone nearby."))
            .setAutoCancel(true)
            .build();
        manager.notify(NOTIFICATION_CHECKIN, notification);
    }

    private static void showSyncFailureNotification(Context context, int failures) {
        NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        Notification notification = new Notification.Builder(context, "permit_updates")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle("Permit Sync Failed")
            .setContentText("Sync has failed " + failures + " times in a row")
            .setStyle(new Notification.BigTextStyle().bigText(
                "Permit sync has failed " + failures + " times in a row.\n" +
                "Check your internet connection or open the app to retry."))
            .setAutoCancel(true)
            .build();

        manager.notify(NOTIFICATION_SYNC_FAILURE, notification);
    }
}