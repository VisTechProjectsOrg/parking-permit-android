package com.visproj.parkingpermitsync;

public class TimeUtils {
    public static String getRelativeTime(long timestamp) {
        if (timestamp == 0) return "Never";

        long now = System.currentTimeMillis();
        long diff = now - timestamp;

        if (diff < 0) return "Just now";

        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (seconds < 5) {
            return "Just now";
        } else if (seconds < 60) {
            return seconds + " sec ago";
        } else if (minutes < 60) {
            return minutes == 1 ? "1 min ago" : minutes + " min ago";
        } else if (hours < 24) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        } else if (days < 7) {
            return days == 1 ? "1 day ago" : days + " days ago";
        } else {
            long weeks = days / 7;
            return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
        }
    }
}
