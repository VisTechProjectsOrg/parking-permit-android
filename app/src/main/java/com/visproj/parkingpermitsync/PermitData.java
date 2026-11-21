package com.visproj.parkingpermitsync;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

public class PermitData {
    @SerializedName("permitNumber")
    public String permitNumber = "";

    @SerializedName("plateNumber")
    public String plateNumber = "";

    @SerializedName("vehicleName")
    public String vehicleName = "";

    @SerializedName("validFrom")
    public String validFrom = "";

    @SerializedName("validTo")
    public String validTo = "";

    @SerializedName("barcodeValue")
    public String barcodeValue = "";

    @SerializedName("barcodeLabel")
    public String barcodeLabel = "";

    @SerializedName("amountPaid")
    public String price = "";

    @SerializedName("displayFlipped")
    public boolean displayFlipped = false;

    // Latest validTo of any permit bought for this plate (from the site), same format as validTo.
    // Lets the app tell "next permit already bought" apart from "nothing bought for tomorrow".
    @SerializedName("coveredThrough")
    public String coveredThrough = "";

    // The other bought permit still in play (usually next week's). The display caches it and
    // switches to it on its own at validFrom, so it doesn't need the phone at midnight.
    @SerializedName("next")
    public PermitData next;

    // From the site: whether the autobuyer is on, and when it buys the next permit (permit format)
    @SerializedName("autobuyer")
    public Boolean autobuyer;

    @SerializedName("nextBuy")
    public String nextBuy = "";

    // Set while the autobuyer is paused on purpose (away for a few days): the day the next
    // permit starts ("Nov 25, 2026"). Until then a gap in coverage is planned, not a problem.
    @SerializedName("resumesOn")
    public String resumesOn = "";

    public static final int COVERAGE_OK = 0;
    public static final int COVERAGE_ENDS_TODAY = 1;   // nothing bought for tomorrow
    public static final int COVERAGE_EXPIRED = 2;      // car is not covered right now
    public static final int COVERAGE_UNKNOWN = 3;
    public static final int COVERAGE_PAUSED = 4;       // gap is planned, see resumesOn

    public PermitData() {}

    public PermitData(String permitNumber, String plateNumber, String validFrom,
                      String validTo, String barcodeValue, String barcodeLabel) {
        this.permitNumber = permitNumber;
        this.plateNumber = plateNumber;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.barcodeValue = barcodeValue;
        this.barcodeLabel = barcodeLabel;
    }

    public boolean isValid() {
        return permitNumber != null && !permitNumber.isEmpty();
    }

    // Check all required fields for BLE transfer
    public boolean isComplete() {
        return permitNumber != null && !permitNumber.isEmpty()
            && plateNumber != null && !plateNumber.isEmpty()
            && validFrom != null && !validFrom.isEmpty()
            && validTo != null && !validTo.isEmpty()
            && barcodeValue != null && !barcodeValue.isEmpty()
            && barcodeLabel != null && !barcodeLabel.isEmpty();
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    // What the display reads over BLE: only the fields it draws, the cached next permit, and
    // the phone's local time (the display has no other clock source). Kept small so it fits
    // one 512-byte characteristic read.
    public String toDisplayJson(boolean flipped) {
        com.google.gson.JsonObject o = displayFields(this);
        o.addProperty("displayFlipped", flipped);
        if (next != null && next.isComplete()) o.add("next", displayFields(next));
        long nowMs = System.currentTimeMillis();
        o.addProperty("now", (nowMs + java.util.TimeZone.getDefault().getOffset(nowMs)) / 1000);
        return o.toString();
    }

    private static com.google.gson.JsonObject displayFields(PermitData p) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("permitNumber", p.permitNumber);
        o.addProperty("plateNumber", p.plateNumber);
        o.addProperty("validFrom", p.validFrom);
        o.addProperty("validTo", p.validTo);
        o.addProperty("barcodeValue", p.barcodeValue);
        o.addProperty("barcodeLabel", p.barcodeLabel);
        return o;
    }

    private static long parseTime(String s) {
        try {
            java.util.Date d = new java.text.SimpleDateFormat("MMM dd, yyyy: HH:mm", java.util.Locale.US).parse(s);
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // validTo is the last minute of coverage, so it counts through hh:59
    public boolean isValidAt(long now) {
        long from = parseTime(validFrom), to = parseTime(validTo);
        return from > 0 && to > 0 && from <= now && now < to + 60_000;
    }

    public boolean startsAfter(long now) {
        return parseTime(validFrom) > now;
    }

    // Warning text for a coverage state, or null when there is nothing to warn about
    public static String coverageMessage(int coverage) {
        if (coverage == COVERAGE_ENDS_TODAY) return "No permit bought for tomorrow. Buy one before midnight.";
        if (coverage == COVERAGE_EXPIRED) return "Permit expired. The car is not covered.";
        return null;
    }

    // Whether the car is covered today and tomorrow, judged by the last day of any bought permit.
    public int getCoverage() {
        String end = (coveredThrough != null && !coveredThrough.isEmpty()) ? coveredThrough : validTo;
        if (end == null || end.isEmpty()) return COVERAGE_UNKNOWN;
        try {
            java.util.Date endDate = new java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US)
                .parse(end.split(":")[0].trim());
            if (endDate == null) return COVERAGE_UNKNOWN;
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
            String endDay = day.format(endDate);
            String today = day.format(new java.util.Date());
            int cmp = endDay.compareTo(today);
            if (cmp > 0) return COVERAGE_OK;
            // Out of coverage (or ending today) on purpose: the next permit is set to start later
            if (resumesOn != null && !resumesOn.isEmpty()) {
                java.util.Date resume = new java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.US)
                    .parse(resumesOn.split(":")[0].trim());
                if (resume != null && day.format(resume).compareTo(today) > 0) return COVERAGE_PAUSED;
            }
            return cmp < 0 ? COVERAGE_EXPIRED : COVERAGE_ENDS_TODAY;
        } catch (java.text.ParseException e) {
            return COVERAGE_UNKNOWN;
        }
    }
}
