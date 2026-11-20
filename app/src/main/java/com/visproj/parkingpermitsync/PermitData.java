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

    public static final int COVERAGE_OK = 0;
    public static final int COVERAGE_ENDS_TODAY = 1;   // nothing bought for tomorrow
    public static final int COVERAGE_EXPIRED = 2;      // car is not covered right now
    public static final int COVERAGE_UNKNOWN = 3;

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
            if (cmp < 0) return COVERAGE_EXPIRED;
            if (cmp == 0) return COVERAGE_ENDS_TODAY;
            return COVERAGE_OK;
        } catch (java.text.ParseException e) {
            return COVERAGE_UNKNOWN;
        }
    }
}
